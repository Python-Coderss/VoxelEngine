package com.voxel.biome;

/**
 * Handles decoration of chunks with biome-specific features.
 * Ported from Minecraft 1.12.2 BiomeDecorator.
 * Places trees, flowers, grass, ores, and other decorations per chunk.
 */
public class BiomeDecorator {

    private Biome biome;
    private int chunkX, chunkZ;

    public void decorate(Biome biome, int cx, int cz, com.voxel.World world, java.util.Random rand) {
        this.biome = biome;
        this.chunkX = cx;
        this.chunkZ = cz;

        int worldX = cx << 4;
        int worldZ = cz << 4;

        // Sand patches
        for (int i = 0; i < biome.sandPatchesPerChunk; i++) {
            int x = worldX + rand.nextInt(16) + 8;
            int z = worldZ + rand.nextInt(16) + 8;
            int y = getSurfaceHeight(world, x, z);
            if (y > 0) placeSandPatch(world, x, y, z, rand);
        }

        // Gravel patches
        for (int i = 0; i < biome.gravelPatchesPerChunk; i++) {
            int x = worldX + rand.nextInt(16) + 8;
            int z = worldZ + rand.nextInt(16) + 8;
            int y = getSurfaceHeight(world, x, z);
            if (y > 0) placeGravelPatch(world, x, y, z, rand);
        }

        // Clay patches
        for (int i = 0; i < biome.clayPerChunk; i++) {
            int x = worldX + rand.nextInt(16) + 8;
            int z = worldZ + rand.nextInt(16) + 8;
            int y = getSurfaceHeight(world, x, z);
            if (y > 0) placeClayPatch(world, x, y, z, rand);
        }

        // Trees
        int treeCount = biome.treesPerChunk;
        if (treeCount > 0) {
            for (int i = 0; i < treeCount; i++) {
                int x = worldX + rand.nextInt(16) + 8;
                int z = worldZ + rand.nextInt(16) + 8;
                int y = getSurfaceHeight(world, x, z);
                if (y > 0) placeTree(biome, world, x, y + 1, z, rand);
            }
        }

        // Flowers
        for (int i = 0; i < biome.flowersPerChunk; i++) {
            int x = worldX + rand.nextInt(16);
            int z = worldZ + rand.nextInt(16);
            int y = getSurfaceHeight(world, x, z);
            if (y > 0 && y < 128) {
                int blockBelow = world.getVoxel(x, y, z);
                if (blockBelow == biome.topBlockId || blockBelow == 1) { // on grass
                    int flowerId = biome.pickRandomFlower(rand);
                    if (flowerId > 0 && world.getVoxel(x, y + 1, z) == 0) {
                        world.setVoxel(x, y + 1, z, flowerId);
                    }
                }
            }
        }

        // Grass
        for (int i = 0; i < biome.grassPerChunk; i++) {
            int x = worldX + rand.nextInt(16);
            int z = worldZ + rand.nextInt(16);
            int y = getSurfaceHeight(world, x, z);
            if (y > 0 && y < 128) {
                int blockBelow = world.getVoxel(x, y, z);
                if (blockBelow == biome.topBlockId || blockBelow == 1) {
                    if (world.getVoxel(x, y + 1, z) == 0) {
                        world.setVoxel(x, y + 1, z, 35); // tallgrass
                    }
                }
            }
        }

        // Dead bushes (desert, mesa)
        for (int i = 0; i < biome.deadBushPerChunk; i++) {
            int x = worldX + rand.nextInt(16);
            int z = worldZ + rand.nextInt(16);
            int y = getSurfaceHeight(world, x, z);
            if (y > 0 && y < 128) {
                if (world.getVoxel(x, y + 1, z) == 0) {
                    world.setVoxel(x, y + 1, z, 36); // dead bush
                }
            }
        }

        // Mushrooms
        for (int i = 0; i < biome.mushroomsPerChunk; i++) {
            int x = worldX + rand.nextInt(16);
            int z = worldZ + rand.nextInt(16);
            int y = getSurfaceHeight(world, x, z);
            if (y > 0 && y < 128) {
                if (world.getVoxel(x, y + 1, z) == 0) {
                    int mushroomId = rand.nextBoolean() ? 37 : 38; // brown or red
                    world.setVoxel(x, y + 1, z, mushroomId);
                }
            }
        }

        // Cacti (desert, mesa)
        for (int i = 0; i < biome.cactiPerChunk; i++) {
            int x = worldX + rand.nextInt(16);
            int z = worldZ + rand.nextInt(16);
            int y = getSurfaceHeight(world, x, z);
            if (y > 0 && y < 128) {
                int blockBelow = world.getVoxel(x, y, z);
                if (blockBelow == 14 && world.getVoxel(x, y + 1, z) == 0) { // on sand
                    int height = 1 + rand.nextInt(2);
                    for (int h = 0; h < height; h++) {
                        world.setVoxel(x, y + 1 + h, z, 39); // cactus
                    }
                }
            }
        }

        // Reeds/sugar cane (desert, swamp, rivers)
        for (int i = 0; i < biome.reedsPerChunk; i++) {
            int x = worldX + rand.nextInt(16);
            int z = worldZ + rand.nextInt(16);
            int y = getSurfaceHeight(world, x, z);
            if (y > 0 && y < 128) {
                if (world.getVoxel(x, y, z) == 1 || world.getVoxel(x, y, z) == 14 || world.getVoxel(x, y, z) == 13) {
                    if (world.getVoxel(x, y + 1, z) == 0) {
                        int height = 1 + rand.nextInt(2);
                        for (int h = 0; h < height; h++) {
                            world.setVoxel(x, y + 1 + h, z, 40); // reed
                        }
                    }
                }
            }
        }

        // Waterlilies (swamp)
        for (int i = 0; i < biome.waterlilyPerChunk; i++) {
            int x = worldX + rand.nextInt(16);
            int z = worldZ + rand.nextInt(16);
            int y = getSurfaceHeight(world, x, z);
            if (y > 0) {
                for (int ly = y; ly >= y - 4; ly--) {
                    if (world.getVoxel(x, ly, z) == 15) { // water
                        if (world.getVoxel(x, ly + 1, z) == 0) {
                            world.setVoxel(x, ly + 1, z, 41); // waterlily
                            break;
                        }
                    }
                }
            }
        }

        // Big mushrooms (swamp, roofed forest)
        for (int i = 0; i < biome.bigMushroomsPerChunk; i++) {
            int x = worldX + rand.nextInt(16) + 8;
            int z = worldZ + rand.nextInt(16) + 8;
            int y = getSurfaceHeight(world, x, z);
            if (y > 0) placeBigMushroom(world, x, y + 1, z, rand);
        }

        // Desert wells
        if (biome.desertWellChance > 0 && rand.nextInt(biome.desertWellChance) == 0) {
            int x = worldX + rand.nextInt(16) + 4;
            int z = worldZ + rand.nextInt(16) + 4;
            int y = getSurfaceHeight(world, x, z);
            if (y > 0) placeDesertWell(world, x, y, z);
        }

        // Fossils
        if (biome.fossilChance > 0 && rand.nextInt(biome.fossilChance) == 0) {
            int x = worldX + rand.nextInt(16) + 8;
            int z = worldZ + rand.nextInt(16) + 8;
            int y = 40 + rand.nextInt(40);
            placeFossil(world, x, y, z, rand);
        }

        // Pumpkins
        if (biome.pumpkinPerChunk > 0) {
            int x = worldX + rand.nextInt(16);
            int z = worldZ + rand.nextInt(16);
            int y = getSurfaceHeight(world, x, z);
            if (y > 0 && world.getVoxel(x, y + 1, z) == 0 && world.getVoxel(x, y, z) == 1) {
                world.setVoxel(x, y + 1, z, 42); // pumpkin
            }
        }

        // Melons (jungle)
        if (biome.melonPerChunk > 0) {
            for (int i = 0; i < biome.melonPerChunk; i++) {
                int x = worldX + rand.nextInt(16);
                int z = worldZ + rand.nextInt(16);
                int y = getSurfaceHeight(world, x, z);
                if (y > 0 && world.getVoxel(x, y + 1, z) == 0 && world.getVoxel(x, y, z) == 1) {
                    world.setVoxel(x, y + 1, z, 43); // melon
                }
            }
        }

        // Vines (jungle)
        if (biome.vinePerChunk > 0) {
            for (int i = 0; i < biome.vinePerChunk; i++) {
                int x = worldX + rand.nextInt(16);
                int z = worldZ + rand.nextInt(16);
                for (int y = 128; y > 40; y--) {
                    if (world.getVoxel(x, y, z) == 4) { // on leaves
                        if (world.getVoxel(x, y - 1, z) == 0) {
                            world.setVoxel(x, y - 1, z, 44); // vine
                            break;
                        }
                    }
                }
            }
        }
    }

