package com.voxel.world;

import com.voxel.game.ChestManager;

/**
 * Hand-authored "Lighting Demo": two halls side by side that differ in exactly
 * one thing — how much sky they see — so the renderer's lighting can be judged
 * without hunting for a cave or waiting for dusk.
 *
 * <ul>
 *   <li><b>Light hall</b> (west, x -30..-11): a sandstone courtyard open to the
 *       sky, with a glass skylight bay over its eastern half. No artificial
 *       light at all: everything in it is sun, skylight bounce and ambient
 *       occlusion, so a lighting change shows up immediately.</li>
 *   <li><b>Dark hall</b> (east, x 11..30): the same footprint under a solid
 *       stone roof. Torches on the walls, a glowstone lamp, a lava channel
 *       behind glass and one deliberately unlit alcove at the back, so the
 *       block-light falloff and the ambient floor can be read in one frame.</li>
 * </ul>
 *
 * <p>Both halls carry the same furniture at mirrored positions — a chest, a
 * plank bench, a water pool — so differences between the two frames are
 * lighting only, not geometry. A stone-brick corridor at z 0 joins them, and
 * the player spawns in its mouth at (0, G+1, 0) facing the dark hall.</p>
 *
 * <p>Like {@link ShowcaseWorldAuthor} this writes through a {@link Sink};
 * {@link LightingDemoWorldGenerator} materialises the result into every
 * section it is asked to populate, so the demo exists with no chunk files on
 * disk.</p>
 */
public final class LightingDemoWorldAuthor {

    /** Surface height of the flat plain (player feet rest at G + 1 = 63). */
    public static final int G = 62;
    /** World extent: -56..55 in X/Z (7x7 chunks). */
    public static final int MIN = -56, MAX = 55;
    /** Authored Y range (base terrain up to G, builds to G+16). */
    public static final int AREA_H = 80;

    /** Where the player stands on entry. */
    public static final double SPAWN_X = 0.5, SPAWN_Z = 0.5;

    // ── Block ids (the registry table the other authors use) ────────────────
    private static final int GRASS = 1, STONE = 2, GLASS = 3, DIRT = 13,
            GLOWSTONE = 17, LAVA = 21, WATER = 15, SANDSTONE = 59,
            COBBLESTONE = 71, OAK_PLANKS = 72, WOOL = 91, CHEST = 118,
            STONEBRICK = 131, MARBLE = 926, SOUL_LAMP = 935, TORCH = 211;

    /** Interior of the light hall, inclusive. */
    private static final int LIGHT_X0 = -30, LIGHT_X1 = -11;
    /** Interior of the dark hall, inclusive. */
    private static final int DARK_X0 = 11, DARK_X1 = 30;
    private static final int HALL_Z0 = -9, HALL_Z1 = 9;
    /** Interior ceiling: light hall is open, the dark hall's roof sits here. */
    private static final int ROOF_Y = G + 7;

    private LightingDemoWorldAuthor() {}

    /** Sink the world is built into (the exporter and the generator both pass one). */
    public interface Sink {
        void set(int x, int y, int z, int type, int extra);
    }

    /** Builds the complete demo world into {@code sink} and stocks its chests. */
    public static void buildAll(Sink sink, ChestManager chests) {
        new Author(sink, chests).build();
    }

    private static final class Author {
        private final Sink sink;
        private final ChestManager chests;

        Author(Sink sink, ChestManager chests) {
            this.sink = sink;
            this.chests = chests;
        }

        private void set(int x, int y, int z, int type) {
            sink.set(x, y, z, type, 0);
        }

        private void clear(int x, int y, int z) {
            sink.set(x, y, z, 0, 0);
        }

        private void fill(int x0, int y0, int z0, int x1, int y1, int z1, int type) {
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z, type);
        }

        /** Floor of the whole authored area: a mown-plain apron outside the halls. */
        private void build() {
            buildLightHall();
            buildDarkHall();
            buildCorridor();
            buildApron();
        }

