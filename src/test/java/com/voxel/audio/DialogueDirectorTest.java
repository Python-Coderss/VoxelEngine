package com.voxel.audio;

import com.voxel.entity.VillagerEntity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import villager.voice.SpeechOptions;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Tests for the improved dialogue selection layer (entity-free core API). */
public class DialogueDirectorTest {

    private static final int VILLAGER_ID = 99;
    private final SpeechOptions neutralOptions = new SpeechOptions(
            1.0, 0.0, 1.0, 0.0, 0.60, "neutral", 0.0, 0.0, false);
    private List<DialogueLine> catalog;

    @Before
    public void setUp() {
        DialogueDirector.reset();
        catalog = new ArrayList<DialogueLine>();
        catalog.add(new DialogueLine("cat_shop_1", "Welcome to my shop, friend.",
                "SHOPKEEPER", "*", 0, SpeechOptions.DEFAULT));
        catalog.add(new DialogueLine("cat_shop_2", "A fine price for fine goods.",
                "SHOPKEEPER", "*", 1, SpeechOptions.DEFAULT));
    }

    @After
    public void tearDown() {
        DialogueDirector.reset();
    }

    @Test
    public void nullVillagerGivesFallbackLine() {
        DialogueLine line = DialogueDirector.choose((VillagerEntity) null, "DAY", 0, catalog);
        assertNotNull(line);
        assertEquals("Hmm...", line.getText());
    }

    @Test
    public void builtinsAreAlwaysAvailable() {
        // A profession/period with no catalog entries still yields a line.
        DialogueLine line = DialogueDirector.choose(VILLAGER_ID, "SHOPKEEPER",
                "NIGHT", 0, null);
        assertNotNull(line);
        assertFalse(line.getText().isEmpty());
        assertTrue("builtin ids use the builtin_ prefix",
                line.getId().startsWith("builtin_"));
    }

    @Test
    public void catalogLinesWinOverBuiltins() {
        boolean sawCatalogLine = false;
        for (int i = 0; i < 24; i++) {
            DialogueLine line = DialogueDirector.choose(VILLAGER_ID, "SHOPKEEPER",
                    "DAY", i, catalog);
            if (line.getId().startsWith("cat_")) {
                sawCatalogLine = true;
                break;
            }
        }
        assertTrue("catalog entries should appear in selection", sawCatalogLine);
    }

    @Test
    public void catalogAndBuiltinPoolsAreMerged() {
        boolean sawBuiltin = false;
        boolean sawCatalog = false;
        for (int i = 0; i < 60; i++) {
            DialogueLine line = DialogueDirector.choose(VILLAGER_ID, "SHOPKEEPER",
                    "DAY", i, catalog);
            if (line.getId().startsWith("builtin_")) sawBuiltin = true;
            if (line.getId().startsWith("cat_")) sawCatalog = true;
            if (sawBuiltin && sawCatalog) break;
        }
        assertTrue("both pools should be selectable", sawBuiltin && sawCatalog);
    }

    @Test
    public void temperamentIsStablePerId() {
        DialogueDirector.State a = DialogueDirector.state(VILLAGER_ID);
        DialogueDirector.State b = DialogueDirector.state(VILLAGER_ID);
        DialogueDirector.State c = DialogueDirector.state(VILLAGER_ID + 1);
        assertTrue("same id returns the same state", a == b);
        assertTrue("different ids differ", a != c);
        assertTrue("chattiness is in range",
                a.temperament.chattiness >= 0.0 && a.temperament.chattiness <= 1.0);
    }

    @Test
    public void hurtMakesDeliveryScaredAndLoud() {
        DialogueDirector.onHurt(42);
        SpeechOptions options = DialogueDirector.optionsFor(
                DialogueDirector.state(42), SpeechOptions.DEFAULT);
        assertEquals("scared", options.getEmotion());
        assertTrue("fear should raise volume",
                options.getVolume() > SpeechOptions.DEFAULT.getVolume());
        assertTrue("fear should raise pitch",
                options.getPitchSemitones() > SpeechOptions.DEFAULT.getPitchSemitones());
    }

    @Test
    public void calmMoodKeepsAuthoredEmotion() {
        // The engine default is a cheerful villager: passthrough, not forced.
        SpeechOptions options = DialogueDirector.optionsFor(
                DialogueDirector.state(55), SpeechOptions.DEFAULT);
        assertEquals("happy", options.getEmotion());
    }

    @Test
    public void neutralDeliveryFallsBackToMoodColoring() {
        // Authored-neutral lines take emotion from mood. Pick a villager whose
        // temperament is not grumpy enough to color a calm line sad.
        int calmId = -1;
        for (int id = 100; id < 200; id++) {
            if (DialogueDirector.state(id).temperament.cheer >= -0.4) {
                calmId = id;
                break;
            }
        }
        assertTrue("at least one non-grumpy villager should exist", calmId >= 0);
        SpeechOptions calm = DialogueDirector.optionsFor(
                DialogueDirector.state(calmId), neutralOptions);
        assertEquals("neutral", calm.getEmotion());

        // Fear overrides everything except authored anger.
        DialogueDirector.onHurt(calmId);
        SpeechOptions afraid = DialogueDirector.optionsFor(
                DialogueDirector.state(calmId), neutralOptions);
        assertEquals("scared", afraid.getEmotion());
    }

    @Test
    public void moodDecaysOverTime() {
        DialogueDirector.onHurt(43);
        assertTrue(moodFear(43) > 0.9);
        for (int i = 0; i < 40; i++) {
            DialogueDirector.tick(43, 0.5f); // 20 s total
        }
        assertTrue("fear should decay toward zero", moodFear(43) < 0.1);
    }

    @Test
    public void noRepeatMemoryAvoidsImmediateRepeats() {
        String previous = null;
        for (int i = 0; i < 12; i++) {
            DialogueLine line = DialogueDirector.choose(VILLAGER_ID, "SHOPKEEPER",
                    "DAY", i, catalog);
            if (previous != null) {
                assertFalse("immediate repeat should be avoided: " + previous,
                        line.getId().equals(previous));
            }
            previous = line.getId();
        }
    }

    @Test
    public void selectionIsDeterministicForSameInputs() {
        DialogueLine a = DialogueDirector.choose(VILLAGER_ID, "SHOPKEEPER", "DAY",
                3, catalog);
        DialogueDirector.reset();
        DialogueLine b = DialogueDirector.choose(VILLAGER_ID, "SHOPKEEPER", "DAY",
                3, catalog);
        assertEquals(a.getId(), b.getId());
    }

    @Test
    public void tickIsSafeForUnknownVillagers() {
        DialogueDirector.tick(12345, 0.016f);
        DialogueDirector.tick(12345, -1f); // malformed dt must not corrupt state
        assertTrue(moodFear(12345) <= 1.0);
    }

    @Test
    public void gossipLiftsFearMilderThanDirectSight() {
        DialogueDirector.onGossip(70, 0.8f);
        double gossipFear = moodFear(70);
        assertTrue("gossip should lift fear", gossipFear > 0.2);

        DialogueDirector.reset();
        DialogueDirector.onThreatSeen(71, 0.8f);
        double sightFear = moodFear(71);
        assertTrue("direct sight should scare more than gossip",
                sightFear > gossipFear);
    }

    @Test
    public void gossipAddsWariness() {
        double before = DialogueDirector.state(72).mood.warinessLevel();
        DialogueDirector.onGossip(72, 1.0f);
        assertTrue(DialogueDirector.state(72).mood.warinessLevel() > before);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static double moodFear(int id) {
        return DialogueDirector.state(id).mood.fearLevel();
    }
}
