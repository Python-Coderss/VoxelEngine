package com.voxel.world;

import com.voxel.game.ChestManager;

/**
 * Hand-authored Point &amp; Click demo world — a small, self-contained showcase
 * for the MCSM-style point-and-click + billboard system.
 *
 * <p>The central plaza holds every interactable type in one screen: crafting
 * table, furnace, chest, person TV, both portals and the villager / ancient
 * builder pedestals, surrounded by a low wall and lamp posts. The player spawns
 * at the centre so every billboard is visible immediately on entry.</p>
 *
 * <p>Four districts radiate from the plaza along the cardinal paths, so the
 * demo is a place to walk around rather than a single diorama:</p>
 * <ul>
 *   <li><b>Builder's Yard</b> (west) — a doored cottage with a working bench,
 *       furnace, anvil and a stocked chest.</li>
 *   <li><b>Market Row</b> (north) — three awned stalls, chests and a TV lounge
 *       with wool sofas.</li>
 *   <li><b>The Quarry Face</b> (east) — an exposed ore wall with a lit tunnel,
 *       ladder and minecart rail.</li>
 *   <li><b>Sunfield Farm</b> (south) — an irrigation channel, reeds, pumpkins,
 *       melons and a scarecrow.</li>
 * </ul>
 *
 * <p>Like {@link TutorialWorldAuthor}, this writes the whole world eagerly
 * through a {@link Sink}; {@link com.voxel.tools.PointClickWorldExporter}
 * materialises it into a bundled template, and
 * {@link PointClickWorldGenerator} regenerates the same voxels at runtime.</p>
 *
 * <p>World size: 128x128 columns (8x8 chunks), flat, height ~72. The plaza sits
 * at the centre; the rest is open grass so the scene has breathing room.</p>
 */
public final class PointClickWorldAuthor {

    /** Surface height of the flat plaza (player spawns with feet at G+1 = 63). */
    public static final int G = 62;
    /** World extent: -64..63 in X/Z (8x8 chunks = 128x128 blocks). */
    public static final int MIN = -64, MAX = 63;
    /** Authored Y range (base terrain up to G, structures to G+8). */
    public static final int AREA_H = 72;

    // Block IDs (mirror TutorialWorldAuthor).
    private static final int GRASS = 1, STONE = 2, DIRT = 13, SAND = 14, WATER = 15,
            OBSIDIAN = 16, GLOWSTONE = 17, NETHER_PORTAL = 19,
            OAK_LEAVES = 4, OAK_LOG = 5, WOOL = 91, TORCH = 211,
            POPPY = 34, TALLGRASS = 35, DANDELION = 121,
            COAL_ORE = 61, IRON_ORE = 81, GOLD_ORE = 82, DIAMOND_ORE = 83,
            COBBLE = 71, PLANKS = 72, GRAVEL = 54,
            REEDS = 40, PUMPKIN = 42, MELON = 43,
            RAIL_NS = 391, MINECART = 393,
            AETHER_PORTAL = 106, CRAFT_TABLE = 115, FURNACE = 116, CHEST = 118,
            STONE_BRICK = 131, BOOKSHELF = 136, GOLD_BLOCK = 138,
            ANVIL = 456, BEACON = 457, ENCHANTING_TABLE = 458, VILLAGER_TV = 274;

    private static final int OAK_DOOR_LOWER = DecorBlocks.OAK_DOOR_LOWER;
    private static final int OAK_DOOR_UPPER = DecorBlocks.OAK_DOOR_UPPER;
    private static final int OAK_DOOR_LOWER_EW = DecorBlocks.OAK_DOOR_LOWER_EW;
    private static final int OAK_DOOR_UPPER_EW = DecorBlocks.OAK_DOOR_UPPER_EW;
    private static final int LADDER = DecorBlocks.LADDER;

    /** A target that receives every voxel the author places (type + facing). */
    public interface Sink {
        void set(int x, int y, int z, int type, int extra);
    }

    /**
     * Builds the complete demo world into {@code sink} and records the plaza
     * chest inventory into {@code chests}.
     */
    public static void buildAll(Sink sink, ChestManager chests) {
        Author a = new Author(sink, chests);
        a.buildBaseTerrain();
        a.buildPlaza();
        a.buildPaths();
        a.buildBuildersYard();
        a.buildMarketRow();
        a.buildQuarry();
        a.buildFarm();
        a.scatterNature();
    }

    private static final class Author {
        final Sink sink;
        final ChestManager chests;

