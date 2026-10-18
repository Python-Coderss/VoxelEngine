package com.voxel.world;

import com.voxel.game.ChestManager;

/**
 * Hand-authored "Showcase World": a single creative-mode hub laid out so every
 * major engine feature — and every dimension — can be inspected in one place.
 *
 * <p>Districts, all connected by lamp-lit walkways around a central plaza:</p>
 * <ul>
 *   <li><b>Showcase Plaza</b> — spawn, fountain, welcome arch, guide chest.</li>
 *   <li><b>Dimension Gateway</b> — a pillared hall holding one lit portal per
 *       dimension (Nether, Aether, Sift, and an active End pad) behind real
 *       double doors.</li>
 *   <li><b>Item Gallery</b> — a museum aisle of pedestals reached by a roofed
 *       corridor off the gateway.</li>
 *   <li><b>Crafting &amp; Storage Workshop</b> — crafting table, furnace, anvil,
 *       brewing stand, cauldron, enchanting corner and stocked chests, with the
 *       book blocks on display.</li>
 *   <li><b>Redstone &amp; Machines</b> — redstone circuits, pistons and the
 *       Create-style kinetic machines, plus a beacon landmark.</li>
 *   <li><b>Farm &amp; Nature</b> — crops, orchard, pond, flower pots and a barn
 *       with double doors.</li>
 * </ul>
 *
 * <h2>How the geometry stays sound</h2>
 * Every building is placed with a small set of primitives that guarantee a
 * well-formed result, rather than ad-hoc block writes:
 * <ul>
 *   <li>{@link #fill}/{@link #pad} for solid volumes and single-level floors,</li>
 *   <li>{@link #walls} for a perimeter shell (never a floating slab),</li>
 *   <li>{@link #pitchedRoof} for a stepped gable roof that always sits on the
 *       wall ring and overhangs the gable ends by one block,</li>
 *   <li>{@link #doorZ}/{@link #doorX} for two-block-tall doors placed in the
 *       correct wall orientation (see {@link DecorBlocks}).</li>
 * </ul>
 * Nothing is left floating or unsupported — {@code ShowcaseStructureIntegrityTest}
 * builds the whole world and asserts every authored block above the ground
 * layer is connected to the ground.
 *
 * <p>Like {@link PointClickWorldAuthor} this writes the whole world eagerly
 * through a {@link Sink}; {@link ShowcaseWorldGenerator} materialises it into
 * every section it is asked to populate, so the hub exists even with no chunk
 * files on disk.</p>
 *
 * <p>World size: 128x128 columns (8x8 chunks, -64..63), flat at y=62. Player
 * spawns at (0, 63, 8.5) inside the plaza, facing the gateway.</p>
 */
public final class ShowcaseWorldAuthor {

    /** Surface height of the flat hub (player feet rest at G+1 = 63). */
    public static final int G = 62;
    /** World extent: -64..63 in X/Z (8x8 chunks). */
    public static final int MIN = -64, MAX = 63;
    /** Authored Y range (base terrain up to G, builds to G+16). */
    public static final int AREA_H = 96;

    // ── Terrain / masonry ──────────────────────────────────────────────────
    private static final int GRASS = 1, STONE = 2, GLASS = 3, OAK_LEAVES = 4,
            OAK_LOG = 5, DIRT = 13, SAND = 14, WATER = 15, OBSIDIAN = 16,
            GLOWSTONE = 17, END_STONE = 18, NETHER_PORTAL = 19, LAVA = 21,
            REDSTONE_BLOCK = 25, REDSTONE_TORCH = 27, REDSTONE_LAMP = 28,
            REDSTONE_WIRE = 29, PISTON = 31, POPPY = 34, TALLGRASS = 35,
            CACTUS = 39, REEDS = 40, WATERLILY = 41, PUMPKIN = 42, MELON = 43,
            BIRCH_LOG = 46, SPRUCE_LOG = 47, JUNGLE_LOG = 49, ACACIA_LOG = 51,
            DARK_OAK_LOG = 52, SANDSTONE = 59, TULIP = 62, AZURE_BLUET = 63,
            FERN = 64, HARDENED_CLAY = 65, ICE = 68, COBBLESTONE = 71,
            OAK_PLANKS = 72, SPRUCE_PLANKS = 73, BIRCH_PLANKS = 74,
            JUNGLE_PLANKS = 75, ACACIA_PLANKS = 76, DARK_OAK_PLANKS = 77,
            RED_SAND = 78, SMOOTH_SANDSTONE = 79, IRON_ORE = 81, GOLD_ORE = 82,
            DIAMOND_ORE = 83, EMERALD_ORE = 84, LAPIS_ORE = 85, WOOL = 91,
            AETHER_PORTAL_NS = 106, CRAFT_TABLE = 115, FURNACE = 116,
            CHEST = 118, DANDELION = 121, ROSE = 122, BRICK = 130,
            STONEBRICK = 131, MOSSY_COBBLESTONE = 132, ANDESITE = 133,
            DIORITE = 134, GRANITE = 135, BOOKSHELF = 136, IRON_BLOCK = 137,
            GOLD_BLOCK = 138, DIAMOND_BLOCK = 139, EMERALD_BLOCK = 140,
            LAPIS_BLOCK = 141, COPPER_ORE = 142, COPPER_BLOCK = 143,
            ZINC_ORE = 144, ZINC_BLOCK = 145;

    // ── Cut / shaped blocks ────────────────────────────────────────────────
    private static final int OAK_STAIRS = 200, STONE_STAIRS = 201,
            STONE_BRICK_STAIRS = 202, BRICK_STAIRS = 203, SANDSTONE_STAIRS = 204,
            SLAB_OAK = 206, SLAB_COBBLESTONE = 207, SLAB_STONE_BRICK = 208,
            SLAB_BRICK = 209, SLAB_SANDSTONE = 210, TORCH = 211;

