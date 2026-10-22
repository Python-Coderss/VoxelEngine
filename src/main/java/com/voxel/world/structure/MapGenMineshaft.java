package com.voxel.world.structure;

import com.voxel.World;
import java.util.Random;

/**
 * Mineshaft structure generator (ported from Minecraft 1.12.2, overhauled).
 *
 * <p>Older versions carved bare 3x3 tunnels and declared rails, fences and
 * torches that were never actually placed (the constants were {@code 0}). This
 * version furnishes the shaft the way a real mineshaft reads:</p>
 * <ul>
 *   <li>wooden support frames at regular intervals, with cobblestone footings,</li>
 *   <li>a rail line down the centre of every corridor, oriented to the tunnel,</li>
 *   <li>torches on the support posts for lighting,</li>
 *   <li>ore veins studding the corridor walls, and</li>
 *   <li>treasure rooms at the branch ends, with a chest and a spawner.</li>
 * </ul>
 *
 * <p>Deterministic for a given {@link Random}, and testable headlessly through
 * the public {@code force} overload.</p>
 */
public class MapGenMineshaft {

    private static final int MINESHAFT_CHANCE = 10; // 1 in 10 chunks
    private static final int MAX_DEPTH = 40;
    private static final int MIN_DEPTH = 20;

    private static final int AIR = 0;
    private static final int OAK_LOG = 5;
    private static final int COBBLESTONE = 71;
    private static final int PLANKS = 72;
    private static final int TORCH = 211;
    private static final int RAIL_NS = 391;
    private static final int RAIL_EW = 392;
    private static final int CHEST = 118;
    private static final int SPAWNER = 258;
    private static final int COAL_ORE = 61;
    private static final int IRON_ORE = 81;
    private static final int GOLD_ORE = 82;
    private static final int DIAMOND_ORE = 83;
    private static final int GRAVEL = 54;
    private static final int OAK_SLAB = 206;
    private static final int GLOWSTONE = 17;
    private static final int MINECART = 393;
    private static final int ANVIL = 456;

    private static final int[] ORES = {COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE};

    /** Attempts to generate a mineshaft in the given chunk. */
    public boolean generate(World world, int cx, int cz, Random rand) {
        return generate(world, cx, cz, rand, false);
    }

    /**
     * As {@link #generate(World, int, int, Random)}, but {@code force} skips the
     * 1-in-{@value #MINESHAFT_CHANCE} roll so tests and tooling can stamp a
     * shaft on demand.
     */
    public boolean generate(World world, int cx, int cz, Random rand, boolean force) {
        if (world == null || rand == null) return false;
        if (!force && rand.nextInt(MINESHAFT_CHANCE) != 0) return false;

        int centerX = (cx << 4) + 8;
        int centerZ = (cz << 4) + 8;
        int startY = MIN_DEPTH + rand.nextInt(MAX_DEPTH - MIN_DEPTH);

        // Main corridor.
        int length = 10 + rand.nextInt(20);
        int direction = rand.nextInt(4); // 0=N 1=E 2=S 3=W
        generateCorridor(world, centerX, startY, centerZ, direction, length, rand);

        // Branch corridors, the first of which always ends in a treasure room.
        int branches = 2 + rand.nextInt(3);
        for (int i = 0; i < branches; i++) {
            int branchDir = (direction + 1 + rand.nextInt(2)) % 4;
            int along = rand.nextInt(Math.max(1, length));
            int branchX = centerX + (direction == 1 ? along * 3 : direction == 3 ? -along * 3 : 0);
            int branchZ = centerZ + (direction == 0 ? -along * 3 : direction == 2 ? along * 3 : 0);
            int branchLen = 5 + rand.nextInt(10);
            generateCorridor(world, branchX, startY, branchZ, branchDir, branchLen, rand);

            if (i == 0 || rand.nextBoolean()) {
                int ex = branchX + (branchDir == 1 ? branchLen * 3 : branchDir == 3 ? -branchLen * 3 : 0);
                int ez = branchZ + (branchDir == 0 ? -branchLen * 3 : branchDir == 2 ? branchLen * 3 : 0);
                generateRoom(world, ex, startY, ez, rand);
            }
        }
        return true;
    }

