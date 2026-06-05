package com.voxel.world;

import com.voxel.World;
import com.voxel.biome.Biome;
import com.voxel.biome.BiomeDecorator;
import com.voxel.biome.BiomeProvider;
import com.voxel.biome.BiomeRegistry;
import com.voxel.utils.PerlinNoise;

/**
 * Procedural world generators for each dimension.
 * Overworld uses multi-octave Perlin noise + biome system for realistic
 * Minecraft 1.12.2-style terrain generation with full biome distribution,
 * decorations, and structures.
 *
 * Other dimensions (Nether, End, Aether) have unique terrain generation logic.
 */
public class DimensionWorldGenerator extends WorldGenerator {

    private final DimensionType dimension;
    private final PerlinNoise continentalNoise;
    private final PerlinNoise erosionNoise;
    private final PerlinNoise detailNoise;
    private final PerlinNoise treeNoise;

    private final int stoneId;
    private final int grassId;
    private final int dirtId;
    private final int sandId;
    private final int waterId;
    private final int lavaId;
    private final int netherrackId;
    private final int glowstoneId;
    private final int quartzId;
    private final int soulSandId;
    private final int netherBrickId;
    private final int endStoneId;
    private final int obsidianId;
    private final int aetherPortalId;
    private final int dandelionId;
    private final int poppyId;
    private final int tallGrassId;
    private final int oakLogId;
    private final int oakLeavesId;
    private final int birchLogId;
    private final int spruceLogId;
    private final int snowLayerId;
    private final int iceId;

    // Biome system (biomeProvider is inherited from WorldGenerator)
    private final BiomeDecorator biomeDecorator;
    private final java.util.Random decorationRand = new java.util.Random();

    private static final int WATER_LEVEL = 62;
    private static final int SPAWN_X = 1024;
    private static final int SPAWN_Z = 1024;

    // Structure generators
    private final com.voxel.world.structure.MapGenStructure structureGen;

    public DimensionWorldGenerator(DimensionType dimension, com.voxel.utils.BlockDataManager blockDataManager) {
        super(0, blockDataManager);
        this.dimension = dimension;
        this.continentalNoise = new PerlinNoise(42);
        this.erosionNoise = new PerlinNoise(123);
        this.detailNoise = new PerlinNoise(456);
        this.treeNoise = new PerlinNoise(131415);

        // Initialize biome system for overworld
        BiomeRegistry.init();
        super.biomeProvider = new BiomeProvider(42);
        this.biomeDecorator = new BiomeDecorator();
        this.structureGen = new com.voxel.world.structure.MapGenStructure(42);

        this.stoneId = blockDataManager.findBlockId("stone");
        this.grassId = blockDataManager.findBlockId("grass_block");
        this.dirtId = blockDataManager.findBlockId("dirt");
        this.sandId = blockDataManager.findBlockId("sand");
        this.waterId = blockDataManager.findBlockId("water");
        this.lavaId = blockDataManager.findBlockId("lava");
        this.netherrackId = blockDataManager.findBlockId("netherrack");
        this.glowstoneId = blockDataManager.findBlockId("glowstone");
        this.quartzId = blockDataManager.findBlockId("quartz_ore");
        this.soulSandId = blockDataManager.findBlockId("soul_sand");
        this.netherBrickId = blockDataManager.findBlockId("nether_bricks");
        this.endStoneId = blockDataManager.findBlockId("end_stone");
        this.obsidianId = blockDataManager.findBlockId("obsidian");
        this.aetherPortalId = blockDataManager.findBlockId("aether_portal_ns");
        this.dandelionId = blockDataManager.findBlockId("dandelion");
        this.poppyId = blockDataManager.findBlockId("rose");
        this.tallGrassId = blockDataManager.findBlockId("tallgrass");
        this.oakLogId = blockDataManager.findBlockId("oak_log");
        this.oakLeavesId = blockDataManager.findBlockId("oak_leaves");
        this.birchLogId = blockDataManager.findBlockId("birch_log");
        this.spruceLogId = blockDataManager.findBlockId("spruce_log");
        Integer slId = blockDataManager.findBlockId("snow_layer");
        this.snowLayerId = slId != null ? slId : 0;
        Integer iceIdObj = blockDataManager.findBlockId("ice");
        this.iceId = iceIdObj != null ? iceIdObj : 0;
    }

    @Override
    public int getHeight(int x, int z) {
        if (dimension != DimensionType.OVERWORLD) {
            return dimension.baseHeight;
        }
        return getBiomeHeight(x, z);
    }