        Author(Sink sink, ChestManager chests) {
            this.sink = sink;
            this.chests = chests;
        }

        private void set(int x, int y, int z, int b) { sink.set(x, y, z, b, 0); }
        private void place(int x, int y, int z, int b) { sink.set(x, y, z, b, 0); }

        private void buildBaseTerrain() {
            // Flat grass plain under the whole 128x128 area, with dirt + stone
            // beneath so the player can dig if they want.
            for (int x = MIN; x <= MAX; x++) {
                for (int z = MIN; z <= MAX; z++) {
                    set(x, G, z, GRASS);
                    set(x, G - 1, z, DIRT);
                    set(x, G - 2, z, DIRT);
                    set(x, G - 3, z, DIRT);
                    for (int y = G - 4; y >= 1; y--) set(x, y, z, STONE);
                    set(x, 0, z, STONE);
                }
            }
        }

        private void buildPlaza() {
            int x = 0, zz = 0; // spawn at center
            // Stone-brick plaza floor on top of the grass.
            floor(x - 10, zz - 10, x + 10, zz + 10, G, STONE_BRICK);
            // Low ornamental border wall (1 block high — doesn't block billboards).
            for (int a = -10; a <= 10; a++) {
                place(x + a, G + 1, zz - 10, STONE_BRICK);
                place(x + a, G + 1, zz + 10, STONE_BRICK);
                place(x - 10, G + 1, zz + a, STONE_BRICK);
                place(x + 10, G + 1, zz + a, STONE_BRICK);
            }
            // Gateways through the border wall where the four paths leave.
            for (int o = -1; o <= 1; o++) {
                set(o, G + 1, zz - 10, 0);
                set(o, G + 1, zz + 10, 0);
                set(-10, G + 1, o, 0);
                set(10, G + 1, o, 0);
            }
            // ── Interactable stations, clustered so all billboards fit on screen ──
            place(x - 6, G + 1, zz - 4, CRAFT_TABLE);
            place(x - 3, G + 1, zz - 4, FURNACE);
            place(x, G + 1, zz - 4, CHEST);
            chestAt(x, G + 1, zz - 4,
                new String[]{"oak_log","iron_ingot","coal","apple","stick","torch","flint_and_steel"},
                new int[]{16, 8, 16, 4, 16, 12, 1});
            // Nether portal: a full 4-wide x 5-tall frame enclosing a lit 2x3
            // interior, standing on the plaza floor.
            portalFrame(x + 2, x + 5, zz - 5, OBSIDIAN, NETHER_PORTAL);
            // Aether portal, same shape, glowstone frame.
            portalFrame(x + 6, x + 9, zz - 5, GLOWSTONE, AETHER_PORTAL);
            place(x + 2, G + 1, zz - 4, VILLAGER_TV);
            // Enchanting corner — table flanked by bookshelf shelves.
            place(x - 8, G + 1, zz + 2, ENCHANTING_TABLE);
            place(x - 8, G + 1, zz + 1, BOOKSHELF);
            place(x - 8, G + 2, zz + 1, BOOKSHELF);
            place(x - 8, G + 1, zz + 3, BOOKSHELF);
            // Smithy: an anvil on a stone plinth.
            place(x + 6, G, zz + 2, STONE);
            place(x + 6, G + 1, zz + 2, ANVIL);
            // Beacon on a gold plinth at the plaza's east edge.
            place(x + 8, G + 1, zz + 2, GOLD_BLOCK);
            place(x + 8, G + 2, zz + 2, BEACON);
            // Villager + ancient-builder pedestals (the mobs spawn at runtime).
            place(x - 2, G + 1, zz + 4, GOLD_BLOCK);
            place(x - 2, G + 2, zz + 4, GOLD_BLOCK);
            place(x + 2, G + 1, zz + 4, GOLD_BLOCK);
            place(x + 2, G + 2, zz + 4, GOLD_BLOCK);
            // Lamp posts at the four corners of the plaza.
            lampPost(x - 8, G, zz - 8); lampPost(x + 8, G, zz - 8);
            lampPost(x - 8, G, zz + 8); lampPost(x + 8, G, zz + 8);
            // Welcome sign (bookshelves) behind the villager pedestal.
            place(x, G + 1, zz + 6, BOOKSHELF);
            place(x, G + 2, zz + 6, BOOKSHELF);
            place(x + 1, G + 1, zz + 6, BOOKSHELF);
            place(x - 1, G + 1, zz + 6, BOOKSHELF);
        }

