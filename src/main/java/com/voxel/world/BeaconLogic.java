package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Beacon pyramid detection + activation tracking.
 *
 * <p>A beacon is the id {@code beacon} block (id 457 in this engine). When
 * a 3×3 (or larger) pyramid of valid-metal blocks sits directly below it,
 * the beacon activates and emits a vertical beam plus buffs to nearby
 * players. The pyramid shape:</p>
 *
 * <pre>
 *   level 1   3x3   (9 blocks at y=-1)
 *   level 2   5x5   (+16 at y=-2)
 *   level 3   7x7   (+25 at y=-3)
 *   level 4   9x9   (+36 at y=-4)
 * </pre>
 *
 * <p>Valid pyramid blocks are iron_block, gold_block, diamond_block,
 * emerald_block (netherite_block would qualify too but isn't bundled with
 * this resource pack). The beacon contributes one tier of buffs at level
 * 1, two at level 2, three at level 3, and a fourth at level 4 (which
 * also unlocks the secondary power).</p>
 *
 * <p>Tracking is per-beacon-position so Main.tick can scan only the active
 * beacons each frame, not every block in the world.</p>
 */
public final class BeaconLogic {
    private BeaconLogic() {}

    /** Valid pyramid metals as a Set of block IDs (filled in by Main.init). */
    public static final Set<Integer> VALID_PYRAMID_BLOCKS = new HashSet<>();

    /** Tracks which beacon positions currently have an active pyramid. */
    private static final Set<Long> activeBeacons = new HashSet<>();

    /** Beacon positions that have ever been activated; used to find candidate beacons to scan. */
    private static final Set<Long> knownBeacons = new HashSet<>();

    /** Test hook: clear the active list to make tests deterministic. */
    public static void resetForTests() {
        activeBeacons.clear();
        knownBeacons.clear();
    }

    /** Registers a beacon position so subsequent ticks scan it. */
    public static void track(int x, int y, int z) {
        knownBeacons.add(key(x, y, z));
    }

    public static Set<Long> getActiveBeacons() { return activeBeacons; }
    public static Set<Long> getKnownBeacons() { return knownBeacons; }

    /** Pyramid level at {@code (x, y, z)}, where (x,y,z) is a beacon block.
     *  Returns 0..4. */
    public static int pyramidLevel(World world, BlockDataManager bdm, int x, int y, int z) {
        // Validate 1..4 layers. The beacon contributes 0 of its own blocks,
        // so level N requires the corresponding tier of metal below.
        for (int level = 4; level >= 1; level--) {
            if (isValidLevel(world, bdm, x, y, z, level)) return level;
        }
        return 0;
    }

    private static boolean isValidLevel(World world, BlockDataManager bdm,
                                          int x, int y, int z, int level) {
        for (int tier = 1; tier <= level; tier++) {
            int size = 1 + 2 * tier; // 3, 5, 7, 9
            int y0 = y - tier;
            int x0 = x - (size - 1) / 2;
            int z0 = z - (size - 1) / 2;
            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    int blockId = world.getVoxel(x0 + dx, y0, z0 + dz);
                    if (!VALID_PYRAMID_BLOCKS.contains(blockId)) return false;
                }
            }
        }
        return true;
    }

    /** Per-tick scan: refresh the active set, return beacons that
     *  transitioned from inactive → active. Main.tick uses this to flip
     *  Player buff fields on/off. */
    public static Set<Long> scan(World world, BlockDataManager bdm) {
        Set<Long> newlyActivated = new HashSet<>();
        Set<Long> stillActive = new HashSet<>();
        for (Long key : knownBeacons) {
            int x = (int) (key & 0xFFFFFFFL);
            int y = (int) ((key >> 24) & 0xFFFFFL);
            int z = (int) ((key >> 44) & 0xFFFFFFFL);
            int level = pyramidLevel(world, bdm, x, y, z);
            if (level >= 1) {
                stillActive.add(key);
                if (!activeBeacons.contains(key)) newlyActivated.add(key);
            }
        }
        activeBeacons.clear();
        activeBeacons.addAll(stillActive);
        return newlyActivated;
    }

    /** Compute the buff magnitude from a beacon level. Mojang's curve is:
     *  level 1→ 9s, level 2→ 11s, level 3→ 13s, level 4→ 15s. We mirror
     *  that here as a positive float the engine can multiply onto Player
     *  jump/speed multipliers. */
    public static float buffDurationSeconds(int level) {
        switch (level) {
            case 4: return 15.0f;
            case 3: return 13.0f;
            case 2: return 11.0f;
            case 1: return 9.0f;
            default: return 0.0f;
        }
    }

    /** Horizontal radius (in blocks) inside which players receive buffs. */
    public static float buffRadius(int level) {
        // Mojang: 20 / 30 / 40 / 50 blocks for tiers 1..4.
        switch (level) {
            case 4: return 50.0f;
            case 3: return 40.0f;
            case 2: return 30.0f;
            default: return 20.0f;
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFFFFL)
                | (((long) y & 0xFFFFFL) << 24)
                | (((long) z & 0xFFFFFFFL) << 44);
    }

    /** Reverse the key back into (x, y, z). Exposed so Main.tick can iterate
     *  the active set and apply buffs to nearby players. */
    public static int[] decodeKey(long key) {
        return new int[] {
                (int) (key & 0xFFFFFFFL),
                (int) ((key >> 24) & 0xFFFFFL),
                (int) ((key >> 44) & 0xFFFFFFFL)
        };
    }
}