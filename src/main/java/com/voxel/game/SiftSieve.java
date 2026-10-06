package com.voxel.game;

import com.voxel.world.SiftTides;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The sifting mechanic of the Sift — Minecraft's new dimension lets players
 * strain buried material out of its unique soil, and the shifting tide decides
 * how rich the take is.
 *
 * <p>Right-clicking a {@code sift_bed} rolls loot: common grains at low tide,
 * and a rising chance of the Sift's rare finds as the tide climbs. Each bed has
 * a short per-block cooldown so a held right-click cannot strip the whole
 * dimension in a second.</p>
 *
 * <p>Pure and deterministic per {@link Random}, so the table and cooldown are
 * unit-tested without a GL context.</p>
 */
public final class SiftSieve {

    /** A rolled sift result. */
    public static final class Loot {
        public final String itemId;
        public final int count;
        public final boolean rare;

        Loot(String itemId, int count, boolean rare) {
            this.itemId = itemId;
            this.count = count;
            this.rare = rare;
        }
    }

    private static final Loot[] COMMON = {
            new Loot("coal", 1, false),
            new Loot("stick", 2, false),
            new Loot("clay", 1, false),
            new Loot("flint", 1, false),
            new Loot("copper_ingot", 1, false),
            new Loot("zinc_ingot", 1, false),
    };

    private static final Loot[] RARE = {
            new Loot("iron_ingot", 1, true),
            new Loot("gold_ingot", 1, true),
            new Loot("nether_quartz", 1, true),
            new Loot("glowstone", 1, true),
            new Loot("diamond", 1, true),
            new Loot("power_fragment", 1, true),
    };

    /** Per-bed cooldown so one block cannot be farmed continuously. */
    public static final long COOLDOWN_MILLIS = 1500L;

    private static final Map<Long, Long> lastSift = new HashMap<>();

    private SiftSieve() {
    }

    /**
     * Roll loot for a sift.
     *
     * @param tideStrength 0 (low tide) .. 1 (high tide)
     * @param rng          the roll source
     */
    public static Loot roll(float tideStrength, Random rng) {
        float t = Math.max(0f, Math.min(1f, tideStrength));
        // Rare chance climbs from 8% at low tide to 55% at high tide.
        float rareChance = 0.08f + t * 0.47f;
        if (rng != null && rng.nextFloat() < rareChance) {
            return RARE[rng.nextInt(RARE.length)];
        }
        return COMMON[rng == null ? 0 : rng.nextInt(COMMON.length)];
    }

    /** Number of distinct entries in each pool (for tests/tools). */
    public static int commonCount() { return COMMON.length; }
    public static int rareCount() { return RARE.length; }

    /** True when a bed was sifted too recently to sift again. */
    public static boolean isOnCooldown(int x, int y, int z, long nowMillis) {
        Long last = lastSift.get(key(x, y, z));
        return last != null && nowMillis - last < COOLDOWN_MILLIS;
    }

    /** Record that a bed was just sifted. */
    public static void markSifted(int x, int y, int z, long nowMillis) {
        lastSift.put(key(x, y, z), nowMillis);
    }

    /** Clears cooldowns (tests / fresh worlds). */
    public static void resetForTest() {
        lastSift.clear();
    }

    /** Cooldown remaining in seconds, for a friendly status line (0 = ready). */
    public static float cooldownRemaining(int x, int y, int z, long nowMillis) {
        Long last = lastSift.get(key(x, y, z));
        if (last == null) return 0f;
        long remaining = COOLDOWN_MILLIS - (nowMillis - last);
        return remaining <= 0 ? 0f : remaining / 1000f;
    }

    /** Convenience: roll with the richness of the given tide. */
    public static Loot roll(SiftTides tides, Random rng) {
        return roll(tides == null ? 0f : tides.strength(), rng);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 21) | (y & 0x1FFFFF);
    }
}