        /** Three-wide stone-brick avenues from each plaza gate to its district. */
        private void buildPaths() {
            for (int d = 11; d <= 37; d++) {
                for (int o = -1; o <= 1; o++) {
                    set(d, G, o, STONE_BRICK);
                    set(-d, G, o, STONE_BRICK);
                    set(o, G, d, STONE_BRICK);
                    set(o, G, -d, STONE_BRICK);
                }
                if (d % 9 == 0) {
                    lampPost(-d, G, 2); lampPost(d, G, -2);
                    lampPost(-2, G, -d); lampPost(2, G, d);
                }
            }
        }

        /** West: a small doored cottage that shows the workshop block set. */
        private void buildBuildersYard() {
            int cx = -30, cz = 0;
            floor(cx - 6, cz - 4, cx + 4, cz + 4, G, COBBLE);
            // Plank walls with log corners, 4 high.
            for (int x = cx - 6; x <= cx + 4; x++) {
                for (int z = cz - 4; z <= cz + 4; z++) {
                    boolean rim = x == cx - 6 || x == cx + 4 || z == cz - 4 || z == cz + 4;
                    if (!rim) continue;
                    boolean corner = (x == cx - 6 || x == cx + 4) && (z == cz - 4 || z == cz + 4);
                    for (int y = G + 1; y <= G + 4; y++) {
                        place(x, y, z, corner ? OAK_LOG : PLANKS);
                    }
                }
            }
            // Door on the east wall (wall runs along Z -> non-EW halves).
            set(cx + 4, G + 1, cz, 0);
            set(cx + 4, G + 2, cz, 0);
            place(cx + 4, G + 1, cz, OAK_DOOR_LOWER);
            place(cx + 4, G + 2, cz, OAK_DOOR_UPPER);
            place(cx + 4, G + 3, cz, GLOWSTONE); // lintel lamp
            // Stepped gable roof, supported on the wall ring.
            for (int step = 0; step <= 4; step++) {
                int y = G + 5 + step;
                for (int x = cx - 6 + step; x <= cx + 4 - step; x++) {
                    place(x, y, cz - 4, PLANKS);
                    place(x, y, cz + 4, PLANKS);
                }
                for (int z = cz - 4 + step; z <= cz + 4 - step; z++) {
                    place(cx - 6 + step, y, z, PLANKS);
                    place(cx + 4 - step, y, z, PLANKS);
                }
            }
            // Interior workshop.
            place(cx - 4, G + 1, cz - 2, CRAFT_TABLE);
            place(cx - 2, G + 1, cz - 2, FURNACE);
            place(cx - 2, G + 1, cz + 2, ANVIL);
            place(cx - 5, G + 1, cz + 2, CHEST);
            chestAt(cx - 5, G + 1, cz + 2,
                new String[]{"oak_planks","cobblestone","glass","torch","iron_ingot","lantern"},
                new int[]{32, 32, 8, 16, 8, 4});
            for (int b = 0; b < 3; b++) {
                place(cx - 4 + b, G + 1, cz + 3, BOOKSHELF);
                place(cx - 4 + b, G + 2, cz + 3, BOOKSHELF);
            }
            lampPost(cx + 7, G, cz + 3);
            chestAt(cx + 6, G + 1, cz + 3, new String[]{"torch"}, new int[]{16});
        }

        /** North: market stalls, chests and a TV lounge. */
        private void buildMarketRow() {
            int cx = 0, cz = -30;
            floor(cx - 10, cz - 6, cx + 10, cz + 6, G, GRAVEL);
            // Three awned stalls: plank counter, log posts, wool canopy.
            for (int s = -1; s <= 1; s++) {
                int sx = cx + s * 6;
                int sz = cz - 2;
                for (int dz = -2; dz <= 2; dz++) place(sx + dz, G + 1, sz, PLANKS);
                for (int dz = -2; dz <= 2; dz += 4) {
                    place(sx + dz, G + 2, sz - 2, OAK_LOG);
                    place(sx + dz, G + 2, sz + 2, OAK_LOG);
                    place(sx + dz, G + 3, sz - 2, OAK_LOG);
                    place(sx + dz, G + 3, sz + 2, OAK_LOG);
                }
                for (int dz = -2; dz <= 2; dz++) {
                    place(sx + dz, G + 4, sz - 2, WOOL);
                    place(sx + dz, G + 4, sz + 2, WOOL);
                    place(sx + dz, G + 4, sz, WOOL);
                }
                place(sx, G + 1, sz + 4, CHEST);
                chestAt(sx, G + 1, sz + 4,
                        s == 0 ? new String[]{"apple","bread","cake"} : new String[]{"emerald","gold_ingot","stick"},
                        s == 0 ? new int[]{8, 8, 1} : new int[]{8, 8, 16});
                place(sx + 1, G + 1, sz, GLOWSTONE);
            }
            // TV lounge on the east half: a screen facing wool sofas.
            place(cx + 8, G + 1, cz - 4, VILLAGER_TV);
            for (int dz = -1; dz <= 1; dz++) {
                place(cx + 8, G + 1, cz - 1 + dz, WOOL);
                place(cx + 8, G + 2, cz - 1 + dz, WOOL);
                place(cx + 7, G + 1, cz - 1 + dz, WOOL);
            }
            place(cx + 6, G + 1, cz + 2, BOOKSHELF);
            place(cx + 6, G + 2, cz + 2, BOOKSHELF);
            lampPost(cx - 9, G, cz + 5);
            lampPost(cx + 9, G, cz + 5);
        }