    /**
     * Gets terrain height using biome system for biome-specific terrain features.
     * Uses the biome's base height and height variation combined with noise.
     * Ported from Minecraft 1.12.2-style terrain generation.
     */
    private int getBiomeHeight(int x, int z) {
        float continental = continentalNoise.noise(x, z, 0.0015f) * 22.0f;
        float erosion = erosionNoise.noise(x, z, 0.008f) * 8.0f;
        float detail = detailNoise.noise(x, z, 0.035f) * 3.0f;
        
        // Base terrain height
        float height = 58.0f + continental + erosion + detail;

        // Mountains
        float mountainNoise = continentalNoise.noise(x, z, 0.0008f) * 1.5f - 0.3f;
        if (mountainNoise > 0) {
            height += mountainNoise * mountainNoise * 25.0f;
        }

        float steepNoise = Math.abs(erosionNoise.noise(x, z, 0.015f)) * 6.0f;
        if (erosionNoise.noise(x, z, 0.01f) > 0.4f) {
            height += steepNoise;
        }

        // Biome height adjustment
        Biome biome = biomeProvider.getBiome(x, z);
        float biomeHeightOffset = biome.getBaseHeight() * 8.0f;
        float biomeVariation = biome.getHeightVariation() * 4.0f;
        float biomeNoise = detailNoise.noise(x + 500, z + 500, 0.01f) * biomeVariation;
        height += biomeHeightOffset + biomeNoise;

        // Ocean biomes: terrain goes below water level
        if (biome instanceof com.voxel.biome.BiomeOcean) {
            float oceanFloor = 30.0f + continental * 0.5f;
            height = Math.min(height, oceanFloor);
        }

        // River biomes: carve valleys
        if (biome instanceof com.voxel.biome.BiomeRiver) {
            height = WATER_LEVEL - 6 + erosion * 2.0f;
        }

        int finalHeight = Math.round(Math.max(1, height));

        // Spawn area pool carving
        float dx = x - SPAWN_X;
        float dz = z - SPAWN_Z;
        float distSq = dx * dx + dz * dz;

        if (distSq < 25) {
            return WATER_LEVEL - 1;
        }
        if (distSq < 100) {
            float ringDist = (float) Math.sqrt(distSq);
            float blend = (ringDist - 5.0f) * 1.0f;
            return Math.max(WATER_LEVEL + (int) blend, finalHeight);
        }

        return finalHeight;
    }

    @Override
    public int getBlockType(int x, int y, int z, int height) {
        switch (dimension) {
            case NETHER:
                return generateNether(x, y, z, height);
            case END:
                return generateEnd(x, y, z, height);
            case AETHER:
                return 0; // Aether uses AetherGenerator, not this class
            default:
                return generateOverworld(x, y, z, height);
        }
    }

    private int generateOverworld(int x, int y, int z, int height) {
        if (y > height) {
            if (y <= WATER_LEVEL && height < WATER_LEVEL) {
                return waterId;
            }
            return 0; // Air
        }

        // Get biome for block type determination
        Biome biome = biomeProvider.getBiome(x, z);
        int topBlock = biome.topBlockId;
        int fillerBlock = biome.fillerBlockId;
        
        // Snow biomes: cover with snow on top
        if (biome.enableSnow && y == height && height > 0) {
            // Check if block above is air (surface exposed)
            // Return grass block as base, snow will be placed as decoration
        }

        int depth = height - y;
        if (depth == 0) {
            if (height < WATER_LEVEL) {
                // Underwater: sand or clay
                if (biome instanceof com.voxel.biome.BiomeOcean || biome instanceof com.voxel.biome.BiomeRiver) {
                    return sandId;
                }
                return sandId;
            }
            // Check near water for sand beaches
            if (isNearWater(x, z)) return sandId;
            return topBlock;
        }
        if (depth <= 3) {
            if (height < WATER_LEVEL) return sandId;
            return fillerBlock;
        }
        return stoneId;
    }

