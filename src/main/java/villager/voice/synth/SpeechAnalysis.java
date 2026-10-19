package villager.voice.synth;

import java.util.ArrayList;
import java.util.List;

/**
 * Pitch analysis for the re-voicing path.
 *
 * <p>Mirrors the F0 estimator in {@code tools/villager_voice/analyze.py}. Two
 * details in there were hard won and are reproduced deliberately:</p>
 *
 * <ul>
 *   <li><b>Octave protection.</b> Breathy or noisy voiced frames often peak at
 *       twice F0, so a 116 Hz voice measures as 238 Hz. The estimator takes the
 *       <em>shortest</em> lag whose normalised correlation is within 8% of the
 *       best, and requires that lag to be a genuine local peak -- otherwise
 *       noise on a short lag can win and halve the period.</li>
 *   <li><b>Parabolic interpolation</b> around the peak, for sub-sample period
 *       accuracy. Integer-lag resolution at 24 kHz is about 1.4% of a 116 Hz
 *       period, which is larger than the jitter being measured.</li>
 * </ul>
 *
 * <p>The full LPC/formant/jitter/HNR analyser stays in Python: it is the
 * verification harness, and the Java side only needs pitch to drive PSOLA.</p>
 */
public final class SpeechAnalysis {

    /** Analysis hop for the pitch track, ~6.7 ms at 24 kHz. */
    public static final int HOP = 160;
    /** Analysis frame, 20 ms at 24 kHz. */
    public static final int FRAME = 480;
    /** Correlation peak below which a frame is called unvoiced. */
    public static final double VOICING_CONFIDENCE = 0.40;

    private SpeechAnalysis() {
    }

    /** One pitch estimate: {@code [f0Hz, confidence]}, with f0 = 0 when unvoiced. */
    public static final class Pitch {
        public final double f0;
        public final double confidence;

        Pitch(double f0, double confidence) {
            this.f0 = f0;
            this.confidence = confidence;
        }

        public boolean isVoiced() {
            return f0 > 0.0 && confidence >= VOICING_CONFIDENCE;
        }
    }

    /**
     * Autocorrelation pitch with octave protection and parabolic interpolation.
     *
     * @param frame      the analysis window
     * @param sampleRate samples per second
     * @param fMin       lowest plausible F0 in Hz
     * @param fMax       highest plausible F0 in Hz
     */
    public static Pitch estimateF0(double[] frame, int sampleRate, double fMin, double fMax) {
        int n = frame.length;
        if (n < 32) {
            return new Pitch(0.0, 0.0);
        }
        double mean = 0.0;
        for (double value : frame) {
            mean += value;
        }
        mean /= n;
        double[] centred = new double[n];
        for (int i = 0; i < n; i++) {
            centred[i] = frame[i] - mean;
        }
        int lagMin = Math.max(2, (int) (sampleRate / fMax));
        int lagMax = Math.min(n - 1, (int) (sampleRate / fMin));
        if (lagMax <= lagMin) {
            return new Pitch(0.0, 0.0);
        }

        double zeroLag = 0.0;
        for (double value : centred) {
            zeroLag += value * value;
        }
        if (zeroLag <= 1e-9) {
            return new Pitch(0.0, 0.0);
        }

        // Correlation by lag, so the octave search can look a lag either side.
        double[] correlations = new double[n];
        int bestLag = -1;
        double bestValue = -1.0;
        for (int lag = lagMin; lag <= lagMax; lag++) {
            double total = 0.0;
            for (int i = lag; i < n; i++) {
                total += centred[i] * centred[i - lag];
            }
            // The biased estimator loses energy with lag; undoing that keeps a
            // long period from looking weaker than a short one.
            double normalised = total / (zeroLag * (1.0 - lag / (double) n));
            correlations[lag] = normalised;
            if (normalised > bestValue) {
                bestValue = normalised;
                bestLag = lag;
            }
        }

        if (bestLag < 0 || bestValue < 0.25) {
            return new Pitch(0.0, Math.max(0.0, bestValue));
        }

        int candidate = bestLag;
        for (int lag = lagMin + 1; lag < bestLag - 1; lag++) {
            double value = correlations[lag];
            if (value < 0.92 * bestValue) {
                continue;
            }
            double left = lag - 1 >= 0 ? correlations[lag - 1] : -1.0;
            double right = lag + 1 < n ? correlations[lag + 1] : -1.0;
            if (value >= left && value >= right) {
                candidate = lag;
                break;
            }
        }
        if (candidate != bestLag && candidate > 0) {
            bestLag = candidate;
            bestValue = correlations[candidate];
        }

        double left = bestLag - 1 >= 0 && correlations[bestLag - 1] != 0.0
                ? correlations[bestLag - 1] : bestValue;
        double right = bestLag + 1 < n && correlations[bestLag + 1] != 0.0
                ? correlations[bestLag + 1] : bestValue;
        double denominator = left - 2.0 * bestValue + right;
        double offset = 0.0;
        if (Math.abs(denominator) > 1e-12) {
            offset = 0.5 * (left - right) / denominator;
            offset = Math.max(-1.0, Math.min(1.0, offset));
        }
        double period = bestLag + offset;
        if (period <= 0) {
            return new Pitch(0.0, 0.0);
        }
        return new Pitch(sampleRate / period, Math.min(1.0, bestValue));
    }

