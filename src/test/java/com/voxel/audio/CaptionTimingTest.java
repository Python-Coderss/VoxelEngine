package com.voxel.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Captions must follow the audio, not an estimate: the wording has to land on
 * the recording's voiced stretches, with the pauses preserved.
 */
public class CaptionTimingTest {

    private static final int RATE = 16000;

    /** Two short bursts separated by a real pause, with lead-in silence. */
    private static float[] twoBursts() {
        float[] samples = new float[(int) (RATE * 2.5f)];
        burst(samples, 0.40f, 1.00f, 220f);
        burst(samples, 1.50f, 2.10f, 180f);
        return samples;
    }

    private static void burst(float[] samples, float from, float to, float hz) {
        int first = (int) (from * RATE);
        int last = Math.min(samples.length, (int) (to * RATE));
        for (int i = first; i < last; i++) {
            samples[i] = 0.5f * (float) Math.sin(2 * Math.PI * hz * i / RATE);
        }
    }

    @Test
    public void wordsLandInTheVoicedStretches() {
        float[] times = CaptionTiming.wordTimes("we built this wall", twoBursts(), RATE);
        assertNotNull("a recording with speech must yield timings", times);
        assertEquals(4, times.length);
        assertTrue("first word starts with the first burst", times[0] >= 0.38f);
        assertTrue("lead-in silence is not skipped", times[0] < 0.55f);
        assertTrue("second word follows the first", times[1] >= times[0]);
        assertTrue("later words cross the pause",
                times[2] >= 1.48f || times[3] >= 1.48f);
        assertTrue("nothing is placed after the audio", times[3] <= 2.12f);
    }

    @Test
    public void aPauseHoldsTheWordsAheadOfIt() {
        float[] times = CaptionTiming.wordTimes("well ... okay then", twoBursts(), RATE);
        assertNotNull(times);
        // At least one word must wait for the second burst instead of racing
        // through the silence on evenly-spaced time.
        boolean crossed = false;
        for (float time : times) {
            if (time >= 1.48f) crossed = true;
        }
        assertTrue("some word belongs to the second burst", crossed);
    }

    @Test
    public void silenceHasNoTiming() {
        assertNull(CaptionTiming.wordTimes("nothing to hear",
                new float[RATE], RATE));
        assertNull(CaptionTiming.wordTimes("", twoBursts(), RATE));
        assertNull(CaptionTiming.wordTimes("no audio at all", new float[0], RATE));
    }

    @Test
    public void timingIsMonotonicAndInsideTheClip() {
        float[] samples = twoBursts();
        float[] times = CaptionTiming.wordTimes(
                "one two three four five six seven eight", samples, RATE);
        assertNotNull(times);
        float duration = samples.length / (float) RATE;
        for (int i = 0; i < times.length; i++) {
            assertTrue("word " + i + " is inside the clip", times[i] >= 0f
                    && times[i] <= duration);
            if (i > 0) {
                assertTrue("word times never go backwards",
                        times[i] >= times[i - 1]);
            }
        }
    }

    @Test
    public void syllablesWeightLongWords() {
        assertEquals(1, CaptionTiming.syllables("cat"));
        assertEquals(2, CaptionTiming.syllables("building"));
        assertEquals(3, CaptionTiming.syllables("beautiful"));
        assertEquals(1, CaptionTiming.syllables(""));
    }
}
