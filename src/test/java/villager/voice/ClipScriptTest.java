package villager.voice;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ClipScriptTest {

    private static final String MANIFEST = "{"
            + "\"version\":1,"
            + "\"clips\":["
            + "{\"id\":\"addon:a\",\"kind\":\"addon\",\"file\":\"a\","
            + "\"text\":\"Ahh! Help! Run away!\",\"duration\":1.5,"
            + "\"cues\":[[0,\"Ahh! Help!\"],[0.9,\"Run away!\"]]},"
            + "{\"id\":\"addon:b\",\"kind\":\"addon\",\"file\":\"b\","
            + "\"text\":\"Look at that beautiful wall!\",\"duration\":2.0},"
            + "{\"id\":\"addon:c\",\"kind\":\"addon\",\"file\":\"c\","
            + "\"text\":\"I made a terrible mistake.\",\"duration\":1.8},"
            + "{\"id\":\"addon:d\",\"kind\":\"addon\",\"file\":\"d\","
            + "\"text\":\"Hello there, fellow villager!\",\"duration\":1.2}"
            + "]}";

    @Test
    public void topicPicksReturnRealCatalogClips() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        Random rng = new Random(3);
        for (ClipScript.Topic topic : ClipScript.Topic.values()) {
            for (int i = 0; i < 20; i++) {
                ClipIndex.Clip clip = ClipScript.pick(index, topic, rng,
                        Collections.<String>emptyList());
                assertNotNull("a topic always resolves to a clip", clip);
                assertTrue("must be a catalog clip",
                        index.clips().contains(clip));
                assertTrue("must be a real clip transcript, never invented",
                        clip.text.length() > 0);
            }
        }
    }

    @Test
    public void panicTopicPrefersPanickyClips() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        Random rng = new Random(11);
        int panicky = 0;
        for (int i = 0; i < 50; i++) {
            ClipIndex.Clip clip = ClipScript.pick(index, ClipScript.Topic.PANIC,
                    rng, Collections.<String>emptyList());
            if (clip.id.equals("addon:a")) panicky++;
        }
        assertTrue("panic lines should dominate (" + panicky + "/50)",
                panicky > 40);
    }

    @Test
    public void scoreRewardsTopicalKeywords() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        ClipIndex.Clip panic = index.exact("Ahh! Help! Run away!");
        ClipIndex.Clip hello = index.exact("Hello there, fellow villager!");
        assertTrue(ClipScript.score(panic, ClipScript.Topic.PANIC)
                > ClipScript.score(hello, ClipScript.Topic.PANIC));
        assertTrue(ClipScript.score(hello, ClipScript.Topic.GREETING)
                > ClipScript.score(panic, ClipScript.Topic.GREETING));
    }

    @Test
    public void recentClipsAreAvoidedWhenPossible() {
        ClipIndex index = ClipIndex.parse(MANIFEST);
        ClipIndex.Clip clip = ClipScript.pick(index, ClipScript.Topic.PANIC,
                new Random(5), Arrays.asList("addon:a"));
        assertNotNull(clip);
        assertTrue("avoids the recent clip when alternatives exist",
                !"addon:a".equals(clip.id));
    }

    @Test
    public void emotionsAreValidSpeechOptionsEmotions() {
        Set<String> valid = new HashSet<>(Arrays.asList(
                "neutral", "happy", "sad", "angry", "scared"));
        for (ClipScript.Topic topic : ClipScript.Topic.values()) {
            assertTrue("emotion valid for " + topic,
                    valid.contains(ClipScript.emotionFor(topic)));
        }
        assertEquals("scared", ClipScript.emotionFor(ClipScript.Topic.PANIC));
        assertEquals("angry", ClipScript.emotionFor(ClipScript.Topic.BLUFF));
    }
}
