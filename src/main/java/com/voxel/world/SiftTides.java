package com.voxel.world;

/**
 * The shifting tides of the Sift — the defining mechanic of the dimension.
 *
 * <p>The tide crosses a full low → high → low cycle once every
 * {@link #CYCLE_SECONDS}. {@link #strength()} rises from 0 (low tide) to 1
 * (high tide) and back, driving three things: how hazardous the realm is (high
 * tide wakes its hazards), how rich a sift is (the rising water uncovers buried
 * material), and what the HUD/status reports.</p>
 *
 * <p>Pure and deterministic so it can be unit-tested and driven by the world
 * clock without a GL context.</p>
 */
public final class SiftTides {

    public enum Phase {
        /** Tide at its lowest: calm, safe, poor sifting. */
        LOW,
        /** Water climbing. */
        RISING,
        /** Tide at its peak: hazardous, richest sifting. */
        HIGH,
        /** Water receding. */
        EBBING
    }

    /** Seconds for one full low → high → low cycle. */
    public static final float CYCLE_SECONDS = 240f;

    private final float cycleSeconds;
    private float time;

    public SiftTides() {
        this(CYCLE_SECONDS);
    }

    public SiftTides(float cycleSeconds) {
        this.cycleSeconds = Math.max(1f, cycleSeconds);
    }

    /** Advance the tide clock. */
    public void tick(float dt) {
        if (dt <= 0f) return;
        time += dt;
        while (time >= cycleSeconds) time -= cycleSeconds;
    }

    /** Reset to the start of the cycle (tests / fresh worlds). */
    public void reset() {
        time = 0f;
    }

    /** Seconds into the current cycle. */
    public float time() {
        return time;
    }

    /** 0 at low tide, 1 at high tide (a smooth cosine swell). */
    public float strength() {
        double frac = time / (double) cycleSeconds;
        return (float) ((1.0 - Math.cos(2.0 * Math.PI * frac)) * 0.5);
    }

    /** The phase the tide is in right now. */
    public Phase phase() {
        double frac = time / (double) cycleSeconds;
        float s = strength();
        if (s < 0.15f) return Phase.LOW;
        if (s > 0.85f) return Phase.HIGH;
        return frac < 0.5 ? Phase.RISING : Phase.EBBING;
    }

    /** High tide makes the Sift's hazards active. */
    public boolean hazardous() {
        return strength() > 0.7f;
    }

    /** How much richer sifting is right now (1.0 low tide .. 3.0 high tide). */
    public float siftYieldMultiplier() {
        return 1f + strength() * 2f;
    }

    /** A short status line for the HUD. */
    public String describe() {
        switch (phase()) {
            case HIGH: return "The Sift is at high tide — the realm is awake.";
            case RISING: return "The Sift tide is rising.";
            case EBBING: return "The Sift tide is ebbing.";
            case LOW:
            default: return "The Sift is at low tide — safe ground.";
        }
    }
}
