package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The artifact audit must catch the failure shapes neural vocoders are known
 * for: a steady tonal whine, impulsive clicks, and a DC offset — while clean
 * speech-like signals pass. Synthetic signals keep this deterministic.
 */
public class SpectralAuditTest {

    private static final int RATE = 22050;

    /** Voiced-ish harmonic tone with vibrato and decaying gaps. */
    private static float[] cleanSpeechLike() {
        int length = RATE * 2;
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            double t = i / (double) RATE;
            double gate = (Math.sin(2.0 * Math.PI * 2.5 * t) > -0.2) ? 1.0 : 0.02;
            double vibrato = 1.0 + 0.01 * Math.sin(2.0 * Math.PI * 5.0 * t);
            double value = Math.sin(2.0 * Math.PI * 140.0 * vibrato * t)
                    + 0.5 * Math.sin(2.0 * Math.PI * 280.0 * vibrato * t)
                    + 0.25 * Math.sin(2.0 * Math.PI * 560.0 * t);
            samples[i] = (float) (value * 0.25 * gate);
        }
        return samples;
    }

    @Test
    public void cleanSpeechLikeSignalPasses() {
        SpectralAudit.Report report = SpectralAudit.audit(cleanSpeechLike(), RATE);
        System.out.println("SpectralAuditTest clean: " + report);
        assertTrue("clean signal should pass: " + report.failures, report.passes());
    }

    @Test
    public void steadyTonalWhineFails() {
        float[] samples = cleanSpeechLike();
        // A 6 kHz vocoder-style whine riding 20 dB under the voice.
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) RATE;
            samples[i] += 0.025f * Math.sin(2.0 * Math.PI * 6000.0 * t);
        }
        SpectralAudit.Report report = SpectralAudit.audit(samples, RATE);
        System.out.println("SpectralAuditTest whine: " + report);
        assertFalse("a steady 6 kHz whine must fail the audit", report.passes());
    }

    @Test
    public void impulsiveClicksFail() {
        float[] samples = cleanSpeechLike();
        // Truncation-style clicks in the gaps (gap centers sit at
        // t = 0.3 + 0.4k for the 2.5 Hz gate): five in two seconds.
        for (int k = 0; k < 5; k++) {
            int i = (int) ((0.3 + 0.4 * k) * RATE);
            samples[i] = 0.9f;
            samples[i + 1] = -0.9f;
        }
        SpectralAudit.Report report = SpectralAudit.audit(samples, RATE);
        System.out.println("SpectralAuditTest clicks: " + report);
        assertFalse("impulsive clicks must fail the audit", report.passes());
    }

    @Test
    public void dcOffsetFails() {
        float[] samples = cleanSpeechLike();
        for (int i = 0; i < samples.length; i++) {
            samples[i] += 0.05f;
        }
        SpectralAudit.Report report = SpectralAudit.audit(samples, RATE);
        assertFalse("a DC offset must fail the audit", report.passes());
    }

    @Test
    public void tooShortToAuditFails() {
        SpectralAudit.Report report = SpectralAudit.audit(new float[100], RATE);
        assertFalse(report.passes());
    }
}
