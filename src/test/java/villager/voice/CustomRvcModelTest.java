package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the pure DSP helpers in {@link CustomRvcModel}. These never load
 * ONNX models, so they run in a plain `mvnw test` without the 630 MB model
 * bundle.
 */
public class CustomRvcModelTest {

    // ── median3Smooth ───────────────────────────────────────────────────

    @Test
    public void medianRemovesSingleFrameOutlier() {
        float[] raw = {0f, 0f, 100f, 100f, 480f, 100f, 100f, 0f, 0f};
        float[] smoothed = CustomRvcModel.median3Smooth(raw);
        assertEquals(100f, smoothed[4], 1e-6f);
        assertArrayEquals(new float[]{100f, 100f, 100f},
                new float[]{smoothed[2], smoothed[3], smoothed[5]}, 1e-6f);
    }

    @Test
    public void medianKeepsRealGlideIntact() {
        float[] glide = {100f, 110f, 120f, 130f, 140f};
        float[] smoothed = CustomRvcModel.median3Smooth(glide);
        for (int i = 0; i < glide.length; i++) {
            assertEquals(glide[i], smoothed[i], 1e-6f);
        }
    }

    @Test
    public void medianNeverBridgesUnvoicedGaps() {
        float[] raw = {0f, 0f, 150f, 0f, 150f};
        float[] smoothed = CustomRvcModel.median3Smooth(raw);
        assertEquals(0f, smoothed[3], 1e-6f);
        assertEquals(0f, smoothed[0], 1e-6f);
        assertEquals(150f, smoothed[2], 1e-6f);
    }

    // ── noiseGains ──────────────────────────────────────────────────────

    @Test
    public void silenceKeepsFloorGain() {
        float[] silence = new float[8000];
        float[] gains = CustomRvcModel.noiseGains(silence, 40000, 100);
        assertEquals(100, gains.length);
        for (float gain : gains) {
            assertEquals(0.35f, gain, 1e-6f);
        }
    }

    @Test
    public void loudSignalReachesFullGain() {
        float[] loud = new float[8000];
        for (int i = 0; i < loud.length; i++) {
            loud[i] = 0.8f;
        }
        float[] gains = CustomRvcModel.noiseGains(loud, 40000, 50);
        assertEquals(1.0f, gains[25], 1e-6f);
        for (float gain : gains) {
            assertTrue(gain >= 0.35f && gain <= 1.0f);
        }
    }

    @Test
    public void noiseGainsAreDeterministicAndOrdered() {
        float[] audio = new float[16000];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) Math.sin(i * 0.1) * (i < 8000 ? 0.9f : 0.1f);
        }
        float[] first = CustomRvcModel.noiseGains(audio, 40000, 64);
        float[] second = CustomRvcModel.noiseGains(audio, 40000, 64);
        assertArrayEquals(first, second, 0.0f);
        // The loud half must carry more excitation than the quiet half.
        float loudHalf = 0f, quietHalf = 0f;
        for (int i = 0; i < 32; i++) loudHalf += first[i];
        for (int i = 32; i < 64; i++) quietHalf += first[i];
        assertTrue(loudHalf > quietHalf);
    }

    // ── noiseSeed ───────────────────────────────────────────────────────

    @Test
    public void seedIsStableForIdenticalClip() {
        float[] clip = {0.1f, -0.2f, 0.3f, 0.0f, 0.5f};
        assertEquals(CustomRvcModel.noiseSeed(clip), CustomRvcModel.noiseSeed(clip));
    }

    @Test
    public void seedDiffersBetweenClips() {
        assertNotEquals(CustomRvcModel.noiseSeed(new float[]{1f, 2f, 3f}),
                CustomRvcModel.noiseSeed(new float[]{1f, 2f, 4f}));
        assertNotEquals(CustomRvcModel.noiseSeed(new float[]{0f, 0f, 0f}),
                CustomRvcModel.noiseSeed(new float[]{0f, 0f, 0f, 0f}));
        assertNotEquals(CustomRvcModel.noiseSeed(new float[1024]),
                CustomRvcModel.noiseSeed(new float[1025]));
    }

    // ── fitOutput ───────────────────────────────────────────────────────

    @Test
    public void shortOutputIsPaddedWithFadeNotHardEdge() {
        float[] output = new float[100];
        java.util.Arrays.fill(output, 0.5f);
        float[] fitted = CustomRvcModel.fitOutput(output, 120);
        assertEquals(120, fitted.length);
        assertEquals(0.0f, fitted[119], 1e-6f); // padded region stays silent
        assertNotEquals(0.0f, fitted[90], 1e-6f); // copied region untouched
        // No NaN anywhere (peak ceiling math is safe on the ramp).
        for (float sample : fitted) {
            assertTrue(Float.isFinite(sample));
        }
    }

    @Test
    public void longOutputIsTruncated() {
        float[] output = new float[150];
        java.util.Arrays.fill(output, 0.25f);
        float[] fitted = CustomRvcModel.fitOutput(output, 120);
        assertEquals(120, fitted.length);
        assertEquals(0.25f, fitted[119], 1e-6f);
    }

    @Test
    public void exactLengthPassesThroughUnchanged() {
        float[] output = new float[64];
        assertTrue(CustomRvcModel.fitOutput(output, 64) == output);
    }
}