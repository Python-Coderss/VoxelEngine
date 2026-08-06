package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class VillagerNewsIntroTest {
    @Test
    public void packagedArrangementLoadsFromMidi() throws Exception {
        VillagerNewsIntro intro = VillagerNewsIntro.loadDefault();
        assertEquals("MIDI", intro.getKey());
        assertTrue(intro.getBpm() > 0.0);
        assertTrue(intro.getSourceUrl().endsWith("villager_news_intro.mid"));
        assertTrue(!intro.getNotes().isEmpty());
        assertTrue(intro.getNotes().get(0).getMidi() >= 0);
    }

    @Test
    public void assetEditsChangeTheCacheKey() throws Exception {
        String first = "{\"version\":1,\"bpm\":120,\"notes\":["
                + "{\"text\":\"da\",\"pitch\":\"D4\",\"startBeat\":0,\"durationBeats\":1}]}";
        String second = first.replace("\"D4\"", "\"E4\"");
        VillagerNewsIntro a = VillagerNewsIntro.fromJson(first);
        VillagerNewsIntro b = VillagerNewsIntro.fromJson(second);
        assertNotEquals(a.cacheKey(SpeechOptions.DEFAULT), b.cacheKey(SpeechOptions.DEFAULT));
        assertEquals(62, a.getNotes().get(0).getMidi());
        assertEquals(64, b.getNotes().get(0).getMidi());
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsNotesOutOfOrder() throws Exception {
        VillagerNewsIntro.fromJson("{\"notes\":["
                + "{\"pitch\":\"D4\",\"startBeat\":1,\"durationBeats\":1},"
                + "{\"pitch\":\"D4\",\"startBeat\":0,\"durationBeats\":1}]} ");
    }
}
