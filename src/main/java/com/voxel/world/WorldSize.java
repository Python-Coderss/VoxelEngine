package com.voxel.world;

/**
 * World size presets that control the X/Z integer bit width.
 * Higher bits = farther Far Lands = larger playable area.
 *
 * The hard world border is placed at {@code (1L << (intBits - 1)) - 1} blocks
 * from origin on each horizontal axis.
 */
public enum WorldSize {
    /** 16 X/Z int bits: border ~32K, Far Lands ~3K (classic). */
    TINY("Tiny", 16),
    /** 18 X/Z int bits: border ~131K. */
    SMALL("Small", 18),
    /** 20 X/Z int bits: border ~524K (default / classic Beta). */
    MEDIUM("Medium", 20),
    /** 24 X/Z int bits: border ~8.4M. */
    LARGE("Large", 24),
    /** 28 X/Z int bits: border ~134M. */
    HUGE("Huge", 28);

    private final String displayName;
    private final int intBits;

    WorldSize(String displayName, int intBits) {
        this.displayName = displayName;
        this.intBits = intBits;
    }

    public String displayName() { return displayName; }
    public int intBits() { return intBits; }

    /** World border radius in blocks: half the signed int range minus a margin. */
    public long borderRadius() {
        return (1L << (intBits - 1)) - 16L;
    }

    /** Parse from a case-insensitive name or display name. */
    public static WorldSize fromString(String s) {
        if (s == null) return MEDIUM;
        String lower = s.toLowerCase().trim();
        for (WorldSize ws : values()) {
            if (ws.name().toLowerCase().equals(lower)
                    || ws.displayName.toLowerCase().equals(lower)) {
                return ws;
            }
        }
        // Accept raw int bit counts too
        try {
            int bits = Integer.parseInt(s);
            for (WorldSize ws : values()) {
                if (ws.intBits == bits) return ws;
            }
        } catch (NumberFormatException ignored) {}
        return MEDIUM;
    }
}
