package villager.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Frequency-domain artifact audit for neural TTS output.
 *
 * <p>Neural vocoders fail in recognizable spectral ways, and this audit looks
 * for each of them directly instead of trusting a waveform glance:</p>
 * <ul>
 *   <li><b>Gap hiss</b> — broadband high-frequency energy sitting in the
 *   pauses between words, where the room should be silent. Measured as the
 *   4–10 kHz energy ratio of quiet frames against voiced frames.</li>
 *   <li><b>Tonal whine</b> — narrow peaks (vocoder comb/harmonic whine) in
 *   the long-term average spectrum, measured as the strongest peak's
 *   prominence over its local neighborhood in the 1–8 kHz band.</li>
 *   <li><b>Chirps and crackle</b> — single-frame spectral bursts, measured as
 *   spectral-flux spikes above a multiple of the median flux.</li>
 *   <li><b>Clicks</b> — time-domain discontinuities: a sample-to-sample jump
 *   far beyond the local signal level (truncation/padding cuts).</li>
 *   <li><b>DC and clipping</b> — mean offset and the fraction of samples at
 *   full scale.</li>
 * </ul>
 *
 * <p>Pure Java (iterative radix-2 FFT, Hann window), no dependencies, fast
 * enough to gate every headless render test. Thresholds in {@link Limits}
 * were calibrated on real Coqui VITS and Kokoro renders plus the recorded
 * clip corpus: clean neural speech passes with margin, the known-bad
 * artifact shapes (steady 6 kHz whine, white-noise gaps, impulsive clicks)
 * fail loudly.</p>
 */
public final class SpectralAudit {

    /** Pass/fail limits; see the calibration note in the class javadoc. */
    public static final class Limits {
        /**
         * Mean 4–10 kHz energy of quiet frames, as a fraction of the peak
         * frame's total energy. Silence is ~1e-6; audible hiss is ~1e-3.
         */
        public double maxGapHissEnergy = 0.002;
        /** Strongest 1–8 kHz tonal peak prominence, in dB. */
        public double maxWhineProminenceDb = 18.0;
        /** Allowed flux spikes per second of audio. */
        public double maxFluxSpikesPerSecond = 6.0;
        /** Allowed time-domain clicks per second of audio. */
        public double maxClicksPerSecond = 2.0;
        /** Mean absolute offset ceiling. */
        public float maxDcOffset = 0.01f;
        /** Fraction of samples allowed at full scale. */
        public double maxClippedFraction = 0.001;

        public Limits() {
        }
    }

    /** One audit run: every metric plus the verdict. */
    public static final class Report {
        public final double seconds;
        public final double gapHissEnergy;
        public final double whineProminenceDb;
        public final double whineFrequencyHz;
        public final double fluxSpikesPerSecond;
        public final double clicksPerSecond;
        public final float dcOffset;
        public final double clippedFraction;
        public final List<String> failures;

        Report(double seconds, double gapHissEnergy, double whineProminenceDb,
               double whineFrequencyHz,               double fluxSpikesPerSecond, double clicksPerSecond, float dcOffset,
               double clippedFraction, List<String> failures) {
            this.seconds = seconds;
            this.gapHissEnergy = gapHissEnergy;
            this.whineProminenceDb = whineProminenceDb;
            this.whineFrequencyHz = whineFrequencyHz;
            this.fluxSpikesPerSecond = fluxSpikesPerSecond;
            this.clicksPerSecond = clicksPerSecond;
            this.dcOffset = dcOffset;
            this.clippedFraction = clippedFraction;
            this.failures = Collections.unmodifiableList(new ArrayList<String>(failures));
        }

