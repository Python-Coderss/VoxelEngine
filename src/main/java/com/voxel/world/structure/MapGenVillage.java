package com.voxel.world.structure;

import com.voxel.World;
import java.util.Random;

/**
 * Simple village structure generator ported from Minecraft 1.12.2.
 * Generates clusters of buildings: houses, roads, and wells.
 */
public class MapGenVillage {

    private static final int VILLAGE_CHANCE = 32; // 1 in 32 chunks
    private static final int VILLAGE_SIZE = 48; // blocks radius
    private static final int VILLAGE_MIN_HEIGHT = 63;

    private static final int PLANKS = 72;
    private static final int COBBLESTONE = 71;
    private static final int GLASS = 3;
    private static final int OAK_LOG = 5;
    private static final int OAK_LEAVES = 4;
    private static final int OAK_SLAB = 0; // Placeholder - no slab support
    private static final int FENCE = 0;     // Placeholder
    private static final int TORCH = 0;     // Placeholder
    private static final int DIRT = 13;
    private static final int GRAVEL = 54;
    private static final int SANDSTONE = 59;
    private static final int WATER = 15;

    /**
     * Attempts to generate a village if the chunk qualifies.
     * Returns true if a village was generated.
     */
    public boolean generate(World world, int cx, int cz, java.util.Random rand) {
        if (rand.nextInt(VILLAGE_CHANCE) != 0) return false;

        int centerX = (cx << 4) + 8;
        int centerZ = (cz << 4) + 8;

        // Find surface height at center
        int surfaceY = findSurfaceHeight(world, centerX, centerZ);
        if (surfaceY < VILLAGE_MIN_HEIGHT || surfaceY > 100) return false;

        // Generate a few buildings
        int buildingCount = 3 + rand.nextInt(3); // 3-5 buildings

        // Central well
        generateWell(world, centerX, surfaceY, centerZ, rand);

        // Generate buildings in a rough circle
        for (int i = 0; i < buildingCount; i++) {
            double angle = (Math.PI * 2 * i) / buildingCount + rand.nextDouble() * 0.5;
            int dist = 20 + rand.nextInt(15);
            int bx = centerX + (int)(Math.cos(angle) * dist);
            int bz = centerZ + (int)(Math.sin(angle) * dist);
            int by = findSurfaceHeight(world, bx, bz);

            if (by > VILLAGE_MIN_HEIGHT && by < 90) {
                // Randomly choose building type
                int type = rand.nextInt(3);
                if (type == 0) {
                    generateSmallHouse(world, bx, by, bz, rand);
                } else if (type == 1) {
                    generateLargeHouse(world, bx, by, bz, rand);
                } else {
                    generateFarm(world, bx, by, bz, rand);
                }
            }
        }

        return true;
    }

    private void generateWell(World world, int x, int y, int z, Random rand) {
        // 3x3 well: cobblestone frame with water in center
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int bx = x + dx;
                int bz = z + dz;
                // Floor
                world.setVoxel(bx, y, bz, COBBLESTONE);
                // Walls (2 blocks high)
                for (int h = 1; h <= 2; h++) {
                    if (Math.abs(dx) == 1 || Math.abs(dz) == 1) {
                        world.setVoxel(bx, y + h, bz, COBBLESTONE);
                    }
                }
            }
        }
        // Water in center
        world.setVoxel(x, y + 1, z, WATER);
        world.setVoxel(x, y + 2, z, 0); // Air above water
    }

    private void generateSmallHouse(World world, int x, int y, int z, Random rand) {
        int width = 5 + rand.nextInt(3);
        int depth = 5 + rand.nextInt(3);
        int height = 3 + rand.nextInt(2);

        // Floor
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                world.setVoxel(x + dx, y, z + dz, PLANKS);
            }
        }

        // Walls
        for (int h = 1; h <= height; h++) {
            for (int dx = 0; dx < width; dx++) {
                world.setVoxel(x + dx, y + h, z, COBBLESTONE); // front wall
                world.setVoxel(x + dx, y + h, z + depth - 1, COBBLESTONE); // back wall
            }
            for (int dz = 0; dz < depth; dz++) {
                world.setVoxel(x, y + h, z + dz, COBBLESTONE); // left wall
                world.setVoxel(x + width - 1, y + h, z + dz, COBBLESTONE); // right wall
            }
        }

        // Door opening (front wall)
        int doorX = x + width / 2;
        world.setVoxel(doorX, y + 1, z, 0);
        world.setVoxel(doorX, y + 2, z, 0);

        // Roof (oak planks)
        for (int dx = -1; dx < width + 1; dx++) {
            for (int dz = -1; dz < depth + 1; dz++) {
                world.setVoxel(x + dx, y + height + 1, z + dz, PLANKS);
            }
        }

        // Window (glass)
        if (width > 5) {
            int winX = x + 2;
            world.setVoxel(winX, y + 2, z, GLASS);
        }
    }

    private void generateLargeHouse(World world, int x, int y, int z, Random rand) {
        int width = 7 + rand.nextInt(3);
        int depth = 6 + rand.nextInt(3);
        int height = 4;

        // Floor
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                world.setVoxel(x + dx, y, z + dz, PLANKS);
            }
        }

        // Stone brick base
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                world.setVoxel(x + dx, y + 1, z + dz, COBBLESTONE);
            }
        }

        // Wooden walls above
        for (int h = 2; h <= height; h++) {
            for (int dx = 0; dx < width; dx++) {
                world.setVoxel(x + dx, y + h, z, OAK_LOG);
                world.setVoxel(x + dx, y + h, z + depth - 1, OAK_LOG);
            }
            for (int dz = 0; dz < depth; dz++) {
                world.setVoxel(x, y + h, z + dz, OAK_LOG);
                world.setVoxel(x + width - 1, y + h, z + dz, OAK_LOG);
            }
        }

        // Door
        int doorX = x + width / 2;
        world.setVoxel(doorX, y + 1, z, 0);
        world.setVoxel(doorX, y + 2, z, 0);

        // Roof
        for (int dx = -1; dx < width + 1; dx++) {
            for (int dz = -1; dz < depth + 1; dz++) {
                world.setVoxel(x + dx, y + height + 1, z + dz, PLANKS);
            }
        }
    }

    private void generateFarm(World world, int x, int y, int z, Random rand) {
        int size = 5 + rand.nextInt(3);

        // Farmland patch
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                world.setVoxel(x + dx, y, z + dz, DIRT);
            }
        }

        // Fence around farm
        for (int dx = -1; dx <= size; dx++) {
            world.setVoxel(x + dx, y + 1, z - 1, OAK_LOG);
            world.setVoxel(x + dx, y + 1, z + size, OAK_LOG);
        }
        for (int dz = -1; dz <= size; dz++) {
            world.setVoxel(x - 1, y + 1, z + dz, OAK_LOG);
            world.setVoxel(x + size, y + 1, z + dz, OAK_LOG);
        }
    }

    private int findSurfaceHeight(World world, int x, int z) {
        for (int y = 127; y >= 0; y--) {
            if (world.getVoxel(x, y, z) > 0) return y;
        }
        return -1;
    }
}
