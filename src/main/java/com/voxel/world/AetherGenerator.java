package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.PerlinNoise;
import java.util.Random;

/**
 * Procedural generator for the Aether dimension.
 * Ported from the Aether mod's skylands.json and various features (Aercloud, Shelf, Lake).
 */
public class AetherGenerator extends WorldGenerator {

    private final PerlinNoise continentalNoise;
    private final PerlinNoise erosionNoise;
    private final PerlinNoise detailNoise;
    private final PerlinNoise treeNoise;

    private final int grassId;
    private final int holystoneId;
    private final int dirtId;
    private final int skyrootLogId;
    private final int skyrootLeavesId;
    private final int aerogelId;
    private final int portalId;
    private final int ambrosiumId;
    private final int zaniteId;
    private final int gravititeId;
    private final int quicksoilId;
    private final int icestoneId;
    private final int mossyHolystoneId;
    private final int blueCloudId;
    private final int coldCloudId;
    private final int goldenCloudId;
    private final int aetherFluidId;

    public AetherGenerator(long seed, com.voxel.utils.BlockDataManager blockDataManager) {
        super(seed, blockDataManager);
        this.continentalNoise = new PerlinNoise(seed + 42);
        this.erosionNoise = new PerlinNoise(seed + 123);
        this.detailNoise = new PerlinNoise(seed + 456);
        this.treeNoise = new PerlinNoise(seed + 131415);

        this.grassId = blockDataManager.findBlockId("aether_grass_block");
        this.holystoneId = blockDataManager.findBlockId("holystone");
        this.dirtId = blockDataManager.findBlockId("aether_dirt");
        this.skyrootLogId = blockDataManager.findBlockId("skyroot_log");
        this.skyrootLeavesId = blockDataManager.findBlockId("skyroot_leaves");
        this.aerogelId = blockDataManager.findBlockId("aerogel");
        this.portalId = blockDataManager.findBlockId("aether_portal_ns");
        this.ambrosiumId = blockDataManager.findBlockId("ambrosium_ore");
        this.zaniteId = blockDataManager.findBlockId("zanite_ore");
        this.gravititeId = blockDataManager.findBlockId("gravitite_ore");
        this.quicksoilId = blockDataManager.findBlockId("quicksoil");
        this.icestoneId = blockDataManager.findBlockId("icestone");
        this.mossyHolystoneId = blockDataManager.findBlockId("mossy_holystone");
        this.blueCloudId = blockDataManager.findBlockId("blue_aercloud");
        this.coldCloudId = blockDataManager.findBlockId("cold_aercloud");
        this.goldenCloudId = blockDataManager.findBlockId("golden_aercloud");
        this.aetherFluidId = blockDataManager.findBlockId("water");
    }

    @Override
    public int getHeight(int x, int z) {
        return 128;
    }

    @Override
    public int getBlockType(int x, int y, int z, int height) {
        if (y < 0 || y > 128) return 0;

        float density = computeAetherDensity(x, y, z);

        if (density <= 0) {
            // Cloud generation in air gaps between terrain bands (y=40-120)
            if (y >= 40 && y <= 120) {
                float cloudNoise = detailNoise.noise(x, z, 0.03f)
                        + continentalNoise.noise(x, z, 0.02f) * 0.6f
                        + erosionNoise.noise(x, y * 0.12f, 0.025f) * 0.4f;
                // Denser cloud layers at specific height bands
                if (y >= 40 && y < 60 && cloudNoise > 0.52f) return coldCloudId;
                if (y >= 60 && y < 80 && cloudNoise > 0.45f) return coldCloudId;
                if (y >= 80 && y < 100 && cloudNoise > 0.40f) return coldCloudId;
                if (y >= 100 && y <= 120 && cloudNoise > 0.50f) return coldCloudId;
            }
            return 0;
        }

        // Solid block: check density above for surface detection
        float above = computeAetherDensity(x, y + 1, z);

        // Surface layer: block above is air
        if (above <= 0) {
            // Occasional icestone crust patches
            if (detailNoise.noise(x, z, 0.07f) > 0.62f) return icestoneId;
            // Mossy holystone patches
            float mossNoise = detailNoise.noise(x, z, 0.03f) + erosionNoise.noise(x, z, 0.025f);
            if (mossNoise > 0.55f) return mossyHolystoneId;
            return grassId;
        }

        // Dirt layer: ~5 blocks below surface
        float above5 = computeAetherDensity(x, y + 5, z);
        if (above5 <= 0) return dirtId;

        // Stone body with ores
        int hash = (x * 31 + z * 73 + y * 137) & 0xFF;

        // Ambrosium ore: y=16-32
        if (y >= 16 && y <= 32 && hash < 8) return ambrosiumId;
        // Zanite ore: y=32-48
        if (y > 32 && y <= 48 && hash < 5) return zaniteId;
        // Gravitite ore: y>48
        if (y > 48 && hash < 3) return gravititeId;

        return holystoneId;
    }

