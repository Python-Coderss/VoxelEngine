package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BiomeManager;
import com.voxel.utils.BlockDataManager;

import java.util.EnumMap;
import java.util.Map;

/**
 * Manages multiple dimension instances, each with their own World, ChunkManager, and generator.
 */
public class DimensionManager {
    private final Map<DimensionType, DimensionInstance> dimensions = new EnumMap<>(DimensionType.class);
    private DimensionType activeDimension = DimensionType.OVERWORLD;
    private final BlockDataManager blockDataManager;
    private final WorldSaveManager saveManager;
    private final BiomeManager biomeManager;

    public DimensionManager(BlockDataManager blockDataManager, WorldSaveManager saveManager, BiomeManager biomeManager) {
        this.blockDataManager = blockDataManager;
        this.saveManager = saveManager;
        this.biomeManager = biomeManager;
    }

    /**
     * Creates a new dimension instance (world + chunk manager).
     * If the dimension already exists, this is a no-op.
     */
    public void createDimension(DimensionType type, int renderDistance) {
        if (dimensions.containsKey(type)) return;

        // Calculate pool size: (renderDistance*2+1)² * 16 chunks, doubled for safety margin
        int chunksNeeded = (renderDistance * 2 + 1) * (renderDistance * 2 + 1) * 16;
        int poolSize = Math.max(chunksNeeded * 2, 2048);

        System.out.println("Creating dimension: " + type.name + " (pool=" + poolSize + " chunks, ~" + (poolSize * 4096L * 4 / 1024 / 1024) + " MB)");
        World world = new World(poolSize);
        WorldGenerator generator;
        if (type == DimensionType.AETHER) {
            generator = new AetherGenerator(0, blockDataManager);
        } else {
            generator = new DimensionWorldGenerator(type, blockDataManager);
        }
        LightEngine lightEngine = new LightEngine(world, blockDataManager);
        ChunkManager chunkManager = new ChunkManager(world, generator, lightEngine, renderDistance, saveManager, type, biomeManager, blockDataManager);

        // Wire the biome provider into BiomeManager so the tint map reflects actual biomes
        if (biomeManager != null && generator.getBiomeProvider() != null) {
            biomeManager.setBiomeProvider(generator.getBiomeProvider());
            biomeManager.generateBiomeData(2048);
            chunkManager.markBiomeMapDirty();
        }

        dimensions.put(type, new DimensionInstance(world, chunkManager, generator));
    }

    /**
     * Gets or creates a dimension lazily. Only creates the dimension if it doesn't exist yet.
     * This saves memory by not creating all dimensions at startup.
     */
    public DimensionInstance getOrCreateDimension(DimensionType type, int renderDistance) {
        DimensionInstance inst = dimensions.get(type);
        if (inst == null) {
            createDimension(type, renderDistance);
            inst = dimensions.get(type);
        }
        return inst;
    }

    /**
     * Ensures a dimension exists. Returns true if it was just created.
     */
    public boolean ensureDimension(DimensionType type, int renderDistance) {
        if (!dimensions.containsKey(type)) {
            createDimension(type, renderDistance);
            return true;
        }
        return false;
    }

    /**
     * Switches the active dimension.
     */
    public void switchTo(DimensionType type) {
        if (dimensions.containsKey(type)) {
            activeDimension = type;
        }
    }

    public DimensionType getActiveDimension() {
        return activeDimension;
    }

    public World getActiveWorld() {
        DimensionInstance inst = dimensions.get(activeDimension);
        return inst != null ? inst.world : null;
    }

    public ChunkManager getActiveChunkManager() {
        DimensionInstance inst = dimensions.get(activeDimension);
        return inst != null ? inst.chunkManager : null;
    }

    public WorldGenerator getActiveGenerator() {
        DimensionInstance inst = dimensions.get(activeDimension);
        return inst != null ? inst.generator : null;
    }

    /**
     * Unloads a dimension to free memory.
     */
    public void unloadDimension(DimensionType type) {
        if (type == activeDimension) return; // Don't unload the active dimension
        DimensionInstance inst = dimensions.remove(type);
        if (inst != null) {
            inst.chunkManager.shutdown();
            System.out.println("Unloaded dimension: " + type.name);
        }
    }

    /**
     * Gets the World for a specific dimension, creating it if needed.
     */
    public World getWorld(DimensionType type, int renderDistance) {
        DimensionInstance inst = getOrCreateDimension(type, renderDistance);
        return inst != null ? inst.world : null;
    }

    /**
     * Gets the ChunkManager for a specific dimension, creating it if needed.
     */
    public ChunkManager getChunkManager(DimensionType type, int renderDistance) {
        DimensionInstance inst = getOrCreateDimension(type, renderDistance);
        return inst != null ? inst.chunkManager : null;
    }

    private static class DimensionInstance {
        final World world;
        final ChunkManager chunkManager;
        final WorldGenerator generator;

        DimensionInstance(World world, ChunkManager chunkManager, WorldGenerator generator) {
            this.world = world;
            this.chunkManager = chunkManager;
            this.generator = generator;
        }
    }
}
