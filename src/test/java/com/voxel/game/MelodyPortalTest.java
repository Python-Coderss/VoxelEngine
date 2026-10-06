package com.voxel.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ancient City rift opens on a fixed tune. These tests pin the note
 * validation (chaining, reset-on-wrong, start-over) and the pitch mapping that
 * turns a tuning stone's position into the note it plays.
 */
public class MelodyPortalTest {

    @Test
    public void pitchMapsFromPositionOffset() {
        MelodyPortal.resetForTest();
        assertFalse(MelodyPortal.hasOrigin());
        // With no origin, everything plays the base note rather than crashing.
        assertEquals(0, MelodyPortal.pitchAt(999, 5, 999));

        MelodyPortal.setOrigin(0, 0, 0);
        assertTrue(MelodyPortal.hasOrigin());
        assertEquals(0, MelodyPortal.pitchAt(0, 0, 0));
        assertEquals(1, MelodyPortal.pitchAt(1, 0, 0));
        // Moving -z by one is the same as +1 in x for this mapping.
        assertEquals(1, MelodyPortal.pitchAt(0, 0, -1));
        assertEquals(0, MelodyPortal.pitchAt(7, 0, 0));
    }

    @Test
    public void playingTheTuneInOrderSolvesIt() {
        MelodyPortal.resetForTest();
        for (int i = 0; i < MelodyPortal.MELODY.length - 1; i++) {
            assertFalse("note " + i + " must not solve early",
                    MelodyPortal.playNote(MelodyPortal.MELODY[i]));
            assertEquals(i + 1, MelodyPortal.progress());
            assertFalse(MelodyPortal.isSolved());
        }
        int last = MelodyPortal.MELODY[MELODY_LAST()];
        assertTrue("the final note opens the rift", MelodyPortal.playNote(last));
        assertTrue(MelodyPortal.isSolved());
        assertEquals(-1, MelodyPortal.expectedNote());
        // Once solved, further notes are ignored.
        assertFalse(MelodyPortal.playNote(MelodyPortal.MELODY[0]));
    }

    @Test
    public void wrongNoteClearsTheRun() {
        MelodyPortal.resetForTest();
        MelodyPortal.playNote(MelodyPortal.MELODY[0]);
        assertEquals(1, MelodyPortal.progress());
        int wrong = MelodyPortal.MELODY[0] + 1;
        assertFalse(MelodyPortal.playNote(wrong));
        assertEquals("a wrong note resets the melody", 0, MelodyPortal.progress());
        assertFalse(MelodyPortal.isSolved());
    }

    @Test
    public void wrongNoteThatStartsTheTuneBeginsAFreshAttempt() {
        MelodyPortal.resetForTest();
        MelodyPortal.playNote(MelodyPortal.MELODY[0]);
        MelodyPortal.playNote(MelodyPortal.MELODY[1]);
        assertEquals(2, MelodyPortal.progress());
        // The first note again is "wrong" for the run, but restarts it.
        assertFalse(MelodyPortal.playNote(MelodyPortal.MELODY[0]));
        assertEquals(1, MelodyPortal.progress());
        assertEquals(MelodyPortal.MELODY[1], MelodyPortal.expectedNote());
    }

    @Test
    public void resetClearsProgressAndOrigin() {
        MelodyPortal.setOrigin(3, 4, 5);
        MelodyPortal.playNote(MelodyPortal.MELODY[0]);
        MelodyPortal.reset();
        assertEquals(0, MelodyPortal.progress());
        assertFalse(MelodyPortal.isSolved());
        assertFalse(MelodyPortal.hasOrigin());
    }

    private static int MELODY_LAST() {
        return MelodyPortal.MELODY.length - 1;
    }
}
