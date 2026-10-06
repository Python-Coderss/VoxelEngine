package villager.voice;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClipIndexTest {

    private static final String MANIFEST = "{"
            + "\"version\":1,"
            + "\"clips\":["
            + "{\"id\":\"addon:a\",\"kind\":\"addon\",\"file\":\"a\",\"text\":\"Hello there!\",\"duration\":1.2},"
            + "{\"id\":\"addon:b\",\"kind\":\"addon\",\"file\":\"b\",\"text\":\"What are you doing?\",\"duration\":2.0},"
            + "{\"id\":\"addon:c\",\"kind\":\"addon\",\"file\":\"c\",\"text\":\"I am building a wall.\",\"duration\":2.5},"
            + "{\"id\":\"addon:d\",\"kind\":\"addon\",\"file\":\"d\",\"text\":\"\",\"duration\":0.8},"
            + "{\"id\":\"teavrsp:hello\",\"kind\":\"teavrsp\",\"file\":\"Hello.wav\",\"text\":\"Hello\",\"duration\":0.6}"
            + "]}";

    @Test
    public void parsesClipsAndSplitsVocalizations() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        assertEquals(5, index.size());
        assertEquals(4, index.spokenClips().size());
        assertEquals(1, index.vocalClips().size());
    }

    @Test
    public void exactTranscriptMatchesIgnorePunctuation() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        ClipIndex.Clip clip = index.exact("hello there");
        assertNotNull(clip);
        assertEquals("addon:a", clip.id);
        assertNull(index.exact("nothing like this at all"));
    }

    @Test
    public void bestMatchPrefersMostSharedWords() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        // Not an exact match (words reordered + extra), but clearly closest
        // to "What are you doing?".
        ClipIndex.Clip clip = index.bestMatch("doing what are you doing",
                new Random(1), Collections.<String>emptyList());
        assertNotNull(clip);
        assertEquals("addon:b", clip.id);
    }

    @Test
    public void topicalMatchBeatsUnrelatedClips() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        ClipIndex.Clip clip = index.bestMatch("that wall is mine",
                new Random(1), Collections.<String>emptyList());
        assertEquals("wall talk should pick the wall clip", "addon:c", clip.id);
    }

    @Test
    public void unmatchedLinesFallBackToVocalizations() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        ClipIndex.Clip clip = index.bestMatch("xylophones quantum zebra",
                new Random(1), Collections.<String>emptyList());
        assertNotNull(clip);
        assertTrue("fallback must be a vocalization", clip.isVocalization());
    }

    @Test
    public void recentClipsAreAvoidedWhenAlternativesExist() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        ClipIndex.Clip clip = index.bestMatch("doing what are you doing",
                new Random(1), Arrays.asList("addon:b"));
        assertNotNull(clip);
        assertTrue("should avoid the recent clip", !"addon:b".equals(clip.id));
    }

    @Test
    public void zeroOverlapStillSpeaksSomething() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        ClipIndex.Clip clip = index.bestMatch("",
                new Random(3), Collections.<String>emptyList());
        assertNotNull(clip);
    }
}