    private void generateCorridor(World world, int startX, int startY, int startZ,
                                  int direction, int length, Random rand) {
        int dx = 0, dz = 0;
        switch (direction) {
            case 0: dz = -1; break;
            case 1: dx = 1; break;
            case 2: dz = 1; break;
            default: dx = -1; break;
        }

        int width = 3;
        int height = 3;
        int railId = (dx != 0) ? RAIL_EW : RAIL_NS;

        for (int step = 0; step < length; step++) {
            int x = startX + dx * step;
            int z = startZ + dz * step;

            // Carve the 3x3 tunnel.
            for (int wx = -1; wx <= 1; wx++) {
                for (int wz = -1; wz <= 1; wz++) {
                    for (int hy = 0; hy < height; hy++) {
                        world.setVoxel(x + wx, startY + hy, z + wz, AIR);
                    }
                    // Cobblestone / plank floor.
                    world.setVoxel(x + wx, startY - 1, z + wz,
                            (step % 3 == 0) ? COBBLESTONE : PLANKS);
                }
            }

            // Rail line down the centre, oriented to the tunnel.
            world.setVoxel(x, startY, z, railId);

            // Support frame every 4 blocks: posts, cross beam, cobble footing.
            if (step % 4 == 0 && step > 0 && step < length - 1) {
                for (int hy = 0; hy < height; hy++) {
                    world.setVoxel(x + 1, startY + hy, z, OAK_LOG);
                    world.setVoxel(x - 1, startY + hy, z, OAK_LOG);
                }
                world.setVoxel(x + 1, startY + height, z, OAK_LOG);
                world.setVoxel(x, startY + height, z, OAK_LOG);
                world.setVoxel(x - 1, startY + height, z, OAK_LOG);
                // Cobble footings under the posts.
                world.setVoxel(x + 1, startY - 1, z, COBBLESTONE);
                world.setVoxel(x - 1, startY - 1, z, COBBLESTONE);
                // Torch on the post, alternating sides for even light.
                world.setVoxel(x + ((step / 4) % 2 == 0 ? 1 : -1), startY + 2, z, TORCH);
            }

            // Timbered roof: slabs across the tunnel on alternate steps.
            if (step % 2 == 0) {
                for (int o = -1; o <= 1; o++) {
                    if (dx != 0) world.setVoxel(x, startY + height, z + o, OAK_SLAB);
                    else world.setVoxel(x + o, startY + height, z, OAK_SLAB);
                }
            }

            // Supply niche every so often: a lit alcove cut into one wall.
            if (step % 9 == 4) {
                int side = rand.nextBoolean() ? 2 : -2;
                int ax = dx == 0 ? x + side : x;
                int az = dx == 0 ? z : z + side;
                world.setVoxel(ax, startY, az, CHEST);
                world.setVoxel(ax, startY + 1, az, AIR);
                world.setVoxel(ax, startY + 2, az, GLOWSTONE);
            }

            // A spare cart parked on the line, and loose gravel at the edges.
            if (step % 13 == 7) world.setVoxel(x, startY, z, MINECART);
            if (rand.nextInt(9) == 0) {
                int side = rand.nextBoolean() ? 2 : -2;
                if (dx == 0) world.setVoxel(x + side, startY - 1, z, GRAVEL);
                else world.setVoxel(x, startY - 1, z + side, GRAVEL);
            }

            // Ore vein in the tunnel wall, occasionally.
            if (rand.nextInt(6) == 0) {
                int side = rand.nextBoolean() ? 2 : -2;
                int ore = ORES[rand.nextInt(ORES.length)];
                int oy = startY + rand.nextInt(height);
                if (dx == 0) world.setVoxel(x + side, oy, z, ore);
                else world.setVoxel(x, oy, z + side, ore);
            }
        }
    }

    private void generateRoom(World world, int x, int y, int z, Random rand) {
        int roomWidth = 5 + rand.nextInt(4);
        int roomDepth = 5 + rand.nextInt(4);
        int roomHeight = 4;
        int halfW = roomWidth / 2, halfD = roomDepth / 2;

        // Carve.
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                for (int hy = 0; hy < roomHeight; hy++) {
                    world.setVoxel(x + dx, y + hy, z + dz, AIR);
                }
                world.setVoxel(x + dx, y - 1, z + dz, PLANKS);
            }
        }

        // Corner pillars and a lit ceiling.
        for (int[] c : new int[][]{{-halfW, -halfD}, {-halfW, halfD},
                {halfW, -halfD}, {halfW, halfD}}) {
            for (int hy = 0; hy < roomHeight; hy++) {
                world.setVoxel(x + c[0], y + hy, z + c[1], OAK_LOG);
            }
        }
        world.setVoxel(x, y + roomHeight - 1, z, TORCH);

        // Treasure: a chest in one corner, and a spawner at the heart.
        world.setVoxel(x + halfW - 1, y, z + halfD - 1, CHEST);
        world.setVoxel(x, y, z, SPAWNER);

        // Work table, anvil, crates and ore samples in the walls.
        world.setVoxel(x + 1, y, z - 1, PLANKS);
        world.setVoxel(x + 1, y + 1, z - 1, ANVIL);
        world.setVoxel(x - 1, y, z + 1, ANVIL);
        world.setVoxel(x - halfW + 1, y, z - halfD + 1, PLANKS);
        world.setVoxel(x - halfW + 1, y + 1, z - halfD + 1, OAK_SLAB);
        world.setVoxel(x + halfW - 1, y, z - halfD + 1, PLANKS);
        world.setVoxel(x + halfW - 1, y + 1, z - halfD + 1, GLOWSTONE);
        world.setVoxel(x + halfW + 1, y + 2, z, COAL_ORE);
        world.setVoxel(x - halfW - 1, y + 2, z, IRON_ORE);
        world.setVoxel(x, y + 2, z + halfD + 1, GOLD_ORE);
        world.setVoxel(x, y + 1, z - halfD - 1, DIAMOND_ORE);
        world.setVoxel(x - halfW + 1, y + roomHeight - 1, z + halfD - 1, GLOWSTONE);
        world.setVoxel(x + halfW - 1, y + roomHeight - 1, z + halfD - 1, GLOWSTONE);
    }
}