    /**
     * Computes 3D density field matching the Aether mod's skylands.json noise formula.
     */
    private float computeAetherDensity(int x, int y, int z) {
        if (y < 0 || y > 128) return -1.0f;

        // Bottom slide: 0 at y=8, reaches 1 at y=40
        float bottomSlide = Math.min(1.0f, Math.max(0.0f, (y - 8.0f) / 32.0f));
        // Top slide: 1 at y=56, reaches 0 at y=128
        float topSlide = Math.min(1.0f, Math.max(0.0f, (128.0f - y) / 72.0f));

        // 3D noise - Reduced scale for larger, more cohesive islands (from 0.25 to 0.035)
        float noise = continentalNoise.noise3D(x, y, z, 0.035f);
        
        // Add some connectivity using 2D noise
        float connectivity = continentalNoise.noise(x, z, 0.015f) * 0.2f;

        // Density formula: bottomSlide * (topSlide * (noise + 0.07) - 0.1) - 0.15
        float density = bottomSlide * (topSlide * (noise + 0.07f + connectivity) - 0.1f) - 0.15f;

        // Squeeze function
        density = density / (1.0f + Math.abs(density));

        // Spawn area flattening (1024, 1024)
        float dxSpawn = x - 1024.0f;
        float dzSpawn = z - 1024.0f;
        float distSq = dxSpawn * dxSpawn + dzSpawn * dzSpawn;
        float flatRadius = 18.0f;
        float blendRadius = 28.0f;

        if (distSq < blendRadius * blendRadius) {
            float dist = (float) Math.sqrt(distSq);
            float blend = (dist < flatRadius) ? 1.0f : 1.0f - ((dist - flatRadius) / (blendRadius - flatRadius));
            float targetDensity = (y >= 76 && y <= 95) ? 1.0f : (y >= 96 ? -1.0f : density);
            density = density * (1.0f - blend) + targetDensity * blend;
        }

        return density;
    }

