package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;

import java.util.Arrays;

/**
 * Read-only harness for sampling existing voxel data. Additive builders (such as
 * the Tutorial World) use this to discover the procedural terrain — surface
 * heights, block types, liquids — and place their structures ON TOP of it
 * instead of blindly flattening or overriding the world.
 *
 * <p>All methods treat coordinates as absolute world coordinates and are pure
 * reads: they never modify the world, so they are safe to call from any thread
 * and can be used to probe the terrain before committing a build.
 */
public final class TerrainSampler {

    /** Name fragments that never count as "ground" even if they are full voxels. */
    private static final String[] NON_GROUND_FRAGMENTS = {
        "leaves", "leaf", "_log", "sapling", "flower", "poppy", "dandelion",
        "rose", "tulip", "bluet", "fern", "tallgrass", "dead_bush", "mushroom",
        "cactus", "reeds", "waterlily", "vine", "snow_layer", "torch",
        "rail", "slab", "stairs", "pumpkin", "melon", "portal", "aercloud", "aerogel"
    };

    private final World world;
    private final BlockDataManager blockDataManager;

    public TerrainSampler(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
    }

    /** Raw block id at an absolute voxel (0 = air / unloaded chunk). */
    public int block(int x, int y, int z) {
        return world.getVoxel(x, y, z);
    }

    /** True if the voxel is empty air (or an unloaded chunk). */
    public boolean isAir(int x, int y, int z) {
        return world.getVoxel(x, y, z) == 0;
    }

    /** True if the voxel is a liquid (water, lava, ...). */
    public boolean isLiquid(int x, int y, int z) {
        return blockDataManager.isLiquid(world.getVoxel(x, y, z));
    }

    /**
     * True if the voxel is solid buildable terrain: a full, opaque block that is
     * neither a liquid nor foliage/vegetation (grass, dirt, stone, sand, gravel...).
     */
    public boolean isGround(int x, int y, int z) {
        int b = world.getVoxel(x, y, z);
        if (b == 0) return false;
        if (blockDataManager.isLiquid(b)) return false;
        if (!blockDataManager.isFullBlock(b)) return false;
        String name = blockDataManager.getName(b);
        for (String f : NON_GROUND_FRAGMENTS) {
            if (name.contains(f)) return false;
        }
        return true;
    }

    /**
     * Height (absolute Y) of the topmost ground block in a column, or -1 if the
     * column has no ground within [yMin, yMax]. Trees (logs/leaves), flowers and
     * liquids are skipped so the result is the actual terrain surface.
     */
    public int surfaceHeight(int x, int z, int yMin, int yMax) {
        for (int y = yMax; y >= yMin; y--) {
            if (isGround(x, y, z)) return y;
        }
        return -1;
    }

    /**
     * Y of the first free (air) cell above the surface — the cell where a block
     * should be placed so it sits on top of the ground. Returns -1 if the column
     * has no ground.
     */
    public int groundLevel(int x, int z, int yMin, int yMax) {
        int s = surfaceHeight(x, z, yMin, yMax);
        return s < 0 ? -1 : s + 1;
    }

    /**
     * Median surface height over an XZ footprint, ignoring columns with no ground
     * (e.g. ocean or unloaded columns). A robust "natural level" for a build pad:
     * more stable against a single tree-topped or hole-ridden column than a plain
     * average. Returns -1 if no column has ground.
     */
    public int medianSurface(int x0, int z0, int x1, int z1, int yMin, int yMax) {
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        int[] hs = new int[w * h];
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int s = surfaceHeight(x, z, yMin, yMax);
                if (s >= 0) hs[n++] = s;
            }
        }
        if (n == 0) return -1;
        Arrays.sort(hs, 0, n);
        return hs[n / 2];
    }
}
