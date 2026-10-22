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
 * <p>A stairwell off the plain at z 14 leads down to a carved <b>cave</b>: no
 * sky light at all, a lava pool, a drip pool, two torches and a chest, so the
 * bottom of the lighting scale can be judged next to the two halls.</p>
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
            STONEBRICK = 131, MARBLE = 926, SOUL_LAMP = 935, TORCH = 211,
            IRON_ORE = 81, GOLD_ORE = 82, DIAMOND_ORE = 83, COAL_ORE = 61,
            GRAVEL = 54;

    /** Interior of the light hall, inclusive. */
    private static final int LIGHT_X0 = -30, LIGHT_X1 = -11;
    /** Interior of the dark hall, inclusive. */
    private static final int DARK_X0 = 11, DARK_X1 = 30;
    private static final int HALL_Z0 = -9, HALL_Z1 = 9;
    /** Cave chamber extents: floor surface, ceiling, footprint. */
    public static final int CAVE_FLOOR = G - 18, CAVE_CEIL = G - 8;
    public static final int CAVE_X0 = -14, CAVE_X1 = 14, CAVE_Z0 = 26, CAVE_Z1 = 48;
    /** Centre of the sunken lava basin. */
    public static final int CAVE_LAVA_X = 8, CAVE_LAVA_Z = 40;
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
            // The cave is carved last: its stairwell cuts through the apron's
            // ground layer, which would otherwise be grassed back over.
            buildCave();
        }

        // ── Cave: no sky light at all, lava and torches only ────────────────
        /**
         * A stairwell off the plain into a carved chamber, so the darkest end of
         * the lighting scale can be judged too: {0,0,0} sky light, one lava pool
         * for a coloured bounce, two torches and a chest for scale. The chamber is
         * cut out of the generator's solid stone, so its walls are real rock rather
         * than a hollow box with grass on top.
         */
        private void buildCave() {
            // ── 1. Carve the chamber ────────────────────────────────────────
            // Both the floor and the roof undulate by a deterministic hash and
            // the rim columns pinch, so the cavity reads as dug-out rock instead
            // of a stone box. The generator already filled everything down here
            // with stone, so only the carved cells need authoring.
            for (int x = CAVE_X0; x <= CAVE_X1; x++) {
                for (int z = CAVE_Z0; z <= CAVE_Z1; z++) {
                    int floor = CAVE_FLOOR + (hash(x, z, 23) & 1);
                    int ceil = CAVE_CEIL - (hash(x, z, 11) & 3);
                    int edge = Math.min(Math.min(x - CAVE_X0, CAVE_X1 - x),
                                        Math.min(z - CAVE_Z0, CAVE_Z1 - z));
                    if (edge <= 1) {
                        // Pinch the rim: the walls close in over the outer two
                        // columns, which hides the rectangle of the footprint.
                        floor += 2;
                        ceil -= 2;
                    }
                    for (int y = floor + 1; y <= ceil; y++) clear(x, y, z);
                    // Gravel patches break up the floor under the lights.
                    set(x, floor, z, (hash(x, z, 41) % 9) == 0 ? GRAVEL : STONE);
                    set(x, ceil + 1, z, STONE);
                }
            }

            // ── 2. Sunken lava basin ────────────────────────────────────────
            // Sunk two blocks below the floor with a stone lip, so the glow pools
            // into the pit instead of lighting the whole chamber evenly — the
            // thing the first cave pass got wrong.
            int lavaDepth = CAVE_FLOOR - 2;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int d2 = dx * dx + dz * dz;
                    int x = CAVE_LAVA_X + dx, z = CAVE_LAVA_Z + dz;
                    if (d2 <= 9) {
                        set(x, lavaDepth - 1, z, STONE);
                        set(x, lavaDepth, z, LAVA);
                        clear(x, lavaDepth + 1, z);
                        clear(x, lavaDepth + 2, z);
                    } else if (d2 <= 13) {
                        // Lip: solid rock at the old floor height.
                        set(x, CAVE_FLOOR, z, COBBLESTONE);
                        set(x, CAVE_FLOOR - 1, z, STONE);
                    }
                }
            }

            // ── 4. Waterfall into a drip pool at the west end ───────────────
            int wfX = CAVE_X0 + 6, wfZ = CAVE_Z0 + 6;
            int wfTop = CAVE_CEIL - (hash(wfX, wfZ, 11) & 3) - 1;
            for (int y = CAVE_FLOOR + 1; y <= wfTop; y++) set(wfX, y, wfZ, WATER);
            set(wfX, wfTop + 1, wfZ, STONE);
            fill(wfX - 2, CAVE_FLOOR, wfZ - 2, wfX + 2, CAVE_FLOOR, wfZ + 2, WATER);
            for (int x = wfX - 3; x <= wfX + 3; x++) {
                set(x, CAVE_FLOOR, wfZ - 3, COBBLESTONE);
                set(x, CAVE_FLOOR, wfZ + 3, COBBLESTONE);
            }
            for (int z = wfZ - 3; z <= wfZ + 3; z++) {
                set(wfX - 3, CAVE_FLOOR, z, COBBLESTONE);
                set(wfX + 3, CAVE_FLOOR, z, COBBLESTONE);
            }

            // ── 5. Torches on posts by the stairs ───────────────────────────
            int[][] posts = {{-2, CAVE_Z0 + 5}, {4, CAVE_Z0 + 5}};
            for (int[] p : posts) {
                set(p[0], CAVE_FLOOR + 1, p[1], COBBLESTONE);
                set(p[0], CAVE_FLOOR + 2, p[1], COBBLESTONE);
                set(p[0], CAVE_FLOOR + 3, p[1], TORCH);
            }

            // ── 6. Ore veins in the walls ───────────────────────────────────
            // Only wall cells: the first pass scattered ore into open air and the
            // chamber looked like it had been furnished with floating cubes.
            int[] ores = {COAL_ORE, IRON_ORE, GOLD_ORE, COAL_ORE, DIAMOND_ORE};
            for (int i = 0; i < 40; i++) {
                int along = i * 3 + (hash(i, i * 7, 5) & 7);
                int y = CAVE_FLOOR + 2 + (hash(i, i * 3, 71) % 5);
                int ore = ores[i % ores.length];
                switch (i & 3) {
                    case 0: set(CAVE_X0 - 1, y, CAVE_Z0 + 2 + along % (CAVE_Z1 - CAVE_Z0 - 4), ore); break;
                    case 1: set(CAVE_X1 + 1, y, CAVE_Z0 + 2 + along % (CAVE_Z1 - CAVE_Z0 - 4), ore); break;
                    case 2: set(CAVE_X0 + 2 + along % (CAVE_X1 - CAVE_X0 - 4), y, CAVE_Z0 - 1, ore); break;
                    default: set(CAVE_X0 + 2 + along % (CAVE_X1 - CAVE_X0 - 4), y, CAVE_Z1 + 1, ore); break;
                }
            }

            // ── 7. Stalactites, stalagmites and boulders ────────────────────
            for (int x = CAVE_X0 + 2; x <= CAVE_X1 - 2; x++) {
                for (int z = CAVE_Z0 + 2; z <= CAVE_Z1 - 2; z++) {
                    int edge = Math.min(Math.min(x - CAVE_X0, CAVE_X1 - x),
                                        Math.min(z - CAVE_Z0, CAVE_Z1 - z));
                    if (edge < 3) continue;
                    int ceil = CAVE_CEIL - (hash(x, z, 11) & 3);
                    int floor = CAVE_FLOOR + (hash(x, z, 23) & 1);
                    int h = hash(x, z, 97) % 17;
                    if (h == 0) set(x, ceil, z, STONE);              // stalactite
                    if (h == 1) set(x, ceil - 1, z, STONE);
                    if (h == 3) set(x, floor + 1, z, COBBLESTONE);    // stalagmite
                    if (h == 4) set(x, floor + 1, z, STONE);
                }
            }

            // ── 8. Stairwell from the plain ─────────────────────────────────
            // Cut last so the scattered stalagmites above cannot land on a step.
            for (int i = 0; i <= 18; i++) {
                int stepTop = G - i;
                int z = 14 + i;
                for (int x = 1; x <= 3; x++) {
                    set(x, stepTop, z, COBBLESTONE);
                    for (int y = stepTop + 1; y <= stepTop + 3; y++) clear(x, y, z);
                }
            }

            // ── 9. Chest on the ledge above the drip pool ───────────────────
            set(wfX + 5, CAVE_FLOOR + 1, wfZ, CHEST);
            chestAt(wfX + 5, CAVE_FLOOR + 1, wfZ,
                    new String[] {"torch", "coal", "iron_ore"}, new int[] {16, 32, 8});
        }

        /** Deterministic per-cell noise: the cave must look the same every launch. */
        private static int hash(int x, int z, int salt) {
            int h = x * 374761393 + z * 668265263 + salt * 2147483647;
            h = (h ^ (h >>> 13)) * 1274126177;
            return (h ^ (h >>> 16)) & 0x7FFFFFFF;
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
