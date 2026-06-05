package com.voxel.world;

import com.voxel.biome.BiomeProvider;

/**
 * Base world generator. All terrain generation is handled by
 * {@link DimensionWorldGenerator} which overrides getHeight(),
 * getBlockType(), and decorate() with dimension-specific logic.
 */
public class WorldGenerator {
    protected final com.voxel.utils.BlockDataManager blockDataManager;
    protected BiomeProvider biomeProvider;

    public WorldGenerator(long seed, com.voxel.utils.BlockDataManager blockDataManager) {
        this.blockDataManager = blockDataManager;
    }

    /** Returns the biome provider for this generator, or null if not available. */
    public BiomeProvider getBiomeProvider() {
        return biomeProvider;
    }

    public int getHeight(int x, int z) {
        return 64;
    }

    /**
     * Decorate a chunk with features like trees, flowers, etc.
     * Called after base terrain is generated.
     */
    public void decorate(int cx, int cy, int cz, int slot, com.voxel.World world) {
        // Override in subclasses
    }

    public int getBlockType(int x, int y, int z, int height) {
        if (y > height) return 0;
        if (y == height) return 1;
        if (y > height - 3) return 13;
        return 2;
    }
}