    private int getSurfaceHeight(com.voxel.World world, int x, int z) {
        for (int y = 127; y >= 0; y--) {
            if (world.getVoxel(x, y, z) > 0) return y;
        }
        return -1;
    }

    // ---- Tree Placement - public so WorldGenerator can access ----
    private static final int OAK_LOG = 5;
    private static final int OAK_LEAVES = 4;
    private static final int BIRCH_LOG = 46;
    private static final int SPRUCE_LOG = 47;
    private static final int SPRUCE_LEAVES = 48;
    private static final int JUNGLE_LOG = 49;
    private static final int JUNGLE_LEAVES = 50;
    private static final int ACACIA_LOG = 51;
    private static final int DARK_OAK_LOG = 52;
    private static final int DARK_OAK_LEAVES = 53;

    private void placeTree(Biome biome, com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int treeType = biome.getRandomTreeFeature(rand);
        if (treeType == 0) treeType = rand.nextInt(10); // default distribution

        // 0-3: Oak, 4-5: Big Oak, 6: Birch, 7: Spruce, 8: Jungle, 9: Acacia/Dark Oak
        if (treeType >= 0 && treeType <= 3) {
            placeOakTree(world, x, y, z, rand);
        } else if (treeType == 4 || treeType == 5) {
            placeBigOakTree(world, x, y, z, rand);
        } else if (treeType == 6) {
            placeBirchTree(world, x, y, z, rand);
        } else if (treeType == 7) {
            placeSpruceTree(world, x, y, z, rand);
        } else if (treeType == 8) {
            placeJungleTree(world, x, y, z, rand);
        } else if (treeType == 9) {
            if (biome instanceof BiomeSavanna || (biome.name != null && biome.name.toLowerCase().contains("savanna"))) {
                placeAcaciaTree(world, x, y, z, rand);
            } else {
                placeDarkOakTree(world, x, y, z, rand);
            }
        }
    }

