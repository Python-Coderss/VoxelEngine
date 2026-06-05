package com.voxel.world.structure;

import com.voxel.World;
import java.util.Random;

/**
 * Simple mineshaft structure generator ported from Minecraft 1.12.2.
 * Generates branching underground tunnels with supports and rails.
 */
public class MapGenMineshaft {

    private static final int MINESHAFT_CHANCE = 10; // 1 in 10 chunks
    private static final int MAX_DEPTH = 40;
    private static final int MIN_DEPTH = 20;

    private static final int PLANKS = 72;
    private static final int FENCE = 0;
    private static final int RAIL = 0;
    private static final int TORCH = 0;
    private static final int COBBLESTONE = 71;
    private static final int AIR = 0;

    /**
     * Attempts to generate a mineshaft in the given chunk.
     */
    public boolean generate(World world, int cx, int cz, java.util.Random rand) {
        if (rand.nextInt(MINESHAFT_CHANCE) != 0) return false;

        int centerX = (cx << 4) + 8;
        int centerZ = (cz << 4) + 8;
        int startY = MIN_DEPTH + rand.nextInt(MAX_DEPTH - MIN_DEPTH);

        // Generate main corridor
        int length = 10 + rand.nextInt(20);
        int direction = rand.nextInt(4); // 0=North, 1=East, 2=South, 3=West

        generateCorridor(world, centerX, startY, centerZ, direction, length, rand);

        // Branch corridors
        int branches = 2 + rand.nextInt(3);
        for (int i = 0; i < branches; i++) {
            int branchDir = (direction + 1 + rand.nextInt(2)) % 4; // Perpendicular
            int branchX = centerX + (direction == 1 ? rand.nextInt(length) * 3 : 0);
            int branchZ = centerZ + (direction == 0 ? rand.nextInt(length) * 3 : 0);
            int branchLen = 5 + rand.nextInt(10);

            generateCorridor(world, branchX, startY, branchZ, branchDir, branchLen, rand);

            // Maybe add a room at the end
            if (rand.nextBoolean()) {
                generateRoom(world, branchX + (branchDir == 1 ? branchLen * 3 : 0),
                    startY,
                    branchZ + (branchDir == 0 ? branchLen * 3 : 0), rand);
            }
        }

        return true;
    }

    private void generateCorridor(World world, int startX, int startY, int startZ, int direction, int length, Random rand) {
        int dx = 0, dz = 0;
        switch (direction) {
            case 0: dz = -1; break; // North
            case 1: dx = 1; break;  // East
            case 2: dz = 1; break;  // South
            case 3: dx = -1; break; // West
        }

        int width = 3;
        int height = 3;

        for (int step = 0; step < length; step++) {
            int cx = startX + dx * step;
            int cz = startZ + dz * step;

            // Carve tunnel (3x3)
            for (int wx = -1; wx <= 1; wx++) {
                for (int wz = -1; wz <= 1; wz++) {
                    for (int hy = 0; hy < height; hy++) {
                        world.setVoxel(cx + wx, startY + hy, cz + wz, AIR);
                    }
                }
            }

            // Floor supports (planks)
            for (int wx = -1; wx <= 1; wx++) {
                world.setVoxel(cx + wx, startY - 1, cz, PLANKS);
                world.setVoxel(cx + wx, startY - 1, cz, PLANKS); // Double line
            }

            // Wooden supports every 4 blocks
            if (step % 4 == 0 && step > 0 && step < length - 1) {
                for (int hy = 0; hy < height; hy++) {
                    world.setVoxel(cx + 1, startY + hy, cz, OAK_LOG);
                    world.setVoxel(cx - 1, startY + hy, cz, OAK_LOG);
                }
                // Cross beam
                world.setVoxel(cx + 1, startY + height, cz, OAK_LOG);
                world.setVoxel(cx, startY + height, cz, OAK_LOG);
                world.setVoxel(cx - 1, startY + height, cz, OAK_LOG);
            }
        }
    }

    private static final int OAK_LOG = 5;

    private void generateRoom(World world, int x, int y, int z, Random rand) {
        int roomWidth = 5 + rand.nextInt(4);
        int roomDepth = 5 + rand.nextInt(4);
        int roomHeight = 4;

        // Carve room
        for (int dx = -roomWidth/2; dx <= roomWidth/2; dx++) {
            for (int dz = -roomDepth/2; dz <= roomDepth/2; dz++) {
                for (int hy = 0; hy < roomHeight; hy++) {
                    world.setVoxel(x + dx, y + hy, z + dz, AIR);
                }
            }
        }

        // Floor
        for (int dx = -roomWidth/2; dx <= roomWidth/2; dx++) {
            for (int dz = -roomDepth/2; dz <= roomDepth/2; dz++) {
                world.setVoxel(x + dx, y - 1, z + dz, PLANKS);
            }
        }

        // Support pillars
        world.setVoxel(x - roomWidth/4, y, z - roomDepth/4, OAK_LOG);
        world.setVoxel(x + roomWidth/4, y, z + roomDepth/4, OAK_LOG);
        world.setVoxel(x + roomWidth/4, y, z - roomDepth/4, OAK_LOG);
        world.setVoxel(x - roomWidth/4, y, z + roomDepth/4, OAK_LOG);
    }
}