        /** East: an exposed ore face with a walk-in tunnel, ladder and rail. */
        private void buildQuarry() {
            int cx = 32, cz = 0;
            // A small stone hill with a west-facing mouth.
            for (int x = cx - 6; x <= cx + 6; x++) {
                for (int z = cz - 6; z <= cz + 6; z++) {
                    int h = 4 - Math.max(0, Math.abs(z) - 2) / 2;
                    for (int y = G + 1; y <= G + h; y++) place(x, y, z, STONE);
                }
            }
            // Carve the walk-in tunnel (2 high, 3 wide) through the hill.
            for (int x = cx - 6; x <= cx + 3; x++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    set(x, G + 1, z, 0);
                    set(x, G + 2, z, 0);
                }
            }
            // Line the tunnel with timber and torches.
            for (int x = cx - 4; x <= cx + 3; x += 2) {
                place(x, G + 1, cz - 2, OAK_LOG);
                place(x, G + 1, cz + 2, OAK_LOG);
                place(x, G + 2, cz, TORCH);
            }
            // Ore face around the mouth.
            place(cx - 6, G + 1, cz - 3, COAL_ORE);
            place(cx - 6, G + 2, cz - 3, COAL_ORE);
            place(cx - 6, G + 1, cz + 3, IRON_ORE);
            place(cx - 6, G + 2, cz + 3, IRON_ORE);
            place(cx - 6, G + 3, cz - 2, GOLD_ORE);
            place(cx - 6, G + 3, cz + 2, DIAMOND_ORE);
            // Rail spur and a parked cart at the back of the tunnel.
            for (int x = cx - 3; x <= cx + 3; x++) {
                set(x, G, cz, GRAVEL);
                place(x, G + 1, cz, RAIL_NS);
            }
            place(cx + 4, G + 1, cz, MINECART);
            // Ladder shaft down to a lit seam room.
            for (int d = 1; d <= 6; d++) {
                set(cx + 5, G + 1 - d, cz, LADDER);
                if ((d & 1) == 1) place(cx + 5, G + 1 - d, cz + 1, OAK_LOG);
            }
            for (int x = cx + 1; x <= cx + 6; x++) {
                set(x, G - 6, cz, 0);
                set(x, G - 5, cz, 0);
                set(x, G - 6, cz + 1, 0);
                place(x, G - 5, cz + 1, TORCH);
            }
            place(cx + 2, G - 6, cz + 1, COAL_ORE);
            place(cx + 3, G - 6, cz + 1, IRON_ORE);
            place(cx + 5, G - 6, cz + 1, DIAMOND_ORE);
            place(cx - 5, G + 1, cz + 4, CHEST);
            chestAt(cx - 5, G + 1, cz + 4,
                new String[]{"stone_pickaxe","iron_pickaxe","torch","rail","minecart"},
                new int[]{2, 1, 16, 16, 2});
            lampPost(cx - 8, G, cz - 4);
        }