    @Override
    public void decorate(int cx, int cy, int cz, int slot, World world) {
        int worldX = cx << 4;
        int worldZ = cz << 4;

        // --- Spawn Portals ---
        if (cx == 64 && cz == 64 && cy == 6) {
            placePortal(world, 1028, 96, 1030, 17, portalId); // Back to Overworld (Glowstone frame)
            placePortal(world, 1028, 96, 1036, 16, 19);       // To Nether (Obsidian frame)
        }

        // --- Quicksoil Shelves ---
        int sectionMinY = cy << 4;
        int sectionMaxY = (cy << 4) + 16;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;
                for (int yScan = sectionMinY; yScan < sectionMaxY; yScan++) {
                    int blockHere = world.getVoxel(wx, yScan, wz);
                    int blockAbove = world.getVoxel(wx, yScan + 1, wz);
                    int blockAbove2 = world.getVoxel(wx, yScan + 2, wz);
                    boolean validAbove = blockAbove == grassId || blockAbove == holystoneId || blockAbove == dirtId
                            || blockAbove == icestoneId || blockAbove == mossyHolystoneId;
                    if (blockHere == 0 && validAbove && blockAbove2 == 0) {
                        int radius = 2 + ((wx * 31 + wz * 73 + yScan * 137) & 3);
                        placeQuicksoilDisk(world, wx, yScan, wz, radius);
                        break;
                    }
                }
            }
        }

        // --- Aerclouds ---
        long chunkSeed = ((long) cx * 73856093L) ^ ((long) cz * 19349663L);
        Random aercloudRng = new Random(chunkSeed);

        // Cold aerclouds (White)
        if (cy >= 2 && cy <= 6 && aercloudRng.nextInt(10) == 0) {
            generateAercloud(world, worldX, worldZ, cy, aercloudRng, coldCloudId, 12);
        }

        // Blue aerclouds (Bouncy) - Increased chance
        aercloudRng = new Random(chunkSeed ^ 0x5A5A5A5AL);
        if (cy >= 2 && cy <= 6 && aercloudRng.nextInt(16) == 0) {
            generateAercloud(world, worldX, worldZ, cy, aercloudRng, blueCloudId, 6);
        }

        // Golden aerclouds (Fast) - Added
        aercloudRng = new Random(chunkSeed ^ 0x99999999L);
        if (cy >= 5 && cy <= 7 && aercloudRng.nextInt(20) == 0) {
            generateAercloud(world, worldX, worldZ, cy, aercloudRng, goldenCloudId, 4);
        }

        // --- Lakes (AetherLakeFeature) ---
        aercloudRng = new Random(chunkSeed ^ 0x12345678L);
        if (cy >= 3 && cy <= 5 && aercloudRng.nextInt(15) == 0) {
            int lx = aercloudRng.nextInt(16);
            int lz = aercloudRng.nextInt(16);
            int ly = (cy << 4) + aercloudRng.nextInt(16);
            generateLake(world, worldX + lx, ly, worldZ + lz, aercloudRng);
        }

        // --- Trees and vegetation ---
        for (int lx = 2; lx < 14; lx++) {
            for (int lz = 2; lz < 14; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;
                float treeValue = treeNoise.noise(wx, wz, 0.08f);
                if (treeValue < 0.985f) continue;
                for (int yScan = cy << 4; yScan < (cy << 4) + 16; yScan++) {
                    int block = world.getVoxel(wx, yScan, wz);
                    if (block == grassId || block == mossyHolystoneId) {
                        float goldenChance = continentalNoise.noise(wx, wz, 0.035f);
                        boolean preferGolden = block == mossyHolystoneId && goldenChance > 0.15f;
                        if (goldenChance > 0.4f || preferGolden) {
                            placeGoldenOakTree(world, wx, yScan + 1, wz);
                        } else {
                            placeSkyrootTree(world, wx, yScan + 1, wz);
                        }
                        break;
                    }
                }
            }
        }
    }

    private void generateAercloud(World world, int worldX, int worldZ, int cy, Random rng, int blockId, int bounds) {
        // Reduced cloudCount and blob size for smaller clouds
        int cloudCount = 4 + rng.nextInt(bounds / 2 + 1);
        boolean direction = rng.nextBoolean();
        int bx = worldX + rng.nextInt(16);
        int bz = worldZ + rng.nextInt(16);
        int by = (cy << 4) + rng.nextInt(16);
        for (int a = 0; a < cloudCount; a++) {
            int xo = rng.nextInt(2);
            int yo = rng.nextBoolean() ? rng.nextInt(2) - 1 : 0; // Reduced vertical spread
            int zo = rng.nextInt(2);
            if (direction) bz -= zo; else bz += zo;
            bx += xo;
            by += yo;
            // Smaller blobs
            int rx = rng.nextInt(2) + 2;
            int ry = rng.nextInt(1) + 1;
            int rz = rng.nextInt(2) + 2;
            for (int px = bx; px < bx + rx; px++) {
                for (int py = by; py < by + ry; py++) {
                    for (int pz = bz; pz < bz + rz; pz++) {
                        if (py < 0 || py > 128) continue;
                        if (world.getVoxel(px, py, pz) == 0) {
                            int manhattan = Math.abs(px - bx) + Math.abs(py - by) + Math.abs(pz - bz);
                            if (manhattan < 3 + rng.nextInt(2)) {
                                world.setVoxel(px, py, pz, blockId);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Ported from AetherLakeFeature - generates a lake using stacked ellipsoids.
     */
    private void generateLake(World world, int x, int y, int z, Random random) {
        if (y <= 4) return;
        
        boolean[] booleans = new boolean[2048];
        int ellipsoidCount = random.nextInt(4) + 4;

        for (int j = 0; j < ellipsoidCount; ++j) {
            double d0 = random.nextDouble() * 6.0 + 3.0;
            double d1 = random.nextDouble() * 4.0 + 2.0;
            double d2 = random.nextDouble() * 6.0 + 3.0;
            double d3 = random.nextDouble() * (16.0 - d0 - 2.0) + 1.0 + d0 / 2.0;
            double d4 = random.nextDouble() * (8.0 - d1 - 4.0) + 2.0 + d1 / 2.0;
            double d5 = random.nextDouble() * (16.0 - d2 - 2.0) + 1.0 + d2 / 2.0;

            for (int l = 1; l < 15; ++l) {
                for (int i1 = 1; i1 < 15; ++i1) {
                    for (int j1 = 1; j1 < 7; ++j1) {
                        double d6 = ((double) l - d3) / (d0 / 2.0);
                        double d7 = ((double) j1 - d4) / (d1 / 2.0);
                        double d8 = ((double) i1 - d5) / (d2 / 2.0);
                        double d9 = d6 * d6 + d7 * d7 + d8 * d8;
                        if (d9 < 1.0) {
                            booleans[(l * 16 + i1) * 8 + j1] = true;
                        }
                    }
                }
            }
        }

        // Validate placement - check boundary is valid before carving (matches AetherLakeFeature)
        for (int k1 = 0; k1 < 16; ++k1) {
            for (int k = 0; k < 16; ++k) {
                for (int l2 = 0; l2 < 8; ++l2) {
                    boolean flag = !booleans[(k1 * 16 + k) * 8 + l2] && 
                                   (k1 < 15 && booleans[((k1 + 1) * 16 + k) * 8 + l2] ||
                                    k1 > 0 && booleans[((k1 - 1) * 16 + k) * 8 + l2] ||
                                    k < 15 && booleans[(k1 * 16 + k + 1) * 8 + l2] ||
                                    k > 0 && booleans[(k1 * 16 + (k - 1)) * 8 + l2] ||
                                    l2 < 7 && booleans[(k1 * 16 + k) * 8 + l2 + 1] ||
                                    l2 > 0 && booleans[(k1 * 16 + k) * 8 + (l2 - 1)]);
                    if (flag) {
                        int wx = x + k1;
                        int wy = y - 4 + l2;
                        int wz = z + k;
                        int blockAtPos = world.getVoxel(wx, wy, wz);
                        // Aether checks: boundary liquid at water level OR non-solid below water level means invalid
                        if (l2 >= 4 && isLiquid(blockAtPos)) {
                            return; // Already has liquid above water level - invalid
                        }
                        if (l2 < 4 && !isSolid(blockAtPos) && blockAtPos != aetherFluidId) {
                            return; // Not solid below water level - invalid
                        }
                    }
                }
            }
        }

        // Carve the lake - directly set blocks like Aether does
        for (int l1 = 0; l1 < 16; ++l1) {
            for (int i2 = 0; i2 < 16; ++i2) {
                for (int i3 = 0; i3 < 8; ++i3) {
                    if (booleans[(l1 * 16 + i2) * 8 + i3]) {
                        int wx = x + l1;
                        int wy = y - 4 + i3;
                        int wz = z + i2;
                        if (wy < 0 || wy > 128) continue;
                        
                        boolean isAir = i3 >= 4;
                        if (isAir) {
                            world.setVoxel(wx, wy, wz, 0); // Air above water level
                        } else {
                            world.setVoxel(wx, wy, wz, aetherFluidId); // Aether fluid
                        }
                    }
                }
            }
        }

        // Place top block (holystone) on sky-exposed dirt below the lake air cavity
        for (int i2 = 0; i2 < 16; ++i2) {
            for (int j3 = 0; j3 < 16; ++j3) {
                for (int j4 = 4; j4 < 8; ++j4) {
                    if (booleans[(i2 * 16 + j3) * 8 + j4]) {
                        int wx = x + i2;
                        int wy = y - 4 + j4 - 1;
                        int wz = z + j3;
                        if (wy < 0 || wy > 128) continue;
                        
                        int blockBelow = world.getVoxel(wx, wy, wz);
                        if (isDirt(blockBelow)) {
                            // Check sky exposure: block above must be air and have sky light
                            int blockAbove = world.getVoxel(wx, wy + 1, wz);
                            if (blockAbove == 0 && hasSkyLight(world, wx, wy + 1, wz)) {
                                world.setVoxel(wx, wy, wz, holystoneId);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isLiquid(int blockId) {
        // Check if block is a liquid (water or aether fluid)
        return blockId == 15 || blockId == aetherFluidId;
    }

    private boolean isSolid(int blockId) {
        // Solid blocks are anything that isn't air or liquid
        return blockId != 0 && blockId != 15 && blockId != aetherFluidId;
    }

    private boolean isDirt(int blockId) {
        return blockId == dirtId || blockId == grassId;
    }

    private boolean hasSkyLight(World world, int wx, int wy, int wz) {
        // Simple sky light check - if block above is air and y is high enough, assume sky light
        if (wy >= 120) return true; // Above cloud layer = full sky light
        // Check if there's a clear path to sky (simplified)
        for (int checkY = wy; checkY <= 128; checkY++) {
            int blockAtY = world.getVoxel(wx, checkY, wz);
            if (blockAtY != 0 && blockAtY != coldCloudId && blockAtY != blueCloudId && blockAtY != goldenCloudId) {
                return false;
            }
        }
        return true;
    }

    private void placeQuicksoilDisk(World world, int wx, int wy, int wz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    if (world.getVoxel(wx + dx, wy, wz + dz) == 0) {
                        world.setVoxel(wx + dx, wy, wz + dz, quicksoilId);
                    }
                }
            }
        }
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

    private void placeGoldenOakTree(World world, int x, int y, int z) {
        int trunkHeight = 4 + (int)(Math.abs(continentalNoise.noise(x, z, 0.45f)) * 2.0f);
        for (int i = 0; i < trunkHeight; i++) world.setVoxel(x, y + i, z, skyrootLogId);
        int leafBaseY = y + trunkHeight - 2;
        int leafTopY = y + trunkHeight + 1;
        for (int ly = leafBaseY; ly <= leafTopY; ly++) {
            int offset = ly - leafBaseY;
            int radius = (offset == 0) ? 3 : (offset <= 2 ? 4 : 2);
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    if (lx * lx + lz * lz > radius * radius) continue;
                    if (lx == 0 && lz == 0 && ly > y && ly < y + trunkHeight - 1) continue;
                    int existing = world.getVoxel(x + lx, ly, z + lz);
                    if (existing == 0 || existing == grassId || existing == holystoneId || existing == dirtId) {
                        world.setVoxel(x + lx, ly, z + lz, skyrootLeavesId);
                    }
                }
            }
        }
    }

    private void placeSkyrootTree(World world, int x, int y, int z) {
        int trunkHeight = 5 + (int)(Math.abs(continentalNoise.noise(x, z, 0.5f)) * 3.0f);
        for (int i = 0; i < trunkHeight; i++) world.setVoxel(x, y + i, z, skyrootLogId);
        int leafBaseY = y + trunkHeight - 3;
        int leafTopY = y + trunkHeight;
        for (int ly = leafBaseY; ly <= leafTopY; ly++) {
            int radius = (ly == leafTopY) ? 1 : (ly == leafBaseY ? 3 : 2);
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    if (lx == 0 && lz == 0 && ly > y && ly < y + trunkHeight - 1) continue;
                    int existing = world.getVoxel(x + lx, ly, z + lz);
                    if (existing == 0 || existing == grassId || existing == holystoneId || existing == dirtId) {
                        world.setVoxel(x + lx, ly, z + lz, skyrootLeavesId);
                    }
                }
            }
        }
    }
}
