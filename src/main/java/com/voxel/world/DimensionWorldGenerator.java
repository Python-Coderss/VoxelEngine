package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.PerlinNoise;

/**
 * Procedural world generators for each dimension.
 * Overworld uses multi-octave Perlin noise for realistic Minecraft-style terrain.
 * Other dimensions have unique terrain generation logic.
 */
public class DimensionWorldGenerator extends WorldGenerator {

    private final DimensionType dimension;
    private final PerlinNoise continentalNoise;
    private final PerlinNoise erosionNoise;
    private final PerlinNoise detailNoise;
    private final PerlinNoise tempNoise;
    private final PerlinNoise humNoise;
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

    private static final int WATER_LEVEL = 62;
    private static final int SPAWN_X = 1024;
    private static final int SPAWN_Z = 1024;

    public DimensionWorldGenerator(DimensionType dimension, com.voxel.utils.BlockDataManager blockDataManager) {
        super(0, blockDataManager);
        this.dimension = dimension;
        this.continentalNoise = new PerlinNoise(42);
        this.erosionNoise = new PerlinNoise(123);
        this.detailNoise = new PerlinNoise(456);
        this.tempNoise = new PerlinNoise(789);
        this.humNoise = new PerlinNoise(101112);
        this.treeNoise = new PerlinNoise(131415);

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
    }

    @Override
    public int getHeight(int x, int z) {
        if (dimension != DimensionType.OVERWORLD) {
            return dimension.baseHeight;
        }
        return getFinalHeight(x, z);
    }

    /**
     * Gets the raw terrain height at (x,z) BEFORE the spawn pool carving.
     */
    private int getRawHeight(int x, int z) {
        float continental = continentalNoise.noise(x, z, 0.0015f) * 22.0f;
        float erosion = erosionNoise.noise(x, z, 0.008f) * 8.0f;
        float detail = detailNoise.noise(x, z, 0.035f) * 3.0f;
        float height = 58.0f + continental + erosion + detail;

        float mountainNoise = continentalNoise.noise(x, z, 0.0008f) * 1.5f - 0.3f;
        if (mountainNoise > 0) {
            height += mountainNoise * mountainNoise * 25.0f;
        }

        float steepNoise = Math.abs(erosionNoise.noise(x, z, 0.015f)) * 6.0f;
        if (erosionNoise.noise(x, z, 0.01f) > 0.4f) {
            height += steepNoise;
        }

        return Math.round(Math.max(1, height));
    }

    /**
     * Final height including spawn pool carving.
     */
    private int getFinalHeight(int x, int z) {
        int rawHeight = getRawHeight(x, z);
        float dx = x - SPAWN_X;
        float dz = z - SPAWN_Z;
        float distSq = dx * dx + dz * dz;

        if (distSq < 25) {
            return WATER_LEVEL - 1;
        }
        if (distSq < 100) {
            float ringDist = (float) Math.sqrt(distSq);
            float blend = (ringDist - 5.0f) * 1.0f;
            int poolEdgeHeight = WATER_LEVEL;
            return Math.max(poolEdgeHeight + (int) blend, rawHeight);
        }

        return rawHeight;
    }

