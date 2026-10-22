package com.voxel.game;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** MCSM decision memory: flags, recap cap, restore. */
public class StoryStateTest {

    @Test
    public void flagsCanBeSetReadAndReplaced() {
        StoryState story = new StoryState();
        story.set("choice.gate", "Hold it open");
        assertTrue(story.has("choice.gate"));
        assertEquals("Hold it open", story.get("choice.gate", ""));
        assertTrue(story.is("choice.gate", "hold it open")); // case-insensitive

        story.set("choice.gate", "Run");
        assertEquals("Run", story.get("choice.gate", ""));
        assertFalse(story.is("choice.gate", "Hold it open"));

        assertFalse(story.has("missing"));
        assertEquals("fallback", story.get("missing", "fallback"));
        assertNull(story.flags().get("missing"));
    }

    @Test
    public void recapKeepsNewestTwelve() {
        StoryState story = new StoryState();
        for (int i = 0; i < 20; i++) {
            story.recordChoice("Prompt " + i, "Option " + i);
        }
        List<String> choices = story.choices();
        assertEquals(12, choices.size());
        assertEquals("Prompt 8 — Option 8", choices.get(0));
        assertEquals("Prompt 19 — Option 19", choices.get(11));
    }

    @Test
    public void blankOptionsAreIgnored() {
        StoryState story = new StoryState();
        story.recordChoice("Prompt", "");
        story.recordChoice("Prompt", "   ");
        assertTrue(story.choices().isEmpty());
        story.recordChoice("Prompt", "Answer");
        assertEquals("Prompt — Answer", story.choices().get(0));
    }

    @Test
    public void restoreReplacesEverything() {
        StoryState story = new StoryState();
        story.set("old", "value");
        story.recordChoice("Old", "Choice");

        Map<String, String> flags = new LinkedHashMap<String, String>();
        flags.put("choice.a", "one");
        flags.put("choice.b", "two");
        List<String> recap = new ArrayList<String>();
        recap.add("A — one");
        story.restore(flags, recap);

        assertFalse(story.has("old"));
        assertEquals("one", story.get("choice.a", ""));
        assertEquals("two", story.get("choice.b", ""));
        assertEquals(1, story.choices().size());

        story.clear();
        assertTrue(story.flags().isEmpty());
        assertTrue(story.choices().isEmpty());
    }

    @Test
    public void flagsSnapshotIsImmutableAndOrdered() {
        StoryState story = new StoryState();
        story.set("b", "2");
        story.set("a", "1");
        Map<String, String> snapshot = story.flags();
        try {
            snapshot.put("c", "3");
            assertTrue("snapshot should be immutable", false);
        } catch (UnsupportedOperationException expected) {
            // good
        }
        assertEquals(java.util.Arrays.asList("b", "a"),
                new ArrayList<String>(story.flags().keySet()));
    }
}
