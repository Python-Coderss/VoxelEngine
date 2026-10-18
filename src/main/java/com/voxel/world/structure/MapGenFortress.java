package com.voxel.world.structure;

import com.voxel.World;
import com.voxel.world.DecorBlocks;

import java.util.Random;

/**
 * Nether fortress. Towers rise at the intersections of a 6-chunk (96-block)
 * grid; corridor segments run along the grid lines between them, giving the
 * classic fortress maze of brick corridors and rooms. Generation is fully
 * self-contained per chunk (only writes inside the current 16x16 column via
 * setVoxelInPool), so it slots straight into DimensionWorldGenerator.decorate.
 *
 * Block IDs: nether_bricks 24, nether_brick_stairs 205, nether_brick_slab 210,
 * lava 21, glowstone 17.
 */
public class MapGenFortress {

    private static final int GRID = 6;          // tower spacing in chunks
    private static final int BRICK = 24;
    private static final int STAIRS = 205;
    private static final int SLAB = 210;
    private static final int LAVA = 21;
    private static final int GLOWSTONE = 17;
    /** Real doors for the tower rooms (registered by Main.registerDecorativeBlocks). */
    private static final int DOOR_LOWER_Z = DecorBlocks.DARK_OAK_DOOR_LOWER;
    private static final int DOOR_UPPER_Z = DecorBlocks.DARK_OAK_DOOR_UPPER;
    private static final int DOOR_LOWER_X = DecorBlocks.DARK_OAK_DOOR_LOWER_EW;
    private static final int DOOR_UPPER_X = DecorBlocks.DARK_OAK_DOOR_UPPER_EW;
    /** Block id of the mob spawner block. Main registers this as id 258. */
    private static final int SPAWNER = 258;

    /** Returns true if this chunk is part of a fortress grid cell. */
    public static boolean isFortressChunk(int cx, int cz) {
        return (cx % GRID == 0) || (cz % GRID == 0);
    }

    /**
     * Generate fortress pieces inside chunk (cx, cz) at section cy.
     * Only the section that contains the fortress floor band (y 32-47) is
     * decorated — the caller decides that (cy == 2).
     */
    public void generate(World world, int slot, int cx, int cz, int cy) {
        if (cy != 2) return; // fortress floor band lives in section 2

        boolean tower = (cx % GRID == 0) && (cz % GRID == 0);
        boolean nsCorridor = (cx % GRID == 0) && !tower;
        boolean ewCorridor = (cz % GRID == 0) && !tower;
        if (!tower && !nsCorridor && !ewCorridor) return;

        long seed = (long) cx * 341873128712L + (long) cz * 132897987541L + 73L;
        Random rand = new Random(seed ^ (seed >>> 33));

        int baseLy = 35 - (cy << 4); // local y of the fortress floor

        if (tower) {
            generateTower(world, slot, rand, baseLy);
            // 1-in-8 towers host a blaze spawner platform on top of the
            // parapet, replacing the lava pool with a 1x1 spawner block.
            // The spawner block has metadata-driven spawn logic so the
            // spawn-loop run by Main.tick picks up the spawn type from
            // this chunk's meta channel.
            if (rand.nextInt(8) == 0) {
                // A brick plinth under the spawner so the block is supported
                // rather than floating in mid-air.
                set(world, slot, 7, baseLy + 1, 7, BRICK);
                set(world, slot, 7, baseLy + 2, 7, SPAWNER);
            }
        } else if (nsCorridor) {
            generateCorridor(world, slot, rand, baseLy, true);
        } else {
            generateCorridor(world, slot, rand, baseLy, false);
        }
    }

