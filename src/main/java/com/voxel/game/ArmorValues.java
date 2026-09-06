package com.voxel.game;

import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla 1.12.2 armor table: armor item id → equipment slot and defense
 * points. Slot constants follow the armor inventory order (helmet, chestplate,
 * leggings, boots). Only ids whose item models exist in the resource pack can
 * reach the player's hands; entries for the full vanilla set are safe either
 * way.
 */
public final class ArmorValues {

    public static final int SLOT_HELMET = 0;
    public static final int SLOT_CHESTPLATE = 1;
    public static final int SLOT_LEGGINGS = 2;
    public static final int SLOT_BOOTS = 3;
    public static final int ARMOR_SLOT_COUNT = 4;

    /** armor id → {slot index, defense points} */
    private static final Map<String, int[]> ARMOR = new HashMap<>();

    private static void set(String id, int slot, int defense) {
        ARMOR.put(id, new int[] { slot, defense });
    }

    static {
        // Leather set (total 7)
        set("leather_helmet", SLOT_HELMET, 1);
        set("leather_chestplate", SLOT_CHESTPLATE, 3);
        set("leather_leggings", SLOT_LEGGINGS, 2);
        set("leather_boots", SLOT_BOOTS, 1);
        // Gold set (total 11)
        set("golden_helmet", SLOT_HELMET, 2);
        set("golden_chestplate", SLOT_CHESTPLATE, 5);
        set("golden_leggings", SLOT_LEGGINGS, 3);
        set("golden_boots", SLOT_BOOTS, 1);
        // Chainmail set (total 12)
        set("chainmail_helmet", SLOT_HELMET, 2);
        set("chainmail_chestplate", SLOT_CHESTPLATE, 5);
        set("chainmail_leggings", SLOT_LEGGINGS, 4);
        set("chainmail_boots", SLOT_BOOTS, 1);
        // Iron set (total 15)
        set("iron_helmet", SLOT_HELMET, 2);
        set("iron_chestplate", SLOT_CHESTPLATE, 6);
        set("iron_leggings", SLOT_LEGGINGS, 5);
        set("iron_boots", SLOT_BOOTS, 2);
        // Diamond set (total 20)
        set("diamond_helmet", SLOT_HELMET, 3);
        set("diamond_chestplate", SLOT_CHESTPLATE, 8);
        set("diamond_leggings", SLOT_LEGGINGS, 6);
        set("diamond_boots", SLOT_BOOTS, 3);
    }

    private ArmorValues() { }

    /** Equipment slot for an armor item id, or -1 when it is not armor. */
    public static int slotFor(String itemId) {
        int[] v = ARMOR.get(itemId);
        return v == null ? -1 : v[0];
    }

    /** Defense points for an armor item id, or 0. */
    public static int defenseFor(String itemId) {
        int[] v = ARMOR.get(itemId);
        return v == null ? 0 : v[1];
    }

    /** True when the item id is a wearable armor piece. */
    public static boolean isArmor(String itemId) {
        return itemId != null && ARMOR.containsKey(itemId);
    }

    /** Number of armor pieces known to the table (for tests/tooling). */
    public static int size() {
        return ARMOR.size();
    }
}
