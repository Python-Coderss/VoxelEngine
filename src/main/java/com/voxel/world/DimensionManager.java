package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightPropagationEngine;
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

    public DimensionManager(BlockDataManager blockDataManager) {
        this.blockDataManager = blockDataManager;
    }

    /**
     * Creates a new dimension instance (world + chunk manager).
     * If the dimension already exists, this is a no-op.
     */
    public void createDimension(DimensionType type, int renderDistance) {
        if (dimensions.containsKey(type)) return;

        System.out.println("Creating dimension: " + type.name);
        World world = new World();
        DimensionWorldGenerator generator = new DimensionWorldGenerator(type);
        LightPropagationEngine lightEngine = new LightPropagationEngine(world, blockDataManager);
        ChunkManager chunkManager = new ChunkManager(world, generator, lightEngine, renderDistance);

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

    public DimensionWorldGenerator getActiveGenerator() {
        DimensionInstance inst = dimensions.get(activeDimension);
        return inst != null ? inst.generator : null;
    }

    /**
     * Checks if a dimension has been created yet.
     */
    public boolean isDimensionReady(DimensionType type) {
        return dimensions.containsKey(type);
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
        final DimensionWorldGenerator generator;

        DimensionInstance(World world, ChunkManager chunkManager, DimensionWorldGenerator generator) {
            this.world = world;
            this.chunkManager = chunkManager;
            this.generator = generator;
        }
    }
}
