package com.voxel.audio;

import java.util.List;
import java.util.Random;

/**
 * KlattSynth — a compact formant (Klatt-style) synthesizer.
 * <p>
 * Pipeline per sample: glottal source (Rosenberg pulse) + frication noise
 * → five parallel second-order formant resonators (peak-gain normalized) → out.
 * Formant frequencies, F0 and the noise mix are interpolated smoothly across
 * each segment, and F0 carries an optional vibrato for a sung quality.
 */
public class KlattSynth {

    public static final int FS = 22050;
    private static final double TWO_PI = 2 * Math.PI;

    /** One phoneme/segment: start→end formants, F0 contour, voicing + noise mix. */
    public static class Seg {
        public double ms;
        public double f1s, f2s, f3s, f1e, f2e, f3e;
        public double f0s, f0e;     // Hz; 0 = unvoiced
        public double glottal;      // 0..1 glottal gain
        public double noise;        // 0..1 frication/aspiration mix
        public double noiseFreq;    // Hz center for the noise shaping filter

        public Seg(double ms, double f1, double f2, double f3,
                   double f0s, double f0e, double glottal, double noise) {
            this.ms = ms;
            this.f1s = this.f1e = f1;
            this.f2s = this.f2e = f2;
            this.f3s = this.f3e = f3;
            this.f0s = f0s;
            this.f0e = f0e;
            this.glottal = glottal;
            this.noise = noise;
            this.noiseFreq = 0;
        }

        public Seg withTransition(double f1e, double f2e, double f3e) {
            this.f1e = f1e;
            this.f2e = f2e;
            this.f3e = f3e;
            return this;
        }

        public Seg withNoiseFreq(double f) {
            this.noiseFreq = f;
            return this;
        }
    }

    // ── Second-order resonator (all-pole, peak gain normalized to ~1) ──
    static class Res {
        double a1, a2, A;
        double gain = 1.0;   // relative loudness of this formant
        double y1, y2;

        void set(double f, double bw) {
            if (f < 60) f = 60;
            double r = Math.exp(-Math.PI * bw / FS);
            double th = TWO_PI * f / FS;
            a1 = -2 * r * Math.cos(th);
            a2 = r * r;
            A = (1 - r) * Math.sqrt(1 - 2 * r * Math.cos(2 * th) + r * r);
        }

        double tick(double x) {
            double y = A * x - a1 * y1 - a2 * y2;
            y2 = y1;
            y1 = y;             // state holds the true filter output
            return gain * y;    // scale level AFTER the recursion (taper only)
        }

        void reset() {
            y1 = y2 = 0;
        }
    }

    // ── RBJ low-shelf filter — restores the low-frequency body of the voice ──
    static class Shelf {
        double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;

        void set(double gainDb, double fc) {
            double A = Math.pow(10, gainDb / 40.0);
            double w0 = 2 * Math.PI * fc / FS;
            double alpha = Math.sin(w0) / 2 * Math.sqrt(2);
            double cosw = Math.cos(w0);
            double sqrtA = Math.sqrt(A);
            double a0 = (A + 1) + (A - 1) * cosw + 2 * sqrtA * alpha;
            b0 = A * ((A + 1) - (A - 1) * cosw + 2 * sqrtA * alpha) / a0;
            b1 = 2 * A * ((A - 1) - (A + 1) * cosw) / a0;
            b2 = A * ((A + 1) - (A - 1) * cosw - 2 * sqrtA * alpha) / a0;
            a1 = -2 * ((A - 1) + (A + 1) * cosw) / a0;
            a2 = ((A + 1) + (A - 1) * cosw - 2 * sqrtA * alpha) / a0;
        }