        /** South: an irrigation channel with reeds, pumpkins, melons and a scarecrow. */
        private void buildFarm() {
            int cx = 0, cz = 30;
            floor(cx - 10, cz - 5, cx + 10, cz + 5, G, SAND);
            // Water channel down the middle, with grass banks either side.
            for (int x = cx - 8; x <= cx + 8; x++) set(x, G, cz, WATER);
            for (int x = cx - 8; x <= cx + 8; x += 2) {
                place(x, G + 1, cz - 4, REEDS);
                place(x, G + 2, cz - 4, REEDS);
                place(x, G + 1, cz + 4, PUMPKIN);
                place(x, G + 1, cz + 3, MELON);
                place(x + 1, G + 1, cz - 2, OAK_LOG);
                place(x, G + 1, cz + 2, REEDS);
            }
            // Scarecrow: post, crossbar, pumpkin head, wool shirt.
            place(cx + 7, G + 1, cz - 3, OAK_LOG);
            place(cx + 7, G + 2, cz - 3, OAK_LOG);
            place(cx + 6, G + 2, cz - 3, OAK_LOG);
            place(cx + 8, G + 2, cz - 3, OAK_LOG);
            place(cx + 7, G + 3, cz - 3, PUMPKIN);
            place(cx + 7, G + 1, cz - 2, WOOL);
            place(cx + 7, G + 1, cz + 1, CHEST);
            chestAt(cx + 7, G + 1, cz + 1,
                new String[]{"wheat","wheat_seeds","pumpkin","melon","bread","water_bucket"},
                new int[]{16, 16, 8, 8, 8, 1});
            lampPost(cx - 9, G, cz + 4);
            lampPost(cx + 9, G, cz - 4);
        }

        /** Deterministic trees and flowers fill the grass without touching builds. */
        private void scatterNature() {
            java.util.Random rnd = new java.util.Random(20261017L);
            for (int i = 0; i < 26; i++) {
                double a = rnd.nextDouble() * Math.PI * 2.0;
                double r = 20 + rnd.nextDouble() * 26.0;
                int x = (int) Math.round(Math.cos(a) * r);
                int z = (int) Math.round(Math.sin(a) * r);
                if (freeGround(x, z)) tree(x, z);
            }
            for (int i = 0; i < 60; i++) {
                int x = rnd.nextInt(100) - 50;
                int z = rnd.nextInt(100) - 50;
                if (!freeGround(x, z)) continue;
                int flower = (i % 5 == 0) ? DANDELION : (i % 3 == 0 ? POPPY
                        : (i % 2 == 0 ? TALLGRASS : REEDS));
                place(x, G + 1, z, flower);
            }
        }

        /** Grass that no district, path or test column occupies. */
        private boolean freeGround(int x, int z) {
            if (Math.abs(x) > 44 || Math.abs(z) > 44) return false; // keep the far test chunk empty
            if (Math.abs(x) < 12 && Math.abs(z) < 12) return false; // plaza
            if (Math.abs(x) < 12 && z <= -22 && z >= -38) return false; // market
            if (x >= -38 && x <= -24 && Math.abs(z) <= 6) return false;  // builder's yard
            if (x >= 24 && x <= 40 && Math.abs(z) <= 8) return false;    // quarry
            if (Math.abs(x) <= 12 && z >= 24 && z <= 38) return false;   // farm
            if (Math.abs(x) <= 2 || Math.abs(z) <= 2) return false;      // avenues
            return true;
        }

        private void tree(int x, int z) {
            int h = 4 + Math.floorMod(x * 31 + z * 17, 2);
            for (int y = G + 1; y <= G + h; y++) place(x, y, z, OAK_LOG);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int rr = dx * dx + dz * dz;
                    if (rr > 4) continue;
                    place(x + dx, G + h, z + dz, OAK_LEAVES);
                    if (rr <= 2) place(x + dx, G + h + 1, z + dz, OAK_LEAVES);
                }
            }
            place(x, G + h + 2, z, OAK_LEAVES);
        }

        private void floor(int x0, int z0, int x1, int z1, int y, int b) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) place(x, y, z, b);
        }

        private void lampPost(int x, int y, int z) {
            place(x, y + 1, z, STONE_BRICK);
            place(x, y + 2, z, STONE_BRICK);
            place(x, y + 3, z, GLOWSTONE);
        }

        /**
         * Builds a proper standing portal: a 4-wide by 5-tall frame spanning X
         * (bottom course, top course and two side columns) with a lit 2-wide by
         * 3-tall interior. The player steps onto the bottom course, so their
         * body voxel is a portal block and the normal teleport triggers.
         */
        private void portalFrame(int fx0, int fx1, int z, int frame, int portal) {
            for (int i = fx0; i <= fx1; i++) {
                place(i, G, z, frame);
                place(i, G + 4, z, frame);
            }
            for (int y = G + 1; y <= G + 3; y++) {
                place(fx0, y, z, frame);
                place(fx1, y, z, frame);
            }
            for (int y = G + 1; y <= G + 3; y++)
                for (int i = fx0 + 1; i <= fx1 - 1; i++) place(i, y, z, portal);
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
