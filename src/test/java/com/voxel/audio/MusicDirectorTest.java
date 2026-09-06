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
    public void rangesAreSaneForTuning() {
        assertEquals(24.0f, MusicDirector.DANGER_RANGE, 0.001f);
        assertEquals(10.0f, MusicDirector.COMBAT_RANGE, 0.001f);
    }
}
