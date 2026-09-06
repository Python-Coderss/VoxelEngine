package com.voxel.game;

import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla 1.12.2 food table: item id → food points restored and saturation
 * ratio. Saturation added when eaten = food × ratio × 2 (capped at hunger),
 * exactly like Minecraft's ItemFood. Items here are expected to exist in the
 * resource pack (auto-registered item models); only ids actually present in
 * the pack ever reach the player's hands, but extra entries are harmless.
 */
public final class FoodValues {

    /** food points (0-20), saturation ratio (0..) */
    private static final Map<String, float[]> FOOD = new HashMap<>();

    private static void put(String id, float hunger, float saturationRatio) {
        FOOD.put(id, new float[] { hunger, saturationRatio });
    }

    static {
        // Tier 1: low value
        put("rotten_flesh", 4, 0.1f);
        put("spider_eye", 2, 0.8f);
        put("cookie", 2, 0.1f);
        put("melon", 2, 0.3f);
        put("chicken", 2, 0.3f);
        put("fish", 2, 0.1f);
        put("mutton", 2, 0.3f);
        put("porkchop", 3, 0.3f);
        put("beef", 3, 0.3f);
        put("rabbit", 3, 0.3f);
        put("carrot", 3, 0.6f);
        put("poisonous_potato", 2, 1.2f);
        put("beetroot", 1, 0.6f);
        put("potato", 1, 0.3f);
        // Tier 2: mid value
        put("baked_potato", 5, 0.6f);
        put("bread", 5, 0.6f);
        put("cooked_fish", 5, 0.6f);
        put("cooked_salmon", 6, 0.8f);
        put("salmon", 2, 0.1f);
        put("cooked_chicken", 6, 0.6f);
        put("cooked_mutton", 6, 0.8f);
        put("cooked_rabbit", 5, 0.6f);
        put("golden_carrot", 6, 1.2f);
        put("chorus_fruit", 4, 0.3f);
        // Tier 3: high value
        put("cooked_porkchop", 8, 0.8f);
        put("cooked_beef", 8, 0.8f);
        put("apple", 4, 0.3f);
        put("golden_apple", 4, 1.2f);
        put("pumpkin_pie", 8, 0.3f);
        put("mushroom_stew", 6, 0.6f);
        put("rabbit_stew", 10, 0.6f);
        put("beetroot_soup", 6, 0.6f);
        put("dried_kelp", 1, 0.3f);
    }

    private FoodValues() { }

    /** True when the item id is a known edible item. */
    public static boolean isFood(String itemId) {
        return itemId != null && FOOD.containsKey(itemId);
    }

    /** Food points restored (0-20). */
    public static float hunger(String itemId) {
        float[] v = FOOD.get(itemId);
        return v == null ? 0 : v[0];
    }

    /** Saturation ratio used to compute the saturation gained on eating. */
    public static float saturationRatio(String itemId) {
        float[] v = FOOD.get(itemId);
        return v == null ? 0 : v[1];
    }

    /** Number of distinct foods known to the table (for tests/tooling). */
    public static int size() {
        return FOOD.size();
    }
}