    // ── Display / item blocks ──────────────────────────────────────────────
    private static final int ITEM_DROP_FLINT = 231, ITEM_DROP_IRON_INGOT = 232,
            ITEM_DROP_STICK = 233, ITEM_DROP_DIAMOND_PICKAXE = 247,
            ITEM_DROP_QUARTZ = 390, ITEM_DROP_WRENCH = 417,
            ITEM_DROP_GOGGLES = 419, ITEM_DROP_BRASS_INGOT = 421;

    // ── Functional blocks ──────────────────────────────────────────────────
    private static final int COGWHEEL = 294, LARGE_COGWHEEL = 295,
            WATER_WHEEL = 296, RAIL_NS = 391, BLAZE_BURNER = 394,
            STEAM_ENGINE = 396, HAND_CRANK = 404, WINDMILL_BEARING = 405,
            WINDMILL_SAIL = 406, MECHANICAL_PRESS = 407, MILLSTONE = 408,
            CRUSHING_WHEEL = 409, MECHANICAL_DRILL = 410, MECHANICAL_SAW = 411,
            DEPLOYER = 412, BELT_CONVEYOR = 413, ITEM_VAULT = 414,
            BRASS_CASING = 415, COAL_BLOCK = 455, ANVIL = 456, BEACON = 457,
            ENCHANTING_TABLE = 458, DISPENSER = 459, DROPPER = 460,
            HOPPER = 461, CAULDRON = 462, BREWING_STAND = 463, END_BRICKS = 464,
            END_ROD = 465, DRAGON_EGG = 466, PRISMARINE_ROUGH = 467,
            PRISMARINE_BRICKS = 468, PRISMARINE_DARK = 469, SEA_LANTERN = 470,
            IRON_BARS = 471, PURPUR_BLOCK = 900, PURPUR_PILLAR = 901,
            CHORUS_PLANT = 902, CHORUS_FLOWER = 903, END_GLASS = 904,
            VOID_STEEL = 905, FARMLAND_DRY = 906, FARMLAND_WET = 907,
            FORMIDI_BOMB = 916, CONCRETE = 920, CONCRETE_DARK = 921,
            STEEL_BEAM = 922, CEILING_LIGHT = 923, TILE_BLOCK = 924,
            OFFICE_GLASS = 925, MARBLE = 926, RIFT_FRAME = 933,
            SIFT_PORTAL = 934, SOUL_LAMP = 935, SOUL_SLATE = 936,
            NOTE_BLOCK = 937, ITEM_BOOK = 940, ITEM_WRITABLE_BOOK = 942,
            ITEM_WRITTEN_BOOK = 944, ITEM_ENCHANTED_BOOK = 946;

    // ── Door kits: {lower Z-wall, upper Z-wall, lower X-wall, upper X-wall} ─
    private static final int[] OAK_DOOR = {DecorBlocks.OAK_DOOR_LOWER,
            DecorBlocks.OAK_DOOR_UPPER, DecorBlocks.OAK_DOOR_LOWER_EW,
            DecorBlocks.OAK_DOOR_UPPER_EW};
    private static final int[] SPRUCE_DOOR = {DecorBlocks.SPRUCE_DOOR_LOWER,
            DecorBlocks.SPRUCE_DOOR_UPPER, DecorBlocks.SPRUCE_DOOR_LOWER_EW,
            DecorBlocks.SPRUCE_DOOR_UPPER_EW};
    private static final int[] BIRCH_DOOR = {DecorBlocks.BIRCH_DOOR_LOWER,
            DecorBlocks.BIRCH_DOOR_UPPER, DecorBlocks.BIRCH_DOOR_LOWER_EW,
            DecorBlocks.BIRCH_DOOR_UPPER_EW};
    private static final int[] DARK_OAK_DOOR = {DecorBlocks.DARK_OAK_DOOR_LOWER,
            DecorBlocks.DARK_OAK_DOOR_UPPER, DecorBlocks.DARK_OAK_DOOR_LOWER_EW,
            DecorBlocks.DARK_OAK_DOOR_UPPER_EW};

    private static final int TRAPDOOR = DecorBlocks.OAK_TRAPDOOR;
    private static final int LADDER = DecorBlocks.LADDER;
    private static final int FLOWER_POT = DecorBlocks.FLOWER_POT;

    /** A target that receives every voxel the author places (type + facing). */
    public interface Sink {
        void set(int x, int y, int z, int type, int extra);
    }

    /**
     * Resolves block ids that the engine assigns dynamically at registry load
     * (notably the {@code end_portal} family). Returns {@code -1} when unknown,
     * in which case the optional block is skipped.
     */
    public interface Ids {
        int get(String name);
    }