    @Override
    public int getBlockType(int x, int y, int z, int height) {
        switch (dimension) {
            case NETHER:
                return generateNether(x, y, z, height);
            case END:
                return generateEnd(x, y, z, height);
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

        int depth = height - y;
        if (depth == 0) {
            if (height < WATER_LEVEL) return sandId;
            if (isNearWater(x, y, z)) return sandId;
            return grassId;
        }
        if (depth <= 3) {
            if (height < WATER_LEVEL) return sandId;
            return dirtId;
        }
        return stoneId;
    }

    private boolean isNearWater(int x, int y, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                int neighborHeight = getRawHeight(x + dx, z + dz);
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
            // Platform spans x=[100,104], z=[0,4], y=47 (chunk cy=2 covers y=32..47)
            if (cx == 6 && cz == 0) {
                int platformY = 47;
                int localY = platformY - (cy << 4); // 47 - 32 = 15
                int lxStart = 100 - (cx << 4);      // 100 - 96 = 4
                int lzStart = 0;                     // 0 - 0 = 0
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

        for (int lx = 2; lx < 14; lx++) {
            for (int lz = 2; lz < 14; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;
                int dx = wx - SPAWN_X;
                int dz = wz - SPAWN_Z;
                if (dx * dx + dz * dz < 100) continue;

                int height = getFinalHeight(wx, wz);
                if (height < WATER_LEVEL + 1) continue;
                int surfaceBlock = world.getVoxel(wx, height, wz);
                if (surfaceBlock != grassId) continue;
                if (cy != (height >> 4)) continue;

                float treeValue = treeNoise.noise(wx, wz, 0.1f);
                if (treeValue >= 0.96f) {
                    float treeType = treeNoise.noise(wx + 1000, wz + 2000, 0.15f);
                    if (treeType < 0.0f) placeBirchTree(world, wx, height + 1, wz);
                    else if (treeType < 0.5f) placeOakTree(world, wx, height + 1, wz);
                    else placeSpruceTree(world, wx, height + 1, wz);
                }

                float flowerValue = treeNoise.noise(wx + 500, wz + 500, 0.15f);
                if (flowerValue > 0.85f) {
                    int aboveBlock = world.getVoxel(wx, height + 1, wz);
                    if (aboveBlock == 0) {
                        float flowerType = treeNoise.noise(wx + 700, wz + 900, 0.2f);
                        if (flowerType < 0.2f) world.setVoxel(wx, height + 1, wz, dandelionId);
                        else if (flowerType < 0.6f) world.setVoxel(wx, height + 1, wz, poppyId);
                        else world.setVoxel(wx, height + 1, wz, tallGrassId);
                    }
                }
            }
        }
    }

    private void placeOakTree(World world, int x, int y, int z) {
        int trunkHeight = 4 + (int)(Math.abs(continentalNoise.noise(x, z, 0.5f)) * 2.0f);
        for (int i = 0; i < trunkHeight; i++) world.setVoxel(x, y + i, z, oakLogId);
        int leafBaseY = y + trunkHeight - 2;
        int leafTopY = y + trunkHeight;
        for (int ly = leafBaseY; ly <= leafTopY; ly++) {
            int radius = (ly == leafTopY) ? 1 : 2;
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    if (Math.abs(lx) == radius && Math.abs(lz) == radius) {
                        if (treeNoise.noise(x + lx, z + lz, 0.3f) > 0.5f) continue;
                    }
                    if (lx == 0 && lz == 0 && ly > y && ly < y + trunkHeight - 1) continue;
                    int existing = world.getVoxel(x + lx, ly, z + lz);
                    if (existing == 0 || existing == tallGrassId || existing == grassId) world.setVoxel(x + lx, ly, z + lz, oakLeavesId);
                }
            }
        }
    }

    private void placeBirchTree(World world, int x, int y, int z) {
        int trunkHeight = 5 + (int)(Math.abs(continentalNoise.noise(x + 50, z + 100, 0.5f)) * 2.0f);
        for (int i = 0; i < trunkHeight; i++) world.setVoxel(x, y + i, z, birchLogId);
        int leafBaseY = y + trunkHeight - 3;
        int leafTopY = y + trunkHeight;
        for (int ly = leafBaseY; ly <= leafTopY; ly++) {
            int radius = (ly == leafTopY) ? 1 : 2;
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    int distSq = lx * lx + lz * lz;
                    if (distSq > radius * radius) continue;
                    if (lx == 0 && lz == 0 && ly > y && ly < y + trunkHeight - 1) continue;
                    int existing = world.getVoxel(x + lx, ly, z + lz);
                    if (existing == 0 || existing == tallGrassId || existing == grassId) world.setVoxel(x + lx, ly, z + lz, oakLeavesId);
                }
            }
        }
    }

    private void placeSpruceTree(World world, int x, int y, int z) {
        int trunkHeight = 6 + (int)(Math.abs(continentalNoise.noise(x + 200, z + 300, 0.4f)) * 3.0f);
        for (int i = 0; i < trunkHeight; i++) world.setVoxel(x, y + i, z, spruceLogId);
        int[][] layers = {{trunkHeight - 1, 1}, {trunkHeight - 2, 1}, {trunkHeight - 3, 2}, {trunkHeight - 4, 2}, {trunkHeight - 5, 3}, {trunkHeight - 6, 3}};
        for (int[] layer : layers) {
            int ly = y + layer[0];
            int radius = layer[1];
            if (ly < y) continue;
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    int distSq = lx * lx + lz * lz;
                    if (distSq > radius * radius) continue;
                    int existing = world.getVoxel(x + lx, ly, z + lz);
                    if (existing == 0 || existing == tallGrassId || existing == grassId) world.setVoxel(x + lx, ly, z + lz, oakLeavesId);
                }
            }
        }
    }
}
