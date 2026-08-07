package com.voxel.world;

import com.voxel.World;

/** Ancient-builder testing facility placed at the normal X/Z Far Lands edge. */
public final class AncientBuilderFacility {
    public static final int FACILITY_X = 3064;
    /** One facility for each configured Y-precision degradation band. */
    // Facility floors follow the six Y precision bands. Most are section
    // aligned; Y=188 is intentionally the first visible Y Far Lands level and
    // is split safely across its two containing sections during generation.
    public static final int[] FACILITY_YS = {188, 400, 704, 1008, 1200, 1408};
    /** Backward-compatible alias for the lowest facility. */
    public static final int FACILITY_Y = FACILITY_YS[0];
    public static final int FACILITY_Z = 1032;
    public static final int POWER_CHEST_X = FACILITY_X;
    public static final int POWER_CHEST_Y = FACILITY_Y + 1;
    public static final int POWER_CHEST_Z = FACILITY_Z - 3;

    private AncientBuilderFacility() { }

    public static boolean isPowerFragmentChest(int x, int y, int z) {
        if (x != POWER_CHEST_X || z != POWER_CHEST_Z) return false;
        for (int facilityY : FACILITY_YS) {
            if (y == facilityY + 1) return true;
        }
        return false;
    }

    /** Returns the closest configured facility that is at or above the player. */
    public static int closestFacilityYAbove(double playerY) {
        for (int facilityY : FACILITY_YS) {
            if (facilityY >= playerY) return facilityY;
        }
        // There is no configured facility above the highest band; use the top
        // one rather than returning an unusable sentinel coordinate.
        return FACILITY_YS[FACILITY_YS.length - 1];
    }

    /** True when a generated section intersects the facility at the given Y. */
    public static boolean intersectsSection(int sectionY, int facilityY) {
        int sectionMinY = sectionY << 4;
        int sectionMaxY = sectionMinY + 15;
        return sectionMinY <= facilityY + 8 && sectionMaxY >= facilityY;
    }

    public static String defaultCommandAt(int x, int y, int z) {
        if (z != FACILITY_Z) return "";
        for (int facilityY : FACILITY_YS) {
            if (y != facilityY + 1) continue;
            if (x == FACILITY_X - 3) return "dimension portal_hall";
            if (x == FACILITY_X - 2) return "tp ~ ~ ~";
            if (x == FACILITY_X + 1) return "dimension end";
            if (x == FACILITY_X + 3) return "dimension aether";
        }
        return "";
    }

    /** Builds the lowest compact facility for backward-compatible callers. */
    public static void generate(World world) {
        generate(world, FACILITY_Y);
    }

    /** Builds a compact facility for one Y-precision degradation band. */
    public static void generate(World world, int facilityY) {
        generate(world, facilityY, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Builds only the part of a facility belonging to one loaded section.
     * This matters for the first facility at Y=188, which spans sections 11 and
     * 12 instead of being aligned to a section boundary.
     */
    public static void generate(World world, int facilityY, int sectionY) {
        int sectionMinY = sectionY << 4;
        generate(world, facilityY, sectionMinY, sectionMinY + 15);
    }

    private static void generate(World world, int facilityY, int minY, int maxY) {
        final int stone = 2;
        final int bricks = 131;
        final int glass = 3;
        final int chest = 118;
        final int command = 275;
        final int chain = 276;
        final int repeating = 277;
        final int glowstone = 17;
        final int x0 = FACILITY_X, y0 = facilityY, z0 = FACILITY_Z;

        for (int x = x0 - 6; x <= x0 + 6; x++) {
            for (int z = z0 - 5; z <= z0 + 5; z++) {
                setVoxel(world, x, y0, z, bricks, minY, maxY);
                for (int y = y0 + 1; y <= y0 + 7; y++) {
                    boolean wall = x == x0 - 6 || x == x0 + 6 || z == z0 - 5 || z == z0 + 5;
                    setVoxel(world, x, y, z, wall ? stone : 0, minY, maxY);
                }
            }
        }
        for (int x = x0 - 6; x <= x0 + 6; x++) {
            for (int z = z0 - 5; z <= z0 + 5; z++) setVoxel(world, x, y0 + 8, z, bricks, minY, maxY);
        }
        // Survival entrance through the front wall, with observation windows above.
        for (int y = y0 + 1; y <= y0 + 3; y++) setVoxel(world, x0, y, z0 - 5, 0, minY, maxY);
        for (int x = x0 - 3; x <= x0 + 3; x += 2) setVoxel(world, x, y0 + 4, z0 - 5, glass, minY, maxY);

        // Four consoles: portal hall, Nether, End, and Aether. The chain block
        // is directly adjacent to its impulse predecessor.
        setVoxel(world, x0 - 3, y0 + 1, z0, command, minY, maxY);
        setVoxel(world, x0 - 2, y0 + 1, z0, chain, minY, maxY);
        setVoxel(world, x0 + 1, y0 + 1, z0, repeating, minY, maxY);
        setVoxel(world, x0 + 3, y0 + 1, z0, command, minY, maxY);
        // The consoles are intentionally unpowered: the explorer chooses which
        // destination to activate by supplying a redstone source to that console.
        setVoxel(world, x0, y0 + 1, z0 - 3, chest, minY, maxY);
        setVoxel(world, x0 - 5, y0 + 1, z0 - 3, glowstone, minY, maxY);
        setVoxel(world, x0 + 5, y0 + 1, z0 - 3, glowstone, minY, maxY);
    }

    private static void setVoxel(World world, int x, int y, int z, int blockId,
                                 int minY, int maxY) {
        if (y >= minY && y <= maxY) world.setVoxel(x, y, z, blockId);
    }
}
