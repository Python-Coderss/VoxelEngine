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
    /** World generation seed shared by all dimensions (0 = classic defaults). */
    private long worldSeed = 0L;

    public DimensionManager(BlockDataManager blockDataManager, WorldSaveManager saveManager, BiomeManager biomeManager) {
        this.blockDataManager = blockDataManager;
        this.saveManager = saveManager;
        this.biomeManager = biomeManager;
    }

    public void setWorldSeed(long seed) {
        this.worldSeed = seed;
        // Seeds are baked into generator constructors, so changing the seed only
        // affects dimensions created afterwards. Call before createDimension.
    }

    public long getWorldSeed() { return worldSeed; }

    /** Mixes the world seed into a per-dimension noise seed, keeping 0 → classic. */
    public long seedFor(DimensionType type) {
        if (worldSeed == 0L) return 0L;
        return worldSeed ^ ((long) type.ordinal() + 1) * 0x9E3779B97F4A7C15L;
    } 

    /**
     * Creates a new dimension instance (world + chunk manager).
     * If the dimension already exists, this is a no-op.
     */
    public void createDimension(DimensionType type, int renderDistance) {
        if (dimensions.containsKey(type)) return;

        // Allocate the bounded sliding-window pool, not the entire theoretical
        // render-distance volume. The spawn bootstrap needs only the immediate
        // 3x3x3 area; ChunkManager evicts the farthest unpinned columns as the
        // normal stream expands after spawn. Allocating the old render-distance
        // estimate here reserved ~850 MB of Java arrays before the first chunk.
        // 2048 sections leaves ample room for streaming while keeping boot fast.
        int poolSize = 2048;

        System.out.println("Creating dimension: " + type.name + " (pool=" + poolSize + " chunks, ~" + (poolSize * 4096L * 4 / 1024 / 1024) + " MB)");
        long createStart = System.nanoTime();
        World world = new World(poolSize);
        System.out.println("[BOOT] world pools ready " + ((System.nanoTime() - createStart) / 1_000_000L) + " ms");
        System.out.flush();
        WorldGenerator generator;
        long typeSeed = seedFor(type);
        if (type == DimensionType.AETHER) {
            generator = new AetherGenerator(typeSeed, blockDataManager);
        } else if (type == DimensionType.OVERWORLD) {
            // The normal Overworld uses the coordinate-tuned far-lands policy
            // (OverworldBetaPrecision): classic integer far lands (~3,060
            // blocks) with near-full float precision in bands 0–1, then
            // tuned float values 23→20→12→6→4→2→1. X/Z doubles stay at
            // 52 bits through 4,000, then use the historical 26-bit mask;
            // Y follows the same full-through-4,000 degradation bands.
            generator = new BetaWorldGenerator(typeSeed, blockDataManager,
                    com.voxel.world.beta.BetaNumericProfile.OVERWORLD);
        } else if (type == DimensionType.ERROR502) {
            // ERROR502 remains the isolated experimental world using the
            // aggressive coordinate-aware degradation switches
            // (Error502BetaPrecision): float 23→16→11→6→4→2→1, doubles
            // 52→40→30→18→11→6→1.
            generator = new BetaWorldGenerator(typeSeed, blockDataManager,
                    com.voxel.world.beta.BetaNumericProfile.DEFAULT);
        } else if (type == DimensionType.PORTAL_HALL) {
            generator = new PortalHallGenerator(blockDataManager);
        } else {
            generator = new DimensionWorldGenerator(type, blockDataManager);
            if (typeSeed != 0L && generator instanceof DimensionWorldGenerator) {
                ((DimensionWorldGenerator) generator).setSeed(typeSeed);
            }
        }
        System.out.println("[BOOT] generator ready " + ((System.nanoTime() - createStart) / 1_000_000L) + " ms");
        System.out.flush();
        LightEngine lightEngine = new LightEngine(world, blockDataManager);
        ChunkManager chunkManager = new ChunkManager(world, generator, lightEngine, renderDistance, saveManager, type, biomeManager, blockDataManager);
        System.out.println("[BOOT] chunk manager ready " + ((System.nanoTime() - createStart) / 1_000_000L) + " ms");
        System.out.flush();

        // Wire the biome provider into BiomeManager so the tint map reflects actual biomes
        if (biomeManager != null && generator.getBiomeProvider() != null) {
            biomeManager.setBiomeProvider(generator.getBiomeProvider());
            // Use a neutral, full-size fallback immediately. The actual biome noise
            // map is generated after the first terrain is visible by the gen thread.
            biomeManager.generateFallbackBiomeData(2048);
            chunkManager.markBiomeMapDirty();
        }

        dimensions.put(type, new DimensionInstance(world, chunkManager, generator));
        System.out.println("[BOOT] dimension ready " + ((System.nanoTime() - createStart) / 1_000_000L) + " ms");
        System.out.flush();
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
