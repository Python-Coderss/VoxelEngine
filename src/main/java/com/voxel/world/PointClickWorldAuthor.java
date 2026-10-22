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
 * <p>A detail pass fills the quadrants between those districts so the map reads
 * as a place rather than four dioramas: a three-cottage <b>hamlet</b> with a
 * well and vegetable plot (north-west), a reed-fringed <b>pond</b> with a jetty,
 * rowboat and fishing hut (far north-west), a fenced <b>orchard</b> and a
 * traveller's camp (south-west), a spruce <b>farmstead</b> with barn, paddock
 * and windmill west of the fields, a timber <b>head-frame</b>, spoil heap and
 * site office at the quarry, a two-storey watchtower interior, a broken
 * colonnade and fallen statue in the ruins, and benches, banners, planters and
 * signposts along the plaza, market and avenues.</p>
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
            ANVIL = 456, BEACON = 457, ENCHANTING_TABLE = 458, VILLAGER_TV = 274,
            BIRCH_LOG = 46, SPRUCE_LOG = 47, JUNGLE_LOG = 49, DARK_OAK_LOG = 52,
            BIRCH_PLANKS = 74, SPRUCE_PLANKS = 73, DARK_OAK_PLANKS = 77,
            SANDSTONE = 59, BRICK = 130, MOSSY_COBBLE = 132, ANDESITE = 133,
            DIORITE = 134, GRANITE = 135, CLAY = 55, WATERLILY = 41,
            TULIP = 62, AZURE_BLUET = 63, FERN = 64, ROSE = 122, DEAD_BUSH = 36,
            BROWN_MUSHROOM = 37, RED_MUSHROOM = 38, VINE = 44,
            OAK_STAIRS = 200, SLAB_OAK = 206, SLAB_COBBLE = 207,
            SLAB_STONE_BRICK = 208, IRON_BARS = 471, SEA_LANTERN = 470,
            MARBLE = 926, TILE = 924, IRON_BLOCK = 137,
            BIRCH_LEAVES = 4, SPRUCE_LEAVES = 48, JUNGLE_LEAVES = 50,
            DARK_OAK_LEAVES = 53, GLASS = 3, POPPY_ALT = 34;

    private static final int BANNER_NS = DecorBlocks.BANNER_LOWER_NS;
    private static final int BANNER_NS_TOP = DecorBlocks.BANNER_UPPER_NS;
    private static final int BANNER_EW = DecorBlocks.BANNER_LOWER_EW;
    private static final int BANNER_EW_TOP = DecorBlocks.BANNER_UPPER_EW;
    private static final int FLOWER_POT = DecorBlocks.FLOWER_POT;
    private static final int SPRUCE_DOOR_LOWER = DecorBlocks.SPRUCE_DOOR_LOWER;
    private static final int SPRUCE_DOOR_UPPER = DecorBlocks.SPRUCE_DOOR_UPPER;
    private static final int BIRCH_DOOR_LOWER = DecorBlocks.BIRCH_DOOR_LOWER;
    private static final int BIRCH_DOOR_UPPER = DecorBlocks.BIRCH_DOOR_UPPER;
    private static final int BIRCH_DOOR_LOWER_EW = DecorBlocks.BIRCH_DOOR_LOWER_EW;
    private static final int BIRCH_DOOR_UPPER_EW = DecorBlocks.BIRCH_DOOR_UPPER_EW;
    private static final int SPRUCE_DOOR_LOWER_EW = DecorBlocks.SPRUCE_DOOR_LOWER_EW;
    private static final int SPRUCE_DOOR_UPPER_EW = DecorBlocks.SPRUCE_DOOR_UPPER_EW;
    private static final int DARK_OAK_DOOR_LOWER_EW = DecorBlocks.DARK_OAK_DOOR_LOWER_EW;
    private static final int DARK_OAK_DOOR_UPPER_EW = DecorBlocks.DARK_OAK_DOOR_UPPER_EW;
    private static final int BED_FOOT_NORTH = DecorBlocks.BED_FOOT_NORTH;
    private static final int BED_HEAD_NORTH = DecorBlocks.BED_HEAD_NORTH;

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
        a.buildFountain();
        a.buildWatchtower();
        a.buildRuinsGarden();
        a.buildStoneCircle();
        a.buildGraveyard();
        // Detail pass: the hamlet, waterside, orchard, farmstead and the
        // small props that make the districts read as lived-in places.
        a.buildHamlet();
        a.buildPond();
        a.buildOrchardAndCamp();
        a.buildFarmstead();
        a.buildQuarryWorks();
        a.buildDistrictProps();
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

        /**
         * Plaza centrepiece: a low stone-brick fountain basin just north of the
         * spawn. The spawn cell (0, G+1, 0) itself stays clear so the player
         * never starts inside water.
         */
        private void buildFountain() {
            int cx = 0, cz = -8;
            for (int x = cx - 3; x <= cx + 3; x++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    boolean rim = x == cx - 3 || x == cx + 3 || z == cz - 1 || z == cz + 1;
                    set(x, G, z, rim ? STONE_BRICK : WATER);
                }
            }
            // Corner lamps + a glowstone heart in the middle of the pool.
            for (int dx = -3; dx <= 3; dx += 6) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    place(cx + dx, G + 1, cz + dz, STONE_BRICK);
                    place(cx + dx, G + 2, cz + dz, GLOWSTONE);
                }
            }
            place(cx, G, cz, GLOWSTONE);
            place(cx, G + 1, cz, WATER);
            // Benches facing the water on both long sides.
            for (int dx = -2; dx <= 2; dx += 2) {
                place(cx + dx, G + 1, cz - 3, PLANKS);
                place(cx + dx, G + 1, cz + 3, PLANKS);
            }
        }

        /**
         * North-east watchtower: a cobble keep on a stone-brick plinth with a
         * ladder up the inside, a lit beacon platform and a banner.
         */
        private void buildWatchtower() {
            int cx = 30, cz = -30;
            floor(cx - 4, cz - 4, cx + 4, cz + 4, G, COBBLE);
            // Square shell, corner logs. Capped at G+9 so the whole tower fits
            // inside AREA_H (72) — anything taller is clipped when exported.
            for (int y = G + 1; y <= G + 7; y++) {
                for (int x = cx - 3; x <= cx + 3; x++) {
                    for (int z = cz - 3; z <= cz + 3; z++) {
                        boolean rim = Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3;
                        if (!rim) continue;
                        boolean corner = Math.abs(x - cx) == 3 && Math.abs(z - cz) == 3;
                        place(x, y, z, corner ? OAK_LOG : COBBLE);
                    }
                }
            }
            // Door + windows.
            set(cx - 3, G + 1, cz, 0);
            set(cx - 3, G + 2, cz, 0);
            for (int y = G + 4; y <= G + 7; y += 3) {
                place(cx + 3, y, cz, GLOWSTONE);
                place(cx, y, cz + 3, GLOWSTONE);
            }
            // Ladder shaft up the west wall.
            for (int y = G + 1; y <= G + 7; y++) set(cx - 2, y, cz, LADDER);
            // Battlement + beacon platform.
            for (int x = cx - 3; x <= cx + 3; x++) {
                for (int z = cz - 3; z <= cz + 3; z++) {
                    if (Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3) place(x, G + 8, z, COBBLE);
                }
            }
            place(cx, G + 8, cz, GOLD_BLOCK);
            place(cx, G + 9, cz, BEACON);
            // Banner mast on the north corner.
            place(cx + 3, G + 9, cz - 3, WOOL);
            // Stone-brick spur joining the tower to the plaza's avenue.
            for (int d = 11; d <= 26; d++) {
                for (int o = -1; o <= 1; o++) set(12 + o, G, -d, STONE_BRICK);
            }
            lampPost(cx - 5, G, cz + 4);
            lampPost(cx + 4, G, cz - 5);
        }

        /**
         * South-east ruins garden: a broken ancient arch over a reflecting
         * pool, toppled columns, flower beds and a reading bench.
         */
        private void buildRuinsGarden() {
            int cx = 30, cz = 30;
            floor(cx - 6, cz - 6, cx + 6, cz + 6, G, SAND);
            // Reflecting pool in the middle (rim of stone bricks).
            for (int x = cx - 3; x <= cx + 3; x++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    boolean rim = Math.abs(x - cx) == 3 || Math.abs(z - cz) == 2;
                    set(x, G, z, rim ? STONE_BRICK : WATER);
                }
            }
            // Standing arch on the north edge.
            for (int y = G + 1; y <= G + 5; y++) {
                place(cx - 3, y, cz - 5, STONE_BRICK);
                place(cx + 3, y, cz - 5, STONE_BRICK);
            }
            for (int x = cx - 3; x <= cx + 3; x++) place(x, G + 6, cz - 5, STONE_BRICK);
            place(cx, G + 7, cz - 5, GLOWSTONE);
            // Toppled columns and rubble.
            for (int i = 0; i < 4; i++) {
                place(cx - 5 + i, G + 1, cz + 4, STONE_BRICK);
                place(cx + 5 - i, G + 1, cz + 5, i % 2 == 0 ? STONE_BRICK : COBBLE);
            }
            place(cx - 5, G + 1, cz + 3, COBBLE);
            place(cx + 4, G + 1, cz + 4, COBBLE);
            // Flower beds flanking the pool.
            for (int z = cz - 1; z <= cz + 1; z++) {
                place(cx - 5, G + 1, z, z % 2 == 0 ? POPPY : DANDELION);
                place(cx + 5, G + 1, z, z % 2 == 0 ? DANDELION : TALLGRASS);
            }
            // Reading corner: bench + bookshelf + chest with old maps.
            place(cx - 4, G + 1, cz - 4, BOOKSHELF);
            place(cx - 4, G + 2, cz - 4, BOOKSHELF);
            place(cx - 2, G + 1, cz - 4, CHEST);
            chestAt(cx - 2, G + 1, cz - 4,
                new String[]{"bread","oak_log","iron_ingot","stick","torch"},
                new int[]{4, 8, 4, 16, 8});
            lampPost(cx + 5, G, cz - 4);
            // Spur path from the avenue.
            for (int d = 11; d <= 26; d++) {
                for (int o = -1; o <= 1; o++) set(12 + o, G, d, STONE_BRICK);
            }
        }

        // ── Detail pass ────────────────────────────────────────────────────

        /**
         * A small cottage: floor, walled shell with log corners, a south-facing
         * door, a hip roof and a furnished interior. Bounds are inclusive.
         */
        private void house(int x0, int z0, int x1, int z1, int wall, int floorBlock,
                           int doorLower, int doorUpper) {
            int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
            floor(x0, z0, x1, z1, G, floorBlock);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean rim = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!rim) continue;
                    boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                    for (int y = G + 1; y <= G + 4; y++) {
                        place(x, y, z, corner ? OAK_LOG : wall);
                    }
                }
            }
            // South-facing door (the wall runs along X, so use the EW panel).
            set(cx, G + 1, z1, 0);
            set(cx, G + 2, z1, 0);
            place(cx, G + 1, z1, doorLower);
            place(cx, G + 2, z1, doorUpper);
            place(cx, G + 3, z1, GLOWSTONE); // lintel lamp
            // Windows either side of the entrance.
            place(x0, G + 3, cz, GLASS);
            place(x1, G + 3, cz, GLASS);
            place(cx, G + 3, z0, GLASS);
            // Hip roof: each level steps one block in and one block up.
            for (int k = 0; k <= 3; k++) {
                int y = G + 5 + k;
                if (x0 + k > x1 - k || z0 + k > z1 - k) break;
                for (int x = x0 + k; x <= x1 - k; x++) {
                    place(x, y, z0 + k, PLANKS);
                    place(x, y, z1 - k, PLANKS);
                }
                for (int z = z0 + k; z <= z1 - k; z++) {
                    place(x0 + k, y, z, PLANKS);
                    place(x1 - k, y, z, PLANKS);
                }
            }
            // Stone chimney on the north-east corner, grounded beside the wall.
            for (int y = G + 1; y <= G + 7; y++) place(x1 + 1, y, z0, BRICK);
            place(x1 + 1, G + 8, z0, GLOWSTONE);
            // Furnishings: bed, chest, workbench and a flower pot.
            if (cx - 2 > x0) {
                place(cx - 2, G + 1, cz, BED_FOOT_NORTH);
                place(cx - 2, G + 1, cz - 1, BED_HEAD_NORTH);
            }
            place(x1 - 1, G + 1, z1 - 1, CHEST);
            chestAt(x1 - 1, G + 1, z1 - 1,
                    new String[]{"bread","torch","oak_planks","apple"},
                    new int[]{4, 8, 16, 4});
            place(x1 - 1, G + 1, z0 + 1, CRAFT_TABLE);
            place(cx - 1, G + 1, z0 + 1, FLOWER_POT);
            place(cx - 1, G + 2, z0 + 1, POPPY);
            place(cx, G + 1, z1 - 1, OAK_STAIRS);
            place(cx, G + 4, cz, GLOWSTONE); // ceiling lamp under the ridge
        }

        private void bench(int x, int z, boolean alongX, int length) {
            for (int i = 0; i < length; i++) {
                int bx = alongX ? x + i : x;
                int bz = alongX ? z : z + i;
                place(bx, G + 2, bz, PLANKS);
                if (i % 2 == 0) place(bx, G + 1, bz, OAK_LOG);
            }
        }

        private void planter(int x, int z, int flower) {
            place(x, G + 1, z, BRICK);
            place(x, G + 2, z, flower);
        }

        private void bannerPost(int x, int z, boolean alongX) {
            place(x, G + 1, z, OAK_LOG);
            place(x, G + 2, z, alongX ? BANNER_EW : BANNER_NS);
            place(x, G + 3, z, alongX ? BANNER_EW_TOP : BANNER_NS_TOP);
        }

        private void signpost(int x, int z) {
            place(x, G + 1, z, OAK_LOG);
            place(x, G + 2, z, OAK_LOG);
            place(x, G + 3, z, PLANKS);
            place(x + 1, G + 3, z, PLANKS);
            place(x, G + 3, z + 1, PLANKS);
        }

        private void hedge(int x0, int z0, int x1, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) place(x, G + 1, z, OAK_LEAVES);
        }

        /**
         * North-west: a three-cottage hamlet around a green with a well, a
         * vegetable plot, a laundry line and a hand cart. Connected to the
         * open ground west of the plaza by a cobbled lane.
         */
        private void buildHamlet() {
            for (int x = -44; x <= -14; x++)
                for (int z = -21; z <= -19; z++) set(x, G, z, GRAVEL);
            for (int z = -34; z <= -20; z++) set(-28, G, z, COBBLE);
            floor(-33, -26, -23, -18, G, GRASS);
            floor(-32, -25, -24, -19, G, GRAVEL);
            house(-36, -27, -30, -23, PLANKS, COBBLE,
                    OAK_DOOR_LOWER_EW, OAK_DOOR_UPPER_EW);
            house(-25, -32, -19, -28, BIRCH_PLANKS, COBBLE,
                    BIRCH_DOOR_LOWER_EW, BIRCH_DOOR_UPPER_EW);
            house(-38, -39, -32, -33, DARK_OAK_PLANKS, COBBLE,
                    SPRUCE_DOOR_LOWER_EW, SPRUCE_DOOR_UPPER_EW);
            well(-28, -22);
            // Fenced vegetable plot on the green's east side.
            for (int x = -24; x <= -20; x++) {
                set(x, G, -17, DIRT);
                set(x, G, -16, DIRT);
                for (int z = -17; z <= -16; z++) {
                    if (x == -24 || x == -20) place(x, G + 1, z, OAK_LOG);
                    else place(x, G + 1, z, (x + z) % 2 == 0 ? PUMPKIN : MELON);
                }
            }
            for (int x = -24; x <= -20; x++) place(x, G + 1, -15, OAK_LOG);
            // Laundry line between two posts.
            for (int y = G + 1; y <= G + 4; y++) {
                place(-33, y, -18, OAK_LOG);
                place(-30, y, -18, OAK_LOG);
            }
            place(-32, G + 4, -18, WOOL);
            place(-31, G + 4, -18, WOOL);
            // Hand cart: plank bed, log wheels and a crate.
            place(-24, G + 1, -20, PLANKS);
            place(-23, G + 1, -20, PLANKS);
            place(-24, G + 2, -20, CHEST);
            place(-25, G + 1, -19, OAK_LOG);
            place(-22, G + 1, -19, OAK_LOG);
            bench(-31, -22, true, 3);
            bench(-25, -26, false, 3);
            planter(-27, -25, TULIP);
            planter(-23, -23, ROSE);
            bannerPost(-16, -18, false);
            bannerPost(-16, -22, false);
            signpost(-18, -19);
            lampPost(-39, G, -19);
            lampPost(-28, G, -32);
            lampPost(-21, G, -18);
        }

        private void well(int cx, int cz) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    boolean rim = Math.abs(x - cx) == 1 || Math.abs(z - cz) == 1;
                    set(x, G, z, rim ? COBBLE : WATER);
                    if (rim) place(x, G + 1, z, COBBLE);
                }
            }
            for (int y = G + 2; y <= G + 3; y++) {
                place(cx - 1, y, cz, OAK_LOG);
                place(cx + 1, y, cz, OAK_LOG);
            }
            floor(cx - 1, cz - 1, cx + 1, cz + 1, G + 4, PLANKS);
            place(cx, G + 5, cz, GLOWSTONE);
        }

        /** A tree of one of four silhouettes, for the orchard and the wilds. */
        private void treeVariety(int x, int z, int kind) {
            int log, leaf, h;
            switch (Math.floorMod(kind, 4)) {
                case 0: log = OAK_LOG; leaf = OAK_LEAVES; h = 4; break;
                case 1: log = BIRCH_LOG; leaf = BIRCH_LEAVES; h = 5; break;
                case 2: log = SPRUCE_LOG; leaf = SPRUCE_LEAVES; h = 6; break;
                default: log = DARK_OAK_LOG; leaf = DARK_OAK_LEAVES; h = 4; break;
            }
            for (int y = G + 1; y <= G + h; y++) place(x, y, z, log);
            int top = G + h;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int rr = dx * dx + dz * dz;
                    if (rr > 5) continue;
                    place(x + dx, top - 1, z + dz, leaf);
                    if (rr <= 2) place(x + dx, top, z + dz, leaf);
                }
            }
            place(x, top + 1, z, leaf);
        }

        private void tent(int cx, int cz, int cloth) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    if (x == cx && z == cz + 1) continue; // doorway
                    place(x, G + 1, z, cloth);
                    place(x, G + 2, z, cloth);
                }
            }
            place(cx, G + 3, cz, cloth);
            place(cx - 1, G + 1, cz, PLANKS);
            place(cx + 1, G + 1, cz, PLANKS);
        }

        /**
         * North-west corner: an irregular reed-fringed pond with a jetty, a
         * rowboat and a stilted fishing hut on the shore.
         */
        private void buildPond() {
            int cx = -45, cz = -40;
            for (int x = cx - 7; x <= cx + 7; x++) {
                for (int z = cz - 5; z <= cz + 5; z++) {
                    double dx = (x - cx) / 7.0, dz = (z - cz) / 5.0;
                    double d = dx * dx + dz * dz;
                    if (d <= 0.85) set(x, G, z, WATER);
                    else if (d <= 1.35) set(x, G, z, SAND);
                }
            }
            // Reeds, clay and lily pads around the water's edge.
            for (int z = cz - 4; z <= cz + 4; z += 2) {
                place(cx - 7, G + 1, z, REEDS);
                place(cx - 7, G + 2, z, REEDS);
                place(cx + 7, G + 1, z, REEDS);
            }
            for (int x = cx - 4; x <= cx + 4; x += 3) {
                place(x, G + 1, cz - 4, REEDS);
                place(x, G + 1, cz + 4, REEDS);
            }
            set(cx - 2, G, cz - 2, CLAY);
            set(cx + 1, G, cz + 2, CLAY);
            set(cx - 4, G + 1, cz + 1, WATERLILY);
            set(cx + 2, G + 1, cz - 2, WATERLILY);
            set(cx + 4, G + 1, cz + 3, WATERLILY);
            // Jetty reaching out from the south shore, on log piles.
            for (int z = -42; z <= -34; z++) {
                place(cx, G + 1, z, PLANKS);
                if ((z & 1) == 0) set(cx, G, z, OAK_LOG);
            }
            place(cx - 1, G + 1, -42, PLANKS);
            place(cx + 1, G + 1, -42, PLANKS);
            // Rowboat moored beside the jetty.
            for (int x = cx - 4; x <= cx - 2; x++) {
                place(x, G + 1, -38, PLANKS);
                place(x, G + 1, -36, PLANKS);
            }
            place(cx - 4, G + 1, -37, PLANKS);
            place(cx - 2, G + 1, -37, PLANKS);
            place(cx - 3, G + 2, -37, OAK_STAIRS);
            // Stilted fishing hut on the east shore.
            house(-37, -47, -33, -44, PLANKS, COBBLE, OAK_DOOR_LOWER_EW, OAK_DOOR_UPPER_EW);
            place(-34, G + 1, -43, CHEST);
            chestAt(-34, G + 1, -43,
                    new String[]{"bread","reeds","oak_log","torch","water_bucket"},
                    new int[]{4, 16, 8, 8, 1});
            bench(cx - 2, -34, true, 3);
            lampPost(-38, G, -34);
            treeVariety(-51, -34, 1);
            treeVariety(-52, -43, 0);
        }

        /**
         * South-west: a fenced orchard of mixed fruit trees and, beyond it, a
         * traveller's camp with two tents, a fire ring and a washing line.
         */
        private void buildOrchardAndCamp() {
            // Orchard fence with a gate on the east side.
            for (int x = -44; x <= -32; x += 2) {
                place(x, G + 1, 14, OAK_LOG);
                place(x, G + 1, 30, OAK_LOG);
            }
            for (int z = 14; z <= 30; z += 2) {
                place(-44, G + 1, z, OAK_LOG);
                place(-32, G + 1, z, OAK_LOG);
            }
            set(-32, G + 1, 22, 0);
            set(-32, G + 1, 23, 0);
            // Mixed rows, so the orchard shows every wood type.
            int[] xs = {-42, -38, -34};
            int[] zs = {17, 22, 27};
            for (int i = 0; i < xs.length; i++) {
                for (int j = 0; j < zs.length; j++) {
                    treeVariety(xs[i], zs[j], i + j);
                }
            }
            for (int z = 16; z <= 28; z += 3) set(-35, G, z, DIRT);
            // Picking baskets by the gate, plus a bench in the shade.
            place(-31, G + 1, 22, CHEST);
            chestAt(-31, G + 1, 22,
                    new String[]{"apple","bread","oak_log","torch"},
                    new int[]{12, 4, 8, 8});
            place(-41, G + 1, 24, CHEST);
            chestAt(-41, G + 1, 24, new String[]{"apple"}, new int[]{16});
            bench(-37, 15, true, 4);
            bench(-43, 20, false, 4);
            planter(-33, 15, DANDELION);
            planter(-33, 29, POPPY);
            lampPost(-33, G, 17);
            lampPost(-43, G, 29);
            signpost(-30, 24);

            // Traveller's camp on the far south-west lawn.
            tent(-41, 35, WOOL);
            tent(-36, 40, WOOL);
            for (int x = -39; x <= -37; x++)
                for (int z = 37; z <= 39; z++)
                    if (Math.abs(x + 38) == 1 || Math.abs(z - 38) == 1) place(x, G + 1, z, COBBLE);
            set(-38, G, 38, GLOWSTONE);
            bench(-41, 38, false, 2);
            bench(-36, 36, false, 2);
            place(-34, G + 1, 38, CHEST);
            chestAt(-34, G + 1, 38,
                    new String[]{"bread","cooked_beef","torch","water_bucket","oak_log"},
                    new int[]{6, 4, 16, 1, 8});
            bannerPost(-34, 35, false);
            // Drying rack: two posts and a cross pole with cloth.
            for (int y = G + 1; y <= G + 3; y++) {
                place(-43, y, 37, OAK_LOG);
                place(-43, y, 40, OAK_LOG);
            }
            place(-43, G + 4, 38, OAK_LOG);
            place(-43, G + 4, 39, OAK_LOG);
            place(-43, G + 3, 38, WOOL);
            place(-43, G + 3, 39, WOOL);
            lampPost(-34, G, 41);
            treeVariety(-46, 34, 3);
            treeVariety(-48, 40, 2);
        }

        /**
         * The farmstead west of the fields: a spruce barn with a double door,
         * a railed paddock with a trough, a windmill and stacked wool bales.
         */
        private void buildFarmstead() {
            int bx0 = -22, bx1 = -14, bz0 = 26, bz1 = 34;
            floor(bx0, bz0, bx1, bz1, G, COBBLE);
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    boolean rim = x == bx0 || x == bx1 || z == bz0 || z == bz1;
                    if (!rim) continue;
                    boolean corner = (x == bx0 || x == bx1) && (z == bz0 || z == bz1);
                    for (int y = G + 1; y <= G + 4; y++) {
                        place(x, y, z, corner ? OAK_LOG : SPRUCE_PLANKS);
                    }
                }
            }
            // East double door (wall runs along Z -> vanilla panels).
            for (int z = 29; z <= 30; z++) {
                set(bx1, G + 1, z, 0);
                set(bx1, G + 2, z, 0);
                place(bx1, G + 1, z, OAK_DOOR_LOWER);
                place(bx1, G + 2, z, OAK_DOOR_UPPER);
            }
            for (int z = 28; z <= 31; z++) place(bx1, G + 3, z, SPRUCE_PLANKS);
            place(bx1, G + 4, 29, GLOWSTONE);
            // Gable roof (plank shell over the wall ring).
            for (int k = 0; k <= 3; k++) {
                int y = G + 5 + k;
                if (bx0 + k > bx1 - k || bz0 + k > bz1 - k) break;
                for (int x = bx0 + k; x <= bx1 - k; x++) {
                    place(x, y, bz0 + k, SPRUCE_PLANKS);
                    place(x, y, bz1 - k, SPRUCE_PLANKS);
                }
                for (int z = bz0 + k; z <= bz1 - k; z++) {
                    place(bx0 + k, y, z, SPRUCE_PLANKS);
                    place(bx1 - k, y, z, SPRUCE_PLANKS);
                }
            }
            // Interior: feed chests, a bench and a ladder to the loft hatch.
            for (int x = bx0 + 1; x <= bx0 + 2; x++) {
                place(x, G + 1, bz0 + 1, CHEST);
            }
            chestAt(bx0 + 1, G + 1, bz0 + 1,
                    new String[]{"wheat_seeds","bread","apple","torch"},
                    new int[]{16, 8, 8, 8});
            chestAt(bx0 + 2, G + 1, bz0 + 1,
                    new String[]{"oak_log","oak_planks","stick","iron_ingot"},
                    new int[]{16, 32, 16, 4});
            for (int y = G + 1; y <= G + 3; y++) place(bx1 - 1, y, bz0 + 1, LADDER);
            place(bx0 + 4, G + 1, bz1 - 1, CRAFT_TABLE);
            place(bx0 + 6, G + 1, bz1 - 1, FLOWER_POT);
            place(bx0 + 6, G + 2, bz1 - 1, DANDELION);

            // Wool bales on plank pallets beside the door.
            for (int i = 0; i < 3; i++) {
                place(bx1 + 1, G + 1, 27 + i, PLANKS);
                place(bx1 + 1, G + 2, 27 + i, WOOL);
                if (i != 1) place(bx1 + 1, G + 3, 27 + i, WOOL);
            }
            // Railed paddock with a gate, trough and a scarecrow.
            for (int x = -22; x <= -13; x++) {
                for (int z = 36; z <= 42; z++) {
                    boolean rim = x == -22 || x == -13 || z == 36 || z == 42;
                    if (rim && !(z == 36 && x >= -18 && x <= -17)) {
                        place(x, G + 1, z, OAK_LOG);
                        if (((x + z) & 1) == 0) place(x, G + 2, z, SLAB_OAK);
                    }
                }
            }
            for (int x = -21; x <= -18; x++) set(x, G, 41, COBBLE);
            for (int x = -20; x <= -18; x++) set(x, G, 40, STONE_BRICK);
            set(-20, G + 1, 40, STONE_BRICK);
            set(-18, G + 1, 40, STONE_BRICK);
            set(-19, G + 1, 40, WATER);
            place(-15, G + 1, 40, OAK_LOG);
            place(-15, G + 2, 40, OAK_LOG);
            place(-16, G + 2, 40, OAK_LOG);
            place(-14, G + 2, 40, OAK_LOG);
            place(-15, G + 3, 40, PUMPKIN);
            place(-15, G + 1, 41, WOOL);
            // Windmill on the lawn between the barn and the plaza.
            windmill(-20, 21);
            // Stone path from the barn door toward the farm fields.
            for (int x = -13; x <= -11; x++)
                for (int z = 29; z <= 30; z++) set(x, G, z, STONE_BRICK);
            lampPost(-12, G, 26);
            lampPost(-23, G, 34);
            treeVariety(-25, 38, 0);
            treeVariety(-26, 31, 1);
        }

        /** A stone-based windmill with four plank sails, capped inside AREA_H. */
        private void windmill(int cx, int cz) {
            for (int x = cx - 1; x <= cx + 1; x++)
                for (int z = cz - 1; z <= cz + 1; z++)
                    for (int y = G + 1; y <= G + 3; y++) place(x, y, z, STONE_BRICK);
            for (int y = G + 4; y <= G + 6; y++) place(cx, y, cz, OAK_LOG);
            place(cx, G + 7, cz, OAK_LOG);
            for (int d = 1; d <= 2; d++) {
                place(cx + d, G + 7, cz, PLANKS);
                place(cx - d, G + 7, cz, PLANKS);
                place(cx, G + 7, cz + d, PLANKS);
                place(cx, G + 7, cz - d, PLANKS);
                place(cx + d, G + 8, cz, PLANKS);
                place(cx - d, G + 8, cz, PLANKS);
                place(cx, G + 8, cz + d, PLANKS);
                place(cx, G + 8, cz - d, PLANKS);
                if (d == 2) {
                    place(cx + d, G + 9, cz, PLANKS);
                    place(cx - d, G + 9, cz, PLANKS);
                    place(cx, G + 9, cz + d, PLANKS);
                    place(cx, G + 9, cz - d, PLANKS);
                }
            }
            place(cx, G + 8, cz, GLOWSTONE);
        }

        /**
         * East: the working end of the quarry — a timber head-frame over the
         * tunnel with a crane jib and slung crate, a spoil heap, a parked cart,
         * and a site office hut.
         */
        private void buildQuarryWorks() {
            int fx0 = 39, fx1 = 43, fz0 = -2, fz1 = 2;
            for (int y = G + 1; y <= G + 4; y++) {
                place(fx0, y, fz0, OAK_LOG);
                place(fx1, y, fz0, OAK_LOG);
                place(fx0, y, fz1, OAK_LOG);
                place(fx1, y, fz1, OAK_LOG);
            }
            for (int x = fx0; x <= fx1; x++) {
                place(x, G + 5, fz0, OAK_LOG);
                place(x, G + 5, fz1, OAK_LOG);
            }
            for (int z = fz0; z <= fz1; z++) {
                place(fx0, G + 5, z, OAK_LOG);
                place(fx1, G + 5, z, OAK_LOG);
            }
            floor(fx0, fz0, fx1, fz1, G + 6, PLANKS);
            // Crane jib reaching west over the tunnel mouth, chain and crate.
            for (int x = 35; x <= fx0; x++) place(x, G + 6, 0, OAK_LOG);
            for (int y = G + 3; y <= G + 5; y++) place(35, y, 0, IRON_BARS);
            place(35, G + 2, 0, CHEST);
            chestAt(35, G + 2, 0,
                    new String[]{"stone_pickaxe","torch","coal","iron_ore"},
                    new int[]{2, 16, 16, 8});
            // A winch on the deck.
            place(fx0 + 1, G + 7, fz0 + 1, OAK_LOG);
            place(fx0 + 2, G + 7, fz0 + 1, OAK_LOG);
            place(fx0 + 1, G + 8, fz0 + 1, IRON_BLOCK);
            place(fx0 + 2, G + 8, fz0 + 1, IRON_BLOCK);
            // Rail spur out of the tunnel to the head-frame, with a parked cart.
            for (int x = 36; x <= 39; x++) {
                set(x, G, 0, GRAVEL);
                place(x, G + 1, 0, RAIL_NS);
            }
            place(38, G + 1, 0, MINECART);
            // Spoil heap: cobble and gravel mound seeded with low-grade ore.
            for (int x = 40; x <= 45; x++) {
                for (int z = -8; z <= -4; z++) {
                    int d = Math.abs(x - 42) + Math.abs(z + 6);
                    if (d <= 3) set(x, G, z, GRAVEL);
                    if (d <= 2) place(x, G + 1, z, ((x + z) & 1) == 0 ? COBBLE : GRAVEL);
                    if (d == 1) place(x, G + 2, z, COBBLE);
                }
            }
            place(41, G + 2, -6, COAL_ORE);
            place(43, G + 2, -5, IRON_ORE);
            place(44, G + 1, -7, COBBLE);
            // Site office: a small hut with a door, desk and supply chest.
            house(39, 4, 42, 7, PLANKS, COBBLE, OAK_DOOR_LOWER_EW, OAK_DOOR_UPPER_EW);
            chestAt(40, G + 1, 5,
                    new String[]{"torch","stone_pickaxe","rail","iron_ingot"},
                    new int[]{32, 1, 16, 4});
            // Tool stack, spare beams and a lamp by the mouth.
            for (int i = 0; i < 3; i++) {
                place(37, G + 1, 3 + i, OAK_LOG);
                place(37, G + 2, 3 + i, OAK_LOG);
            }
            place(38, G + 1, 3, PLANKS);
            place(38, G + 2, 3, PLANKS);
            signpost(24, 3);
            lampPost(36, G, 8);
            lampPost(45, G, -2);
        }

        /** Benches, planters, banners and signs that dress the existing districts. */
        private void buildDistrictProps() {
            buildPlazaProps();
            buildMarketProps();
            buildWatchtowerInterior();
            buildRuinsProps();
            buildAvenueProps();
        }

        private void buildPlazaProps() {
            // Benches on the east and west sides of the fountain.
            bench(-6, -8, false, 2);
            bench(6, -8, false, 2);
            bench(-7, 8, true, 3);
            bench(4, 8, true, 3);
            // Marble monument north of the farm gate.
            place(-4, G + 1, 8, STONE_BRICK);
            place(-4, G + 2, 8, MARBLE);
            place(-4, G + 3, 8, MARBLE);
            place(-4, G + 4, 8, GOLD_BLOCK);
            place(-4, G + 5, 8, SEA_LANTERN);
            // Notice board and crates by the south gate.
            for (int y = G + 1; y <= G + 2; y++) {
                place(5, y, 9, OAK_LOG);
                place(7, y, 9, OAK_LOG);
            }
            for (int x = 5; x <= 7; x++) place(x, G + 3, 9, PLANKS);
            for (int x = 5; x <= 7; x++) place(x, G + 2, 9, BOOKSHELF);
            place(5, G + 1, 7, PLANKS);
            place(5, G + 2, 7, WOOL);
            place(6, G + 1, 7, PLANKS);
            place(6, G + 2, 7, WOOL);
            place(7, G + 1, 7, CHEST);
            chestAt(7, G + 1, 7,
                    new String[]{"torch","bread","oak_planks","apple"},
                    new int[]{16, 4, 16, 4});
            // Corner planters and hedge runs along the open edges.
            planter(-6, 8, ROSE);
            planter(6, 8, TULIP);
            planter(-9, 6, DANDELION);
            planter(9, 6, POPPY);
            hedge(-9, 9, -9, 9);
            hedge(9, 9, 9, 9);
            for (int z = 5; z <= 8; z += 3) {
                hedge(9, z, 9, z);
                hedge(-9, z, -9, z);
            }
            // Flower pots along the plaza border wall top.
            for (int a = 4; a <= 8; a += 2) {
                place(10, G + 2, a, FLOWER_POT);
                place(10, G + 3, a, a % 4 == 0 ? POPPY : DANDELION);
                place(-10, G + 2, a, FLOWER_POT);
                place(-10, G + 3, a, a % 4 == 0 ? TULIP : ROSE);
            }
            // Banner standards outside the four gateways.
            bannerPost(-11, -2, true);
            bannerPost(-11, 2, true);
            bannerPost(11, -2, true);
            bannerPost(11, 2, true);
            bannerPost(-2, -11, false);
            bannerPost(2, -11, false);
            bannerPost(-2, 11, false);
            bannerPost(2, 11, false);
            // Direction posts at each gate.
            signpost(3, -11);
            signpost(-3, 11);
            signpost(11, 3);
            signpost(-11, -3);
        }

        private void buildMarketProps() {
            // Flower stall on the west end.
            int fx = -11, fz = -33;
            for (int dz = -2; dz <= 2; dz++) place(fx + dz, G + 1, fz + 3, PLANKS);
            for (int dz = -2; dz <= 2; dz += 4) {
                for (int y = G + 2; y <= G + 3; y++) {
                    place(fx + dz, y, fz + 1, OAK_LOG);
                    place(fx + dz, y, fz + 5, OAK_LOG);
                }
            }
            for (int dz = -2; dz <= 2; dz++) {
                place(fx + dz, G + 4, fz + 1, WOOL);
                place(fx + dz, G + 4, fz + 3, WOOL);
                place(fx + dz, G + 4, fz + 5, WOOL);
                place(fx + dz, G + 2, fz + 3, FLOWER_POT);
                place(fx + dz, G + 3, fz + 3, dz % 2 == 0 ? POPPY : TULIP);
            }
            // Bakery oven on the east end: brick shell around a furnace.
            for (int x = 8; x <= 10; x++) {
                for (int z = -27; z <= -25; z++) {
                    place(x, G + 1, z, BRICK);
                    if (x == 9 && z == -26) place(x, G + 1, z, FURNACE);
                }
            }
            place(9, G + 2, -26, BRICK);
            place(9, G + 3, -26, GLOWSTONE);
            place(9, G + 2, -27, CHEST);
            chestAt(9, G + 2, -27,
                    new String[]{"bread","apple","cake","wheat"},
                    new int[]{8, 8, 2, 16});
            // Produce crates and a tasting bench between the stalls.
            for (int x = -4; x <= -3; x++) {
                place(x, G + 1, -27, PLANKS);
                place(x, G + 2, -27, PUMPKIN);
            }
            place(3, G + 1, -27, PLANKS);
            place(3, G + 2, -27, MELON);
            place(4, G + 1, -27, OAK_LOG);
            bench(-2, -26, true, 3);
            // Hanging banners between the stall posts and lamps on the corners.
            bannerPost(-10, -30, false);
            bannerPost(10, -30, false);
            lampPost(-11, G, -24);
            lampPost(11, G, -24);
            signpost(-11, -35);
            signpost(11, -35);
            // TV lounge: a rug and a side table beside the sofas.
            for (int z = -32; z <= -30; z++) set(5, G, z, WOOL);
            place(5, G + 1, -29, PLANKS);
            place(5, G + 2, -29, FLOWER_POT);
            place(6, G + 1, -33, CHEST);
            chestAt(6, G + 1, -33, new String[]{"bread","apple"}, new int[]{8, 8});
        }

        /**
         * The watchtower's two storeys: a planked upper floor with a hatch for
         * the ladder, a bunk, a chest and a workbench, plus buttresses outside.
         */
        private void buildWatchtowerInterior() {
            int cx = 30, cz = -30;
            // Upper floor, leaving the ladder column open.
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    if (x == cx - 2 && z == cz) continue; // ladder column
                    place(x, G + 4, z, PLANKS);
                }
            }
            place(cx + 1, G + 5, cz - 2, BED_FOOT_NORTH);
            place(cx + 1, G + 5, cz - 1, BED_HEAD_NORTH);
            place(cx - 2, G + 5, cz + 1, CHEST);
            chestAt(cx - 2, G + 5, cz + 1,
                    new String[]{"torch","bread","arrow","iron_ingot"},
                    new int[]{16, 4, 16, 4});
            place(cx - 2, G + 5, cz - 1, CRAFT_TABLE);
            place(cx, G + 5, cz - 2, BANNER_NS);
            place(cx, G + 6, cz - 2, BANNER_NS_TOP);
            // Ground floor stores.
            place(cx + 1, G + 1, cz + 1, CHEST);
            chestAt(cx + 1, G + 1, cz + 1,
                    new String[]{"torch","stone_pickaxe","oak_planks","cobblestone"},
                    new int[]{16, 1, 32, 32});
            place(cx - 2, G + 1, cz + 1, PLANKS);
            place(cx - 2, G + 2, cz + 1, WOOL);
            place(cx + 2, G + 1, cz - 2, PLANKS);
            place(cx + 2, G + 2, cz - 2, WOOL);
            place(cx, G + 1, cz - 2, FLOWER_POT);
            place(cx, G + 2, cz - 2, DANDELION);
            // Buttresses and sconces outside the shell.
            for (int y = G + 1; y <= G + 3; y++) {
                place(cx - 4, y, cz - 3, COBBLE);
                place(cx + 4, y, cz + 3, COBBLE);
                place(cx - 4, y, cz + 3, COBBLE);
                place(cx + 4, y, cz - 3, COBBLE);
            }
            place(cx - 4, G + 4, cz - 3, GLOWSTONE);
            place(cx + 4, G + 4, cz + 3, GLOWSTONE);
            // Small stable beside the tower: posts, roof and a hay store.
            for (int x = cx - 8; x <= cx - 5; x++) {
                for (int z = cz + 2; z <= cz + 5; z++) {
                    boolean rim = x == cx - 8 || x == cx - 5 || z == cz + 2 || z == cz + 5;
                    if (rim && !(z == cz + 2 && x == cx - 7)) {
                        place(x, G + 1, z, OAK_LOG);
                        place(x, G + 2, z, OAK_LOG);
                    }
                }
            }
            floor(cx - 8, cz + 2, cx - 5, cz + 5, G + 3, PLANKS);
            place(cx - 7, G + 1, cz + 4, WOOL);
            place(cx - 6, G + 1, cz + 4, WOOL);
            place(cx - 6, G + 2, cz + 4, WOOL);
            place(cx - 7, G + 1, cz + 3, CHEST);
            chestAt(cx - 7, G + 1, cz + 3,
                    new String[]{"wheat","apple","bread","reeds"},
                    new int[]{16, 8, 4, 16});
            lampPost(cx - 9, G, cz + 9);
        }

        private void buildRuinsProps() {
            int cx = 30, cz = 30;
            // Broken colonnade down the east side.
            for (int z = 26; z <= 36; z += 3) {
                for (int y = G + 1; y <= G + 4; y++) place(35, y, z, STONE_BRICK);
                place(34, G + 5, z, STONE_BRICK);
                if (z != 32) place(35, G + 5, z, MARBLE);
            }
            // Mosaic apron around the reflecting pool, in two alternating bands.
            for (int x = cx - 5; x <= cx + 5; x++) {
                for (int z = cz - 4; z <= cz + 4; z++) {
                    int d = Math.max(Math.abs(x - cx), Math.abs(z - cz));
                    if (d == 4) set(x, G, z, ((x + z) & 1) == 0 ? TILE : STONE_BRICK);
                    if (d == 5) set(x, G, z, SANDSTONE);
                }
            }
            // Fallen statue: a marble torso and head half-buried in the sand.
            for (int x = cx - 4; x <= cx - 2; x++) place(x, G + 1, cz + 5, MARBLE);
            place(cx - 5, G + 1, cz + 5, MARBLE);
            place(cx - 1, G + 1, cz + 5, MARBLE);
            place(cx - 5, G + 2, cz + 5, MARBLE);
            // Braziers at the four corners and vines on the arch.
            for (int x : new int[]{cx - 5, cx + 5}) {
                for (int z : new int[]{cz - 4, cz + 4}) {
                    place(x, G + 1, z, STONE_BRICK);
                    place(x, G + 2, z, GLOWSTONE);
                }
            }
            for (int y = G + 3; y <= G + 5; y++) {
                place(cx - 4, y, cz - 5, VINE);
                place(cx + 4, y, cz - 5, VINE);
            }
            place(cx, G + 5, cz - 5, VINE);
            // Extra flower beds and a second reading bench.
            for (int x = cx - 5; x <= cx + 5; x += 2) {
                place(x, G + 1, cz - 6, x % 4 == 0 ? ROSE : AZURE_BLUET);
                place(x, G + 1, cz + 7, x % 4 == 0 ? TULIP : POPPY);
            }
            bench(cx + 1, cz + 4, true, 3);
            place(cx + 4, G + 1, cz - 3, CHEST);
            chestAt(cx + 4, G + 1, cz - 3,
                    new String[]{"book","oak_log","torch","bread"},
                    new int[]{4, 8, 8, 4});
            lampPost(cx - 6, G, cz);
        }

        /** Benches, flower borders and signposts along the four avenues. */
        private void buildAvenueProps() {
            int[] stops = {14, 18, 22};
            for (int d : stops) {
                bench(3, d, false, 2);
                bench(-3, -d, false, 2);
                bench(d, 3, true, 2);
                bench(-d, -3, true, 2);
            }
            signpost(3, 13);
            signpost(-3, -13);
            signpost(13, -3);
            signpost(-13, 3);
            // Flower borders along both sides of every avenue.
            for (int d = 12; d <= 22; d += 2) {
                if (d % 9 == 0) continue; // keep the lamp columns clear
                int flower = d % 4 == 0 ? POPPY : (d % 3 == 0 ? DANDELION : TULIP);
                place(2, G + 1, d, flower);
                place(-2, G + 1, d, flower);
                place(2, G + 1, -d, flower);
                place(-2, G + 1, -d, flower);
                place(d, G + 1, 2, flower);
                place(d, G + 1, -2, flower);
                place(-d, G + 1, 2, flower);
                place(-d, G + 1, -2, flower);
            }
        }

        /**
         * North-east: a ring of weathered standing stones on the rise above the
         * watchtower, with a lit altar, brazier stones and a worn approach path.
         */
        private void buildStoneCircle() {
            int cx = 40, cz = -20;
            // Worn path from the watchtower's east side to the ring.
            for (int x = 34; x <= cx - 5; x++) {
                for (int z = cz - 1; z <= cz + 1; z++) set(x, G, z, STONE_BRICK);
            }
            int[][] spots = {{-4, 0}, {4, 0}, {0, -4}, {0, 4},
                    {-3, -3}, {3, -3}, {-3, 3}, {3, 3}};
            for (int i = 0; i < spots.length; i++) {
                int x = cx + spots[i][0], z = cz + spots[i][1];
                int block = i % 3 == 0 ? MOSSY_COBBLE : STONE_BRICK;
                int h = 3 + (i & 1);
                for (int y = G + 1; y <= G + h; y++) place(x, y, z, block);
                place(x, G + h + 1, z, SLAB_STONE_BRICK);
            }
            // Altar at the centre.
            floor(cx - 1, cz - 1, cx + 1, cz + 1, G, STONE_BRICK);
            place(cx, G + 1, cz, STONE_BRICK);
            place(cx, G + 2, cz, MARBLE);
            place(cx, G + 3, cz, GLOWSTONE);
            // Braziers between the stones, and flowers in the gaps.
            for (int x : new int[]{cx - 5, cx + 5}) {
                for (int z : new int[]{cz - 5, cz + 5}) {
                    place(x, G + 1, z, COBBLE);
                    place(x, G + 2, z, GLOWSTONE);
                }
            }
            for (int x = cx - 2; x <= cx + 2; x += 2) {
                place(x, G + 1, cz - 3, x % 4 == 0 ? POPPY : AZURE_BLUET);
                place(x, G + 1, cz + 3, x % 4 == 0 ? TULIP : DANDELION);
            }
            bench(cx - 2, cz + 2, true, 2);
            lampPost(cx - 6, G, cz);
            lampPost(cx + 6, G, cz);
        }

        /**
         * South-east: a walled graveyard beside the ruins — iron-bar fence,
         * six headstones, a gravel walk, dark yews and a small stone mausoleum
         * with a real door and a stocked chest.
         */
        private void buildGraveyard() {
            int cx = 40, cz = 32;
            // Iron-bar fence with a west gate.
            for (int x = cx - 4; x <= cx + 4; x++) {
                for (int z = cz - 4; z <= cz + 4; z++) {
                    boolean rim = x == cx - 4 || x == cx + 4 || z == cz - 4 || z == cz + 4;
                    if (!rim) continue;
                    if (x == cx - 4 && z >= cz - 1 && z <= cz + 1) continue; // gate
                    place(x, G + 1, z, IRON_BARS);
                }
            }
            // Path from the ruins, and a gravel walk down the middle.
            for (int x = cx - 6; x <= cx - 4; x++) set(x, G, cz, STONE_BRICK);
            for (int z = cz - 3; z <= cz + 3; z++) set(cx, G, z, GRAVEL);
            // Headstones along the west wall, each with a flower at its foot.
            for (int z = cz - 3; z <= cz + 3; z += 2) {
                place(cx - 3, G + 1, z, SLAB_STONE_BRICK);
                place(cx - 2, G + 1, z, POPPY);
            }
            // Dark yews in the north and south corners.
            treeVariety(cx - 2, cz - 3, 3);
            treeVariety(cx - 2, cz + 3, 3);
            // Mausoleum: stone shell, west door, slab roof, gold finial.
            int mx0 = cx + 1, mx1 = cx + 3, mz0 = cz - 2, mz1 = cz + 1;
            floor(mx0, mz0, mx1, mz1, G, COBBLE);
            for (int x = mx0; x <= mx1; x++) {
                for (int z = mz0; z <= mz1; z++) {
                    boolean rim = x == mx0 || x == mx1 || z == mz0 || z == mz1;
                    if (!rim) continue;
                    for (int y = G + 1; y <= G + 3; y++) place(x, y, z, STONE_BRICK);
                    if (x == mx0 && z == mz0 || x == mx1 && z == mz1) {
                        place(x, G + 3, z, MOSSY_COBBLE);
                    }
                }
            }
            set(mx0, G + 1, mz0 + 1, 0);
            set(mx0, G + 2, mz0 + 1, 0);
            place(mx0, G + 1, mz0 + 1, OAK_DOOR_LOWER);
            place(mx0, G + 2, mz0 + 1, OAK_DOOR_UPPER);
            floor(mx0, mz0, mx1, mz1, G + 4, SLAB_STONE_BRICK);
            place(cx + 2, G + 5, cz, GOLD_BLOCK);
            place(cx + 2, G + 1, cz, CHEST);
            chestAt(cx + 2, G + 1, cz,
                    new String[]{"bone","gold_ingot","torch","bread","emerald"},
                    new int[]{8, 4, 8, 2, 2});
            place(cx + 2, G + 1, cz - 1, CRAFT_TABLE);
            lampPost(cx - 3, G, cz + 4);
            bench(cx - 2, cz + 2, true, 2);
        }

        /** Deterministic trees and flowers fill the grass without touching builds. */
        private void scatterNature() {
            java.util.Random rnd = new java.util.Random(20261021L);
            // Copses: trees of every wood type scattered around the districts.
            for (int i = 0; i < 44; i++) {
                double a = rnd.nextDouble() * Math.PI * 2.0;
                double r = 15 + rnd.nextDouble() * 29.0;
                int x = (int) Math.round(Math.cos(a) * r);
                int z = (int) Math.round(Math.sin(a) * r);
                if (freeGround(x, z) && freeGround(x + 1, z) && freeGround(x, z + 1)) {
                    treeVariety(x, z, rnd.nextInt(4));
                }
            }
            // Boulders, stumps and mushroom rings in the long grass.
            for (int i = 0; i < 30; i++) {
                int x = rnd.nextInt(89) - 44;
                int z = rnd.nextInt(89) - 44;
                if (!freeGround(x, z) || !freeGround(x + 1, z) || !freeGround(x, z + 1)) continue;
                if (i % 3 == 0) boulder(x, z, rnd.nextInt(3));
                else if (i % 3 == 1) stump(x, z);
                else mushroomCluster(x, z);
            }
            // Wildflower patches and tall grass everywhere else.
            for (int i = 0; i < 160; i++) {
                int x = rnd.nextInt(89) - 44;
                int z = rnd.nextInt(89) - 44;
                if (!freeGround(x, z)) continue;
                int flower = (i % 7 == 0) ? AZURE_BLUET : (i % 5 == 0) ? TULIP
                        : (i % 3 == 0 ? POPPY : (i % 2 == 0 ? DANDELION : TALLGRASS));
                place(x, G + 1, z, flower);
            }
        }

        private void boulder(int x, int z, int kind) {
            int block = kind == 0 ? COBBLE : (kind == 1 ? ANDESITE : GRANITE);
            place(x, G + 1, z, block);
            place(x + 1, G + 1, z, block);
            place(x, G + 1, z + 1, block);
            place(x + 1, G + 1, z + 1, MOSSY_COBBLE);
            place(x + 1, G + 2, z, block);
            place(x, G + 2, z + 1, block);
        }

        private void stump(int x, int z) {
            place(x, G + 1, z, OAK_LOG);
            place(x + 1, G + 1, z, OAK_LOG);
            place(x, G + 2, z, OAK_LOG);
            place(x + 1, G + 1, z + 1, BROWN_MUSHROOM);
        }

        private void mushroomCluster(int x, int z) {
            place(x, G + 1, z, TALLGRASS);
            place(x + 1, G + 1, z, RED_MUSHROOM);
            place(x, G + 1, z + 1, BROWN_MUSHROOM);
            place(x - 1, G + 1, z + 1, FERN);
        }

        /** Grass that no district, path or test column occupies. */
        private boolean freeGround(int x, int z) {
            if (Math.abs(x) > 44 || Math.abs(z) > 44) return false; // keep the far test chunk empty
            if (Math.abs(x) < 12 && Math.abs(z) < 12) return false; // plaza
            if (Math.abs(x) < 12 && z <= -22 && z >= -38) return false; // market
            if (x >= -38 && x <= -24 && Math.abs(z) <= 6) return false;  // builder's yard
            if (x >= 24 && x <= 45 && Math.abs(z) <= 8) return false;    // quarry + works
            if (x >= 33 && x <= 45 && z >= -10 && z <= 9) return false;  // quarry spoil/office
            if (x >= -27 && x <= -10 && z >= 18 && z <= 43) return false; // farmstead + paddock
            if (Math.abs(x) <= 12 && z >= 24 && z <= 38) return false;   // farm
            if (x >= 20 && x <= 38 && z <= -22 && z >= -40) return false; // watchtower + stable
            if (x >= 22 && x <= 38 && z >= 22 && z <= 38) return false;   // ruins garden
            if (x >= -40 && x <= -14 && z >= -42 && z <= -14) return false; // hamlet
            if (x >= -54 && x <= -34 && z >= -48 && z <= -32) return false; // pond + hut
            if (x >= -46 && x <= -30 && z >= 12 && z <= 32) return false;  // orchard
            if (x >= -46 && x <= -32 && z >= 32 && z <= 44) return false;  // camp
            if (x >= 33 && x <= 46 && z >= -28 && z <= -12) return false;  // stone circle
            if (x >= 33 && x <= 46 && z >= 26 && z <= 38) return false;    // graveyard
            if (Math.abs(x) <= 4 || Math.abs(z) <= 4) return false;      // avenues + shoulders
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