    private boolean isNearWater(int x, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                int neighborHeight = getHeight(x + dx, z + dz);
                if (neighborHeight < WATER_LEVEL) return true;
            }
        }
        return false;
    }

    private int generateNether(int x, int y, int z, int height) {
        int ceiling = height + 18;
        int lavaLevel = height - 8;
        if (y <= lavaLevel) {
            if (y < lavaLevel - 3) return netherrackId;
            return lavaId;
        }
        if (y >= ceiling) return netherrackId;

        float heightRatio = (float)(y - lavaLevel) / (float)(ceiling - lavaLevel);
        float scale = 0.025f + heightRatio * 0.02f;
        float caveNoise = continentalNoise.noise(x, z, scale)
                        + erosionNoise.noise(x * 0.5f, z * 0.5f, 0.015f) * 0.3f
                        + detailNoise.noise(x, z, 0.04f) * 0.15f;

        float carveThreshold = -0.2f + heightRatio * 0.3f;
        carveThreshold += (float)Math.sin(x * 0.05f + z * 0.07f) * 0.1f;

        if (caveNoise < carveThreshold) {
            int hash = (x * 31 + z * 73 + y * 137) & 0xFF;
            if (y > ceiling - 4 && (hash % 20) < 3) return glowstoneId;
            if (hash < 6 && heightRatio > 0.3f && heightRatio < 0.8f) return quartzId;
            if (heightRatio < 0.3f && (hash % 15) < 4) return soulSandId;
            if (hash > 248 && heightRatio > 0.5f) return netherBrickId;
            return netherrackId;
        }
        return 0;
    }

    private static final int END_SPAWN_X = 1024;
    private static final int END_SPAWN_Z = 1024;
    private static final int END_CENTER_RADIUS = 55;
    private static final int END_VOID_RADIUS = 220;

    private int generateEnd(int x, int y, int z, int height) {
        float dx = x - END_SPAWN_X;
        float dz = z - END_SPAWN_Z;
        float distSq = dx * dx + dz * dz;
        float dist = (float) Math.sqrt(distSq);

        if (dist < END_CENTER_RADIUS) {
            float undulation = continentalNoise.noise(x, z, 0.006f) * 3.0f;
            int surfaceY = height + (int)((END_CENTER_RADIUS - dist) * 0.15f) + (int)undulation;
            if (dist < 35) surfaceY = height + (int)undulation;
            if (y == surfaceY) return endStoneId;
            if (y < surfaceY && y > surfaceY - 7) return endStoneId;
            return 0;
        }

        if (dist < END_VOID_RADIUS) {
            float rockNoise = continentalNoise.noise(x, z, 0.04f);
            if (rockNoise > 0.6f) {
                int rockY = height + (int)(rockNoise * 6.0f) - 3;
                if (y == rockY) return endStoneId;
            }
            return 0;
        }

        float islandNoise = continentalNoise.noise(x, z, 0.005f);
        float rawHeight = height + islandNoise * 12.0f;
        int islandCenterY = Math.round(rawHeight + (continentalNoise.noise(x, z, 0.02f)) * 4.0f);
        float erosion = Math.abs(erosionNoise.noise(x, z, 0.01f)) * 10.0f;
        float radius = 8.0f + erosion;

        int cx = x % 64; if (cx < 0) cx += 64;
        int cz = z % 64; if (cz < 0) cz += 64;
        cx -= 32; cz -= 32;
        float periodicDist = (float) Math.sqrt(cx * cx + cz * cz);

        if (periodicDist < radius) {
            int islandSurface = islandCenterY + Math.round((radius - periodicDist) * 0.3f);
            if (y == islandSurface) return endStoneId;
            if (y < islandSurface && y > islandSurface - 5) return endStoneId;
        }
        return 0;
    }

    private void placePortal(World world, int sx, int sy, int sz, int frameId, int portalId) {
        for (int i = 0; i < 4; i++) world.setVoxel(sx + i, sy, sz, frameId);
        for (int i = 0; i < 4; i++) world.setVoxel(sx + i, sy + 4, sz, frameId);
        for (int i = 1; i < 4; i++) world.setVoxel(sx, sy + i, sz, frameId);
        for (int i = 1; i < 4; i++) world.setVoxel(sx + 3, sy + i, sz, frameId);
        for (int px = 1; px <= 2; px++)
            for (int py = 1; py <= 3; py++)
                world.setVoxel(sx + px, sy + py, sz, portalId);
    }

    @Override
    public void decorate(int cx, int cy, int cz, int slot, World world) {
        // --- Spawn portals ---
        if (cx == 64 && cz == 64) {
            if (dimension == DimensionType.OVERWORLD && cy == 4) {
                placePortal(world, 1028, 64, 1030, obsidianId, 19); // Nether portal
                placePortal(world, 1028, 64, 1036, glowstoneId, aetherPortalId);
            } else if (dimension == DimensionType.NETHER && cy == 2) {
                placePortal(world, 1028, 32, 1030, obsidianId, 19);
                placePortal(world, 1028, 32, 1036, glowstoneId, aetherPortalId);
            }
        }

        // --- End obsidian spawn platform (5x5 at (100, 47, 0)) ---
        if (dimension == DimensionType.END && cy == 2) {
            if (cx == 6 && cz == 0) {
                int platformY = 47;
                int localY = platformY - (cy << 4);
                int lxStart = 100 - (cx << 4);
                int lzStart = 0;
                for (int lx = lxStart; lx <= lxStart + 4; lx++) {
                    for (int lz = lzStart; lz <= lzStart + 4; lz++) {
                        world.setVoxelInPool(slot, lx, localY, lz, obsidianId);
                    }
                }
            }
        }

        if (dimension != DimensionType.OVERWORLD) return;

        int worldX = cx << 4;
        int worldZ = cz << 4;

        // --- Structures ---
        if (cy == 4) { // Only generate structures in the surface layer
            structureGen.generateStructures(world, cx, cz, biomeProvider);
        }
        
        // Use biome decorations for this chunk
        // Sample biome at multiple points in the chunk for varied decorations
        for (int lx = 2; lx < 14; lx += 4) {
            for (int lz = 2; lz < 14; lz += 4) {
                int wx = worldX + lx;
                int wz = worldZ + lz;
                Biome biome = biomeProvider.getBiome(wx, wz);
                
                // Skip spawning near spawn point
                int dx = wx - SPAWN_X;
                int dz = wz - SPAWN_Z;
                if (dx * dx + dz * dz < 150) continue;
                
                int height = getHeight(wx, wz);
                if (height < WATER_LEVEL + 1) continue;
                if (cy != (height >> 4)) continue;
                
                int surfaceBlock = world.getVoxel(wx, height, wz);
                boolean isSurface = (surfaceBlock == biome.topBlockId || surfaceBlock == 1 || surfaceBlock == 13);
                if (!isSurface) continue;
                
                decorationRand.setSeed((long) wx * 341873128712L + (long) wz * 132897987541L + 42);
                
                // --- Tree placement ---
                if (biome.treesPerChunk > 0 && height > WATER_LEVEL) {
                    int treeChance = Math.max(1, 16 - biome.treesPerChunk);
                    if (decorationRand.nextInt(treeChance) == 0) {
                        placeTreeForBiome(biome, world, wx, height + 1, wz, decorationRand);
                    }
                }
                
                // --- Flower placement ---
                if (biome.flowersPerChunk > 0 && decorationRand.nextInt(4) == 0) {
                    int flowerId = biome.pickRandomFlower(decorationRand);
                    if (flowerId > 0 && world.getVoxel(wx, height + 1, wz) == 0) {
                        world.setVoxel(wx, height + 1, wz, flowerId);
                    }
                }
                
                // --- Grass placement ---
                if (biome.grassPerChunk > 0 && decorationRand.nextInt(3) == 0) {
                    if (world.getVoxel(wx, height + 1, wz) == 0) {
                        int grassBlockId = biome.getRandomGrassFeature(decorationRand);
                        world.setVoxel(wx, height + 1, wz, grassBlockId);
                    }
                }
                
                // --- Snow cover (cold biomes) ---
                if (biome.enableSnow && height > WATER_LEVEL && snowLayerId > 0) {
                    if (world.getVoxel(wx, height + 1, wz) == 0 && world.getVoxel(wx, height, wz) != 15) {
                        world.setVoxel(wx, height + 1, wz, snowLayerId);
                    }
                }
            }
        }
    }

    /**
     * Places a tree appropriate for the given biome at the specified position.
     */
    private void placeTreeForBiome(Biome biome, World world, int x, int y, int z, java.util.Random rand) {
        // Delegate to BiomeDecorator's tree placement methods via a quick inline implementation
        int treeType = biome.getRandomTreeFeature(rand);
        if (treeType < 0) return;
        if (treeType == 0) treeType = rand.nextInt(10);
        
        com.voxel.biome.BiomeDecorator decorator = new com.voxel.biome.BiomeDecorator();
        
        if (treeType >= 0 && treeType <= 3) {
            decorator.placeOakTree(world, x, y, z, rand);
        } else if (treeType == 4 || treeType == 5) {
            decorator.placeBigOakTree(world, x, y, z, rand);
        } else if (treeType == 6) {
            decorator.placeBirchTree(world, x, y, z, rand);
        } else if (treeType == 7) {
            decorator.placeSpruceTree(world, x, y, z, rand);
        } else if (treeType == 8) {
            decorator.placeJungleTree(world, x, y, z, rand);
        } else if (treeType == 9) {
            if (biome instanceof com.voxel.biome.BiomeSavanna) {
                decorator.placeAcaciaTree(world, x, y, z, rand);
            } else {
                decorator.placeDarkOakTree(world, x, y, z, rand);
            }
        }
    }

    // Old tree methods removed - now using BiomeDecorator for all tree placement
}
