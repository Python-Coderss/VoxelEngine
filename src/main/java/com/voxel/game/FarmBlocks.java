package com.voxel.game;

import com.voxel.Player;
import com.voxel.World;
import com.voxel.world.ChunkManager;

/**
 * Survival farming: farmland blocks (dry/wet), the eight wheat growth stages,
 * and the periodic growth/hydration scan. Kept separate from BlockInteraction
 * so the block ids and the growth rules are unit-testable without GL.
 *
 * <p>Block ids 906-915 are fixed and registered in Main before the resource
 * pack's auto-content-loader, so saves and commands never renumber them.
 */
public final class FarmBlocks {

    public static final int BLOCK_FARMLAND_DRY = 906;
    public static final int BLOCK_FARMLAND_WET = 907;
    public static final int BLOCK_WHEAT_0 = 908;  // seeded
    public static final int BLOCK_WHEAT_7 = 915;  // ripe

    /** Scan half-extent (blocks) around the player for crops + farmland. */
    public static final int SCAN_RADIUS = 40;

    private static final java.util.Random RANDOM = new java.util.Random();
    /** Farmland hydrates when water is within this many blocks (same level). */
    public static final int HYDRATION_RADIUS = 4;

    private FarmBlocks() { }

    public static boolean isFarmland(int blockId) {
        return blockId == BLOCK_FARMLAND_DRY || blockId == BLOCK_FARMLAND_WET;
    }

    public static boolean isWheat(int blockId) {
        return blockId >= BLOCK_WHEAT_0 && blockId <= BLOCK_WHEAT_7;
    }

    /** Growth stage (0-7) of a wheat block, or -1 for non-wheat. */
    public static int wheatStage(int blockId) {
        return isWheat(blockId) ? blockId - BLOCK_WHEAT_0 : -1;
    }

    /** True when the block is dirt, grass (any biome variant), or mycelium. */
    public static boolean isTillable(int blockId, com.voxel.utils.BlockDataManager bdm) {
        if (blockId == 13) return true; // dirt
        String name = bdm.getName(blockId);
        if (name == null) return false;
        return name.equals("grass_block") || name.startsWith("grass")
                || name.startsWith("mycelium") || name.equals("dirt");
    }

    /**
     * Minecraft farmland hydration: a water block within {@link #HYDRATION_RADIUS}
     * blocks on the same level, one above, or one below keeps soil moist.
     */
    public static boolean hasWaterNearby(World world, int x, int y, int z) {
        for (int dx = -HYDRATION_RADIUS; dx <= HYDRATION_RADIUS; dx++) {
            for (int dz = -HYDRATION_RADIUS; dz <= HYDRATION_RADIUS; dz++) {
                if (dx * dx + dz * dz > HYDRATION_RADIUS * HYDRATION_RADIUS) continue;
                if (isWater(world, x + dx, y, z + dz)
                        || isWater(world, x + dx, y + 1, z + dz)
                        || isWater(world, x + dx, y - 1, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWater(World world, int x, int y, int z) {
        if (world == null) return false;
        int v = world.getVoxel(x, y, z);
        if (v <= 0) return false;
        // Water id 15 (source) plus flowing water levels registered as water_*.
        return v == 15 || (v > 15 && v < 21); // flowing water range used by FluidManager
    }

    /**
     * Chooses the farmland block id to place for a cell, hydrating when water
     * is within range (Minecraft's tilling rule).
     */
    public static int farmlandIdFor(World world, int x, int y, int z) {
        return hasWaterNearby(world, x, y, z) ? BLOCK_FARMLAND_WET : BLOCK_FARMLAND_DRY;
    }

    /**
     * Periodic growth + hydration pass. Runs on a fixed cadence (0.5 s) from
     * Main. Scans a bounded box around the player and advances wheat one stage
     * with a per-attempt probability (wet soil grows faster), and toggles
     * farmland wet/dry when hydration changes.
     *
     * @return number of blocks changed this pass (tests assert it stays sane)
     */
    public static int tickSurvival(World world, ChunkManager chunkManager, Player player) {
        if (world == null || chunkManager == null || player == null) return 0;
        int changes = 0;
        int px = com.voxel.utils.FixedPoint.blockX(player.getFixedX());
        int py = com.voxel.utils.FixedPoint.blockX(player.getFixedY());
        int pz = com.voxel.utils.FixedPoint.blockX(player.getFixedZ());

        int minX = px - SCAN_RADIUS, maxX = px + SCAN_RADIUS;
        int minZ = pz - SCAN_RADIUS, maxZ = pz + SCAN_RADIUS;
        int minY = Math.max(0, py - 6), maxY = Math.min(255, py + 2);

        java.util.Random rnd = RANDOM;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int b = world.getVoxel(x, y, z);
                    if (b <= 0) continue;
                    if (isWheat(b)) {
                        int stage = b - BLOCK_WHEAT_0;
                        if (stage >= 7) continue;
                        int below = world.getVoxel(x, y - 1, z);
                        boolean wet = below == BLOCK_FARMLAND_WET;
                        boolean dry = below == BLOCK_FARMLAND_DRY;        if (!wet && !dry) continue; // crops need farmland below
        // Per-0.5s attempt odds: wet ≈ 10%/s, dry ≈ 5%/s.
        double chance = wet ? 0.05 : 0.025;
                        if (rnd.nextDouble() < chance) {
                            if (chunkManager.setVoxel(x, y, z, b + 1)) changes++;
                        }
                    } else if (b == BLOCK_FARMLAND_DRY || b == BLOCK_FARMLAND_WET) {
                        boolean moist = hasWaterNearby(world, x, y, z);
                        int want = moist ? BLOCK_FARMLAND_WET : BLOCK_FARMLAND_DRY;
                        if (want != b && chunkManager.setVoxel(x, y, z, want)) changes++;
                    }
                }
            }
        }
        return changes;
    }
}