    /**
     * A 10x10 tower room with brick walls, corner pillars, a walled lava basin
     * and two real two-block doors — one on the north wall (facing the N-S
     * corridor) and one on the west wall (facing the E-W corridor) — so the
     * room is neither sealed shut nor an open hole in the fortress shell.
     */
    private void generateTower(World world, int slot, Random rand, int baseLy) {
        int x0 = 3, x1 = 12, z0 = 3, z1 = 12;
        int wallTop = baseLy + 4;      // 4-tall walls
        int parapet = baseLy + 5;      // corner pillar caps

        for (int ly = baseLy; ly < wallTop; ly++) {
            for (int lx = x0; lx <= x1; lx++) {
                for (int lz = z0; lz <= z1; lz++) {
                    boolean perimeter = lx == x0 || lx == x1 || lz == z0 || lz == z1;
                    if (ly == baseLy) {
                        set(world, slot, lx, ly, lz, perimeter ? BRICK : SLAB);
                    } else if (perimeter) {
                        // Window gap on each side at the second course.
                        boolean window = (ly == baseLy + 2)
                            && ((lz == z0 && (lx == x0 + 2 || lx == x1 - 2))
                             || (lz == z1 && (lx == x0 + 2 || lx == x1 - 2))
                             || (lx == x0 && (lz == z0 + 2 || lz == z1 - 2))
                             || (lx == x1 && (lz == z0 + 2 || lz == z1 - 2)));
                        set(world, slot, lx, ly, lz, window ? GLOWSTONE : BRICK);
                    }
                }
            }
        }
        // Corner pillars run from the wall top up past the parapet, so the
        // cap never floats above a gap.
        for (int[] c : new int[][]{{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) {
            for (int ly = wallTop; ly <= parapet + 1; ly++) {
                set(world, slot, c[0], ly, c[1], BRICK);
            }
            set(world, slot, c[0], parapet + 2, c[1], GLOWSTONE);
        }
        // Recessed 2x2 lava basin in the south-west corner. A solid brick lip
        // rings it on every side so the lava cannot crawl across the floor.
        int lax = x0 + 2, laz = z1 - 2;
        for (int dx = -1; dx <= 2; dx++)
            for (int dz = -1; dz <= 2; dz++)
                set(world, slot, lax + dx, baseLy + 1, laz + dz, BRICK);
        for (int dx = 0; dx <= 1; dx++)
            for (int dz = 0; dz <= 1; dz++)
                set(world, slot, lax + dx, baseLy + 1, laz + dz, LAVA);

        // Two-wide double doors. The north wall runs along X so it takes the
        // *_ew panels; the west wall runs along Z and takes the plain panels.
        doorAlongX(world, slot, 7, baseLy, z0);
        doorAlongX(world, slot, 8, baseLy, z0);
        doorAlongZ(world, slot, x0, baseLy, 7);
        doorAlongZ(world, slot, x0, baseLy, 8);
        // A slab apron outside each doorway so a step out of the room is not a
        // drop into the void.
        for (int dx = 6; dx <= 9; dx++) set(world, slot, dx, baseLy, z0 - 1, SLAB);
        for (int dz = 6; dz <= 9; dz++) set(world, slot, x0 - 1, baseLy, dz, SLAB);
    }

    /** Two-tall door in a wall that runs along X (fixed z, opening spanning x). */
    private void doorAlongX(World world, int slot, int x, int baseLy, int z) {
        set(world, slot, x, baseLy + 1, z, DOOR_LOWER_X);
        set(world, slot, x, baseLy + 2, z, DOOR_UPPER_X);
    }

    /** Two-tall door in a wall that runs along Z (fixed x, opening spanning z). */
    private void doorAlongZ(World world, int slot, int x, int baseLy, int z) {
        set(world, slot, x, baseLy + 1, z, DOOR_LOWER_Z);
        set(world, slot, x, baseLy + 2, z, DOOR_UPPER_Z);
    }

    /** A 3-wide corridor along the N-S (z) or E-W (x) axis with low brick walls. */
    private void generateCorridor(World world, int slot, Random rand, int baseLy, boolean northSouth) {
        int wallTop = baseLy + 3;
        for (int i = 0; i < 16; i++) {
            if (northSouth) {
                // Three-wide walk: a floor under every cell (the middle lane
                // used to have none, so the corridor had a hole down the
                // centre), brick side walls and clear head height.
                set(world, slot, 7, baseLy, i, SLAB);
                set(world, slot, 8, baseLy, i, SLAB);
                set(world, slot, 9, baseLy, i, SLAB);
                for (int ly = baseLy + 1; ly < wallTop; ly++) {
                    set(world, slot, 7, ly, i, 0);
                    set(world, slot, 8, ly, i, 0);
                    set(world, slot, 9, ly, i, 0);
                    set(world, slot, 6, ly, i, BRICK);
                    set(world, slot, 10, ly, i, BRICK);
                }
            } else {
                set(world, slot, i, baseLy, 7, SLAB);
                set(world, slot, i, baseLy, 8, SLAB);
                set(world, slot, i, baseLy, 9, SLAB);
                for (int ly = baseLy + 1; ly < wallTop; ly++) {
                    set(world, slot, i, ly, 7, 0);
                    set(world, slot, i, ly, 8, 0);
                    set(world, slot, i, ly, 9, 0);
                    set(world, slot, i, ly, 6, BRICK);
                    set(world, slot, i, ly, 10, BRICK);
                }
            }
        }
        // Occasional cross-brace with an arch gap.
        if (rand.nextBoolean()) {
            int at = 3 + rand.nextInt(8);
            if (northSouth) {
                set(world, slot, 7, baseLy + 1, at, BRICK);
                set(world, slot, 7, baseLy + 2, at, BRICK);
                set(world, slot, 8, baseLy + 1, at, 0); // arch
                set(world, slot, 8, baseLy + 2, at, 0);
                set(world, slot, 9, baseLy + 1, at, BRICK);
                set(world, slot, 9, baseLy + 2, at, BRICK);
            } else {
                set(world, slot, at, baseLy + 1, 7, BRICK);
                set(world, slot, at, baseLy + 2, 7, BRICK);
                set(world, slot, at, baseLy + 1, 8, 0);
                set(world, slot, at, baseLy + 2, 8, 0);
                set(world, slot, at, baseLy + 1, 9, BRICK);
                set(world, slot, at, baseLy + 2, 9, BRICK);
            }
        }
    }

    private static void set(World world, int slot, int lx, int ly, int lz, int type) {
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || ly < 0 || ly > 15) return;
        world.setVoxelInPool(slot, lx, ly, lz, type);
    }
}