        // ── Light hall: open to the sky, no artificial light ────────────────
        private void buildLightHall() {
            // Plank floor with a sandstone rim, so the floor reads as two
            // materials under one light.
            fill(LIGHT_X0, G, HALL_Z0, LIGHT_X1, G, HALL_Z1, OAK_PLANKS);
            for (int x = LIGHT_X0; x <= LIGHT_X1; x++) {
                set(x, G, HALL_Z0, SANDSTONE);
                set(x, G, HALL_Z1, SANDSTONE);
            }
            for (int z = HALL_Z0; z <= HALL_Z1; z++) {
                set(LIGHT_X0, G, z, SANDSTONE);
                set(LIGHT_X1, G, z, SANDSTONE);
            }

            // Walls: sandstone with a marble course at head height.
            for (int y = G + 1; y <= ROOF_Y; y++) {
                for (int x = LIGHT_X0 - 1; x <= LIGHT_X1 + 1; x++) {
                    set(x, y, HALL_Z0 - 1, y == G + 4 ? MARBLE : SANDSTONE);
                    set(x, y, HALL_Z1 + 1, y == G + 4 ? MARBLE : SANDSTONE);
                }
                for (int z = HALL_Z0 - 1; z <= HALL_Z1 + 1; z++) {
                    set(LIGHT_X0 - 1, y, z, y == G + 4 ? MARBLE : SANDSTONE);
                    set(LIGHT_X1 + 1, y, z, y == G + 4 ? MARBLE : SANDSTONE);
                }
            }

            // Glass skylight bay over the eastern half: sky light still reaches
            // the floor, but as a softer, sheeted light than the open west half.
            for (int x = -19; x <= LIGHT_X1 + 1; x++)
                for (int z = HALL_Z0 - 1; z <= HALL_Z1 + 1; z++)
                    set(x, ROOF_Y, z, GLASS);

            // East door into the corridor, two wide and three tall.
            for (int y = G + 1; y <= G + 3; y++)
                for (int z = -1; z <= 1; z++) clear(LIGHT_X1 + 1, y, z);

            // Pool in the middle of the open half: a mirror for the sky.
            fill(-26, G, -2, -23, G, 2, WATER);
            for (int x = -27; x <= -22; x++) {
                set(x, G, -3, SANDSTONE);
                set(x, G, 3, SANDSTONE);
            }
            for (int z = -3; z <= 3; z++) {
                set(-27, G, z, SANDSTONE);
                set(-22, G, z, SANDSTONE);
            }

            // Furniture, mirrored in the dark hall.
            set(-16, G + 1, 0, CHEST);
            chestAt(-16, G + 1, 0, new String[] {"torch", "oak_planks", "glass"}, new int[] {16, 32, 16});
            for (int z = -3; z <= 3; z++) set(-14, G + 1, z, OAK_PLANKS);
        }

        // ── Dark hall: solid roof, block light only ─────────────────────────
        private void buildDarkHall() {
            fill(DARK_X0, G, HALL_Z0, DARK_X1, G, HALL_Z1, STONEBRICK);
            for (int x = DARK_X0 - 1; x <= DARK_X1 + 1; x++)
                for (int z = HALL_Z0 - 1; z <= HALL_Z1 + 1; z++)
                    set(x, ROOF_Y, z, STONE);

            for (int y = G + 1; y <= ROOF_Y - 1; y++) {
                for (int x = DARK_X0 - 1; x <= DARK_X1 + 1; x++) {
                    set(x, y, HALL_Z0 - 1, COBBLESTONE);
                    set(x, y, HALL_Z1 + 1, COBBLESTONE);
                }
                for (int z = HALL_Z0 - 1; z <= HALL_Z1 + 1; z++) {
                    set(DARK_X0 - 1, y, z, COBBLESTONE);
                    set(DARK_X1 + 1, y, z, COBBLESTONE);
                }
            }

            // West door into the corridor, matching the light hall's.
            for (int y = G + 1; y <= G + 3; y++)
                for (int z = -1; z <= 1; z++) clear(DARK_X0 - 1, y, z);

            // Wall torches down both long sides.
            for (int x = DARK_X0 + 3; x <= DARK_X1 - 3; x += 6) {
                set(x, G + 3, HALL_Z0, TORCH);
                set(x, G + 3, HALL_Z1, TORCH);
            }
            // Glowstone lamp over the centre pit, the brightest source in the room.
            set(DARK_X0 + 9, ROOF_Y - 1, 0, GLOWSTONE);
            set(DARK_X0 + 10, ROOF_Y - 1, 0, GLOWSTONE);

            // Lava channel behind glass along the north wall.
            for (int x = DARK_X0 + 3; x <= DARK_X0 + 8; x++) {
                set(x, G, HALL_Z0, LAVA);
                set(x, G + 1, HALL_Z0, GLASS);
            }

            // Soul lamp in a recess: a cold, dim source to compare with the torches.
            set(DARK_X1 - 2, G + 2, HALL_Z1, SOUL_LAMP);

            // Same furniture as the light hall, mirrored.
            set(DARK_X0 + 5, G + 1, 0, CHEST);
            chestAt(DARK_X0 + 5, G + 1, 0, new String[] {"torch", "stone", "coal"}, new int[] {8, 32, 16});
            for (int z = -3; z <= 3; z++) set(DARK_X0 + 3, G + 1, z, OAK_PLANKS);

            // The unlit alcove: walled off from every torch, this corner keeps
            // only the ambient floor, which is what the dark half is here to show.
            set(DARK_X1, G + 1, HALL_Z1 - 4, STONEBRICK);
            set(DARK_X1, G + 2, HALL_Z1 - 4, STONEBRICK);
            set(DARK_X1 - 1, G + 1, HALL_Z1 - 4, STONEBRICK);
            set(DARK_X1 - 1, G + 2, HALL_Z1 - 4, STONEBRICK);
        }

