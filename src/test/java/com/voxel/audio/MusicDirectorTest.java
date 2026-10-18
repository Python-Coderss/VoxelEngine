package com.voxel.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Headless coverage for {@link MusicDirector}: hostile proximity outranks
 * time of day, combat outranks danger, and calm is the safe default.
 */
public class MusicDirectorTest {

    @Test
    public void safeDaytimeIsCalm() {
        assertEquals(MusicDirector.CONTEXT_CALM, MusicDirector.contextFor(0, 0, false));
    }

    @Test
    public void nightIsCalmerThanNothing() {
        assertEquals(MusicDirector.CONTEXT_NIGHT, MusicDirector.contextFor(0, 0, true));
    }

    @Test
    public void distantHostilesTriggerDanger() {
        assertEquals(MusicDirector.CONTEXT_DANGER, MusicDirector.contextFor(3, 0, false));
        // Danger also outranks night.
        assertEquals(MusicDirector.CONTEXT_DANGER, MusicDirector.contextFor(1, 0, true));
    }

    @Test
    public void closeHostilesTriggerCombatOverEverything() {
        assertEquals(MusicDirector.CONTEXT_COMBAT, MusicDirector.contextFor(0, 1, false));
        assertEquals(MusicDirector.CONTEXT_COMBAT, MusicDirector.contextFor(5, 2, true));
        assertEquals(MusicDirector.CONTEXT_COMBAT, MusicDirector.contextFor(2, 1, false));
    }

    @Test
    public void sceneSelectionUsesDimensionAndPlace() {
        assertEquals(MusicDirector.CONTEXT_NETHER, MusicDirector.contextForScene(
                "nether", false, 0, 0, false, false, false, false));
        assertEquals(MusicDirector.CONTEXT_END, MusicDirector.contextForScene(
                "end", false, 0, 0, false, false, false, false));
        assertEquals(MusicDirector.CONTEXT_AETHER, MusicDirector.contextForScene(
                "aether", false, 0, 0, false, false, false, false));
        assertEquals(MusicDirector.CONTEXT_SIFT, MusicDirector.contextForScene(
                "sift", false, 0, 0, false, false, false, false));
        assertEquals(MusicDirector.CONTEXT_CAVE, MusicDirector.contextForScene(
                "overworld", true, 0, 0, false, false, false, false));
        assertEquals(MusicDirector.CONTEXT_TUTORIAL, MusicDirector.contextForScene(
                "overworld", false, 0, 0, false, false, true, false));
        assertEquals(MusicDirector.CONTEXT_VILLAGE, MusicDirector.contextForScene(
                "overworld", false, 0, 0, false, false, false, true));
    }

    @Test
    public void bossAndCombatOutrankEveryPlace() {
        assertEquals(MusicDirector.CONTEXT_BOSS, MusicDirector.contextForScene(
                "aether", false, 0, 0, false, true, true, true));
        assertEquals(MusicDirector.CONTEXT_COMBAT, MusicDirector.contextForScene(
                "nether", false, 0, 1, false, false, false, false));
        assertEquals(MusicDirector.CONTEXT_DANGER, MusicDirector.contextForScene(
                "nether", true, 2, 0, true, false, false, false));
    }

    @Test
    public void rangesAreSaneForTuning() {
        assertEquals(24.0f, MusicDirector.DANGER_RANGE, 0.001f);
        assertEquals(10.0f, MusicDirector.COMBAT_RANGE, 0.001f);
    }
}
