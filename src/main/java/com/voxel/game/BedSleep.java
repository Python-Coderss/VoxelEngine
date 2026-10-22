package com.voxel.game;

/**
 * Bed rules shared by {@code BlockInteraction} and the tests.
 *
 * <p>The engine clocks a day as 1440 units: dusk begins at 1080 and dawn at
 * 240. Sleeping is only allowed inside that window and always wakes the player
 * at {@link #DAWN}.</p>
 */
public final class BedSleep {

    public static final float DAY_LENGTH = 1440f;
    public static final float DUSK = 1080f;
    public static final float DAWN = 240f;

    private BedSleep() {
    }

    /** Normalised time of day, 0..1440. */
    public static float timeOfDay(float worldTime) {
        float value = worldTime % DAY_LENGTH;
        return value < 0f ? value + DAY_LENGTH : value;
    }

    /** True between dusk (1080) and dawn (240). */
    public static boolean isNight(float worldTime) {
        float time = timeOfDay(worldTime);
        return time >= DUSK || time < DAWN;
    }

    /** Dawn wake-up time. */
    public static float wakeTime() {
        return DAWN;
    }
}