        double tick(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    private final double breathiness;
    private final double tilt;   // spectral tilt dB (negative = darker/low-dominant)

    public KlattSynth(double breathiness, double tilt) {
        this.breathiness = breathiness;
        this.tilt = tilt;
    }

    /** Low-shelf boost (dB) derived from the profile's spectral tilt. */
    static double bassBoostDb(double tilt) {
        return Math.max(0, Math.min(8, -tilt * 0.6));
    }

    /**
     * Render a segment list to mono float samples at FS.
     *
     * @param segments     phoneme sequence
     * @param vibratoHz    vibrato rate (0 to disable)
     * @param vibratoDepth fractional depth (e.g. 0.03 = ±3%)
     */
    public float[] render(List<Seg> segments, double vibratoHz, double vibratoDepth) {
        int total = 0;
        for (Seg s : segments) total += (int) (s.ms * FS / 1000.0);
        float[] out = new float[total];

        Res r1 = new Res(), r2 = new Res(), r3 = new Res(), r4 = new Res(), r5 = new Res();
        Res noiseF = new Res(); // single bandpass shaping the frication noise
        Random rng = new Random(42);
        double phase = 0;
        int idx = 0;
        double prevIn = 0, prevOut = 0; // DC-blocker state
        double jitCur = 0, jitTarget = 0; // per-cycle F0 jitter (human unsteadiness)
        // Cap breathiness: the analyzer's estimate can be inflated by reverb/
        // backing energy; >0.12 turns the voice into audible white-noise hiss.
        double breath = Math.min(breathiness, 0.12);
        // Parallel branch weights (not a cascade): F1 carries the body, F2
        // gives vowels their identity, F3-F5 add the top that fricatives need.
        // A heavy taper on F2/F3 makes the voice generic and flat; too little
        // taper brings back a thin, top-heavy "talking through a kazoo" tone.
        r1.gain = 1.0;
        r2.gain = 0.66;
        r3.gain = 0.44;
        r4.gain = 0.28;
        r5.gain = 0.18;
        // Restore the fundamental's body with a low-shelf driven by the profile's
        // spectral tilt (Dan's low band runs ~9.6 dB hotter than his high band).
        double bassDb = bassBoostDb(tilt);
        Shelf bass = new Shelf();
        if (bassDb > 0) bass.set(bassDb, 300);

        for (Seg seg : segments) {
            // Reset the noise-shaping filter at each noise segment start so
            // stale resonator state from a previous fricative can't pop.
            if (seg.noise > 0 && seg.noiseFreq > 0) {
                noiseF.reset();
            }
            int n = (int) (seg.ms * FS / 1000.0);
            // Raised-cosine attack/release per segment — without an envelope the
            // hard cuts at segment boundaries sound like glottal stops / gasping
            // (the "choking" artifact). Zero-derivative edges avoid clicks.
            int attack = Math.max(1, Math.min((int) (0.015 * FS), n / 4));
            int release = Math.max(1, Math.min((int) (0.035 * FS), n / 3));
            for (int i = 0; i < n; i++) {
                double amp = 1.0;
                if (i < attack) {
                    amp = 0.5 - 0.5 * Math.cos(Math.PI * i / attack);
                } else if (i >= n - release) {
                    amp = 0.5 - 0.5 * Math.cos(Math.PI * (n - 1 - i) / release);
                }
                double t = n <= 1 ? 0 : (double) i / (n - 1);
                double f1 = lerp(seg.f1s, seg.f1e, t);
                double f2 = lerp(seg.f2s, seg.f2e, t);
                double f3 = lerp(seg.f3s, seg.f3e, t);
                double f0 = lerp(seg.f0s, seg.f0e, t);
                if (f0 > 0 && vibratoHz > 0) {
                    f0 *= 1 + vibratoDepth * Math.sin(TWO_PI * vibratoHz * idx / FS);
                }

                // Wider bandwidths than 60/90/130: narrow resonators sound like
                // a pinched, strangled vocal tract. Wider = open, natural singing.
                r1.set(f1, 100);
                r2.set(f2, 160);
                r3.set(f3, 220);
                r4.set(3400, 350);
                r5.set(4600, 450);

                double glottal = 0;
                if (seg.glottal > 0 && f0 > 0) {
                    double oq = 0.55, rp = 0.25; // open quotient + return phase
                    // Natural F0 jitter: pick a new random target each glottal
                    // cycle and ease toward it. A perfectly steady pitch sounds
                    // robotic/generic; ±1% wander reads as a real human voice.
                    double f0jit = f0 * (1 + jitCur);
                    glottal = seg.glottal * glottalPulse(phase, oq, rp);
                    if (breath > 0) {
                        glottal += (rng.nextDouble() * 2 - 1) * seg.glottal * breath * 0.5;
                    }
                    double period = FS / f0jit;
                    phase += 1.0 / period;
                    if (phase >= 1.0) {
                        phase -= 1.0;
                        jitTarget = (rng.nextDouble() * 2 - 1) * 0.01; // ±1%
                    }
                    jitCur += (jitTarget - jitCur) * 0.05;
                }

                double noise = 0;
                if (seg.noise > 0) {
                    noise = (rng.nextDouble() * 2 - 1) * seg.noise;
                    if (seg.noiseFreq > 0) {
                        noiseF.set(seg.noiseFreq, 900);
                        noise = noiseF.tick(noise);
                    }
                }

                // Parallel formant branches, not a cascade. In a cascade every
                // sample passes through the narrow F1 resonator first, whose
                // ~100 Hz skirt attenuates everything above ~500 Hz by tens of
                // dB — that was the muffled low hum with inaudible consonants.
                // Summing independent branches keeps the F2/F3 character of
                // vowels and the frication of consonants (classic Klatt88
                // parallel topology). Fricatives need a lift: noise crosses
                // every branch's skirt, so without help consonants end up far
                // quieter than the reference recordings.
                double src = glottal + noise * 1.8;
                double shaped = r1.tick(src) + r2.tick(src) + r3.tick(src)
                        + r4.tick(src) + r5.tick(src);

                // Proper DC blocker: y[n] = x[n] − x[n−1] + 0.995·y[n−1],
                // where x is the shaped branch sum. Feeding it the raw source
                // instead leaks a huge offset through the 0.995 pole.
                double dc = shaped - prevIn + 0.995 * prevOut;
                prevIn = shaped;
                prevOut = dc;
                if (bassDb > 0) dc = bass.tick(dc);   // low-frequency body
                out[idx] = (float) (dc * amp);
                idx++;
            }
        }

        // Normalize to peak 0.9
        double peak = 1e-9;
        for (float v : out) peak = Math.max(peak, Math.abs(v));
        float scale = (float) (0.9 / peak);
        for (int i = 0; i < out.length; i++) out[i] *= scale;
        return out;
    }

    /**
     * Rosenberg glottal pulse; phase in [0,1) over one period.
     * Rising cubic over the open quotient oq, then a smooth cubic return phase rp
     * back to zero — the old hard step at phase=oq added a broadband click at
     * every glottal cycle, which read as buzz/distortion.
     */
    static double glottalPulse(double phase, double oq, double rp) {
        if (phase < oq) {
            double t = phase / oq;
            return 3 * t * t - 2 * t * t * t;
        }
        if (phase < oq + rp) {
            double t = (phase - oq) / rp;
            return (1 - t) * (1 - t) * (1 - t);
        }
        return 0;
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
