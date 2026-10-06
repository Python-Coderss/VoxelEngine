package com.voxel.ai.brain;

/**
 * The village-wide escalating chaos meter. Comedy compounds: screams, crowds,
 * arguments, and blunders all feed it, and a high chaos level makes every
 * nearby mob act dumber, braver, and louder. It decays slowly back toward a
 * calm village when nothing funny is happening.
 *
 * <p>Ticked once per logic tick from EntityManager; raised by brains and body
 * comedy. Thread-safe for cheap access from tests and tools.</p>
 */
public final class Chaos {

    /** How fast calm returns: chaos points lost per second. */
    private static final float DECAY_PER_SECOND = 0.012f;

    private static float level;
    private static float peak;

    private Chaos() {
    }

    /** Current chaos, clamped to 0..1. */
    public static synchronized float level() {
        return level;
    }

    /** Highest chaos reached since the last reset (for debugging/tests). */
    public static synchronized float peak() {
        return peak;
    }

    /** Feed the chaos meter (screams, pile-ups, blunders). Clamped to 0..1. */
    public static synchronized void raise(float amount) {
        level = clamp(level + Math.max(0f, amount));
        if (level > peak) peak = level;
    }

    /** Advance the decay; call exactly once per logic tick. */
    public static synchronized void tick(float dt) {
        if (dt <= 0f) return;
        level = clamp(level - dt * DECAY_PER_SECOND);
    }

    /** Test seam / scene reset: back to a peaceful village. */
    public static synchronized void reset() {
        level = 0f;
        peak = 0f;
    }

    private static float clamp(float value) {
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }
}
