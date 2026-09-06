package com.voxel.audio;

/**
 * Picks which music pool should be playing from the live game state — the
 * "like Minecraft but responsive" layer. Context names map to subfolders of
 * the music root (src/main/resources/music/&lt;context&gt;): the game leans on
 * {@code calm} while exploring safely, tightens to {@code danger} when
 * something hostile is near, and switches to {@code combat} when it is close,
 * with {@code night} reserved for quiet dark hours.
 *
 * <p>Priority is deliberate: combat outranks danger, danger outranks night,
 * night outranks calm. The rule table is a pure function so it can be tuned
 * and regression-tested without audio or a running game.
 */
public final class MusicDirector {

    public static final String CONTEXT_CALM = "calm";
    public static final String CONTEXT_NIGHT = "night";
    public static final String CONTEXT_DANGER = "danger";
    public static final String CONTEXT_COMBAT = "combat";

    /** Hostile mobs within this many blocks (other than combat range) → danger. */
    public static final float DANGER_RANGE = 24.0f;
    /** Hostile mobs within this many blocks → combat. */
    public static final float COMBAT_RANGE = 10.0f;

    private MusicDirector() { }

    /**
     * @param hostileNearby count of hostile mobs within {@link #DANGER_RANGE}
     * @param hostileClose  count of hostile mobs within {@link #COMBAT_RANGE}
     * @param isNight       true when the sun is below the horizon
     */
    public static String contextFor(int hostileNearby, int hostileClose, boolean isNight) {
        if (hostileClose > 0) return CONTEXT_COMBAT;
        if (hostileNearby > 0) return CONTEXT_DANGER;
        return isNight ? CONTEXT_NIGHT : CONTEXT_CALM;
    }
}
