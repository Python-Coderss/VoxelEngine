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
        // Nether is a cave world: floor at y=1, ceiling at y=height+18
        int ceiling = height + 18;

        // Lava ocean at the bottom
        int lavaLevel = height - 8;
        if (y <= lavaLevel) {
            if (y < lavaLevel - 3) return 20; // Netherrack floor below lava
            return 21; // Lava
        }

        // Ceiling cap
        if (y >= ceiling) {
            return 20; // Netherrack ceiling
        }

        // Cave generation using 2D noise with y-height variation
        // Use different noise scales at different heights for organic caves
        float heightRatio = (float)(y - lavaLevel) / (float)(ceiling - lavaLevel);
        float scale = 0.025f + heightRatio * 0.02f;

        float caveNoise = continentalNoise.noise(x, z, scale)
                        + erosionNoise.noise(x * 0.5f, z * 0.5f, 0.015f) * 0.3f
                        + detailNoise.noise(x, z, 0.04f) * 0.15f;

        // Vary the carve threshold by height: wider near lava, tighter near ceiling
        float carveThreshold = -0.2f + heightRatio * 0.3f;
        // Add horizontal variation so caves meander
        float horizontalBias = (float)Math.sin(x * 0.05f + z * 0.07f) * 0.1f;
        carveThreshold += horizontalBias;

        if (caveNoise < carveThreshold) {
            // Solid block — determine which kind
            int hash = (x * 31 + z * 73 + y * 137) & 0xFF;

            // Glowstone clusters on ceiling
            if (y > ceiling - 4 && (hash % 20) < 3) {
                return 17; // Glowstone
            }

            // Nether quartz ore pockets in mid-height
            if (hash < 6 && heightRatio > 0.3f && heightRatio < 0.8f) {
                return 23; // Quartz ore
            }

            // Soul sand patches near lava
            if (heightRatio < 0.3f && (hash % 15) < 4) {
                return 22; // Soul sand
            }

            // Nether brick formations
            if (hash > 248 && heightRatio > 0.5f) {
                return 24; // Nether brick
            }

            return 20; // Netherrack
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
        // Floating islands on a 40-block grid with overlapping layers for denser terrain
        // Use two grid scales for more variety: a dense 40-block grid and a sparse 80-block grid
        boolean isIsland = false;
        int islandSurface = 0;
        int islandType = 0; // 0=regular, 1=large

        // --- Check three overlapping island grids ---
        for (int grid = 0; grid < 3; grid++) {
            int gridSize = (grid == 1) ? 80 : 40;
            int offset = (grid == 2) ? 16 : 0;
            int cx = (x + offset) % gridSize; if (cx < 0) cx += gridSize; cx -= gridSize / 2;
            int cz = (z + offset) % gridSize; if (cz < 0) cz += gridSize; cz -= gridSize / 2;
            float dist = (float) Math.sqrt(cx * cx + cz * cz);

            float islandNoise = continentalNoise.noise(x, z, 0.004f + grid * 0.001f) * 18.0f
                              + erosionNoise.noise(x, z, 0.008f + grid * 0.002f) * 6.0f;
            int centerY = height + Math.round(islandNoise);

            // Radius varies: small to medium (6-14 for 40-grid), large (12-24 for 80-grid)
            float radius = 6.0f + Math.abs(erosionNoise.noise(x, z, 0.012f + grid * 0.005f)) * (grid == 1 ? 18 : 8);

            if (dist < radius) {
                // Dome shape: parabolic profile for smooth rolling hills
                float domeFactor = 1.0f - (dist / radius);
                float domeHeight = domeFactor * domeFactor * (grid == 1 ? 6.0f : 4.0f);
                int surf = centerY + Math.round(domeHeight);

                // Surface ripple for organic feel
                float bumpNoise = detailNoise.noise(x, z, 0.05f) * 1.2f;
                surf += Math.round(bumpNoise);

                // Take the highest surface from any grid
                if (!isIsland || surf > islandSurface) {
                    islandSurface = surf;
                    islandType = grid;
                }
                isIsland = true;
            }
        }

        if (isIsland && y <= islandSurface) {
            if (y == islandSurface) return 100; // Aether Grass
            int depth = islandSurface - y;
            if (depth == 1) return 102; // Aether Dirt (topsoil)
            if (depth <= 5) return 101; // Holystone (main body)
            // Deeper: mix of holystone and ores
            int hash = (x * 31 + z * 73 + y * 137) & 0xFF;
            if (hash < 8 && depth > 5) return 107; // Ambrosium ore near surface
            if (hash < 3 && depth > 10) return 111; // Zanite ore deeper
            if (hash < 2 && depth > 15) return 108; // Gravitite ore deep
            // Quicksoil pockets on lower island edges
            if (hash > 248 && depth > 8) return 109; // Quicksoil
            return 101; // Holystone
        }

        // Floating rocks: tiny single-block or 3-block clusters near islands
        if (!isIsland && y >= height - 5 && y < height + 10) {
            float rockNoise = continentalNoise.noise(x, z, 0.06f) + detailNoise.noise(x, y * 0.1f, 0.08f) * 0.5f;
            if (rockNoise > 0.7f) return 101; // Floating holystone rocks
        }

        // Cloud wisps: billowy white clouds at multiple heights
        float cloudSeed1 = detailNoise.noise(x, z, 0.02f) * 0.7f + detailNoise.noise(x, y * 0.3f, 0.025f) * 0.3f;
        float cloudSeed2 = erosionNoise.noise(x, z, 0.015f);
        // Lower clouds (just above islands)
        if (y > height + 6 && y < height + 14 && cloudSeed1 > 0.4f) return 105;
        // Upper clouds (higher up, more scattered)
        if (y > height + 16 && y < height + 24 && cloudSeed2 > 0.55f) return 105;

        return 0;
    }

    /**
     * Places a 4-wide x 5-tall portal structure with frame and interior blocks.
     * The portal faces north-south (dx=1, dz=0 orientation).
     */
    private void placePortal(World world, int sx, int sy, int sz, int frameId, int portalId) {
        // Bottom frame
        for (int i = 0; i < 4; i++)
            world.setVoxel(sx + i, sy, sz, frameId);
        // Top frame
        for (int i = 0; i < 4; i++)
            world.setVoxel(sx + i, sy + 4, sz, frameId);
        // Left side
        for (int i = 1; i < 4; i++)
            world.setVoxel(sx, sy + i, sz, frameId);
        // Right side
        for (int i = 1; i < 4; i++)
            world.setVoxel(sx + 3, sy + i, sz, frameId);
        // Interior portal blocks (2x3)
        for (int px = 1; px <= 2; px++)
            for (int py = 1; py <= 3; py++)
                world.setVoxel(sx + px, sy + py, sz, portalId);
    }

    /**
     * Decorates a chunk with trees, portal structures, and other features.
     * Called after the base terrain has been generated.
     */
    public void decorate(int cx, int cy, int cz, int slot, World world) {
        // --- Auto-generate portal structures at spawn in every dimension ---
        // Spawn is at (1024, 1024), chunk (64, 64) covers x=1024-1039, z=1024-1039
        if (cx == 64 && cz == 64) {
            // Place portals at block coordinates entirely within this chunk
            if (dimension == DimensionType.OVERWORLD && cy == 4) {
                // Overworld spawn y=64: Nether portal (obsidian frame) leads to Nether
                placePortal(world, 1028, 64, 1030, 16, 19);
                // Aether portal (glowstone frame) leads to Aether
                placePortal(world, 1028, 64, 1036, 17, 106);
            } else if (dimension == DimensionType.NETHER && cy == 2) {
                // Nether spawn y=32: portal back to Overworld
                placePortal(world, 1028, 32, 1030, 16, 19);
                // Aether portal from Nether to Aether
                placePortal(world, 1028, 32, 1036, 17, 106);
            } else if (dimension == DimensionType.AETHER && cy == 6) {
                // Aether spawn y=96: portal back to Overworld
                placePortal(world, 1028, 96, 1030, 17, 106);
                // Nether portal from Aether to Nether
                placePortal(world, 1028, 96, 1036, 16, 19);
            }
        }

        // Aether decoration: trees and vegetation on islands
        if (dimension == DimensionType.AETHER) {
            int worldX = cx << 4;
            int worldZ = cz << 4;
            for (int lx = 2; lx < 14; lx++) {
                for (int lz = 2; lz < 14; lz++) {
                    int wx = worldX + lx;
                    int wz = worldZ + lz;
                    // Tree noise - about 1.5% chance per block
                    float treeValue = treeNoise.noise(wx, wz, 0.08f);
                    if (treeValue < 0.985f) continue;
                    // Find surface height by scanning from baseHeight upward
                    for (int yScan = cy << 4; yScan < (cy << 4) + 16; yScan++) {
                        int block = world.getVoxel(wx, yScan, wz);
                        if (block == 100) { // Aether Grass
                            placeSkyrootTree(world, wx, yScan + 1, wz);
                            break;
                        }
                    }
                }
            }
            return;
        }

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
     * Places a skyroot tree in the Aether dimension with blue-green leaves.
     * Taller and more elegant than oak trees with weeping foliage.
     */
    private void placeSkyrootTree(World world, int x, int y, int z) {
        int trunkHeight = 5 + (int)(Math.abs(continentalNoise.noise(x, z, 0.5f)) * 3.0f);
        // Place trunk (block type 103 = skyroot_log)
        for (int i = 0; i < trunkHeight; i++) {
            world.setVoxel(x, y + i, z, 103);
        }
        // Place leaves (block type 104 = skyroot_leaves)
        int leafBaseY = y + trunkHeight - 3;
        int leafTopY = y + trunkHeight;
        for (int ly = leafBaseY; ly <= leafTopY; ly++) {
            int radius = (ly == leafTopY) ? 1 : (ly == leafBaseY ? 3 : 2);
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    if (Math.abs(lx) == radius && Math.abs(lz) == radius) {
                        if (treeNoise.noise(x + lx, z + lz, 0.3f) > 0.5f) continue;
                    }
                    if (lx == 0 && lz == 0 && ly > y && ly < y + trunkHeight - 1) continue;
                    int existing = world.getVoxel(x + lx, ly, z + lz);
                    if (existing == 0 || existing == 100 || existing == 101 || existing == 102) {
                        world.setVoxel(x + lx, ly, z + lz, 104);
                    }
                }
            }
        }
        // Weeping vines: hanging leaves below canopy
        if (continentalNoise.noise(x, z, 0.2f) > 0.0f) {
            int vineLen = 1 + (int)(Math.abs(continentalNoise.noise(x, z, 0.3f)) * 3.0f);
            for (int v = 0; v < vineLen; v++) {
                world.setVoxel(x + 1, leafBaseY - v, z, 104);
                world.setVoxel(x - 1, leafBaseY - v, z, 104);
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
