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

    private static final int WATER_LEVEL = 62;
    private static final int SPAWN_X = 1024;
    private static final int SPAWN_Z = 1024;
    private static final int DEEP_WATER_LEVEL = 55;

    public DimensionWorldGenerator(DimensionType dimension) {
        super(0);
        this.dimension = dimension;
        this.continentalNoise = new PerlinNoise(42);
        this.erosionNoise = new PerlinNoise(123);
        this.detailNoise = new PerlinNoise(456);
        this.tempNoise = new PerlinNoise(789);
        this.humNoise = new PerlinNoise(101112);
        this.treeNoise = new PerlinNoise(131415);
    }

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
        // Continental scale: large landmasses and oceans (scale 0.0015)
        float continental = continentalNoise.noise(x, z, 0.0015f) * 22.0f;

        // Erosion/ridge scale: hills and valleys (scale 0.008)
        float erosion = erosionNoise.noise(x, z, 0.008f) * 8.0f;

        // Detail scale: small bumps and surface variation (scale 0.035)
        float detail = detailNoise.noise(x, z, 0.035f) * 3.0f;

        // Base height
        float height = 58.0f + continental + erosion + detail;

        // Mountain amplification: steep, tall peaks where continental noise is high
        float mountainNoise = continentalNoise.noise(x, z, 0.0008f) * 1.5f - 0.3f;
        if (mountainNoise > 0) {
            height += mountainNoise * mountainNoise * 25.0f;
        }

        // Cliff/steepness variation
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

        // Carve out a water pool at spawn
        float dx = x - SPAWN_X;
        float dz = z - SPAWN_Z;
        float distSq = dx * dx + dz * dz;

        // Pool radius ~5 blocks, wide gentle sloping banks
        if (distSq < 25) {
            return WATER_LEVEL - 1; // Pool bottom (1 block deep)
        }
        // Wide gentle slope up from the pool (ring of blended terrain)
        if (distSq < 100) {
            float ringDist = (float) Math.sqrt(distSq);
            float blend = (ringDist - 5.0f) * 1.0f; // Rises 1 block per block of distance
            int poolEdgeHeight = WATER_LEVEL;
            return Math.max(poolEdgeHeight + (int) blend, rawHeight);
        }

        return rawHeight;
    }

    public int getBlockType(int x, int y, int z, int height) {
        switch (dimension) {
            case NETHER:
                return generateNether(x, y, z, height);
            case END:
                return generateEnd(x, y, z, height);
            case AETHER:
                return generateAether(x, y, z, height);
            default:
                return generateOverworld(x, y, z, height);
        }
    }

    private int generateOverworld(int x, int y, int z, int height) {
        if (y > height) {
            // Above ground: fill with water if below water level
            if (y <= WATER_LEVEL && height < WATER_LEVEL) {
                return 15; // Water
            }
            return 0; // Air
        }

        // At or below ground level
        int depth = height - y;

        // Surface layer
        if (depth == 0) {
            if (height < WATER_LEVEL) {
                return 14; // Sand on underwater surfaces
            }
            // Beach detection: sand within 3 blocks of water
            if (isNearWater(x, y, z)) {
                return 14; // Sand beach
            }
            return 1; // Grass
        }

        // Below surface
        if (depth <= 3) {
            if (height < WATER_LEVEL) {
                return 14; // Sand below water surface
            }
            return 13; // Dirt below grass
        }

        // Deep underground: stone
        return 2; // Stone
    }

    /**
     * Checks if a surface block is near water (within 3 blocks in any direction).
     * Uses the raw terrain height to detect nearby water bodies.
     */
    private boolean isNearWater(int x, int y, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                int neighborHeight = getRawHeight(x + dx, z + dz);
                if (neighborHeight < WATER_LEVEL) {
                    return true;
                }
            }
        }
        return false;
    }

    private int generateNether(int x, int y, int z, int height) {
        // Cave-like structure with ceiling/floor
        int floor = height - 12;
        int ceiling = height + 12;

        if (y < floor) return 2; // Stone floor
        if (y > ceiling) return 2; // Stone ceiling

        // Add some noise-based variation to the cave walls
        float caveNoise = (continentalNoise.noise(x * 0.1f, z * 0.1f, 0.05f) +
                          erosionNoise.noise(x * 0.1f, y * 0.1f, 0.05f)) * 0.5f;

        int caveFloor = floor + (int)(caveNoise * 4);
        int caveCeiling = ceiling + (int)(caveNoise * 4);

        if (y < caveFloor) return 2;
        if (y > caveCeiling) return 2;

        // Random pillars and stalactites/stalagmites
        int hash = (x * 31 + z * 73 + y * 137) & 0xFF;
        if (hash < 25) {
            if (y < floor + 8 && y > floor + 2) return 2; // Floor pillar
            if (y > ceiling - 8 && y < ceiling - 2) return 2; // Ceiling stalactite
        }

        // Lava pools at lower levels
        if (y < floor + 4 && (hash % 12) < 4) {
            return 15; // Lava (using water block ID)
        }

        // Nether quartz ore pockets
        if (hash < 8 && y > floor + 3 && y < ceiling - 3) {
            return 2; // Just stone for now
        }

        return 0; // Air (cave interior)
    }

    // Center island and void gap constants
    private static final int END_SPAWN_X = 1024;
    private static final int END_SPAWN_Z = 1024;
    private static final int END_CENTER_RADIUS = 55;
    private static final int END_VOID_RADIUS = 220;

    private int generateEnd(int x, int y, int z, int height) {
        float dx = x - END_SPAWN_X;
        float dz = z - END_SPAWN_Z;
        float distSq = dx * dx + dz * dz;
        float dist = (float) Math.sqrt(distSq);

        // --- Large center island ---
        if (dist < END_CENTER_RADIUS) {
            // Flat plateau with gentle undulation
            float undulation = continentalNoise.noise(x, z, 0.006f) * 3.0f;
            int surfaceY = height + (int)((END_CENTER_RADIUS - dist) * 0.15f) + (int)undulation;
            // Pin the center at exactly baseHeight for a flat main platform
            if (dist < 35) {
                surfaceY = height + (int)undulation;
            }

            if (y == surfaceY) return 2; // End stone surface
            if (y < surfaceY && y > surfaceY - 7) return 2; // End stone body
            return 0; // Air below island
        }

        // --- Void gap: no islands between center and outer ring ---
        if (dist < END_VOID_RADIUS) {
            // Sparse small floating rocks
            float rockNoise = continentalNoise.noise(x, z, 0.04f);
            if (rockNoise > 0.6f) {
                int rockY = height + (int)(rockNoise * 6.0f) - 3;
                if (y == rockY) return 2;
            }
            return 0;
        }

        // --- Outer periodic islands (same as before but with larger spacing) ---
        float islandNoise = continentalNoise.noise(x, z, 0.005f);
        float rawHeight = height + islandNoise * 12.0f;
        int islandCenterY = Math.round(rawHeight + (continentalNoise.noise(x, z, 0.02f)) * 4.0f);
        float erosion = Math.abs(erosionNoise.noise(x, z, 0.01f)) * 10.0f;
        float radius = 8.0f + erosion;

        // Periodically spaced islands on a 64-block grid
        int cx = x % 64; if (cx < 0) cx += 64;
        int cz = z % 64; if (cz < 0) cz += 64;
        cx -= 32; cz -= 32;
        float periodicDist = (float) Math.sqrt(cx * cx + cz * cz);

        if (periodicDist < radius) {
            int islandSurface = islandCenterY + Math.round((radius - periodicDist) * 0.3f);
            if (y == islandSurface) return 2;
            if (y < islandSurface && y > islandSurface - 5) return 2;
        }

        return 0;
    }

    private int generateAether(int x, int y, int z, int height) {
        // Dense floating islands with grass tops, similar pattern to End but more generous
        int cx = x % 32; if (cx < 0) cx += 32;
        int cz = z % 32; if (cz < 0) cz += 32;
        cx -= 16; cz -= 16;
        float periodicDist = (float) Math.sqrt(cx * cx + cz * cz);

        float islandNoise = continentalNoise.noise(x, z, 0.008f) * 6.0f;
        int islandCenterY = height + Math.round(islandNoise);

        float radius = 5.0f + Math.abs(erosionNoise.noise(x, z, 0.02f)) * 6.0f;

        if (periodicDist < radius) {
            int islandSurface = islandCenterY + Math.round((radius - periodicDist) * 0.2f);
            if (y == islandSurface) return 100; // Aether Grass
            if (y < islandSurface && y > islandSurface - 4) {
                return (y == islandSurface - 1) ? 102 : 101; // Aether Dirt then Holystone
            }
        }

        // Cloud wisps above islands (using Aerogel for a bright white cloud look)
        if (y > height + 10 && y < height + 16) {
            float cloudNoise = detailNoise.noise(x, y, 0.04f);
            if (cloudNoise > 0.4f) return 105; // Aerogel clouds
        }

        return 0;
    }

    /**
     * Decorates a chunk with trees and other features.
     * Called after the base terrain has been generated.
     */
    public void decorate(int cx, int cy, int cz, int slot, World world) {
        if (dimension != DimensionType.OVERWORLD) return;

        int worldX = cx << 4;
        int worldY = cy << 4;
        int worldZ = cz << 4;

        // Place trees on grass blocks
        for (int lx = 2; lx < 14; lx++) {
            for (int lz = 2; lz < 14; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;

                // Don't place trees too close to spawn
                int dx = wx - SPAWN_X;
                int dz = wz - SPAWN_Z;
                if (dx * dx + dz * dz < 100) continue;

                // Tree noise - about 2% chance per grass block
                float treeValue = treeNoise.noise(wx, wz, 0.1f);
                if (treeValue < 0.96f) continue;

                int height = getFinalHeight(wx, wz);
                if (height < WATER_LEVEL + 1) continue; // Only on land

                // Check surface is grass (block ID 1)
                int surfaceBlock = world.getVoxel(wx, height, wz);
                if (surfaceBlock != 1) continue;

                // Make sure there's enough vertical space
                // (Trees can span multiple chunk sections)
                if (cy == (height >> 4)) {
                    placeOakTree(world, wx, height + 1, wz);
                }
            }
        }
    }

    /**
     * Places a small oak tree at the given position.
     * Trunk is on the block above the surface.
     */
    private void placeOakTree(World world, int x, int y, int z) {
        int trunkHeight = 4 + (int)(Math.abs(continentalNoise.noise(x, z, 0.5f)) * 2.0f);

        // Place trunk (block type 5 = oak_log)
        for (int i = 0; i < trunkHeight; i++) {
            world.setVoxel(x, y + i, z, 5);
        }

        // Place leaves (block type 4 = oak_leaves)
        int leafBaseY = y + trunkHeight - 2;
        int leafTopY = y + trunkHeight;

        for (int ly = leafBaseY; ly <= leafTopY; ly++) {
            int radius = (ly == leafTopY) ? 1 : 2;
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    // Skip corners for a more natural look
                    if (Math.abs(lx) == radius && Math.abs(lz) == radius) {
                        if (treeNoise.noise(x + lx, z + lz, 0.3f) > 0.5f) continue;
                    }
                    // Don't replace trunk blocks
                    if (lx == 0 && lz == 0 && ly > y && ly < y + trunkHeight - 1) continue;
                    int existing = world.getVoxel(x + lx, ly, z + lz);
                    if (existing == 0 || existing == 13 || existing == 1) {
                        world.setVoxel(x + lx, ly, z + lz, 4);
                    }
                }
            }
        }
    }
}
