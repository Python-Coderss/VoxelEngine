package com.voxel.world.structure;

import com.voxel.World;

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
        } else if (nsCorridor) {
            generateCorridor(world, slot, rand, baseLy, true);
        } else {
            generateCorridor(world, slot, rand, baseLy, false);
        }
    }

    /** A 10x10 tower room with brick walls, corner pillars, lava pool and a doorway. */
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
        // Corner pillars above the parapet.
        for (int[] c : new int[][]{{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) {
            set(world, slot, c[0], parapet, c[1], BRICK);
            set(world, slot, c[0], parapet + 1, c[1], GLOWSTONE);
        }
        // Lava pool in one corner of the floor, with a step up to reach it.
        int lx = x0 + 1, lz = z1 - 1;
        set(world, slot, lx, baseLy + 1, lz, LAVA);
        set(world, slot, lx + 1, baseLy + 1, lz, LAVA);
        set(world, slot, lx, baseLy + 1, lz - 1, LAVA);
        // Stair entrance on the north wall.
        set(world, slot, x0 + 4, baseLy + 1, z0, STAIRS);
    }

    /** A 3-wide corridor along the N-S (z) or E-W (x) axis with low brick walls. */
    private void generateCorridor(World world, int slot, Random rand, int baseLy, boolean northSouth) {
        int wallTop = baseLy + 3;
        for (int ly = baseLy; ly < wallTop; ly++) {
            for (int i = 0; i < 16; i++) {
                int a = i; // along-axis coordinate
                if (northSouth) {
                    // floor
                    set(world, slot, 7, ly == baseLy ? baseLy : ly, a, ly == baseLy ? SLAB : 0);
                    set(world, slot, 9, ly == baseLy ? baseLy : ly, a, ly == baseLy ? SLAB : 0);
                    // side walls at lx=6 and lx=10
                    if (ly > baseLy) {
                        set(world, slot, 6, ly, a, BRICK);
                        set(world, slot, 10, ly, a, BRICK);
                    }
                } else {
                    set(world, slot, a, ly == baseLy ? baseLy : ly, 7, ly == baseLy ? SLAB : 0);
                    set(world, slot, a, ly == baseLy ? baseLy : ly, 9, ly == baseLy ? SLAB : 0);
                    if (ly > baseLy) {
                        set(world, slot, a, ly, 6, BRICK);
                        set(world, slot, a, ly, 10, BRICK);
                    }
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
