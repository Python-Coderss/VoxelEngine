package com.voxel.game;

/**
 * The small enchantment set the table offers, and what each one actually does.
 *
 * <p>Two effects cover the whole tool ring without inventing a new combat
 * system:</p>
 * <ul>
 *   <li><b>Sharpness</b> (swords/axes): +2.5 damage per level.</li>
 *   <li><b>Efficiency</b> (pickaxes/shovels/hoes/axes): +30% mining speed per
 *       level, applied after the tool-tier multiplier.</li>
 * </ul>
 *
 * <p>Attack and mining bonuses are pure functions so they can be tested without
 * a world, and item stacks persist the id + level in {@code level.dat}.</p>
 */
public final class Enchantments {

    public static final String SHARPNESS = "sharpness";
    public static final String EFFICIENCY = "efficiency";

    private Enchantments() {
    }

    /** Display name for a stack's enchantment, or null when plain. */
    public static String label(String id, int level) {
        if (id == null || level <= 0) return null;
        String base;
        if (SHARPNESS.equals(id)) base = "Sharpness";
        else if (EFFICIENCY.equals(id)) base = "Efficiency";
        else base = capitalize(id);
        return base + " " + roman(level);
    }

    /** Extra melee damage from a held weapon's enchantment. */
    public static float attackBonus(String id, int level) {
        if (SHARPNESS.equals(id) && level > 0) return 2.5f * level;
        return 0f;
    }

    /** Mining-speed multiplier from a held tool's enchantment (>= 1). */
    public static float miningMultiplier(String id, int level) {
        if (EFFICIENCY.equals(id) && level > 0) return 1f + 0.30f * level;
        return 1f;
    }

    /** Canonical id for a mining or combat enchantment name, or null. */
    public static String normalize(String id) {
        if (id == null) return null;
        String value = id.trim().toLowerCase(java.util.Locale.ROOT);
        return SHARPNESS.equals(value) || EFFICIENCY.equals(value) ? value : null;
    }

    static String roman(int value) {
        switch (value) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            default: return Integer.toString(value);
        }
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
