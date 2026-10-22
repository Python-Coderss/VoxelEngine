package com.voxel.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class ClipWordTimingTest {

    private static final String DOC = "{"
            + "\"version\":1,"
            + "\"clips\":{"
            + "\"addon:x:a\":{\"words\":3,\"word_starts\":[0.12,0.5,1.4]},"
            + "\"addon:x:b\":{\"words\":2,\"word_starts\":[0.2,0.9]}"
            + "}}";

    @Test
    public void parsesMeasuredWordStarts() {
        ClipWordTiming timing = ClipWordTiming.parse(DOC);
        assertNotNull(timing);
        assertEquals(2, timing.size());
        float[] starts = timing.scaled("addon:x:a", 3, 1.0);
        assertNotNull(starts);
        assertEquals(0.12f, starts[0], 1e-4f);
        assertEquals(1.4f, starts[2], 1e-4f);
    }

    @Test
    public void scalesWithThePlaybackFactor() {
        ClipWordTiming timing = ClipWordTiming.parse(DOC);
        float[] fast = timing.scaled("addon:x:a", 3, 2.0);
        float[] slow = timing.scaled("addon:x:a", 3, 0.5);
        assertEquals("a clip played twice as fast reaches word 2 at half the time",
                0.7f, fast[2], 1e-4f);
        assertEquals("a slowed clip takes longer", 2.8f, slow[2], 1e-4f);
    }

    @Test
    public void aWordCountMismatchFallsBack() {
        ClipWordTiming timing = ClipWordTiming.parse(DOC);
        assertNull("the caption was edited; do not guess word boundaries",
                timing.scaled("addon:x:a", 4, 1.0));
        assertNull("unknown clip", timing.scaled("addon:missing", 3, 1.0));
        assertNull(timing.scaled(null, 3, 1.0));
    }

    @Test
    public void shippedTimingsMatchTheirTranscripts() throws Exception {
        ClipWordTiming timing = ClipWordTiming.get();
        assertNotNull("the alignment resource must ship", timing);
        assertTrue("the whole catalog was aligned, not a sample", timing.size() > 1000);

        villager.voice.ClipIndex index = villager.voice.ClipIndex.loadDefault();
        int matched = 0;
        for (villager.voice.ClipIndex.Clip clip : index.clips()) {
            int words = clip.text == null || clip.text.trim().isEmpty()
                    ? 0 : clip.text.trim().split("\\s+").length;
            float[] starts = timing.scaled(clip.id, words, 1.0);
            if (starts == null) continue;
            matched++;
            assertEquals("one start per caption word: " + clip.id, words, starts.length);
            for (int i = 1; i < starts.length; i++) {
                assertTrue("word starts never go backwards: " + clip.id,
                        starts[i] >= starts[i - 1]);
            }
        }
        assertTrue("most clips must have usable timings, got " + matched, matched > 1500);
    }

    @Test
    public void brokenInputFallsBackToPcmTiming() {
        assertNull(ClipWordTiming.parse("{ not json"));
        assertNull(ClipWordTiming.parse("{\"clips\":[]}"));
        assertNull(ClipWordTiming.load(null));
    }
}