        // ── Corridor between the halls, with the spawn at its mouth ──────────
        private void buildCorridor() {
            fill(LIGHT_X1 + 1, G, -3, DARK_X0 - 1, G, 3, STONEBRICK);
            for (int x = LIGHT_X1 + 1; x <= DARK_X0 - 1; x++) {
                for (int y = G + 1; y <= G + 5; y++) {
                    set(x, y, -4, STONEBRICK);
                    set(x, y, 4, STONEBRICK);
                }
            }
            for (int x = LIGHT_X1 + 1; x <= DARK_X0 - 1; x++)
                for (int z = -4; z <= 4; z++) set(x, G + 6, z, STONE);

            // Opening in the middle of the corridor roof, so the passage itself
            // is the gradient between the two halls.
            for (int x = -3; x <= 3; x++)
                for (int z = -1; z <= 1; z++) clear(x, G + 6, z);

            set(-2, G + 1, 2, TORCH);
            set(2, G + 1, 2, TORCH);
        }

        /** Grass apron outside the halls so the demo does not float on nothing. */
        private void buildApron() {
            for (int x = MIN; x <= MAX; x++) {
                for (int z = MIN; z <= MAX; z++) {
                    if (x >= LIGHT_X0 - 1 && x <= DARK_X1 + 1
                            && z >= HALL_Z0 - 1 && z <= HALL_Z1 + 1) continue;
                    if (x >= LIGHT_X1 + 1 && x <= DARK_X0 - 1
                            && z >= -4 && z <= 4) continue;
                    if (x < LIGHT_X0 - 4 && x > LIGHT_X0 - 6 && Math.abs(z) <= 1) {
                        // Path leading up to the light hall from the west.
                        set(x, G, z, STONEBRICK);
                        continue;
                    }
                    set(x, G, z, GRASS);
                }
            }
            // A few trees give the open hall a shadow to compare against.
            tree(-38, -12);
            tree(-38, 12);
            tree(38, -12);
            tree(38, 12);
        }

        private void tree(int x, int z) {
            for (int y = G + 1; y <= G + 5; y++) set(x, y, z, 5);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int dy = 0; dy <= 2; dy++) {
                        if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                        if (dy == 2 && Math.abs(dx) + Math.abs(dz) > 1) continue;
                        set(x + dx, G + 4 + dy, z + dz, 4);
                    }
            set(x, G + 7, z, 4);
        }

        private void chestAt(int x, int y, int z, String[] items, int[] counts) {
            com.voxel.game.ItemDefinitions.ItemStack[] inv =
                    new com.voxel.game.ItemDefinitions.ItemStack[items.length];
            for (int i = 0; i < items.length; i++) {
                inv[i] = new com.voxel.game.ItemDefinitions.ItemStack(items[i], counts[i]);
            }
            chests.setInventory(x, y, z, inv);
        }
    }
}
