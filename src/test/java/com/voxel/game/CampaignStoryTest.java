package com.voxel.game;

import org.junit.Before;
import org.junit.Test;
import villager.voice.SpeechOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The campaign story must have a beat for every new site, resolve each site to
 * a real map column, and fire each beat exactly once on proximity. It shares
 * the {@link McsmStory.Host} surface, so a stub host records the calls.
 */
public class CampaignStoryTest {

    private static final class RecordingHost implements McsmStory.Host {
        int popups, statuses, speeches;
        String lastTitle, lastSubtitle;

        @Override public void showPopup(String title, String subtitle) {
            popups++;
            lastTitle = title;
            lastSubtitle = subtitle;
        }

        @Override public void setStatus(String message) {
            statuses++;
        }

        @Override public void speak(String text, SpeechOptions options) {
            speeches++;
        }
    }

    @Before
    public void setUp() {
        CampaignStory.resetForTest();
    }

    @Test
    public void everyBeatHasAChapterAndResolvesToASite() {
        assertTrue("a beat per campaign site", CampaignStory.beatCount() >= 24);
        for (int i = 0; i < CampaignStory.beatCount(); i++) {
            String chapter = CampaignStory.chapterAt(i);
            assertNotNull(chapter);
            assertTrue("chapter " + i + " has a title", !chapter.isEmpty());
        }
    }

    @Test
    public void knownSitesResolveAndUnknownOnesDoNot() {
        assertNotNull(CampaignStory.coordsOf("redstonia"));
        assertNotNull(CampaignStory.coordsOf("well_of_fate"));
        assertNotNull(CampaignStory.coordsOf("obsidian_pinnacle"));
        assertNull(CampaignStory.coordsOf("nowhere"));
    }

    @Test
    public void approachingASiteFiresItsBeatOnce() {
        RecordingHost host = new RecordingHost();
        int[] at = CampaignStory.coordsOf("sky_city");
        CampaignStory.tick(host, 0L, at[0] + 0.5f, at[1] + 0.5f, 0.05f);
        assertEquals("one popup", 1, host.popups);
        assertEquals("one spoken line", 1, host.speeches);
        assertEquals("Sky City", host.lastTitle);

        // Standing there longer must not repeat the beat.
        CampaignStory.tick(host, 0L, at[0] + 0.5f, at[1] + 0.5f, 0.05f);
        assertEquals("still one popup", 1, host.popups);
    }

    @Test
    public void farAwayFiresNothing() {
        RecordingHost host = new RecordingHost();
        CampaignStory.tick(host, 0L, 100000f, 100000f, 0.05f);
        assertEquals(0, host.popups);
    }

    @Test
    public void aChoiceBeatWaitsForThePlayer() {
        RecordingHost host = new RecordingHost();
        int[] at = CampaignStory.coordsOf("redstonia");
        CampaignStory.tick(host, 0L, at[0] + 0.5f, at[1] + 0.5f, 0.05f);
        assertTrue("the beat waits for a 1/2 choice", CampaignStory.isAwaitingChoice());
    }

    @Test
    public void bossSitesNameAKnownMob() {
        java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(
                "wither_storm", "great_hog", "arch_illager", "vindicator", "evoker"));
        int withMob = 0;
        for (int i = 0; i < CampaignStory.beatCount(); i++) {
            String mob = CampaignStory.mobAt(i);
            if (mob == null) continue;
            withMob++;
            assertTrue("mob " + mob + " must be spawnable", known.contains(mob));
        }
        assertTrue("several beats summon a mob", withMob >= 5);
    }

    @Test
    public void firingABossBeatRequestsASpawnOnce() {
        RecordingHost host = new RecordingHost();
        int[] at = CampaignStory.coordsOf("wither_storm_arena");
        CampaignStory.tick(host, 0L, at[0] + 0.5f, at[1] + 0.5f, 0.05f);
        assertEquals("wither_storm", CampaignStory.consumeSpawnRequest());
        assertEquals("the request is one-shot", null, CampaignStory.consumeSpawnRequest());
    }

    @Test
    public void nonBossBeatsDoNotRequestASpawn() {
        RecordingHost host = new RecordingHost();
        int[] at = CampaignStory.coordsOf("creeper_woods");
        CampaignStory.tick(host, 0L, at[0] + 0.5f, at[1] + 0.5f, 0.05f);
        assertEquals(null, CampaignStory.consumeSpawnRequest());
    }

    @Test
    public void aBeatWithoutChoicesDoesNotWait() {
        RecordingHost host = new RecordingHost();
        int[] at = CampaignStory.coordsOf("creeper_woods");
        CampaignStory.tick(host, 0L, at[0] + 0.5f, at[1] + 0.5f, 0.05f);
        assertFalse(CampaignStory.isAwaitingChoice());
        assertEquals(1, host.popups);
    }
}
