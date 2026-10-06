package com.voxel.game;

import java.util.ArrayDeque;

/**
 * The melody-locked rift of the Ancient City (§Melody rift).
 *
 * <p>The builders sealed a rift into the Sift and left the key as a tune: the
 * note blocks around the gateway each ring at a fixed pitch derived from where
 * they sit, and playing them in the right order — the {@link #MELODY} — tears
 * the rift open.</p>
 *
 * <p>Pure and static so the tune can be unit-tested without a world or a GL
 * context. The origin is registered by {@code AncientCity} when the site is
 * stamped; {@link #pitchAt(int, int, int)} then maps a note block's world
 * position to the pitch it plays.</p>
 */
public final class MelodyPortal {

    /** Number of distinct pitches (note blocks in the Ancient City row). */
    public static final int NOTES = 7;

    /**
     * The key tune, as note-block pitch indices. A short rising-then-falling
     * arch — the shape the builders used to seal the rift.
     */
    public static final int[] MELODY = {0, 2, 4, 6, 4, 2};

    /** The Ancient City's tuning block. */
    public static final int BLOCK_NOTE = 937;

    private static final ArrayDeque<Integer> progress = new ArrayDeque<>();
    private static boolean solved;
    private static boolean hasOrigin;
    private static int originX, originY, originZ;

    private MelodyPortal() {
    }

    /** Records where the Ancient City's rift sits, so pitches can be mapped. */
    public static void setOrigin(int x, int y, int z) {
        originX = x;
        originY = y;
        originZ = z;
        hasOrigin = true;
    }

    public static boolean hasOrigin() { return hasOrigin; }
    public static int originX() { return originX; }
    public static int originY() { return originY; }
    public static int originZ() { return originZ; }

    /**
     * The pitch a note block at world (x,y,z) plays: its offset from the rift
     * folds into the fixed note range. The Ancient City builds its row so the
     * offsets come out as 0..{@link #NOTES}-1 in order.
     */
    public static int pitchAt(int x, int y, int z) {
        if (!hasOrigin) return 0;
        return Math.floorMod((x - originX) - (z - originZ), NOTES);
    }

    /**
     * Play a note. Extends the running melody when the note is the next one
     * expected; a wrong note clears the run (but a note that starts the tune
     * begins a fresh attempt). Returns true iff this note completed the tune.
     */
    public static boolean playNote(int noteId) {
        if (solved) return false;
        int n = Math.floorMod(noteId, NOTES);
        if (n == MELODY[progress.size()]) {
            progress.addLast(n);
            if (progress.size() == MELODY.length) {
                solved = true;
                return true;
            }
        } else {
            progress.clear();
            if (n == MELODY[0]) progress.addLast(n);
        }
        return false;
    }

    /** How many correct notes are chained right now. */
    public static int progress() { return progress.size(); }

    /** True once the tune has been played and the rift opened. */
    public static boolean isSolved() { return solved; }

    /** The next note expected, or -1 once solved. */
    public static int expectedNote() {
        return solved ? -1 : MELODY[progress.size()];
    }

    /** Clears the run and the solved state (fresh worlds / tests). */
    public static void reset() {
        progress.clear();
        solved = false;
        hasOrigin = false;
    }

    /** Test alias for {@link #reset()}. */
    public static void resetForTest() {
        reset();
    }
}