    /** A named showcase district, given as a rectangle for exact zone lookup. */
    public static final class Zone {
        public final String name, subtitle;
        public final int minX, minZ, maxX, maxZ;
        public Zone(String name, String subtitle, int minX, int minZ, int maxX, int maxZ) {
            this.name = name;
            this.subtitle = subtitle;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }
        public boolean contains(float x, float z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** Player spawn (feet) in the plaza, facing north toward the gateway. */
    public static final double SPAWN_X = 0.5, SPAWN_Z = 8.5;

    // Districts are non-overlapping rectangles, listed north-to-south.
    private static final Zone[] ZONES = {
        new Zone("Item Gallery",
                "Every ore, mineral block, tool and item on show.", -20, -65, 20, -46),
        new Zone("Dimension Gateway",
                "One lit portal per dimension: Nether, Aether, the Sift and the End.",
                -22, -43, 22, -18),
        new Zone("Redstone & Machines",
                "Circuits, pistons and Create-style kinetic power.", -48, -12, -24, 12),
        new Zone("Crafting & Storage Workshop",
                "Crafting table, furnace, anvil, brewing, enchanting and shelves of books.",
                24, -12, 48, 12),
        new Zone("Farm & Nature",
                "Crops, orchard, pond and a barn — grow everything here.", -17, 18, 17, 51),
        new Zone("Showcase Plaza",
                "Spawn point. Walkways lead to every district.", -16, -16, 16, 17),
    };

    /** Districts in draw order. */
    public static Zone[] zones() { return ZONES.clone(); }

    /** Index of the zone containing (x, z), or -1 when outside every zone. */
    public static int zoneAt(float x, float z) {
        for (int i = 0; i < ZONES.length; i++) {
            if (ZONES[i].contains(x, z)) return i;
        }
        return -1;
    }

    /** Builds the complete showcase world into {@code sink} and stocks chests. */
    public static void buildAll(Sink sink, ChestManager chests, Ids ids) {
        new Author(sink, chests, ids).build();
    }

    private static final class Author {
        final Sink sink;
        final ChestManager chests;
        final Ids ids;

        Author(Sink sink, ChestManager chests, Ids ids) {
            this.sink = sink;
            this.chests = chests;
            this.ids = ids;
        }

        // ── Primitives ─────────────────────────────────────────────────────

        private void set(int x, int y, int z, int b) { sink.set(x, y, z, b, 0); }

        /** Solid axis-aligned box (inclusive bounds). */
        private void fill(int x0, int y0, int z0, int x1, int y1, int z1, int b) {
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++)
                    for (int z = z0; z <= z1; z++) set(x, y, z, b);
        }

        /** One-level floor pad. */
        private void pad(int x0, int z0, int x1, int z1, int y, int b) {
            fill(x0, y, z0, x1, y, z1, b);
        }

        /** Perimeter shell (walls only — no floor, no ceiling). */
        private void walls(int x0, int z0, int x1, int z1, int y0, int y1, int b) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    set(x, y, z0, b);
                    set(x, y, z1, b);
                }
                for (int z = z0; z <= z1; z++) {
                    set(x0, y, z, b);
                    set(x1, y, z, b);
                }
            }
        }

        /**
         * Stepped gable roof: level k sits one block above the last and is
         * inset two blocks on the Z sides (constant one-block overhang on the
         * gable ends). The top level becomes the ridge. Because level 0 spans
         * the full footprint directly above the wall ring, the roof is always
         * supported and never floats.
         *
         * @return the Y of the ridge, so callers can add ridge accents.
         */
        private int pitchedRoof(int x0, int z0, int x1, int z1, int wallTop,
                                int body, int ridge) {
            int maxK = 0;
            while (z0 + (maxK + 1) * 2 <= z1 - (maxK + 1) * 2) maxK++;
            for (int k = 0; k <= maxK; k++) {
                int y = wallTop + 1 + k;
                int a = z0 + k * 2, c = z1 - k * 2;
                fill(x0 - 1, y, a, x1 + 1, y, c, k == maxK ? ridge : body);
            }
            return wallTop + 1 + maxK;
        }

        /** Flat roof deck with a one-block eave, plus a crenellated parapet. */
        private void flatRoofWithParapet(int x0, int z0, int x1, int z1, int wallTop,
                                         int deck, int parapet) {
            pad(x0 - 1, z0 - 1, x1 + 1, z1 + 1, wallTop + 1, deck);
            walls(x0 - 1, z0 - 1, x1 + 1, z1 + 1, wallTop + 2, wallTop + 2, parapet);
        }

        /** Glass band around a building perimeter, at one height. */
        private void windowBand(int x0, int z0, int x1, int z1, int y, int step) {
            for (int x = x0 + 1; x <= x1 - 1; x += step) {
                set(x, y, z0, GLASS);
                set(x, y, z1, GLASS);
            }
            for (int z = z0 + 1; z <= z1 - 1; z += step) {
                set(x0, y, z, GLASS);
                set(x1, y, z, GLASS);
            }
        }

        /** Two-block-tall door for a wall that runs along Z (vanilla panel). */
        private void doorZ(int x, int y, int z, int[] kit) {
            set(x, y, z, kit[0]);
            set(x, y + 1, z, kit[1]);
        }

        /** Two-block-tall door for a wall that runs along X (rotated panel). */
        private void doorX(int x, int y, int z, int[] kit) {
            set(x, y, z, kit[2]);
            set(x, y + 1, z, kit[3]);
        }

        /**
         * Carves a two-tall doorway through a wall that runs along Z (fixed X,
         * so the opening is {@code width} cells wide in Z). Pair with
         * {@link #doorZ}.
         */
        private void doorwayAlongZ(int x, int z, int width, int y) {
            for (int i = 0; i < width; i++)
                for (int dy = 0; dy <= 1; dy++) set(x, y + dy, z + i, 0);
        }

        /**
         * Carves a two-tall doorway through a wall that runs along X (fixed Z,
         * so the opening is {@code width} cells wide in X). Pair with
         * {@link #doorX}.
         */
        private void doorwayAlongX(int x, int z, int width, int y) {
            for (int i = 0; i < width; i++)
                for (int dy = 0; dy <= 1; dy++) set(x + i, y + dy, z, 0);
        }

        private void lampPost(int x, int z) {
            set(x, G + 1, z, STONEBRICK);
            set(x, G + 2, z, STONEBRICK);
            set(x, G + 3, z, GLOWSTONE);
            set(x, G + 4, z, SLAB_STONE_BRICK);
        }

        private void pedestal(int x, int z, int y, int display) {
            set(x, y, z, MARBLE);
            set(x, y + 1, z, display);
        }

        private void tree(int x, int z, int log, int leaves) {
            int h = 5;
            for (int y = G + 1; y <= G + h; y++) set(x, y, z, log);
            int top = G + h;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int dy = 0; dy <= 2; dy++) {
                        if (Math.abs(dx) == 2 && Math.abs(dz) == 2 && dy == 2) continue;
                        if (Math.abs(dx) + Math.abs(dz) > 3 && dy == 2) continue;
                        if (dy == 2 && Math.abs(dx) + Math.abs(dz) > 1) continue;
                        set(x + dx, top - 1 + dy, z + dz, leaves);
                    }
            set(x, top + 2, z, leaves);
        }

        private void chestAt(int x, int y, int z, String[] items, int[] counts) {
            com.voxel.game.ItemDefinitions.ItemStack[] inv =
                    new com.voxel.game.ItemDefinitions.ItemStack[items.length];
            for (int i = 0; i < items.length; i++) {
                inv[i] = new com.voxel.game.ItemDefinitions.ItemStack(items[i], counts[i]);
            }
            chests.setInventory(x, y, z, inv);
        }

        // ── Assembly ───────────────────────────────────────────────────────

        private void build() {
            // No base-terrain pass: ShowcaseWorldGenerator synthesises the flat
            // grass/dirt/stone profile procedurally for every column.
            buildPlaza();
            buildWalkways();
            buildGateway();
            buildCorridor();
            buildGallery();
            buildWorkshop();
            buildRedstoneWorks();
            buildFarm();
            buildGardenDetails();
        }

        // ── Plaza ──────────────────────────────────────────────────────────
        private void buildPlaza() {
            pad(-14, -14, 14, 14, G, STONEBRICK);
            // Gold inlay ring.
            for (int a = -12; a <= 12; a++) {
                set(a, G, -12, GOLD_BLOCK);
                set(a, G, 12, GOLD_BLOCK);
                set(-12, G, a, GOLD_BLOCK);
                set(12, G, a, GOLD_BLOCK);
            }
            // Marble compass marks at the four waypoints.
            for (int a = -1; a <= 1; a++) {
                set(a, G, -14, MARBLE);
                set(a, G, 14, MARBLE);
                set(-14, G, a, MARBLE);
                set(14, G, a, MARBLE);
            }

            // Fountain: fully walled basin so the water cannot escape.
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    if (Math.abs(x) == 2 || Math.abs(z) == 2) set(x, G + 1, z, STONEBRICK);
            fill(-1, G + 1, -1, 1, G + 1, 1, WATER);
            for (int y = G + 1; y <= G + 4; y++) set(0, y, 0, STONEBRICK);
            set(0, G + 5, 0, GLOWSTONE);
            set(0, G + 6, 0, SLAB_STONE_BRICK);

            // Welcome arch over the south walkway: two legs and a lintel.
            for (int y = G + 1; y <= G + 5; y++) {
                set(-5, y, 14, STONEBRICK);
                set(5, y, 14, STONEBRICK);
            }
            pad(-5, 14, 5, 14, G + 6, STONEBRICK);
            set(0, G + 7, 14, GLOWSTONE);
            set(-1, G + 7, 14, MARBLE);
            set(1, G + 7, 14, MARBLE);

            // Corner lamps.
            lampPost(-12, -12);
            lampPost(12, -12);
            lampPost(-12, 12);
            lampPost(12, 12);

            // Guide chest + notice board, west of the spawn point.
            set(-4, G + 1, 9, CHEST);
            chestAt(-4, G + 1, 9,
                    new String[] {"flint_and_steel", "water_bucket", "eye_of_ender",
                            "diamond_pickaxe", "oak_door_lower", "ladder",
                            "oak_trapdoor", "flower_pot", "torch", "oak_planks"},
                    new int[] {1, 1, 8, 1, 16, 16, 8, 8, 32, 64});
            set(-9, G + 1, 9, BOOKSHELF);
            set(-8, G + 1, 9, BOOKSHELF);
            set(-7, G + 1, 9, BOOKSHELF);
            set(-6, G + 1, 9, BOOKSHELF);
        }

        // ── Walkways ───────────────────────────────────────────────────────
        private void buildWalkways() {
            // North: plaza -> gateway.
            pad(-2, -21, 2, -15, G, MARBLE);
            lampPost(-3, -16);
            lampPost(3, -16);
            lampPost(-3, -20);
            lampPost(3, -20);
            // East: plaza -> workshop.
            pad(15, -2, 25, 2, G, MARBLE);
            lampPost(17, -3);
            lampPost(21, -3);
            lampPost(17, 3);
            lampPost(21, 3);
            // West: plaza -> redstone works.
            pad(-25, -2, -15, 2, G, MARBLE);
            lampPost(-17, -3);
            lampPost(-21, -3);
            lampPost(-17, 3);
            lampPost(-21, 3);
            // South: plaza -> farm.
            pad(-2, 15, 2, 19, G, MARBLE);
            lampPost(-3, 17);
            lampPost(3, 17);
        }

        // ── Dimension Gateway ──────────────────────────────────────────────
        private void buildGateway() {
            int x0 = -20, x1 = 20, z0 = -42, z1 = -22, wallTop = G + 5;
            pad(x0, z0, x1, z1, G, STONEBRICK);
            walls(x0, z0, x1, z1, G + 1, wallTop, STONEBRICK);
            windowBand(x0, z0, x1, z1, G + 3, 4);

            // South entrance: a two-wide, two-tall doorway with double doors.
            doorwayAlongX(-1, z1, 2, G + 1);
            doorX(-1, G + 1, z1, OAK_DOOR);
            doorX(0, G + 1, z1, OAK_DOOR);
            // Architrave above the entrance.
            for (int x = -2; x <= 1; x++) set(x, G + 3, z1, STONEBRICK);
            set(0, G + 4, z1, GLOWSTONE);

            // Opening through the north wall into the gallery corridor.
            doorwayAlongX(-1, z0, 2, G + 1);

            // Interior pillars with glowing capitals under the roof deck.
            for (int x : new int[] {-14, -7, 7, 14}) {
                for (int y = G + 1; y <= wallTop - 1; y++) set(x, y, z0 + 5, MARBLE);
                set(x, wallTop, z0 + 5, GLOWSTONE);
            }

            // Four portal bays along the north wall, one per dimension.
            int wall = z0 + 1;
            portalBay(-15, wall, OBSIDIAN, NETHER_PORTAL, 4,
                    new String[] {"obsidian", "flint_and_steel", "gold_ingot", "blaze_rod"},
                    new int[] {16, 1, 16, 8});
            portalBay(-5, wall, GLOWSTONE, AETHER_PORTAL_NS, 5,
                    new String[] {"skyroot_log", "ambrosium_ore", "water_bucket", "icestone"},
                    new int[] {32, 16, 1, 16});
            portalBay(5, wall, RIFT_FRAME, SIFT_PORTAL, 5,
                    new String[] {"glowstone", "stone", "icestone", "blaze_powder"},
                    new int[] {8, 32, 16, 4});
            endBay(15, wall);

            // Roof, then glowing accents along the ridge line.
            int ridgeY = pitchedRoof(x0, z0, x1, z1, wallTop, SLAB_STONE_BRICK, STONEBRICK);
            int ridgeZ = (z0 + z1) / 2;
            for (int x = -6; x <= 6; x += 6) set(x, ridgeY, ridgeZ, GLOWSTONE);
        }

        /**
         * A proper portal: 4-wide by 5-tall frame (bottom and top courses plus
         * two side columns) enclosing a lit 2x3 interior. The player steps onto
         * the floor course and their body occupies the portal, which is what
         * {@code PortalSystem.checkTeleport} looks for.
         */
        private void portalBay(int cx, int z, int frame, int portal, int archSpan,
                               String[] loot, int[] counts) {
            int fx = cx - 1; // left frame column; interior is fx+1..fx+2
            // Clear the alcove (the wall was already built here).
            fill(fx - 2, G + 1, z, fx + 4, G + 9, z, 0);
            // Frame.
            for (int i = 0; i < 4; i++) {
                set(fx + i, G, z, frame);
                set(fx + i, G + 4, z, frame);
            }
            for (int y = G + 1; y <= G + 3; y++) {
                set(fx, y, z, frame);
                set(fx + 3, y, z, frame);
                set(fx + 1, y, z, portal);
                set(fx + 2, y, z, portal);
            }
            // Decorated arch above.
            pad(fx - 1, z, fx + 4, z, G + 5, MARBLE);
            set(cx, G + 5, z, GLOWSTONE);
            // Themed chest beside the bay, on the hall floor.
            set(fx - 1, G + 1, z + 6, CHEST);
            chestAt(fx - 1, G + 1, z + 6, loot, counts);
        }

        /**
         * The End bay: an obsidian frame over an active {@code end_portal} pad
         * flush with the floor, so walking in teleports to the End. Falls back
         * to a plain obsidian floor if the engine did not register the block.
         */
        private void endBay(int cx, int z) {
            int portalId = ids.get("end_portal");
            int fx = cx - 1;
            fill(fx - 2, G + 1, z, fx + 4, G + 9, z, 0);
            for (int i = 0; i < 4; i++) {
                set(fx + i, G, z, OBSIDIAN);
                set(fx + i, G + 4, z, OBSIDIAN);
            }
            for (int y = G + 1; y <= G + 3; y++) {
                set(fx, y, z, OBSIDIAN);
                set(fx + 3, y, z, OBSIDIAN);
            }
            for (int x = fx; x <= fx + 3; x++)
                for (int zz = z - 1; zz <= z + 1; zz++)
                    set(x, G, zz, portalId > 0 ? portalId : OBSIDIAN);
            pad(fx - 1, z, fx + 4, z, G + 5, END_GLASS);
            set(cx, G + 5, z, GLOWSTONE);
            set(cx, G + 6, z, DRAGON_EGG);
            set(fx - 1, G + 1, z + 6, CHEST);
            chestAt(fx - 1, G + 1, z + 6,
                    new String[] {"eye_of_ender", "diamond", "end_stone", "chorus_fruit"},
                    new int[] {12, 16, 32, 8});
        }

        // ── Corridor: gateway -> gallery ───────────────────────────────────
        private void buildCorridor() {
            int z0 = -48, z1 = -42;
            pad(-2, z0, 2, z1, G, STONEBRICK);
            // Side walls, roof deck.
            for (int z = z0; z <= z1; z++) {
                for (int y = G + 1; y <= G + 4; y++) {
                    set(-2, y, z, STONEBRICK);
                    set(2, y, z, STONEBRICK);
                }
            }
            pad(-2, z0, 2, z1, G + 5, SLAB_STONE_BRICK);
            // Glowing skylights down the middle of the deck.
            for (int z = z0 + 2; z <= z1 - 1; z += 3) set(0, G + 5, z, GLOWSTONE);
            // Doorway into the gallery.
            doorwayAlongX(-1, z0, 2, G + 1);
            doorX(-1, G + 1, z0, OAK_DOOR);
            doorX(0, G + 1, z0, OAK_DOOR);
            for (int x = -2; x <= 1; x++) set(x, G + 3, z0, STONEBRICK);
        }

        // ── Item Gallery ───────────────────────────────────────────────────
        private void buildGallery() {
            int x0 = -18, x1 = 18, z0 = -64, z1 = -48, wallTop = G + 5;
            pad(x0, z0, x1, z1, G, SMOOTH_SANDSTONE);
            walls(x0, z0, x1, z1, G + 1, wallTop, BRICK);
            windowBand(x0, z0, x1, z1, G + 3, 5);

            // South entrance aligned with the corridor.
            doorwayAlongX(-1, z1, 2, G + 1);
            doorX(-1, G + 1, z1, BIRCH_DOOR);
            doorX(0, G + 1, z1, BIRCH_DOOR);
            for (int x = -2; x <= 1; x++) set(x, G + 3, z1, BRICK);
            set(0, G + 4, z1, GLOWSTONE);

            // Centre aisle with an obelisk.
            for (int y = G + 1; y <= G + 3; y++) set(0, y, -58, MARBLE);
            set(0, G + 4, -58, GLOWSTONE);
            // Roof, then glowing accents along the ridge line.
            int ridgeY = pitchedRoof(x0, z0, x1, z1, wallTop, SLAB_BRICK, SLAB_STONE_BRICK);
            int ridgeZ = (z0 + z1) / 2;
            for (int x = -12; x <= 12; x += 6) set(x, ridgeY, ridgeZ, GLOWSTONE);

            // Pedestals: five slots per side per row, no overlaps.
            int[] ores1 = {IRON_ORE, GOLD_ORE, DIAMOND_ORE, EMERALD_ORE, LAPIS_ORE};
            int[] ores2 = {COPPER_ORE, ZINC_ORE, COAL_BLOCK, REDSTONE_BLOCK, IRON_BLOCK};
            int[] minerals = {GOLD_BLOCK, DIAMOND_BLOCK, EMERALD_BLOCK, LAPIS_BLOCK,
                    COPPER_BLOCK};
            int[] modern = {CONCRETE, CONCRETE_DARK, STEEL_BEAM, CEILING_LIGHT,
                    TILE_BLOCK};
            int[] tools = {ITEM_DROP_FLINT, ITEM_DROP_IRON_INGOT, ITEM_DROP_STICK,
                    ITEM_DROP_DIAMOND_PICKAXE, ITEM_DROP_QUARTZ};
            int[] nature = {SAND, SANDSTONE, RED_SAND, ICE, GRANITE};
            int[] exotic = {PRISMARINE_ROUGH, PRISMARINE_BRICKS, PURPUR_PILLAR,
                    SEA_LANTERN, VOID_STEEL};
            pedestalRow(ores1, -16, -60);
            pedestalRow(ores2, 4, -60);
            pedestalRow(minerals, -16, -56);
            pedestalRow(modern, 4, -56);
            pedestalRow(tools, -16, -52);
            pedestalRow(nature, 4, -52);
            pedestalRow(exotic, 4, -50);

            // Book display: a column per book so each sits on a real plinth.
            int[] bookIds = {ITEM_BOOK, ITEM_WRITABLE_BOOK, ITEM_WRITTEN_BOOK,
                    ITEM_ENCHANTED_BOOK};
            for (int i = 0; i < bookIds.length; i++) {
                int x = -16 + i * 2;
                set(x, G + 1, -50, MARBLE);
                set(x, G + 2, -50, OAK_PLANKS);
                set(x, G + 3, -50, bookIds[i]);
            }
            for (int y = G + 1; y <= G + 2; y++) set(-8, y, -50, BOOKSHELF);
        }

        private void pedestalRow(int[] blocks, int xStart, int z) {
            for (int i = 0; i < blocks.length; i++) {
                int x = xStart + i * 3;
                set(x, G + 1, z, MARBLE);
                set(x, G + 2, z, blocks[i]);
            }
        }

        // ── Workshop ───────────────────────────────────────────────────────
        private void buildWorkshop() {
            int x0 = 26, x1 = 46, z0 = -10, z1 = 10, wallTop = G + 5;
            pad(x0, z0, x1, z1, G, OAK_PLANKS);
            walls(x0, z0, x1, z1, G + 1, wallTop, SPRUCE_PLANKS);
            // Corner posts.
            for (int y = G + 1; y <= wallTop; y++) {
                set(x0, y, z0, OAK_LOG);
                set(x1, y, z0, OAK_LOG);
                set(x0, y, z1, OAK_LOG);
                set(x1, y, z1, OAK_LOG);
            }
            windowBand(x0, z0, x1, z1, G + 3, 4);

            // West entrance (faces the plaza) with double doors and a porch.
            doorwayAlongZ(x0, -1, 2, G + 1);
            doorZ(x0, G + 1, -1, DARK_OAK_DOOR);
            doorZ(x0, G + 1, 0, DARK_OAK_DOOR);
            for (int z = -2; z <= 1; z++) set(x0, G + 3, z, SPRUCE_PLANKS);
            set(x0 - 1, G + 1, -1, OAK_STAIRS);
            set(x0 - 1, G + 1, 0, OAK_STAIRS);
            // Back door.
            doorwayAlongZ(x1, -1, 2, G + 1);
            doorZ(x1, G + 1, -1, DARK_OAK_DOOR);
            doorZ(x1, G + 1, 0, DARK_OAK_DOOR);

            pitchedRoof(x0, z0, x1, z1, wallTop, SLAB_OAK, OAK_PLANKS);

            // Crafting / smelting row along the north wall.
            set(x0 + 4, G + 1, -6, CRAFT_TABLE);
            set(x0 + 7, G + 1, -6, FURNACE);
            set(x0 + 10, G + 1, -6, ANVIL);
            set(x0 + 13, G + 1, -6, BREWING_STAND);
            set(x0 + 16, G + 1, -6, CAULDRON);
            // Enchanting corner: table ringed by bookshelves.
            set(x0 + 8, G + 1, 5, ENCHANTING_TABLE);
            for (int dx = -1; dx <= 1; dx++) {
                set(x0 + 8 + dx, G + 1, 3, BOOKSHELF);
                set(x0 + 8 + dx, G + 1, 7, BOOKSHELF);
            }
            set(x0 + 8, G + 2, 7, BOOKSHELF);
            // Storage wall.
            for (int i = 0; i < 6; i++) set(x0 + 3 + i * 3, G + 1, 9, CHEST);
            chestAt(x0 + 3, G + 1, 9, new String[] {"oak_planks", "cobblestone", "dirt", "stone"},
                    new int[] {64, 64, 64, 64});
            chestAt(x0 + 6, G + 1, 9, new String[] {"glass", "sand", "sandstone", "brick"},
                    new int[] {64, 64, 64, 64});
            chestAt(x0 + 9, G + 1, 9, new String[] {"iron_ingot", "gold_ingot", "diamond", "emerald"},
                    new int[] {64, 64, 16, 16});
            chestAt(x0 + 12, G + 1, 9, new String[] {"coal", "charcoal", "torch", "stick"},
                    new int[] {64, 64, 64, 64});
            chestAt(x0 + 15, G + 1, 9, new String[] {"apple", "bread", "cooked_beef", "cooked_chicken"},
                    new int[] {32, 32, 32, 32});
            chestAt(x0 + 18, G + 1, 9, new String[] {"book", "writable_book", "written_book", "enchanted_book"},
                    new int[] {16, 16, 8, 4});
            // Book shelf display along the north wall.
            for (int i = 0; i < 5; i++) set(x0 + 2 + i * 2, G + 2, -9, OAK_PLANKS);
            set(x0 + 2, G + 3, -9, ITEM_BOOK);
            set(x0 + 4, G + 3, -9, ITEM_WRITABLE_BOOK);
            set(x0 + 6, G + 3, -9, ITEM_WRITTEN_BOOK);
            set(x0 + 8, G + 3, -9, ITEM_ENCHANTED_BOOK);
            set(x0 + 10, G + 3, -9, BOOKSHELF);
            // Loft: ladders up the west wall, a trapdoor table, a flower pot.
            for (int y = G + 1; y <= G + 3; y++) set(x0 + 1, y, -8, LADDER);
            set(x0 + 14, G + 1, 5, MARBLE);
            set(x0 + 14, G + 2, 5, TRAPDOOR);
            pad(x0 + 2, 6, x0 + 2, 6, G + 1, MARBLE);
            set(x0 + 2, G + 2, 6, FLOWER_POT);
            set(x0 + 2, G + 3, 6, POPPY);
        }

        // ── Redstone & Machines ────────────────────────────────────────────
        private void buildRedstoneWorks() {
            int x0 = -46, x1 = -26, z0 = -10, z1 = 10, wallTop = G + 5;
            pad(x0, z0, x1, z1, G, STONEBRICK);
            walls(x0, z0, x1, z1, G + 1, wallTop, STONEBRICK);
            windowBand(x0, z0, x1, z1, G + 3, 4);

            // East entrance (faces the plaza) with double doors.
            doorwayAlongZ(x1, -1, 2, G + 1);
            doorZ(x1, G + 1, -1, SPRUCE_DOOR);
            doorZ(x1, G + 1, 0, SPRUCE_DOOR);
            for (int z = -2; z <= 1; z++) set(x1, G + 3, z, STONEBRICK);

            pitchedRoof(x0, z0, x1, z1, wallTop, SLAB_STONE_BRICK, STONEBRICK);

            // Redstone demonstration on a raised marble plinth.
            pad(x0 + 2, -7, x1 - 2, -5, G + 1, MARBLE);
            int y = G + 2;
            for (int x = x0 + 2; x <= x1 - 2; x++) set(x, y, -6, REDSTONE_WIRE);
            set(x0 + 4, y, -6, REDSTONE_BLOCK);
            set(x0 + 7, y, -6, REDSTONE_TORCH);
            set(x0 + 10, y, -6, REDSTONE_LAMP);
            set(x0 + 13, y, -6, PISTON);
            set(x0 + 16, y, -6, DISPENSER);
            set(x0 + 18, y, -6, DROPPER);
            set(x0 + 3, y, -5, HOPPER);
            set(x0 + 6, y, -5, NOTE_BLOCK);
            set(x0 + 9, y, -5, REDSTONE_LAMP);

            // Machine bank: one row standing on the floor.
            int[] machines = {COGWHEEL, LARGE_COGWHEEL, WATER_WHEEL, HAND_CRANK,
                    WINDMILL_BEARING, WINDMILL_SAIL, MECHANICAL_PRESS, MILLSTONE,
                    CRUSHING_WHEEL, MECHANICAL_DRILL, MECHANICAL_SAW, DEPLOYER,
                    BELT_CONVEYOR, ITEM_VAULT, BRASS_CASING, STEAM_ENGINE,
                    BLAZE_BURNER, COPPER_BLOCK, RAIL_NS, STEEL_BEAM};
            for (int i = 0; i < machines.length; i++) {
                set(x0 + 1 + i, G + 1, 8, machines[i]);
            }
            // A trapdoor table and a flower pot for scale reference.
            pad(x0 + 3, 4, x0 + 3, 4, G + 1, MARBLE);
            set(x0 + 3, G + 2, 4, TRAPDOOR);
            set(x0 + 6, G + 1, 4, FLOWER_POT);
            set(x0 + 6, G + 2, 4, TULIP);

            // Beacon landmark outside the west wall.
            int bx = x0 - 5, bz = 0;
            for (int i = 0; i <= 2; i++)
                for (int x = bx - i; x <= bx + i; x++)
                    for (int z = bz - i; z <= bz + i; z++) set(x, G + i, z, IRON_BLOCK);
            set(bx, G + 3, bz, BEACON);
            for (int gy = G + 4; gy <= G + 8; gy++) set(bx, gy, bz, GLASS);
            // Contained lava pool (obsidian shell so it cannot spread).
            for (int x = bx - 2; x <= bx + 2; x++)
                for (int z = bz + 5; z <= bz + 9; z++) set(x, G + 1, z, OBSIDIAN);
            for (int x = bx - 1; x <= bx + 1; x++)
                for (int z = bz + 6; z <= bz + 8; z++) set(x, G + 1, z, LAVA);
            chestAt(bx, G + 1, bz + 11, new String[] {"redstone_block", "torch", "iron_ingot", "stick"},
                    new int[] {32, 32, 32, 32});
        }

        // ── Farm & Nature ──────────────────────────────────────────────────
        private void buildFarm() {
            int x0 = -14, x1 = 14, z0 = 20, z1 = 48;
            // Tilled fields with irrigation channels every fifth column.
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (x % 5 == 0) {
                        set(x, G, z, WATER);
                    } else {
                        set(x, G, z, FARMLAND_WET);
                        set(x, G + 1, z, (z % 2 == 0) ? REEDS : (x % 3 == 0 ? PUMPKIN : TALLGRASS));
                    }
                }
            }
            // Melon patch and a cactus bed.
            for (int z = z0; z <= z1; z += 4) {
                set(x1 + 2, G, z, FARMLAND_WET);
                set(x1 + 2, G + 1, z, MELON);
            }
            for (int x = x0 - 4; x <= x0 - 2; x++)
                for (int z = z0; z <= z0 + 8; z += 4) {
                    set(x, G, z, SAND);
                    set(x, G + 1, z, CACTUS);
                }
            // Flower border along the south edge.
            for (int x = x0; x <= x1; x += 2) {
                set(x, G, z1 + 3, GRASS);
                set(x, G + 1, z1 + 3, POPPY);
                set(x, G, z1 + 5, GRASS);
                set(x, G + 1, z1 + 5, x % 4 == 0 ? TULIP : DANDELION);
                set(x, G, z1 + 7, GRASS);
                set(x, G + 1, z1 + 7, AZURE_BLUET);
                set(x, G, z1 + 9, GRASS);
                set(x, G + 1, z1 + 9, FERN);
            }

            // Orchard: one tree of every wood type along the east side.
            tree(10, 24, OAK_LOG, OAK_LEAVES);
            tree(14, 28, BIRCH_LOG, OAK_LEAVES);
            tree(10, 32, SPRUCE_LOG, OAK_LEAVES);
            tree(14, 36, JUNGLE_LOG, OAK_LEAVES);
            tree(10, 40, ACACIA_LOG, OAK_LEAVES);
            tree(14, 44, DARK_OAK_LOG, OAK_LEAVES);
            // Flower pots on the walkway into the farm.
            set(-3, G + 1, 20, FLOWER_POT);
            set(-3, G + 2, 20, ROSE);
            set(3, G + 1, 20, FLOWER_POT);
            set(3, G + 2, 20, TULIP);

            // Pond, walled by solid ground on every side so it cannot spread.
            for (int x = -12; x <= -4; x++)
                for (int z = 40; z <= 46; z++) set(x, G, z, WATER);
            for (int x = -13; x <= -3; x++) {
                set(x, G, 39, GRASS);
                set(x, G, 47, GRASS);
            }
            set(-10, G + 1, 43, WATERLILY);
            set(-7, G + 1, 42, WATERLILY);
            set(-6, G + 1, 45, WATERLILY);

            buildBarn();
        }

        private void buildBarn() {
            int bx0 = -12, bx1 = -2, bz0 = 24, bz1 = 34, wallTop = G + 4;
            pad(bx0, bz0, bx1, bz1, G, STONEBRICK);
            walls(bx0, bz0, bx1, bz1, G + 1, wallTop, WOOL);
            // Corner posts.
            for (int y = G + 1; y <= wallTop; y++) {
                set(bx0, y, bz0, OAK_LOG);
                set(bx1, y, bz0, OAK_LOG);
                set(bx0, y, bz1, OAK_LOG);
                set(bx1, y, bz1, OAK_LOG);
            }
            // East entrance: a two-wide double door with a hay-loft above.
            doorwayAlongZ(bx1, 28, 2, G + 1);
            doorZ(bx1, G + 1, 28, OAK_DOOR);
            doorZ(bx1, G + 1, 29, OAK_DOOR);
            for (int z = 27; z <= 30; z++) set(bx1, G + 3, z, WOOL);
            pitchedRoof(bx0, bz0, bx1, bz1, wallTop, SLAB_OAK, OAK_PLANKS);
            // A trapdoor hatch on the loft floor and a ladder to reach it.
            for (int y = G + 1; y <= G + 3; y++) set(bx0 + 1, y, bz0 + 1, LADDER);
            set(bx0 + 3, G + 1, bz1 - 3, CHEST);
            chestAt(bx0 + 3, G + 1, bz1 - 3,
                    new String[] {"apple", "bread", "stick", "cooked_beef"},
                    new int[] {32, 16, 8, 8});
            set(bx0 + 6, G + 1, bz1 - 3, CRAFT_TABLE);
            set(bx0 + 8, G + 1, bz1 - 3, MARBLE);
            set(bx0 + 8, G + 2, bz1 - 3, TRAPDOOR);
            set(bx0 + 4, G + 1, bz0 + 6, FLOWER_POT);
            set(bx0 + 4, G + 2, bz0 + 6, DANDELION);
        }

        // ── Garden details (plaza edge) ────────────────────────────────────
        private void buildGardenDetails() {
            // Low garden walls flanking the plaza entrances.
            for (int a = 6; a <= 11; a++) {
                set(a, G + 1, -14, SLAB_STONE_BRICK);
                set(-a, G + 1, -14, SLAB_STONE_BRICK);
                set(a, G + 1, 15, SLAB_STONE_BRICK);
                set(-a, G + 1, 15, SLAB_STONE_BRICK);
            }
            // Flower pots and rose bushes at the plaza corners.
            int[][] spots = {{-10, -8}, {10, -8}, {-10, 8}, {10, 8}};
            for (int[] s : spots) {
                set(s[0], G + 1, s[1], FLOWER_POT);
                set(s[0], G + 2, s[1], ROSE);
            }
        }
    }

    private ShowcaseWorldAuthor() {}
}