    public void placeOakTree(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int trunkHeight = 4 + rand.nextInt(3);
        // Check space
        for (int i = 0; i < trunkHeight; i++) {
            if (world.getVoxel(x, y + i, z) != 0 && world.getVoxel(x, y + i, z) != 35) return;
        }
        // Trunk
        for (int i = 0; i < trunkHeight; i++) world.setVoxel(x, y + i, z, OAK_LOG);
        // Leaves - round canopy
        int leafBase = y + trunkHeight - 2;
        int leafTop = y + trunkHeight;
        for (int ly = leafBase; ly <= leafTop; ly++) {
            int radius = (ly == leafTop) ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius + 0.5) {
                        int ex = world.getVoxel(x + dx, ly, z + dz);
                        if (ex == 0 || ex == 35) {
                            world.setVoxel(x + dx, ly, z + dz, OAK_LEAVES);
                        }
                    }
                }
            }
        }
    }

    public void placeBigOakTree(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int trunkHeight = 5 + rand.nextInt(4);
        // Trunk
        for (int i = 0; i < trunkHeight; i++) {
            if (world.getVoxel(x, y + i, z) != 0 && world.getVoxel(x, y + i, z) != 35) return;
            world.setVoxel(x, y + i, z, OAK_LOG);
        }
        // Branches at mid-height
        int branchY = y + trunkHeight / 2;
        for (int dir = 0; dir < 4; dir++) {
            int bx = x + (dir < 2 ? (dir == 0 ? -1 : 1) : 0);
            int bz = z + (dir >= 2 ? (dir == 2 ? -1 : 1) : 0);
            int branchLen = 1 + rand.nextInt(2);
            for (int l = 0; l < branchLen; l++) {
                int cx = bx + (dir < 2 ? (dir == 0 ? -l-1 : l+1) : 0);
                int cz = bz + (dir >= 2 ? (dir == 2 ? -l-1 : l+1) : 0);
                if (world.getVoxel(cx, branchY + l, cz) == 0 || world.getVoxel(cx, branchY + l, cz) == 35) {
                    world.setVoxel(cx, branchY + l, cz, OAK_LOG);
                }
            }
        }
        // Wide canopy
        int leafBase = y + trunkHeight - 2;
        int leafTop = y + trunkHeight + 1;
        for (int ly = leafBase; ly <= leafTop; ly++) {
            int radius = (ly - leafBase <= 1) ? 3 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius + 0.5) {
                        if (dx == 0 && dz == 0 && ly < y + trunkHeight - 1) continue;
                        int ex = world.getVoxel(x + dx, ly, z + dz);
                        if (ex == 0 || ex == 35) world.setVoxel(x + dx, ly, z + dz, OAK_LEAVES);
                    }
                }
            }
        }
    }

    public void placeBirchTree(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int trunkHeight = 5 + rand.nextInt(2);
        for (int i = 0; i < trunkHeight; i++) {
            if (world.getVoxel(x, y + i, z) != 0 && world.getVoxel(x, y + i, z) != 35) return;
            world.setVoxel(x, y + i, z, BIRCH_LOG);
        }
        int leafBase = y + trunkHeight - 2;
        int leafTop = y + trunkHeight;
        for (int ly = leafBase; ly <= leafTop; ly++) {
            int radius = (ly == leafTop) ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius + 0.5) {
                        if (dx == 0 && dz == 0 && ly < y + trunkHeight - 1) continue;
                        int ex = world.getVoxel(x + dx, ly, z + dz);
                        if (ex == 0 || ex == 35) world.setVoxel(x + dx, ly, z + dz, OAK_LEAVES);
                    }
                }
            }
        }
    }

    public void placeSpruceTree(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int trunkHeight = 7 + rand.nextInt(3);
        for (int i = 0; i < trunkHeight; i++) {
            if (world.getVoxel(x, y + i, z) != 0 && world.getVoxel(x, y + i, z) != 35) return;
            world.setVoxel(x, y + i, z, SPRUCE_LOG);
        }
        // Cone-shaped leaves (taiga style)
        for (int ly = y + trunkHeight - 3; ly <= y + trunkHeight; ly++) {
            int layerIndex = ly - (y + trunkHeight - 3);
            int radius = 2 - (layerIndex / 2);
            if (radius < 0) radius = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius + 0.5) {
                        int ex = world.getVoxel(x + dx, ly, z + dz);
                        if (ex == 0 || ex == 35) world.setVoxel(x + dx, ly, z + dz, SPRUCE_LEAVES);
                    }
                }
            }
        }
        // Extra top
        if (world.getVoxel(x, y + trunkHeight + 1, z) == 0) {
            world.setVoxel(x, y + trunkHeight + 1, z, SPRUCE_LEAVES);
        }
    }

    public void placeJungleTree(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int trunkHeight = 8 + rand.nextInt(5);
        for (int i = 0; i < trunkHeight; i++) {
            if (world.getVoxel(x, y + i, z) != 0 && world.getVoxel(x, y + i, z) != 35) return;
            world.setVoxel(x, y + i, z, JUNGLE_LOG);
        }
        // Wide canopy
        int leafBase = y + trunkHeight - 3;
        int leafTop = y + trunkHeight;
        for (int ly = leafBase; ly <= leafTop; ly++) {
            int radius = (ly <= leafBase + 1) ? 3 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius + 0.5) {
                        if (dx == 0 && dz == 0 && ly < y + trunkHeight - 1) continue;
                        int ex = world.getVoxel(x + dx, ly, z + dz);
                        if (ex == 0 || ex == 35) world.setVoxel(x + dx, ly, z + dz, JUNGLE_LEAVES);
                    }
                }
            }
        }
    }

    public void placeAcaciaTree(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int trunkHeight = 4 + rand.nextInt(3);
        for (int i = 0; i < trunkHeight; i++) {
            if (world.getVoxel(x, y + i, z) != 0 && world.getVoxel(x, y + i, z) != 35) return;
            world.setVoxel(x, y + i, z, ACACIA_LOG);
        }
        // Branching canopy
        int topY = y + trunkHeight;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                if (dx == 0 && dz == 0) continue;
                int bx = x + dx;
                int bz = z + dz;
                world.setVoxel(bx, topY, bz, ACACIA_LOG);
                world.setVoxel(bx, topY + 1, bz, OAK_LEAVES);
            }
        }
        world.setVoxel(x, topY + 1, z, OAK_LEAVES);
        world.setVoxel(x, topY, z, OAK_LEAVES);
    }

    public void placeDarkOakTree(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int trunkHeight = 5 + rand.nextInt(3);
        for (int i = 0; i < trunkHeight; i++) {
            if (world.getVoxel(x, y + i, z) != 0 && world.getVoxel(x, y + i, z) != 35) return;
            world.setVoxel(x, y + i, z, DARK_OAK_LOG);
        }
        // Wide, flat canopy
        int leafBase = y + trunkHeight - 2;
        int leafTop = y + trunkHeight + 1;
        for (int ly = leafBase; ly <= leafTop; ly++) {
            int radius = (ly <= leafBase + 1) ? 3 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius + 0.5) {
                        if (dx == 0 && dz == 0 && ly < y + trunkHeight - 1) continue;
                        int ex = world.getVoxel(x + dx, ly, z + dz);
                        if (ex == 0 || ex == 35) world.setVoxel(x + dx, ly, z + dz, DARK_OAK_LEAVES);
                    }
                }
            }
        }
    }

    // ---- Ground Patches ----
    private static final int SAND = 14;
    private static final int GRAVEL = 54;
    private static final int CLAY = 55;

    private void placeSandPatch(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int radius = 2 + rand.nextInt(3);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    int block = world.getVoxel(x + dx, y, z + dz);
                    if (block == 13 || block == 1) { // dirt or grass
                        world.setVoxel(x + dx, y, z + dz, SAND);
                    }
                }
            }
        }
    }

    private void placeGravelPatch(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int radius = 2 + rand.nextInt(3);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    int block = world.getVoxel(x + dx, y, z + dz);
                    if (block == 13 || block == 1 || block == 2) { // dirt, grass, or stone
                        world.setVoxel(x + dx, y, z + dz, GRAVEL);
                    }
                }
            }
        }
    }

    private void placeClayPatch(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int radius = 2 + rand.nextInt(2);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    int block = world.getVoxel(x + dx, y, z + dz);
                    if (block == 14) { // sand under water
                        world.setVoxel(x + dx, y, z + dz, CLAY);
                    }
                }
            }
        }
    }

    // ---- Big Mushroom ----
    private static final int BROWN_MUSHROOM_BLOCK = 56;
    private static final int RED_MUSHROOM_BLOCK = 57;
    private static final int MUSHROOM_STEM = 58;

    private void placeBigMushroom(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        int height = 4 + rand.nextInt(3);
        boolean isBrown = rand.nextBoolean();
        int capId = isBrown ? BROWN_MUSHROOM_BLOCK : RED_MUSHROOM_BLOCK;

        // Check space
        for (int i = 0; i < height; i++) {
            if (world.getVoxel(x, y + i, z) != 0) return;
        }
        // Stem
        for (int i = 0; i < height; i++) {
            world.setVoxel(x, y + i, z, MUSHROOM_STEM);
        }
        // Cap
        int capY = y + height;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz <= 5) {
                    if (world.getVoxel(x + dx, capY, z + dz) == 0) {
                        world.setVoxel(x + dx, capY, z + dz, capId);
                    }
                    if (world.getVoxel(x + dx, capY - 1, z + dz) == 0 &&
                        (Math.abs(dx) == 2 || Math.abs(dz) == 2)) {
                        world.setVoxel(x + dx, capY - 1, z + dz, capId);
                    }
                }
            }
        }
    }

    // ---- Desert Well ----
    private static final int SANDSTONE = 59;

    private void placeDesertWell(com.voxel.World world, int x, int y, int z) {
        // 3x3 well with water in center
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setVoxel(x + dx, y, z + dz, SANDSTONE);
                world.setVoxel(x + dx, y + 1, z + dz, SANDSTONE);
            }
        }
        // Corners only for top layer
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                world.setVoxel(x + dx, y + 2, z + dz, SANDSTONE);
            }
        }
        // Water in center
        world.setVoxel(x, y + 1, z, 15); // water
        world.setVoxel(x, y + 1, z, 0); // air above water
    }

    // ---- Fossil ----
    private static final int BONE_BLOCK = 60;
    private static final int COAL_ORE = 61;

    private void placeFossil(com.voxel.World world, int x, int y, int z, java.util.Random rand) {
        // Simple fossil: a few bone blocks in a line
        int length = 3 + rand.nextInt(4);
        int dir = rand.nextInt(4);
        for (int i = 0; i < length; i++) {
            int fx = x + (dir == 0 ? i : dir == 1 ? -i : 0);
            int fz = z + (dir == 2 ? i : dir == 3 ? -i : 0);
            int fy = y + (i % 2 == 0 ? 0 : rand.nextInt(3) - 1);
            if (world.getVoxel(fx, fy, fz) != 0) continue;
            world.setVoxel(fx, fy, fz, rand.nextInt(3) == 0 ? COAL_ORE : BONE_BLOCK);
        }
    }
}