    /** Convenience overload using the default 60-400 Hz search range. */
    public static Pitch estimateF0(double[] frame, int sampleRate) {
        return estimateF0(frame, sampleRate, 60.0, 400.0);
    }

    /** A pitch track on a fixed hop grid. */
    public static final class Track {
        public final double[] times;
        public final double[] f0;
        public final boolean[] voiced;

        Track(double[] times, double[] f0, boolean[] voiced) {
            this.times = times;
            this.f0 = f0;
            this.voiced = voiced;
        }

        /** Median F0 over the voiced frames, or 0 when there are none. */
        public double medianVoicedF0() {
            List<Double> values = new ArrayList<Double>();
            for (int i = 0; i < f0.length; i++) {
                if (voiced[i] && f0[i] > 0) {
                    values.add(Double.valueOf(f0[i]));
                }
            }
            if (values.isEmpty()) {
                return 0.0;
            }
            java.util.Collections.sort(values);
            int mid = values.size() / 2;
            return values.size() % 2 == 1 ? values.get(mid).doubleValue()
                    : 0.5 * (values.get(mid - 1).doubleValue() + values.get(mid).doubleValue());
        }

        public double voicedFraction() {
            if (voiced.length == 0) {
                return 0.0;
            }
            int count = 0;
            for (boolean flag : voiced) {
                if (flag) {
                    count++;
                }
            }
            return count / (double) voiced.length;
        }
    }

    /**
     * F0 and a voicing decision on a fixed hop grid.
     *
     * @param samples  the signal
     * @param sampleRate samples per second
     * @param fMin     lowest plausible F0
     * @param fMax     highest plausible F0
     */
    public static Track f0Track(float[] samples, int sampleRate, double fMin, double fMax) {
        int count = Math.max(1, (samples.length - FRAME) / HOP);
        if (samples.length <= FRAME) {
            count = 1;
        }
        double[] times = new double[count];
        double[] f0 = new double[count];
        boolean[] voiced = new boolean[count];
        double[] frame = new double[FRAME];
        for (int k = 0; k < count; k++) {
            int start = k * HOP;
            if (start + FRAME > samples.length) {
                break;
            }
            for (int i = 0; i < FRAME; i++) {
                frame[i] = samples[start + i];
            }
            Pitch pitch = estimateF0(frame, sampleRate, fMin, fMax);
            times[k] = (start + FRAME * 0.5) / (double) sampleRate;
            f0[k] = pitch.f0;
            voiced[k] = pitch.isVoiced();
        }
        return new Track(times, medianSmooth(f0, voiced), voiced);
    }

    /** Convenience overload using the default search range. */
    public static Track f0Track(float[] samples, int sampleRate) {
        return f0Track(samples, sampleRate, 60.0, 400.0);
    }

    /**
     * Median-smooth the F0 track inside voiced runs only.
     *
     * <p>Averaging across an octave error would place the pitch somewhere
     * between the two candidates, which is worse than either.</p>
     */
    static double[] medianSmooth(double[] values, boolean[] voiced) {
        double[] out = values.clone();
        int half = 2;
        for (int i = 0; i < values.length; i++) {
            if (!voiced[i]) {
                continue;
            }
            int lo = Math.max(0, i - half);
            int hi = Math.min(values.length, i + half + 1);
            List<Double> neighbours = new ArrayList<Double>();
            for (int j = lo; j < hi; j++) {
                if (voiced[j] && values[j] > 0) {
                    neighbours.add(Double.valueOf(values[j]));
                }
            }
            if (neighbours.isEmpty()) {
                continue;
            }
            java.util.Collections.sort(neighbours);
            int mid = neighbours.size() / 2;
            out[i] = neighbours.size() % 2 == 1 ? neighbours.get(mid).doubleValue()
                    : 0.5 * (neighbours.get(mid - 1).doubleValue()
                            + neighbours.get(mid).doubleValue());
        }
        return out;
    }
}
