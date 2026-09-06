package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the vocoder comb-whine removal stage in {@link AudioDsp}. The
 * synthetic fixtures reproduce the artifact measured in the v9 clip set: a
 * near-constant tone at a multiple of the 100 Hz frame rate sticking out of
 * the HF noise floor, which must be notched without dulling the rest of the
 * spectrum or carving real (wobbling) speech harmonics.
 */
public class AudioDspCombWhineTest {

    private static final int RATE = 24000;

    /** A steady 4600 Hz tone buried 15 dB under a speech-like noise floor. */
    private static float[] clipWithWhine() {
        int length = RATE; // 1 s, comfortably above the 8192-sample minimum
        float[] x = new float[length];
        java.util.Random rng = new java.util.Random(0x9E3779B9L);
        // Pink-ish floor via a one-pole low-pass on white noise.
        float state = 0.0f;
        for (int i = 0; i < length; i++) {
            state = (float) (0.85f * state + 0.15f * rng.nextGaussian());
            x[i] = 0.03f * state;
        }
        for (int i = 0; i < length; i++) {
            x[i] += 0.006f * (float) Math.sin(2.0 * Math.PI * 4600.0 * i / (double) RATE);
        }
        return x;
    }

    private static double binLevelDb(float[] x, int sampleRate, double hz) {
        int n = 8192;
        double[] re = new double[n];
        double[] im = new double[n];
        int start = x.length / 2 - n / 2;
        for (int i = 0; i < n; i++) {
            re[i] = x[Math.max(0, Math.min(x.length - 1, start + i))]
                    * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        }
        // Reuse the production FFT via reflection is overkill; a direct DFT at
        // one bin is exact and cheap for the assertion.
        double sumRe = 0.0;
        double sumIm = 0.0;
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * hz * i / sampleRate;
            sumRe += re[i] * Math.cos(angle);
            sumIm -= re[i] * Math.sin(angle);
        }
        return 10.0 * Math.log10(sumRe * sumRe + sumIm * sumIm + 1e-20);
    }

    @Test
    public void steadyToneAboveThresholdIsNotched() {
        float[] x = clipWithWhine();
        double before = binLevelDb(x, RATE, 4600.0);
        AudioDsp.applyCombWhineCleanup(x, RATE);
        double after = binLevelDb(x, RATE, 4600.0);
        assertTrue("whine should drop by >= 8 dB, was " + (before - after),
                before - after >= 8.0);
    }

    @Test
    public void wobblingSpeechHarmonicIsPreserved() {
        int length = RATE;
        float[] x = new float[length];
        java.util.Random rng = new java.util.Random(1234L);
        float state = 0.0f;
        for (int i = 0; i < length; i++) {
            state = (float) (0.85f * state + 0.15f * rng.nextGaussian());
            x[i] = 0.03f * state;
        }
        // A harmonic that wobbles +/-30 cents around 4400 Hz, like real speech.
        double phase = 0.0;
        for (int i = 0; i < length; i++) {
            double cents = 30.0 * Math.sin(2.0 * Math.PI * 3.0 * i / (double) length);
            double hz = 4400.0 * Math.pow(2.0, cents / 1200.0);
            phase += 2.0 * Math.PI * hz / (double) RATE;
            x[i] += 0.01f * (float) Math.sin(phase);
        }
        double before = binLevelDb(x, RATE, 4400.0);
        AudioDsp.applyCombWhineCleanup(x, RATE);
        double after = binLevelDb(x, RATE, 4400.0);
        assertTrue("wobbling harmonic must survive, lost " + (before - after) + " dB",
                before - after <= 3.0);
    }

    @Test
    public void cleanFloorIsReturnedUnchanged() {
        // Speech-like noise floor with no added tone: nothing is above the
        // local spectral median, so no notch may be applied at all.
        int length = RATE;
        float[] x = new float[length];
        java.util.Random rng = new java.util.Random(5678L);
        float state = 0.0f;
        for (int i = 0; i < length; i++) {
            state = (float) (0.85f * state + 0.15f * rng.nextGaussian());
            x[i] = 0.03f * state;
        }
        float[] copy = x.clone();
        AudioDsp.applyCombWhineCleanup(x, RATE);
        for (int i = 0; i < x.length; i++) {
            assertEquals(copy[i], x[i], 0.0f);
        }
    }

    @Test
    public void shortClipIsSkipped() {
        float[] x = new float[1024];
        float[] copy = x.clone();
        AudioDsp.applyCombWhineCleanup(x, RATE);
        assertTrue(java.util.Arrays.equals(copy, x));
    }
}