        public boolean passes() {
            return failures.isEmpty();
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "%.2fs hiss=%.5f whine=%.1fdB@%.0fHz flux=%.1f/s clicks=%.1f/s dc=%.4f clip=%.4f %s",
                    seconds, gapHissEnergy, whineProminenceDb, whineFrequencyHz,
                    fluxSpikesPerSecond, clicksPerSecond, dcOffset, clippedFraction,
                    passes() ? "PASS" : "FAIL " + failures);
        }
    }

    private static final int FRAME = 2048;
    private static final int HOP = 1024;

    private SpectralAudit() {
    }

    /** Audit rendered speech against the default limits. */
    public static Report audit(float[] samples, int sampleRate) {
        return audit(samples, sampleRate, new Limits());
    }

    /** Audit rendered speech against explicit limits. */
    public static Report audit(float[] samples, int sampleRate, Limits limits) {
        List<String> failures = new ArrayList<String>();
        if (samples == null || samples.length < FRAME || sampleRate <= 0) {
            failures.add("too short to audit");
            return new Report(0, 1, 99, 0, 99, 99, 1, 1, failures);
        }
        double seconds = samples.length / (double) sampleRate;

        float dc = mean(samples);
        double clipped = clippedFraction(samples);

        List<Frame> frames = analyze(samples, sampleRate);
        double gapHiss = gapHissEnergy(frames);
        double[] whine = whineProminence(frames, sampleRate);
        double fluxSpikes = fluxSpikesPerSecond(frames, seconds);
        double clicks = clicksPerSecond(samples, seconds);

        if (gapHiss > limits.maxGapHissEnergy) {
            failures.add(String.format(java.util.Locale.ROOT,
                    "gap hiss %.5f", gapHiss));
        }
        if (whine[0] > limits.maxWhineProminenceDb) {
            failures.add(String.format(java.util.Locale.ROOT,
                    "tonal whine %.1fdB at %.0fHz", whine[0], whine[1]));
        }
        if (fluxSpikes > limits.maxFluxSpikesPerSecond) {
            failures.add(String.format(java.util.Locale.ROOT,
                    "spectral bursts %.1f/s", fluxSpikes));
        }
        if (clicks > limits.maxClicksPerSecond) {
            failures.add(String.format(java.util.Locale.ROOT,
                    "clicks %.1f/s", clicks));
        }
        if (Math.abs(dc) > limits.maxDcOffset) {
            failures.add(String.format(java.util.Locale.ROOT, "dc %.4f", dc));
        }
        if (clipped > limits.maxClippedFraction) {
            failures.add(String.format(java.util.Locale.ROOT,
                    "clipped %.4f", clipped));
        }
        return new Report(seconds, gapHiss, whine[0], whine[1],
                fluxSpikes, clicks, dc, clipped, failures);
    }

    // ── Frames ───────────────────────────────────────────────────────────────

    private static final class Frame {
        final double energy;
        final double highBand;
        final double[] magnitude;

        Frame(double energy, double highBand, double[] magnitude) {
            this.energy = energy;
            this.highBand = highBand;
            this.magnitude = magnitude;
        }
    }

    private static List<Frame> analyze(float[] samples, int sampleRate) {
        double[] window = new double[FRAME];
        for (int i = 0; i < FRAME; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / FRAME);
        }
        double[] real = new double[FRAME];
        double[] imag = new double[FRAME];
        int bins = FRAME / 2;
        int highFrom = Math.max(1, (int) (4000.0 * FRAME / sampleRate));
        int highTo = Math.min(bins - 1, (int) (10000.0 * FRAME / sampleRate));
        List<Frame> frames = new ArrayList<Frame>();
        for (int start = 0; start + FRAME <= samples.length; start += HOP) {
            double energy = 0.0;
            for (int i = 0; i < FRAME; i++) {
                double value = samples[start + i] * window[i];
                real[i] = value;
                imag[i] = 0.0;
                energy += value * value;
            }
            fft(real, imag);
            double[] magnitude = new double[bins];
            double highBand = 0.0;
            for (int b = 0; b < bins; b++) {
                double mag = Math.sqrt(real[b] * real[b] + imag[b] * imag[b]);
                magnitude[b] = mag;
                if (b >= highFrom && b <= highTo) highBand += mag * mag;
            }
            frames.add(new Frame(energy, highBand, magnitude));
        }
        return frames;
    }

    /** Iterative radix-2 FFT, in place. */
    static void fft(double[] real, double[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j &= ~bit;
            }
            j |= bit;
            if (i < j) {
                double swap = real[i];
                real[i] = real[j];
                real[j] = swap;
                swap = imag[i];
                imag[i] = imag[j];
                imag[j] = swap;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wReal = Math.cos(angle);
            double wImag = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double cReal = 1.0;
                double cImag = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    double uReal = real[i + k];
                    double uImag = imag[i + k];
                    double vReal = real[i + k + len / 2] * cReal
                            - imag[i + k + len / 2] * cImag;
                    double vImag = real[i + k + len / 2] * cImag
                            + imag[i + k + len / 2] * cReal;
                    real[i + k] = uReal + vReal;
                    imag[i + k] = uImag + vImag;
                    real[i + k + len / 2] = uReal - vReal;
                    imag[i + k + len / 2] = uImag - vImag;
                    double nextReal = cReal * wReal - cImag * wImag;
                    cImag = cReal * wImag + cImag * wReal;
                    cReal = nextReal;
                }
            }
        }
    }

    // ── Metrics ──────────────────────────────────────────────────────────────

    /**
     * Mean 4–10 kHz energy of quiet frames against the peak frame's total
     * energy. Clean gaps are digital silence (~1e-6); vocoder hiss fills the
     * top band even where nobody is speaking (~1e-3 and up). Absolute, not
     * relative to voiced frames, so low-pitched voices cannot skew it.
     */
    private static double gapHissEnergy(List<Frame> frames) {
        double peak = 0.0;
        double[] totals = new double[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            double total = 0.0;
            for (double mag : frames.get(i).magnitude) total += mag * mag;
            totals[i] = total;
            peak = Math.max(peak, total);
        }
        if (peak <= 0.0) return 1.0;
        double quietHigh = 0.0;
        int quietFrames = 0;
        for (int i = 0; i < frames.size(); i++) {
            if (totals[i] < peak * 0.001) {
                quietHigh += frames.get(i).highBand;
                quietFrames++;
            }
        }
        if (quietFrames == 0) return 0.0;
        return quietHigh / quietFrames / peak;
    }

    /**
     * Strongest narrowband peak in 1–8 kHz against its local median
     * neighborhood. Returns {prominenceDb, frequencyHz}.
     */
    private static double[] whineProminence(List<Frame> frames, int sampleRate) {
        int bins = FRAME / 2;
        double[] average = new double[bins];
        for (Frame frame : frames) {
            for (int b = 0; b < bins; b++) average[b] += frame.magnitude[b];
        }
        for (int b = 0; b < bins; b++) average[b] /= frames.size();
        int from = Math.max(2, (int) (1000.0 * FRAME / sampleRate));
        int to = Math.min(bins - 2, (int) (8000.0 * FRAME / sampleRate));
        double bestDb = 0.0;
        double bestHz = 0.0;
        for (int b = from; b <= to; b++) {
            double local = 0.0;
            int count = 0;
            for (int k = Math.max(from, b - 12); k <= Math.min(to, b + 12); k++) {
                if (Math.abs(k - b) <= 1) continue;
                local += average[k];
                count++;
            }
            if (count == 0 || local <= 0.0 || average[b] <= 0.0) continue;
            double db = 20.0 * Math.log10(average[b] / (local / count));
            if (db > bestDb) {
                bestDb = db;
                bestHz = b * sampleRate / (double) FRAME;
            }
        }
        return new double[]{bestDb, bestHz};
    }

    /** Frames whose spectrum jumps far beyond the median change rate. */
    private static double fluxSpikesPerSecond(List<Frame> frames, double seconds) {
        if (frames.size() < 3 || seconds <= 0.0) return 0.0;
        List<Double> flux = new ArrayList<Double>();
        for (int i = 1; i < frames.size(); i++) {
            double[] a = frames.get(i - 1).magnitude;
            double[] b = frames.get(i).magnitude;
            double sum = 0.0;
            for (int k = 0; k < a.length; k++) {
                double diff = b[k] - a[k];
                if (diff > 0) sum += diff * diff;
            }
            flux.add(Math.sqrt(sum));
        }
        List<Double> sorted = new ArrayList<Double>(flux);
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        if (median <= 0.0) return 0.0;
        int spikes = 0;
        for (double value : flux) {
            if (value > median * 12.0) spikes++;
        }
        return spikes / seconds;
    }

    /**
     * Sample jumps far beyond the local signal level (cuts, dropouts). Only
     * quiet regions are scanned: a discontinuity inside loud speech is
     * masked, but one in a gap is the truncation click players actually
     * hear.
     */
    private static double clicksPerSecond(float[] samples, double seconds) {
        if (seconds <= 0.0) return 0.0;
        int window = 64;
        int clicks = 0;
        int i = 0;
        while (i < samples.length - 1) {
            double local = 0.0;
            int from = Math.max(0, i - window);
            for (int k = from; k < i; k++) local += samples[k] * samples[k];
            double rms = Math.sqrt(local / Math.max(1, i - from));
            double jump = Math.abs(samples[i + 1] - samples[i]);
            if (rms < 0.1 && jump > Math.max(0.08, rms * 12.0)) {
                clicks++;
                i += window;
            } else {
                i++;
            }
        }
        return clicks / seconds;
    }

    private static float mean(float[] samples) {
        double sum = 0.0;
        for (float sample : samples) sum += sample;
        return (float) (sum / samples.length);
    }

    private static double clippedFraction(float[] samples) {
        int clipped = 0;
        for (float sample : samples) {
            if (Math.abs(sample) >= 0.999f) clipped++;
        }
        return clipped / (double) samples.length;
    }
}
