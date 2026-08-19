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

    /**
     * Biome provider for the map preview, which runs on the logic thread while
     * chunk generation runs on the gen thread. Defaults to the shared provider
     * (safe for generators whose biome state is read-only); generators with
     * single-threaded biome state (e.g. the beta GenLayer chain) override this
     * with an isolated instance.
     */
    public BiomeProvider getMapBiomeProvider() {
        return getBiomeProvider();
    }

    public int getHeight(int x, int y, int z) {
        return 64;
    }

    /**
     * Decorate a chunk with features like trees, flowers, etc.
     * Called after base terrain is generated.
     */
    public void decorate(int cx, int cy, int cz, int slot, com.voxel.World world) {
        // Override in subclasses
    }

    /**
     * Populate a section directly into the world's pool when the generator has
     * a bulk section representation. A negative result requests the generic
     * per-voxel fallback; otherwise the result is the solid-voxel count.
     */
    public int populateSection(int cx, int cy, int cz, com.voxel.World world, int slot) {
        return -1;
    }

    /**
     * Prepare a section before voxel queries. A false result means the section
     * is known to be entirely empty and the caller may skip its voxel loop.
     * Legacy generators conservatively return true.
     */
    public boolean prepareSection(int cx, int cy, int cz) {
        return true;
    }

    /**
     * Returns the block at a world coordinate without requiring the caller to
     * calculate a column height first. Generators with direct 3D terrain
     * evaluation should override this method. The default keeps the legacy
     * height-based generators compatible.
     */
    public int getBlockType(int x, int y, int z) {
        return getBlockType(x, y, z, getHeight(x, y, z));
    }

    /**
     * Legacy height-based block query retained for the non-Beta generators.
     */
    public int getBlockType(int x, int y, int z, int height) {
        if (y > height) return 0;
        if (y == height) return 1;
        if (y > height - 3) return 13;
        return 2;
    }
}
