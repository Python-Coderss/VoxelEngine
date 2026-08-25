package com.voxel.world;

import com.voxel.game.ChestManager;
import com.voxel.game.ItemDefinitions;

/**
 * The hand-authored Tutorial World, in full.
 *
 * This class is the single source of truth for the hand-built showcase world.
 * It writes the ENTIRE world eagerly through a {@link Sink} — no streaming, no
 * chunk gating, no randomness — so buildings are never truncated at chunk
 * boundaries. The result is deterministic and byte-for-byte reproducible on
 * every run.
 *
 * The author is consumed by {@code TutorialWorldExporter}, which materialises
 * the world into bundled chunk files (shipped under {@code src/main/resources},
 * git-tracked). At runtime the Tutorial World is simply copied into the save
 * directory and loaded from disk like any other save.
 */
public final class TutorialWorldAuthor {

    /** Surface height of the flat tutorial plain. */
    public static final int G = TutorialWorldGenerator.GROUND;

    /** Handcrafted area: 48x48 chunk columns centred on spawn (blocks -384..383). */
    public static final int AREA_CHUNKS = 48;
    public static final int MIN = -(AREA_CHUNKS / 2) * 16; // -384
    public static final int MAX = (AREA_CHUNKS / 2) * 16 - 1; // 383
    /** Handcrafted height: y 0..111 (7 sections), covers every build + bedrock. */
    public static final int AREA_H = 112;

    // ── Block IDs (mirror Main.registerBlock) ──
    private static final int GRASS = 1, STONE = 2, GLASS = 3, OAK_LEAF = 4, OAK_LOG = 5,
            DIRT = 13, SAND = 14, WATER = 15, OBSIDIAN = 16, GLOWSTONE = 17, END_STONE = 18,
            NETHER_PORTAL = 19, NETHERRACK = 20, SOUL_SAND = 22, NETHER_BRICKS = 24,
            REDSTONE_BLOCK = 25, REDSTONE_ORE = 26, R_TORCH = 27, LAMP = 28, WIRE = 29,
            REEDS = 40, PUMPKIN = 42, MELON = 43, CACTUS = 39, CLAY = 55, COAL_ORE = 61,
            LAVA = 21, TORCH = 211, WATERLILY = 41,
            POPPY = 34, TALLGRASS = 35, DEADBUSH = 36, BROWN_MUSHROOM = 37, RED_MUSHROOM = 38,
            DANDELION = 121, ROSE = 122, FERN = 64, MINECART = 393,
            GRAVEL = 54, COBBLE = 71, PLANKS = 72, IRON_ORE = 81, GOLD_ORE = 82, DIAMOND_ORE = 83, LAPIS_ORE = 85,
            ANDESITE = 262,
            WOOL = 91, AETHER_PORTAL = 106, CRAFT_TABLE = 115, FURNACE = 116, CHEST = 118,
            BRICK = 130, STONE_BRICK = 131, MOSSY = 132, BOOKSHELF = 136,
            IRON_BLOCK = 137, GOLD_BLOCK = 138, DIAMOND_BLOCK = 139, EMERALD_BLOCK = 140, LAPIS_BLOCK = 141,
            COPPER_ORE = 142, ZINC_ORE = 144, SPAWNER = 258, FAN = 263, TV = 274,
            SHAFT = 291, SHAFT_X = 292, COG = 294, LARGE_COG = 295, WHEEL = 296,
            RAIL_NS = 391, RAIL_EW = 392,
            RAIL_CURVE_SE = 450, RAIL_CURVE_SW = 451, RAIL_CURVE_NW = 452, RAIL_CURVE_NE = 453,
            BURNER = 394, BURNER_LIT = 395, ENGINE_COLD = 396, ENGINE = 397, TANK = 398,
            CRANK = 404, BEARING = 405, SAIL = 406, PRESS = 407, MILL = 408, CRUSHER = 409,
            DRILL = 410, SAW = 411, BELT = 413, VAULT = 414, CLUTCH = 353, GEARSHIFT = 355,
            // ── End Update blocks (fixed IDs registered in Main) ──
            PURPUR = 900, PURPUR_PILLAR = 901, CHORUS = 902, CHORUS_FLOWER = 903,
            END_GLASS = 904, VOID_STEEL = 905,
            BEACON = 457, ENCHANT_TABLE = 458, END_BRICKS = 464, END_ROD = 465, DRAGON_EGG = 466;

    private static final int RS_PISTON = com.voxel.world.RedstoneManager.BLOCK_PISTON;
    private static final int RS_STICKY = com.voxel.world.RedstoneManager.BLOCK_STICKY_PISTON;
    private static final int RS_REPEATER = com.voxel.world.RedstoneManager.BLOCK_REPEATER_BASE;
    private static final int RS_COMPARATOR = com.voxel.world.RedstoneManager.BLOCK_COMPARATOR_BASE;

    /** A named showcase zone (used for the in-game title-card popups). */
    public static final class Zone {
        public final String name;
        public final String subtitle;
        public final int cx, cz;
        public final int radius;

        Zone(String name, String subtitle, int cx, int cz, int radius) {
            this.name = name;
            this.subtitle = subtitle;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
        }
    }

    /** The tour, laid out on the 768x768 handcrafted grid centred on spawn. */
    private static final Zone[] ZONES = {
        new Zone("Tutorial Castle", "The moated tutorial hub. Each compass gate leads to a feature tour.", 0, 0, 26),
        new Zone("Create Machine Works", "Kinetic power: water wheel + windmill drive belts, presses, crushers, drills & saws.", 0, -160, 22),
        new Zone("Redstone Laboratory", "Lamps, pistons, repeaters & comparators — redstone logic in action.", 160, 0, 22),
        new Zone("Minecart Coaster", "Rails + a rideable minecart. Right-click the cart to hop in.", 0, 160, 22),
        new Zone("Biome Garden", "A living palette: every biome grass, leaves and tree type in one garden.", -160, 0, 22),
        new Zone("Village", "A villager hamlet with houses, a plaza and a TV set.", 160, -160, 24),
        new Zone("Quarry Mine", "Dig deep: exposed coal, iron, gold, diamond, lapis, copper & zinc.", 160, 160, 22),
        new Zone("Nether Temple", "Obsidian portal + nether bricks, soul sand, glowstone and a blaze shrine.", -160, -160, 22),
        new Zone("Aether Outpost", "The floating-island dimension: aether portal and end-stone spire.", -160, 160, 22),
        new Zone("Combat Arena", "A walled gladiator ring — fight mobs, mind the lava moat.", 320, 0, 24),
        new Zone("Farm & Food", "Pumpkins, melons, reeds and a stocked granary.", 0, -320, 22),
        new Zone("Storage Vault", "Item vaults and treasure chests full of every resource.", -320, 0, 22),
        new Zone("Smelting Works", "Furnaces, blaze burners and steam engines — the heat of industry.", 320, -320, 22),
        new Zone("Grand Throne Castle", "A towering stone-brick keep with turrets, banners and a gold throne.", -320, -320, 24),
        new Zone("The Barren Isles", "An End update exhibit: drifting end-stone isles, obsidian monoliths, dead purpur ruins and the last chorus grove.", -320, 160, 22),
    };

    public static Zone[] zones() { return ZONES; }

    /** Zone index of the Minecart Coaster (rideable carts spawn on entry). */
    public static final int MINECART_ZONE = 3;

    /**
     * Rideable minecart entity spawn points (x, y, z) on the coaster's straight
     * rails. y is the rail-top height, so each cart sits on its track and can be
     * driven with W/S (forward/reverse along the rail). These are spawned as live
     * {@code MinecartEntity} instances, not static minecart blocks.
     */
    public static float[][] minecartSpawns() {
        float y = G + 1 + com.voxel.entity.MinecartEntity.RAIL_TOP;
        return new float[][] {
            { 3.5f,  y, 144.5f },  // top rail (east-west)
            { -5.5f, y, 176.5f },  // bottom rail (east-west)
            { -15.5f,y, 160.5f },  // left rail (north-south)
        };
    }

    /** Index of the zone whose radius contains (x,z), or -1. */
    public static int zoneAt(float x, float z) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < ZONES.length; i++) {
            float dx = x - ZONES[i].cx;
            float dz = z - ZONES[i].cz;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d <= ZONES[i].radius && d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** A target that receives every voxel the author places (type + facing). */
    public interface Sink {
        void set(int x, int y, int z, int type, int extra);

        /** Reads back a placed voxel's block type (0 = air), or 0 if unknown. */
        default int get(int x, int y, int z) { return 0; }
    }

    /**
     * Builds the complete handcrafted world into {@code sink} and records chest
     * inventories into {@code chests}. The sink is the sole write target, so the
     * exporter can back it with a dense in-memory array (fast) or a World.
     */
    public static void buildAll(Sink sink, ChestManager chests) {
        Author a = new Author(sink, chests);
        a.buildBaseTerrain();
        for (int i = 0; i < ZONES.length; i++) a.buildZone(i);
        a.buildFill();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Inner author: holds the write targets and all helpers
    // ══════════════════════════════════════════════════════════════════

    private static final class Author {
        final Sink sink;
        final ChestManager chests;

        Author(Sink sink, ChestManager chests) {
            this.sink = sink;
            this.chests = chests;
        }

        private void buildBaseTerrain() {
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

        private void buildZone(int i) {
            Zone z = ZONES[i];
            switch (i) {
                case 0: buildSpawnPlaza(z); break;
                case 1: buildMachineWorks(z); break;
                case 2: buildRedstoneLab(z); break;
                case 3: buildMinecartCoaster(z); break;
                case 4: buildBiomeGarden(z); break;
                case 5: buildVillage(z); break;
                case 6: buildQuarry(z); break;
                case 7: buildNetherTemple(z); break;
                case 8: buildAetherOutpost(z); break;
                case 9: buildCombatArena(z); break;
                case 10: buildFarm(z); break;
                case 11: buildStorageVault(z); break;
                case 12: buildSmeltingWorks(z); break;
                case 13: buildThroneCastle(z); break;
                case 14: buildBarrenIsles(z); break;
            }
        }

        // ── Zone builds ───────────────────────────────────────────────

        private void buildSpawnPlaza(Zone z) {
            int x = z.cx, zz = z.cz;

            // Courtyard.
            floor(x - 12, zz - 12, x + 12, zz + 12, G, STONE_BRICK);

            // Curtain wall with crenellations.
            int w0 = -13, w1 = 13, wh = 4;
            for (int a = w0; a <= w1; a++) {
                for (int b = w0; b <= w1; b++) {
                    if (a != w0 && a != w1 && b != w0 && b != w1) continue;
                    for (int h = 1; h <= wh; h++) place(x + a, G + h, zz + b, STONE_BRICK);
                    if (((a + b) & 1) == 0) place(x + a, G + wh + 1, zz + b, STONE_BRICK);
                }
            }

            // Four gates + drawbridges (N/S/E/W).
            for (int g = -1; g <= 1; g++) for (int h = 1; h <= 3; h++) set(x + g, G + h, zz - 13, 0);
            place(x, G + 4, zz - 13, GLOWSTONE);
            for (int g = -1; g <= 1; g++) for (int s = 1; s <= 3; s++) place(x + g, G + 1, zz - 13 - s, PLANKS);
            for (int g = -1; g <= 1; g++) for (int h = 1; h <= 3; h++) set(x + g, G + h, zz + 13, 0);
            place(x, G + 4, zz + 13, GLOWSTONE);
            for (int g = -1; g <= 1; g++) for (int s = 1; s <= 3; s++) place(x + g, G + 1, zz + 13 + s, PLANKS);
            for (int g = -1; g <= 1; g++) for (int h = 1; h <= 3; h++) set(x + 13, G + h, zz + g, 0);
            place(x + 13, G + 4, zz, GLOWSTONE);
            for (int g = -1; g <= 1; g++) for (int s = 1; s <= 3; s++) place(x + 13 + s, G + 1, zz + g, PLANKS);
            for (int g = -1; g <= 1; g++) for (int h = 1; h <= 3; h++) set(x - 13, G + h, zz + g, 0);
            place(x - 13, G + 4, zz, GLOWSTONE);
            for (int g = -1; g <= 1; g++) for (int s = 1; s <= 3; s++) place(x - 13 - s, G + 1, zz + g, PLANKS);

            // Moat ring (skips the four drawbridge crossings).
            for (int mx = -16; mx <= 16; mx++) {
                for (int mz = -16; mz <= 16; mz++) {
                    int md = Math.max(Math.abs(mx), Math.abs(mz));
                    if (md != 15 && md != 16) continue;
                    boolean bridge = (mz <= -14 && Math.abs(mx) <= 1)
                        || (mz >= 14 && Math.abs(mx) <= 1)
                        || (mx >= 14 && Math.abs(mz) <= 1)
                        || (mx <= -14 && Math.abs(mz) <= 1);
                    if (!bridge) water(x + mx, G, zz + mz);
                }
            }

            // Corner towers.
            tower(x - 13, zz - 13, G, 7, STONE_BRICK);
            tower(x + 13, zz - 13, G, 7, STONE_BRICK);
            tower(x - 13, zz + 13, G, 7, STONE_BRICK);
            tower(x + 13, zz + 13, G, 7, STONE_BRICK);

            // Central keep (north courtyard): throne hall with treasury.
            int kx0 = x - 3, kx1 = x + 3, kz0 = zz - 10, kz1 = zz - 5;
            hollow(kx0, G, kz0, kx1, G + 8, kz1, STONE_BRICK);
            for (int c = kx0; c <= kx1; c += 2) { place(c, G + 9, kz0, STONE_BRICK); place(c, G + 9, kz1, STONE_BRICK); }
            for (int c = kz0; c <= kz1; c += 2) { place(kx0, G + 9, c, STONE_BRICK); place(kx1, G + 9, c, STONE_BRICK); }
            for (int g = -1; g <= 1; g++) for (int h = 1; h <= 3; h++) set(x + g, G + h, kz1, 0);
            place(x - 3, G + 1, kz0 + 1, GOLD_BLOCK); place(x - 2, G + 1, kz0 + 1, GOLD_BLOCK);
            place(x + 2, G + 1, kz0 + 1, GOLD_BLOCK); place(x + 3, G + 1, kz0 + 1, GOLD_BLOCK);
            place(x - 3, G + 2, kz0 + 1, GOLD_BLOCK); place(x + 3, G + 2, kz0 + 1, GOLD_BLOCK);
            place(x - 3, G + 3, kz0 + 1, WOOL); place(x + 3, G + 3, kz0 + 1, WOOL);
            place(x, G + 1, kz0 + 1, WOOL); place(x, G + 2, kz0 + 1, WOOL); place(x, G + 3, kz0 + 1, GLOWSTONE);
            for (int b = 0; b < 4; b++) { place(x - 2 + b, G + 1, kz0 + 3, BOOKSHELF); place(x - 2 + b, G + 2, kz0 + 3, BOOKSHELF); }
            place(x - 3, G + 1, kz0 + 2, CHEST);
            chestAt(x - 3, G + 1, kz0 + 2, new String[]{"gold_block","diamond_block","emerald_block","stone_brick","torch","bread","iron_ingot"}, new int[]{4,2,2,64,32,16,16});

            // Courtyard fountain (spawn centre).
            for (int fx = x - 1; fx <= x + 1; fx++)
                for (int fz = zz - 1; fz <= zz + 1; fz++) water(fx, G, fz);
            place(x, G + 1, zz, GLOWSTONE); place(x, G + 2, zz, GLOWSTONE); place(x, G + 3, zz, GLOWSTONE);

            // Tutorial stations along the east wall.
            place(x + 11, G + 1, zz - 4, CRAFT_TABLE);
            place(x + 11, G + 1, zz - 3, FURNACE);
            for (int b = 0; b < 4; b++) place(x + 8 + b, G + 1, zz - 6, BOOKSHELF);
            for (int b = 0; b < 4; b++) place(x + 8 + b, G + 2, zz - 6, BOOKSHELF);
            place(x + 11, G + 1, zz + 4, CHEST);
            chestAt(x + 11, G + 1, zz + 4, new String[]{"stone_brick","cobblestone","oak_planks","glass","torch","bread","gold_block","glowstone","iron_ingot","coal"}, new int[]{64,64,64,16,32,16,4,8,16,32});

            // Library & enchanting wing along the west wall: bookshelf rows
            // with two enchanting tables so progression has a home.
            for (int lz = zz - 5; lz <= zz + 3; lz += 2) {
                place(x - 11, G + 1, lz, BOOKSHELF);
                place(x - 11, G + 2, lz, BOOKSHELF);
            }
            place(x - 10, G + 1, zz - 2, ENCHANT_TABLE);
            place(x - 10, G + 1, zz + 2, ENCHANT_TABLE);
            place(x - 10, G + 3, zz, TORCH);

            // Beacon pyramid in the south courtyard: iron/gold/diamond tiers
            // crowned with a beacon — a visible landmark from every gate.
            for (int px = -2; px <= 2; px++)
                for (int pz = -2; pz <= 2; pz++) place(x + px, G + 1, zz + 8 + pz, IRON_BLOCK);
            for (int px = -1; px <= 1; px++)
                for (int pz = -1; pz <= 1; pz++) place(x + px, G + 2, zz + 8 + pz, GOLD_BLOCK);
            place(x, G + 3, zz + 8, DIAMOND_BLOCK);
            place(x, G + 4, zz + 8, BEACON);

            // Torches ringing the courtyard.
            for (int t = -8; t <= 8; t += 4) {
                place(x + t, G + 1, zz - 12, TORCH); place(x + t, G + 1, zz + 12, TORCH);
                place(x - 12, G + 1, zz + t, TORCH); place(x + 12, G + 1, zz + t, TORCH);
            }
        }

        private void buildMachineWorks(Zone z) {
            int x = z.cx, zz = z.cz;
            floor(x - 14, zz - 10, x + 14, zz + 10, G, STONE_BRICK);

            // Water channel feeding the water wheel. The wheel is a vertical disc
            // with an east-west axle (axis X) dipping into the channel, so
            // KineticManager.hasAdjacentWater() sees it as a rotation source and the
            // whole network actually spins.
            for (int wx = x - 11; wx <= x - 8; wx++) water(wx, G, zz - 8);
            placeWaterWheel(x - 10, G + 1, zz - 8, 5);   // facing east -> axis X
            placeLargeCog(x - 10, G + 4, zz - 8, 5);     // axis X, coaxial above the wheel

            // X-axis shaft line running east from the wheel's axle. The large cog
            // meshes coaxially on top of the wheel; the machines one block south
            // are driven off any side of the line.
            for (int sx = x - 9; sx <= x + 12; sx++) place(sx, G + 1, zz - 8, SHAFT_X);
            place(x - 9, G + 1, zz - 8, GEARSHIFT);
            place(x + 1, G + 1, zz - 8, CLUTCH);

            // Machines one block south of the shaft line: adjacent, so they're
            // powered and their textures spin.
            place(x + 2, G + 1, zz - 7, MILL, 5);
            place(x + 4, G + 1, zz - 7, PRESS, 5);
            place(x + 6, G + 1, zz - 7, CRUSHER, 5);
            place(x + 8, G + 1, zz - 7, SAW, 5);
            place(x + 10, G + 1, zz - 7, DRILL, 5);

            // Belt conveyor feeding the item vault (adjacent to the machine row).
            for (int n = 0; n <= 4; n++) place(x + 2 + n * 2, G + 1, zz - 6, BELT, 5);
            place(x + 12, G + 1, zz - 6, VAULT);

            // Hand crank: right-click it to jump-start the whole line manually.
            place(x - 4, G + 1, zz - 7, CRANK);

            // Windmill (a second rotation source): bearing with sails exposed to
            // open air on three sides.
            place(x + 11, G + 1, zz + 8, BEARING);
            place(x + 11, G + 1, zz + 7, SAIL);   // north
            place(x + 11, G + 1, zz + 9, SAIL);   // south
            place(x + 10, G + 1, zz + 8, SAIL);   // west
            place(x + 12, G + 1, zz + 8, SAIL);   // east

            // Heat line (blaze burner -> steam engine -> copper tank -> fan).
            place(x + 4, G + 1, zz + 5, BURNER);
            place(x + 5, G + 1, zz + 5, ENGINE_COLD); place(x + 5, G + 2, zz + 5, ENGINE);
            place(x + 6, G + 1, zz + 5, TANK); place(x + 6, G + 2, zz + 5, TANK + 5);
            place(x + 7, G + 1, zz + 5, FAN, 5);

            stamp(BARN, x - 14, G, zz + 2);
            for (int fx = x - 14; fx <= x + 14; fx += 4) { place(fx, G + 1, zz - 10, ANDESITE); place(fx, G + 1, zz + 10, ANDESITE); }
            for (int fz = zz - 10; fz <= zz + 10; fz += 4) { place(x - 14, G + 1, fz, ANDESITE); place(x + 14, G + 1, fz, ANDESITE); }
        }

        private void buildRedstoneLab(Zone z) {
            int x = z.cx, zz = z.cz;
            hollow(x - 14, G, zz - 10, x + 14, G + 6, zz + 10, BRICK);
            set(x, G + 1, zz - 10, 0); set(x, G + 2, zz - 10, 0);
            for (int w = 0; w < 4; w++) { set(x - 12 + w * 4, G + 2, zz - 10, GLASS); set(x - 12 + w * 4, G + 3, zz - 10, GLASS); }
            for (int w = 0; w < 4; w++) { set(x - 12 + w * 4, G + 2, zz + 10, GLASS); set(x - 12 + w * 4, G + 3, zz + 10, GLASS); }

            // ── Display wall: all 16 lamp colors, each sitting on a redstone
            //    block so the whole spectrum stays lit.
            for (int c = 0; c < 16; c++) {
                int lampId = com.voxel.world.RedstoneManager.BLOCK_LAMP_BASE + c * 2;
                place(x - 10 + c, G + 1, zz - 8, lampId);
                place(x - 10 + c, G, zz - 8, REDSTONE_BLOCK);
            }

            // Facing note: repeater/comparator facing comes from the BLOCK ID,
            // not the extra byte. BASE+2=north, +3=south, +4=west, +5=east.
            // Extra byte low nibble = repeater delay (1-4).
            final int REP_EAST = com.voxel.world.RedstoneManager.BLOCK_REPEATER_BASE + 3;
            final int CMP_EAST = com.voxel.world.RedstoneManager.BLOCK_COMPARATOR_BASE + 3;
            final int STICKY_EAST = 273; // sticky piston facing east

            // ── Demo 1: repeater delay chain. Redstone block feeds a wire,
            //    three east-facing repeaters pass it on with rising delays,
            //    and the lamp pops on ~0.7s after the source.
            place(x - 13, G + 1, zz + 2, REDSTONE_BLOCK);
            place(x - 12, G + 1, zz + 2, WIRE);
            place(x - 11, G + 1, zz + 2, WIRE); // feeds the first repeater's back face
            place(x - 10, G + 1, zz + 2, REP_EAST, 1);
            place(x - 8, G + 1, zz + 2, REP_EAST, 2);
            place(x - 6, G + 1, zz + 2, REP_EAST, 4);
            place(x - 5, G + 1, zz + 2, WIRE);
            place(x - 4, G + 1, zz + 2, LAMP);

            // ── Demo 2: signal decay. Two wire runs fed from the same source:
            //    the short row stays fully bright; the long row fades and dies
            //    after 15 blocks (its far lamp never lights).
            place(x + 12, G + 1, zz + 4, REDSTONE_BLOCK);
            for (int d = 1; d <= 10; d++) place(x + 12 - d, G + 1, zz + 4, WIRE); // x+11 .. x+2
            place(x + 1, G + 1, zz + 4, LAMP);
            place(x + 12, G + 1, zz + 6, REDSTONE_BLOCK);
            for (int d = 1; d <= 19; d++) place(x + 12 - d, G + 1, zz + 6, WIRE); // x+11 .. x-7
            place(x - 8, G + 1, zz + 6, LAMP); // beyond the signal: stays dark

            // ── Demo 3: comparator measures chest fullness and drives a lamp.
            place(x + 6, G + 1, zz + 8, CHEST);
            chestAt(x + 6, G + 1, zz + 8, new String[]{"iron_ingot", "gold_ingot"}, new int[]{10, 5});
            place(x + 7, G + 1, zz + 8, CMP_EAST, 0);
            place(x + 8, G + 1, zz + 8, WIRE);
            place(x + 9, G + 1, zz + 8, LAMP);

            // ── Demo 4: torch powers an adjacent lamp; sticky piston (east)
            //    held extended by a redstone block behind it pushes its payload.
            place(x + 2, G + 1, zz, R_TORCH);
            place(x + 3, G + 1, zz, LAMP);
            place(x + 6, G + 1, zz, REDSTONE_BLOCK); // power source behind
            place(x + 7, G + 1, zz, STICKY_EAST, 0);
            place(x + 8, G + 1, zz, PLANKS);         // payload being pushed east

            for (int w = -4; w <= 4; w++) place(x + w, G, zz + 8, WOOL);
            for (int t = x - 14; t <= x + 14; t += 7) { place(t, G + 3, zz - 10, TORCH); place(t, G + 3, zz + 10, TORCH); }
            for (int b = 0; b < 5; b++) place(x - 10 + b * 3, G + 1, zz - 6, BOOKSHELF);

            // Reference chest with components to experiment with.
            place(x - 12, G + 1, zz + 8, CHEST);
            chestAt(x - 12, G + 1, zz + 8, new String[]{"redstone_torch", "redstone_block", "redstone_lamp", "redstone", "repeater", "comparator"}, new int[]{16, 8, 8, 32, 4, 4});
        }

        private void buildMinecartCoaster(Zone z) {
            int x = z.cx, zz = z.cz;
            int r = 16;
            // Outer ring: a rounded rectangle. Four curved corner rails connect
            // the straight E-W and N-S rails so carts ride a smooth loop around
            // the whole coaster (each curve cell links its two rail neighbours).
            for (int dx = -r + 1; dx <= r - 1; dx++) {
                place(x + dx, G, zz - r, GRAVEL); place(x + dx, G + 1, zz - r, RAIL_EW);
                place(x + dx, G, zz + r, GRAVEL); place(x + dx, G + 1, zz + r, RAIL_EW);
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                place(x + r, G, dz + zz, GRAVEL); place(x + r, G + 1, dz + zz, RAIL_NS);
                place(x - r, G, dz + zz, GRAVEL); place(x - r, G + 1, dz + zz, RAIL_NS);
            }
            place(x - r, G, zz - r, GRAVEL); place(x - r, G + 1, zz - r, RAIL_CURVE_SE); // top-left: east+south
            place(x + r, G, zz - r, GRAVEL); place(x + r, G + 1, zz - r, RAIL_CURVE_SW); // top-right: west+south
            place(x + r, G, zz + r, GRAVEL); place(x + r, G + 1, zz + r, RAIL_CURVE_NW); // bottom-right: north+west
            place(x - r, G, zz + r, GRAVEL); place(x - r, G + 1, zz + r, RAIL_CURVE_NE); // bottom-left: north+east
            // Inner ring: the same rounded-loop layout, one step in (16x16).
            for (int dz = -7; dz <= 7; dz++) {
                place(x - 8, G, zz + dz, GRAVEL); place(x - 8, G + 1, zz + dz, RAIL_NS);
                place(x + 8, G, zz + dz, GRAVEL); place(x + 8, G + 1, zz + dz, RAIL_NS);
            }
            for (int dx = -7; dx <= 7; dx++) {
                place(x + dx, G, zz - 8, GRAVEL); place(x + dx, G + 1, zz - 8, RAIL_EW);
                place(x + dx, G, zz + 8, GRAVEL); place(x + dx, G + 1, zz + 8, RAIL_EW);
            }
            place(x - 8, G, zz - 8, GRAVEL); place(x - 8, G + 1, zz - 8, RAIL_CURVE_SE);
            place(x + 8, G, zz - 8, GRAVEL); place(x + 8, G + 1, zz - 8, RAIL_CURVE_SW);
            place(x + 8, G, zz + 8, GRAVEL); place(x + 8, G + 1, zz + 8, RAIL_CURVE_NW);
            place(x - 8, G, zz + 8, GRAVEL); place(x - 8, G + 1, zz + 8, RAIL_CURVE_NE);
            // Rideable carts are spawned as live entities when the player enters
            // this zone (see minecartSpawns()); the rails here are their track.
            floor(x - 3, zz - r - 4, x + 3, zz - r - 2, G, STONE_BRICK);
            place(x, G + 1, zz - r - 3, CHEST);
            chestAt(x, G + 1, zz - r - 3, new String[]{"rail","minecart"}, new int[]{64,4});
            for (int t = x - 3; t <= x + 3; t += 3) place(t, G + 2, zz - r - 2, TORCH);
        }

        private void buildBiomeGarden(Zone z) {
            int x = z.cx, zz = z.cz;
            floor(x - 14, zz - 8, x + 14, zz + 8, G, DIRT);
            int[][] patches = {
                {-12, -5, 1},   // plains grass
                { 12, -5, 86},  // taiga grass
                {  0,  0, 87},  // jungle grass
                {-12,  5, 88},  // swamp grass
                { 12,  5, 89},  // savanna grass
                {  0,  7, 90},  // tundra grass
            };
            for (int[] p : patches)
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        place(x + p[0] + dx, G, zz + p[1] + dz, p[2]);
            tree(x - 12, zz - 5, OAK_LOG, OAK_LEAF);
            tree(x + 12, zz - 5, OAK_LOG, OAK_LEAF);
            tree(x - 12, zz + 5, OAK_LOG, OAK_LEAF);
            tree(x + 12, zz + 5, OAK_LOG, OAK_LEAF);
            tree(x - 6, zz - 7, OAK_LOG, OAK_LEAF);
            tree(x + 6, zz - 7, OAK_LOG, OAK_LEAF);
            tree(x, zz - 4, OAK_LOG, OAK_LEAF);
            floor(x - 2, zz - 8, x + 2, zz - 8, G, SAND);
            place(x - 2, G + 1, zz - 8, CACTUS); place(x + 2, G + 1, zz - 8, CACTUS);
            water(x, G, zz + 8); water(x + 1, G, zz + 8); water(x + 2, G, zz + 8);
            place(x - 1, G + 1, zz + 8, REEDS); place(x - 1, G + 2, zz + 8, REEDS);
            place(x + 3, G + 1, zz + 8, REEDS);
            place(x - 14, G + 1, zz - 8, CLAY); place(x - 14, G + 1, zz + 8, CLAY);
            stamp(HOUSE, x + 8, G, zz - 8);
        }

        private void buildVillage(Zone z) {
            int x = z.cx, zz = z.cz;
            floor(x - 16, zz - 16, x + 16, zz + 16, G, GRAVEL);
            stamp(COTTAGE, x - 15, G, zz - 15);
            stamp(COTTAGE, x + 6, G, zz - 15);
            stamp(HOUSE, x - 15, G, zz + 8);
            stamp(HOUSE, x + 6, G, zz + 8);
            stamp(COTTAGE, x - 4, G, zz + 8);
            stamp(WELL, x - 6, G, zz - 6);
            stamp(STALL, x + 3, G, zz - 4);
            stamp(STALL, x + 3, G, zz + 2);
            stamp(BARN, x - 12, G, zz - 6);
            place(x - 2, G + 1, zz + 15, TV);
            for (int t = -12; t <= 12; t += 6) {
                lampPost(x + t, G, zz - 12); lampPost(x + t, G, zz + 12);
                lampPost(x - 12, G, zz + t); lampPost(x + 12, G, zz + t);
            }
        }

        private void buildQuarry(Zone z) {
            int x = z.cx, zz = z.cz;
            for (int ring = 0; ring < 5; ring++) {
                int r = 13 - ring * 2;
                for (int dx = -r; dx <= r; dx++) {
                    place(x + dx, G - ring, zz - r, STONE);
                    place(x + dx, G - ring, zz + r, STONE);
                }
                for (int dz = -r; dz <= r; dz++) {
                    place(x - r, G - ring, zz + dz, STONE);
                    place(x + r, G - ring, zz + dz, STONE);
                }
            }
            int[][] ores = {{0,0,COAL_ORE},{-5,0,IRON_ORE},{5,0,GOLD_ORE},{0,-5,DIAMOND_ORE},{0,5,LAPIS_ORE},{-8,0,COPPER_ORE},{8,0,ZINC_ORE},{-3,-3,REDSTONE_ORE}};
            for (int[] o : ores) place(x + o[0], G - 3, zz + o[1], o[2]);
            for (int i = 0; i <= 4; i++) place(x - 13, G - i, zz + i, STONE_BRICK);
            place(x + 3, G - 4, zz, CHEST);
            chestAt(x + 3, G - 4, zz, new String[]{"coal","iron_ore","gold_ore","diamond","lapis_ore","copper_ore","zinc_ore","redstone_ore","torch"}, new int[]{8,6,4,2,4,6,6,6,16});
            place(x - 7, G + 1, zz - 7, TORCH); place(x + 7, G + 1, zz + 7, TORCH);
            stamp(HOUSE, x + 10, G, zz - 10);
            for (int i = 0; i < 6; i++) place(x + 14, G + 1 + i, zz - 2, OAK_LOG);
            for (int i = 0; i < 4; i++) place(x + 14 + i, G + 6, zz - 2, OAK_LOG);
        }

        private void buildNetherTemple(Zone z) {
            int x = z.cx, zz = z.cz;
            for (int i = 0; i < 4; i++) { place(x - 1 + i, G, zz - 6, OBSIDIAN); place(x - 1 + i, G + 4, zz - 6, OBSIDIAN); }
            for (int i = 1; i < 4; i++) { place(x - 1, G + i, zz - 6, OBSIDIAN); place(x + 2, G + i, zz - 6, OBSIDIAN); }
            for (int py = 1; py <= 3; py++) place(x, G + py, zz - 6, NETHER_PORTAL);
            floor(x - 8, zz - 10, x + 8, zz - 1, G, NETHER_BRICKS);
            for (int px = x - 8; px <= x + 8; px += 4) place(px, G + 1, zz - 10, NETHER_BRICKS);
            for (int s = 1; s <= 10; s++) place(x, G, zz - 10 - s, SOUL_SAND);
            for (int g = -5; g <= 5; g += 5) { place(x + g, G + 1, zz - 8, GLOWSTONE); place(x + g, G + 2, zz - 8, GLOWSTONE); }
            for (int nx = -5; nx <= 5; nx++) for (int nz = 1; nz <= 4; nz++) place(x + nx, G, zz + nz, NETHERRACK);
            for (int nx = -5; nx <= 5; nx += 2) place(x + nx, G + 1, zz + 1, GLOWSTONE);
            place(x, G + 1, zz + 5, BURNER_LIT);
            place(x + 1, G + 1, zz + 5, SPAWNER);
            place(x - 1, G + 1, zz + 5, REDSTONE_ORE);
            for (int nx = -4; nx <= 4; nx += 4) { place(x + nx, G + 1, zz + 3, NETHER_BRICKS); place(x + nx, G + 2, zz + 3, NETHER_BRICKS); }
            stamp(RUINS, x - 10, G, zz + 2);
            stamp(RUINS, x + 4, G, zz + 2);
        }

        private void buildAetherOutpost(Zone z) {
            int x = z.cx, zz = z.cz;
            for (int i = 0; i < 4; i++) { place(x - 1 + i, G, zz - 6, GLOWSTONE); place(x - 1 + i, G + 4, zz - 6, GLOWSTONE); }
            for (int i = 1; i < 4; i++) { place(x - 1, G + i, zz - 6, GLOWSTONE); place(x + 2, G + i, zz - 6, GLOWSTONE); }
            for (int py = 1; py <= 3; py++) place(x, G + py, zz - 6, AETHER_PORTAL);
            for (int h = 0; h <= 14; h++) place(x + 5, G + h, zz + 3, END_STONE);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) place(x + 5 + dx, G + 15, zz + 3 + dz, END_STONE);
            place(x + 5, G + 16, zz + 3, GLOWSTONE);
            for (int i = 0; i < 4; i++) {
                int ix = x - 7 + i * 5, iz = zz + 7;
                int iy = G + 5 + (i % 2) * 3;
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                    if (dx * dx + dz * dz <= 5) place(ix + dx, iy, iz + dz, END_STONE);
            }
            floor(x - 8, zz - 1, x + 10, zz + 1, G, END_STONE);
            stamp(SHRINE, x - 6, G, zz - 4);
            stamp(STATUE, x + 10, G, zz - 4);
        }

        private void buildCombatArena(Zone z) {
            int x = z.cx, zz = z.cz;
            for (int dx = -14; dx <= 14; dx++)
                for (int dz = -14; dz <= 14; dz++) {
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d <= 14 && d >= 12) {
                        place(x + dx, G, zz + dz, COBBLE);
                        if (d <= 13) place(x + dx, G + 1, zz + dz, COBBLE);
                    }
                }
            for (int dx = -16; dx <= 16; dx++)
                for (int dz = -16; dz <= 16; dz++) {
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d >= 15 && d <= 16) place(x + dx, G, zz + dz, LAVA);
                }
            int[][] corners = {{-14,-14},{14,-14},{-14,14},{14,14}};
            for (int[] c : corners) tower(x + c[0], zz + c[1], G, 6, COBBLE);
            for (int dx = -8; dx <= 8; dx += 8) { place(x + dx, G + 1, zz - 14, WOOL); place(x + dx, G + 1, zz + 14, WOOL); }
            for (int dz = -8; dz <= 8; dz += 8) { place(x - 14, G + 1, zz + dz, WOOL); place(x + 14, G + 1, zz + dz, WOOL); }
            place(x, G + 1, zz, SPAWNER);
            for (int t = -10; t <= 10; t += 10) { place(x + t, G + 1, zz, TORCH); place(x, G + 1, zz + t, TORCH); }
            for (int dx = -6; dx <= 6; dx++) { place(x + dx, G + 1, zz + 16, COBBLE); place(x + dx, G + 2, zz + 16, COBBLE); }
            stamp(WELL, x + 18, G, zz - 2);
        }

        private void buildFarm(Zone z) {
            int x = z.cx, zz = z.cz;
            for (int fx = x - 12; fx <= x + 12; fx++) water(fx, G, zz);
            for (int fz = zz - 7; fz <= zz + 7; fz += 7) water(x, G, fz);
            for (int fx = x - 12; fx <= x + 12; fx += 2) {
                int crop = ((fx - x) & 2) == 0 ? PUMPKIN : MELON;
                place(fx, G + 1, zz - 4, crop);
                place(fx, G + 1, zz + 4, ((fx - x) & 2) == 0 ? MELON : PUMPKIN);
            }
            for (int fx = x - 12; fx <= x + 12; fx += 3) {
                place(fx, G + 1, zz - 2, REEDS); place(fx, G + 2, zz - 2, REEDS);
                place(fx, G + 1, zz + 2, REEDS); place(fx, G + 2, zz + 2, REEDS);
            }
            stamp(BARN, x - 16, G, zz - 12);
            stamp(COTTAGE, x + 10, G, zz - 12);
            place(x - 15, G + 1, zz, OAK_LOG); place(x - 15, G + 2, zz, OAK_LOG); place(x - 15, G + 3, zz, WOOL);
            place(x - 16, G + 2, zz, OAK_LOG); place(x - 14, G + 2, zz, OAK_LOG);
            place(x - 16, G + 1, zz - 4, WOOL); place(x - 15, G + 1, zz - 4, WOOL);
            place(x - 16, G + 1, zz - 3, CRAFT_TABLE); place(x - 14, G + 1, zz - 3, FURNACE);
            place(x + 12, G + 1, zz - 12, CHEST);
            chestAt(x + 12, G + 1, zz - 12, new String[]{"bread","pumpkin","melon","wheat","apple","carrot","reeds","torch"}, new int[]{16,8,8,16,8,8,16,16});
        }

        private void buildStorageVault(Zone z) {
            int x = z.cx, zz = z.cz;
            hollow(x - 10, G, zz - 10, x + 10, G + 7, zz + 10, STONE_BRICK);
            set(x, G + 1, zz - 10, 0); set(x, G + 2, zz - 10, 0);
            for (int w = -8; w <= 8; w += 4) { set(x + w, G + 2, zz + 10, GLASS); set(x + w, G + 3, zz + 10, GLASS); }
            for (int dx = -8; dx <= 8; dx += 4) for (int dz = -8; dz <= 8; dz += 4) {
                if (dx == 0 && dz == 0) continue;
                place(x + dx, G + 1, zz + dz, VAULT);
                place(x + dx, G + 2, zz + dz, VAULT);
            }
            for (int dx = -3; dx <= 3; dx += 3) for (int dz = -3; dz <= 3; dz += 3) {
                if (dx == 0 && dz == 0) continue;
                place(x + dx, G, zz + dz, GOLD_BLOCK);
            }
            place(x - 10, G + 1, zz, CHEST);
            chestAt(x - 10, G + 1, zz, new String[]{"diamond_block","emerald_block","gold_block","iron_block","lapis_block","redstone_block","nether_bricks","end_stone"}, new int[]{2,2,4,8,4,8,16,16});
            place(x + 10, G + 1, zz, CHEST);
            chestAt(x + 10, G + 1, zz, new String[]{"coal","iron_ore","gold_ore","diamond","copper_ore","zinc_ore","obsidian","glowstone"}, new int[]{32,16,8,4,16,16,8,8});
            for (int t = x - 8; t <= x + 8; t += 4) { place(t, G + 4, zz - 10, TORCH); place(t, G + 4, zz + 10, TORCH); }
            tower(x - 10, zz - 10, G, 6, STONE_BRICK);
            tower(x + 10, zz - 10, G, 6, STONE_BRICK);
            tower(x - 10, zz + 10, G, 6, STONE_BRICK);
            tower(x + 10, zz + 10, G, 6, STONE_BRICK);
        }

        private void buildSmeltingWorks(Zone z) {
            int x = z.cx, zz = z.cz;
            hollow(x - 10, G, zz - 10, x + 10, G + 6, zz + 10, BRICK);
            set(x, G + 1, zz - 10, 0); set(x, G + 2, zz - 10, 0);
            for (int i = 0; i < 5; i++) place(x - 8 + i * 4, G + 1, zz - 8, FURNACE);
            for (int i = 0; i < 4; i++) {
                int bx = x - 6 + i * 4;
                place(bx, G + 1, zz, BURNER);
                place(bx + 1, G + 1, zz, ENGINE_COLD); place(bx + 1, G + 2, zz, ENGINE);
                place(bx + 2, G + 1, zz, TANK); place(bx + 2, G + 2, zz, TANK + 5);
            }
            place(x, G + 1, zz + 8, COAL_ORE); place(x + 1, G + 1, zz + 8, COAL_ORE); place(x - 1, G + 1, zz + 8, COAL_ORE);
            place(x - 8, G + 1, zz + 8, CHEST);
            chestAt(x - 8, G + 1, zz + 8, new String[]{"coal","iron_ore","gold_ore","copper_ore","zinc_ore","iron_ingot"}, new int[]{32,16,8,16,16,8});
            for (int ch = 0; ch < 2; ch++) {
                int cx = x - 8 + ch * 16;
                for (int h = 1; h <= 6; h++) place(cx, G + h, zz - 8, BRICK);
            }
            for (int t = x - 10; t <= x + 10; t += 5) { place(t, G + 3, zz - 10, TORCH); place(t, G + 3, zz + 10, TORCH); }
            stamp(WINDMILL, x + 12, G, zz + 6);
        }

        private void buildThroneCastle(Zone z) {
            int x = z.cx, zz = z.cz;
            int kx0 = x - 10, kx1 = x + 10, kz0 = zz - 10, kz1 = zz + 10;
            hollow(kx0, G, kz0, kx1, G + 11, kz1, STONE_BRICK);
            for (int gx = -1; gx <= 1; gx++) for (int h = 1; h <= 3; h++) set(x + gx, G + h, kz1, 0);
            for (int c = kx0; c <= kx1; c += 2) { place(c, G + 12, kz0, STONE_BRICK); place(c, G + 12, kz1, STONE_BRICK); }
            for (int c = kz0; c <= kz1; c += 2) { place(kx0, G + 12, c, STONE_BRICK); place(kx1, G + 12, c, STONE_BRICK); }
            tower(kx0, kz0, G + 11, 6, COBBLE);
            tower(kx1, kz0, G + 11, 6, COBBLE);
            tower(kx0, kz1, G + 11, 6, COBBLE);
            tower(kx1, kz1, G + 11, 6, COBBLE);
            place(x - 3, G + 1, kz0 + 2, GOLD_BLOCK); place(x - 2, G + 1, kz0 + 2, GOLD_BLOCK);
            place(x + 2, G + 1, kz0 + 2, GOLD_BLOCK); place(x + 3, G + 1, kz0 + 2, GOLD_BLOCK);
            place(x - 3, G + 2, kz0 + 2, GOLD_BLOCK); place(x + 3, G + 2, kz0 + 2, GOLD_BLOCK);
            place(x - 3, G + 3, kz0 + 2, WOOL); place(x + 3, G + 3, kz0 + 2, WOOL);
            place(x, G + 1, kz0 + 2, WOOL); place(x, G + 2, kz0 + 2, WOOL); place(x, G + 3, kz0 + 2, GLOWSTONE);
            for (int c = -6; c <= 6; c++) place(x + c, G, kz1 - 1, WOOL);
            place(x - 8, G + 1, kz1 - 2, WOOL); place(x - 8, G + 2, kz1 - 2, WOOL);
            place(x + 8, G + 1, kz1 - 2, WOOL); place(x + 8, G + 2, kz1 - 2, WOOL);
            place(x - 8, G + 1, kz0 + 2, CHEST);
            chestAt(x - 8, G + 1, kz0 + 2, new String[]{"gold_block","diamond_block","emerald_block","stone_brick","torch","bread"}, new int[]{4,2,2,64,16,16});
            for (int b = 0; b < 5; b++) { place(x + 4 + b, G + 1, kz0 + 4, BOOKSHELF); place(x + 4 + b, G + 2, kz0 + 4, BOOKSHELF); }
            place(x + 8, G + 1, kz1 - 3, FURNACE); place(x + 8, G + 1, kz1 - 4, CRAFT_TABLE);
            for (int t = kx0 + 2; t <= kx1 - 2; t += 4) { place(t, G + 3, kz0 + 1, TORCH); place(t, G + 3, kz1 - 1, TORCH); }
        }

        /**
         * Zone 14 — The Barren Isles.
         *
         * The End update exhibit, kept deliberately lifeless: drifting end-stone
         * islands over an empty plain, a ring of obsidian monoliths, a dead purpur
         * pavilion around the dragon egg, one last chorus grove under glass, and a
         * void-steel altar. No cities, no mobs — just cold geometry.
         */
        private void buildBarrenIsles(Zone z) {
            int x = z.cx, zz = z.cz;

            // ── Main isle: a thick tapered disc floating above the plain ──
            final int ISLE_Y = G + 13;
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d > 12f) continue;
                    int depth = d > 10f ? 1 : d > 7f ? 2 : 4; // tapering underside
                    for (int dy = 0; dy < depth; dy++) place(x + dx, ISLE_Y - dy, zz + dz, END_STONE);
                }
            }

            // ── Obsidian monolith ring: cold beacons with glowstone caps ──
            for (int i = 0; i < 6; i++) {
                double ang = i * Math.PI / 3.0;
                int px = x + (int) Math.round(Math.cos(ang) * 9);
                int pz = zz + (int) Math.round(Math.sin(ang) * 9);
                int top = ISLE_Y + 6 + (i % 3) * 2;
                for (int py = ISLE_Y + 1; py <= top; py++) place(px, py, pz, OBSIDIAN);
                place(px, top + 1, pz, GLOWSTONE);
            }

            // ── Dead purpur pavilion at the isle's heart ──
            for (int px = -3; px <= 3; px++)
                for (int pz = -3; pz <= 3; pz++)
                    place(x + px, ISLE_Y + 1, zz + pz, PURPUR);
            for (int[] c : new int[][]{{-3,-3},{3,-3},{-3,3},{3,3}}) {
                for (int py = 2; py <= 4; py++) place(x + c[0], ISLE_Y + py, zz + c[1], PURPUR_PILLAR);
                place(x + c[0], ISLE_Y + 5, zz + c[1], PURPUR); // corner finials
            }
            // Roof ring with an end-glass oculus over the pedestal.
            for (int px = -3; px <= 3; px++)
                for (int pz = -3; pz <= 3; pz++) {
                    boolean edge = Math.max(Math.abs(px), Math.abs(pz)) == 3;
                    boolean oculus = Math.abs(px) == 1 && Math.abs(pz) == 1;
                    if (edge) place(x + px, ISLE_Y + 5, zz + pz, PURPUR);
                    else if (oculus) place(x + px, ISLE_Y + 5, zz + pz, END_GLASS);
                }
            // Dragon egg pedestal: two end-brick tiers + the egg itself.
            for (int px = -1; px <= 1; px++)
                for (int pz = -1; pz <= 1; pz++)
                    place(x + px, ISLE_Y + 2, zz + pz, END_BRICKS);
            place(x, ISLE_Y + 3, zz, END_BRICKS);
            place(x, ISLE_Y + 4, zz, DRAGON_EGG);
            // End rods flanking the pavilion entrance (south side).
            place(x - 2, ISLE_Y + 2, zz + 4, END_ROD);
            place(x + 2, ISLE_Y + 2, zz + 4, END_ROD);

            // Exhibit chest tucked beside the pedestal.
            place(x - 3, ISLE_Y + 2, zz, CHEST);
            chestAt(x - 3, ISLE_Y + 2, zz, new String[]{
                    "end_stone", "end_bricks", "purpur_block", "purpur_pillar",
                    "end_rod", "chorus_fruit", "chorus_fruit_popped", "end_glass", "void_steel"},
                    new int[]{32, 32, 16, 8, 8, 8, 8, 8, 4});

            // ── Rising end-brick causeway from the plain up to the isle ──
            for (int s = 0; s <= 11; s++) {
                int sx = x, sz = zz + 21 - s;
                int sy = G + 1 + s; // climbs one block per step
                place(sx, sy, sz, END_BRICKS);
                for (int fy = G; fy < sy; fy++) place(sx, fy, sz, END_BRICKS); // solid footing
                if (s % 3 == 2 && s < 10) place(sx, sy + 1, sz, END_ROD); // lampposts
            }

            // ── The last chorus grove: its own drifting isle ──
            final int GROVE_Y = G + 17;
            for (int dx = -5; dx <= 5; dx++)
                for (int dz = -5; dz <= 5; dz++) {
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d > 5f) continue;
                    place(x + 19 + dx, GROVE_Y, zz - 9 + dz, END_STONE);
                    if (d <= 3.5f) place(x + 19 + dx, GROVE_Y - 1, zz - 9 + dz, END_STONE);
                }
            int[][] grove = {{-3,-2},{-1,-3},{0,0},{2,-2},{3,1},{-2,2},{1,3},{3,-3}};
            for (int gi = 0; gi < grove.length; gi++) {
                int stem = 2 + (gi % 3);
                for (int s = 1; s <= stem; s++)
                    place(x + 19 + grove[gi][0], GROVE_Y + s, zz - 9 + grove[gi][1], CHORUS);
                place(x + 19 + grove[gi][0], GROVE_Y + stem + 1, zz - 9 + grove[gi][1], CHORUS_FLOWER);
            }

            // ── Void-steel altar islet ──
            final int ALTAR_Y = G + 10;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    place(x - 17 + dx, ALTAR_Y, zz + 7 + dz, VOID_STEEL);
            place(x - 17, ALTAR_Y + 1, zz + 6, VOID_STEEL);
            place(x - 17, ALTAR_Y + 2, zz + 6, END_ROD);

            // Drifting shards: small broken fragments hovering between isles.
            int[][] shards = {{7, 4, G + 15}, {-8, -6, G + 18}, {12, -4, G + 12}, {-14, 2, G + 14}};
            for (int[] s : shards) {
                place(x + s[0], s[2], zz + s[1], END_STONE);
                place(x + s[0] + 1, s[2], zz + s[1], END_STONE);
            }
        }

        // ── Fill pass: roads + a building in every cell (no empty areas) ──

        private static final int FILL_CELL = 32;

        private void buildFill() {
            for (int gx = MIN / FILL_CELL; gx <= MAX / FILL_CELL; gx++) {
                for (int gz = MIN / FILL_CELL; gz <= MAX / FILL_CELL; gz++) {
                    int x = gx * FILL_CELL, z = gz * FILL_CELL;
                    int cx = x + FILL_CELL / 2, cz = z + FILL_CELL / 2;
                    int h = hash(gx, gz);
                    if (nearZone(cx, cz)) { scatterNature(x, z, h); continue; }
                    boolean roadX = Math.floorMod(gx, 5) == 0;
                    boolean roadZ = Math.floorMod(gz, 5) == 0;
                    if (roadX && roadZ) { buildRoadJunction(x, z); continue; }
                    if (roadX) { buildRoadNS(x, z); continue; }
                    if (roadZ) { buildRoadEW(x, z); continue; }
                    int type = Math.floorMod(h, 24);
                    switch (type) {
                        case 0: case 1: stamp(COTTAGE, x + 11, G, z + 11); break;
                        case 2: stamp(HOUSE, x + 12, G, z + 12); break;
                        case 3: stamp(WATCHTOWER, x + 13, G, z + 13); break;
                        case 4: stamp(WELL, x + 13, G, z + 13); water(x + 15, G, z + 15); break;
                        case 5: stamp(BARN, x + 10, G, z + 11); break;
                        case 6: stamp(WINDMILL, x + 13, G, z + 13); break;
                        case 7: stamp(SHRINE, x + 13, G, z + 13); break;
                        case 8: stamp(STATUE, x + 14, G, z + 14); break;
                        case 9: stamp(RUINS, x + 11, G, z + 11); break;
                        case 10: stamp(STALL, x + 13, G, z + 13); break;
                        case 11: stamp(STALL, x + 8, G, z + 13); stamp(STALL, x + 18, G, z + 13); break;
                        case 12: stamp(CHAPEL, x + 11, G, z + 12); break;
                        case 13: tower(x + 13, z + 13, G, 9, STONE_BRICK); break;
                        case 14: buildLake(x, z, h); continue; // full-cell feature, own detail
                        case 15: buildOrchard(x, z, h); break;
                        case 16: buildMineshaft(x, z, h); continue;
                        case 17: buildFarmField(x, z, h); break;
                        case 18: buildStoneCircle(x, z, h); break;
                        case 19: buildPond(x, z, h); continue;
                        case 20: stamp(STALL, x + 8, G, z + 12); stamp(STALL, x + 17, G, z + 12); stamp(STALL, x + 12, G, z + 21); break;
                        case 21: case 22: case 23: buildForest(x, z, h); continue;
                    }
                    scatterNature(x, z, h);
                }
            }
        }

        /** Deterministic trees/flowers/rocks so no cell looks barren. */
        private void scatterNature(int x, int z, int h) {
            int[] s = { h ^ 0x51AB0F3 };
            int trees = 2 + Math.floorMod(h, 3);
            for (int i = 0; i < trees; i++) {
                int tx = x + 2 + (rng(s) % (FILL_CELL - 4));
                int tz = z + 2 + (rng(s) % (FILL_CELL - 4));
                tree(tx, tz, OAK_LOG, OAK_LEAF);
            }
            int flowers = 3 + Math.floorMod(h >> 3, 4);
            for (int i = 0; i < flowers; i++) {
                int fx = x + 2 + (rng(s) % (FILL_CELL - 4));
                int fz = z + 2 + (rng(s) % (FILL_CELL - 4));
                if (sink.get(fx, G + 1, fz) != 0) continue; // don't punch holes in walls/trunks
                set(fx, G + 1, fz, flower(rng(s)));
            }
            if (Math.floorMod(h, 4) == 0) {
                int rx = x + 4 + (rng(s) % (FILL_CELL - 8));
                int rz = z + 4 + (rng(s) % (FILL_CELL - 8));
                set(rx, G + 1, rz, MOSSY); // mossy boulder
            }
            if (Math.floorMod(h >> 2, 5) == 0) {
                int bx = x + 4 + (rng(s) % (FILL_CELL - 8));
                int bz = z + 4 + (rng(s) % (FILL_CELL - 8));
                if (sink.get(bx, G + 1, bz) == 0) set(bx, G + 1, bz, DEADBUSH); // dry scrub
            }
        }

        // ── Extra fill-cell content: lakes, orchards, mines, farms ────

        /** A small sandy lake with lily pads and reeds. */
        private void buildLake(int x, int z, int h) {
            int[] s = { h ^ 0x1ACEF00D };
            int cx = x + 16, cz = z + 16;
            int rx = 4 + rng(s) % 4; // 4..7
            int rz = 3 + rng(s) % 3; // 3..5
            for (int dx = -rx; dx <= rx; dx++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    float ex = (float) dx / (rx + 0.5f);
                    float ez = (float) dz / (rz + 0.5f);
                    float d = ex * ex + ez * ez;
                    if (d <= 1.0f) {
                        set(cx + dx, G, cz + dz, WATER);
                        set(cx + dx, G - 1, cz + dz, SAND); // sandy lakebed
                    } else if (d <= 1.6f) {
                        set(cx + dx, G, cz + dz, rng(s) % 3 == 0 ? GRAVEL : SAND); // shore
                    }
                }
            }
            for (int i = 0; i < 5; i++) {
                int lx = cx - rx + 1 + rng(s) % (2 * rx - 2);
                int lz = cz - rz + 1 + rng(s) % (2 * rz - 2);
                if (sink.get(lx, G, lz) == WATER) set(lx, G + 1, lz, WATERLILY);
            }
            for (int i = 0; i < 6; i++) {
                double rad = Math.toRadians(rng(s) % 360);
                int ex = cx + (int) Math.round((rx + 1.6) * Math.cos(rad));
                int ez = cz + (int) Math.round((rz + 1.6) * Math.sin(rad));
                int shore = sink.get(ex, G, ez);
                if (shore == SAND || shore == GRAVEL) {
                    set(ex, G + 1, ez, REEDS);
                    set(ex, G + 2, ez, REEDS);
                }
            }
        }

        /** A tiny round pond with lily pads, sand ring and reeds. */
        private void buildPond(int x, int z, int h) {
            int[] s = { h ^ 0x60FFEE };
            int cx = x + 16, cz = z + 16;
            int r = 2 + rng(s) % 2; // 2..3
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz <= r * r) {
                        set(cx + dx, G, cz + dz, WATER);
                        set(cx + dx, G - 1, cz + dz, SAND);
                    } else if (dx * dx + dz * dz <= (r + 1) * (r + 1)) {
                        set(cx + dx, G, cz + dz, SAND);
                    }
                }
            }
            set(cx, G + 1, cz, WATERLILY);
            set(cx + r - 1, G + 1, cz - r + 1, WATERLILY);
            set(cx + r + 1, G + 1, cz, REEDS); set(cx + r + 1, G + 2, cz, REEDS);
            set(cx - r - 1, G + 1, cz, REEDS); set(cx - r - 1, G + 2, cz, REEDS);
        }

        /** Rows of fruit trees with melon/pumpkin bushes between them. */
        private void buildOrchard(int x, int z, int h) {
            int[] s = { h ^ 0x0B0A7A11 };
            for (int row = 0; row < 2; row++) {
                int rz = z + 5 + row * 12;
                for (int c = 0; c < 5; c++) tree(x + 4 + c * 6, rz, OAK_LOG, OAK_LEAF);
            }
            for (int i = 0; i < 10; i++) {
                int fx = x + 3 + rng(s) % 26;
                int fz = z + 9 + rng(s) % 4;
                if (sink.get(fx, G + 1, fz) != 0) continue;
                set(fx, G + 1, fz, (i & 1) == 0 ? PUMPKIN : MELON);
            }
            for (int i = 0; i < 8; i++) {
                int fx = x + 3 + rng(s) % 26;
                int fz = z + 12 + (rng(s) % 2) * 6;
                if (sink.get(fx, G + 1, fz) != 0) continue;
                set(fx, G + 1, fz, flower(rng(s)));
            }
        }

        /** A framed mine entrance descending to a torch-lit rail tunnel with ore. */
        private void buildMineshaft(int x, int z, int h) {
            int[] s = { h ^ 0x51AB0F3 };
            int mx = x + 16, mz = z + 16;
            int depth = 6 + rng(s) % 3; // 6..8 blocks down
            // Timber frame around the entrance.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    place(mx + dx, G + 1, mz + dz, OAK_LOG);
                    place(mx + dx, G + 2, mz + dz, OAK_LOG);
                }
            }
            set(mx, G + 1, mz, 0); set(mx, G + 2, mz, 0); // opening
            // Shaft: rungs against one wall, torches on the other.
            for (int d = 1; d <= depth; d++) {
                set(mx, G + 1 - d, mz, 0);
                if ((d & 1) == 1) place(mx, G + 1 - d, mz + 1, OAK_LOG);
                else place(mx, G + 1 - d, mz - 1, TORCH);
            }
            // Bottom: rail tunnel running +z with timber supports and ore veins.
            int botY = G - depth;
            for (int t = 1; t <= 6; t++) {
                set(mx, botY, mz + t, 0);
                set(mx, botY + 1, mz + t, 0);
                place(mx, botY, mz + t, RAIL_NS);
            }
            for (int t = 2; t <= 6; t += 2) {
                place(mx - 1, botY, mz + t, OAK_LOG);
                place(mx + 1, botY, mz + t, OAK_LOG);
                place(mx, botY + 1, mz + t, TORCH);
            }
            place(mx - 1, botY + 1, mz + 3, COAL_ORE);
            place(mx + 1, botY + 1, mz + 4, IRON_ORE);
            place(mx - 1, botY + 1, mz + 5, GOLD_ORE);
            place(mx, botY + 1, mz + 1, TORCH);
            place(mx, botY + 1, mz + 6, MINECART); // ore cart on the rail
        }

        /** Pumpkins, melons and reeds around an irrigation trough, with a scarecrow. */
        private void buildFarmField(int x, int z, int h) {
            for (int fx = x + 2; fx <= x + 29; fx++) set(fx, G, z + 16, WATER);
            for (int fx = x + 2; fx <= x + 29; fx += 2) {
                set(fx, G + 1, z + 12, PUMPKIN);
                set(fx, G + 1, z + 20, MELON);
                set(fx, G + 1, z + 8, REEDS); set(fx, G + 2, z + 8, REEDS);
                set(fx, G + 1, z + 24, REEDS); set(fx, G + 2, z + 24, REEDS);
            }
            place(x + 2, G + 1, z + 26, OAK_LOG);
            place(x + 2, G + 2, z + 26, OAK_LOG);
            place(x + 2, G + 3, z + 26, PUMPKIN); // scarecrow head
            place(x + 1, G + 2, z + 26, OAK_LOG);
            place(x + 3, G + 2, z + 26, OAK_LOG);
            place(x + 2, G + 1, z + 27, WOOL);
        }

        /** A henge of stone-brick monoliths with glowstone caps. */
        private void buildStoneCircle(int x, int z, int h) {
            int[] s = { h ^ 0x51C1E };
            int cx = x + 16, cz = z + 16;
            for (int a = 0; a < 8; a++) {
                double rad = Math.toRadians(a * 45 + rng(s) % 8);
                int ox = cx + (int) Math.round(6 * Math.cos(rad));
                int oz = cz + (int) Math.round(6 * Math.sin(rad));
                int ht = 2 + rng(s) % 3; // 2..4 tall
                for (int i = 1; i <= ht; i++) set(ox, G + i, oz, STONE_BRICK);
                set(ox, G + ht + 1, oz, GLOWSTONE);
            }
            set(cx, G + 1, cz, GLOWSTONE);
            set(cx + 1, G + 1, cz, MOSSY);
            set(cx - 1, G + 1, cz, MOSSY);
            set(cx, G + 1, cz + 1, MOSSY);
            set(cx, G + 1, cz - 1, MOSSY);
        }

        /** A dense mixed wood with undergrowth, flowers and mossy rocks. */
        private void buildForest(int x, int z, int h) {
            int[] s = { h ^ 0xF0E57 };
            int trees = 6 + Math.floorMod(h, 5); // 6..10
            for (int i = 0; i < trees; i++) {
                int tx = x + 2 + (rng(s) % (FILL_CELL - 4));
                int tz = z + 2 + (rng(s) % (FILL_CELL - 4));
                tree(tx, tz, OAK_LOG, OAK_LEAF);
            }
            for (int i = 0; i < 12; i++) {
                int fx = x + 2 + (rng(s) % (FILL_CELL - 4));
                int fz = z + 2 + (rng(s) % (FILL_CELL - 4));
                if (sink.get(fx, G + 1, fz) != 0) continue;
                set(fx, G + 1, fz, flower(rng(s)));
            }
            for (int i = 0; i < 3; i++) {
                int rx = x + 4 + (rng(s) % (FILL_CELL - 8));
                int rz = z + 4 + (rng(s) % (FILL_CELL - 8));
                if (sink.get(rx, G + 1, rz) == 0) set(rx, G + 1, rz, MOSSY);
            }
        }

        /** A flower/mushroom/grass pick for ground scatter. */
        private int flower(int r) {
            switch (Math.floorMod(r, 8)) {
                case 0: return POPPY;
                case 1: return DANDELION;
                case 2: return ROSE;
                case 3: return TALLGRASS;
                case 4: return FERN;
                case 5: return BROWN_MUSHROOM;
                case 6: return RED_MUSHROOM;
                default: return TALLGRASS;
            }
        }

        private static int rng(int[] s) {
            s[0] = s[0] * 1103515245 + 12345;
            return (s[0] >>> 16) & 0x7FFF;
        }

        private boolean nearZone(int x, int z) {
            for (Zone zone : ZONES) {
                int dx = x - zone.cx, dz = z - zone.cz;
                if (dx * dx + dz * dz < 44 * 44) return true;
            }
            return false;
        }

        private void buildRoadNS(int x, int z) {
            for (int dz = 0; dz < FILL_CELL; dz++)
                for (int dx = -1; dx <= 1; dx++) set(x + FILL_CELL / 2 + dx, G, z + dz, STONE_BRICK);
            for (int dz = 2; dz < FILL_CELL; dz += 8) lampPost(x + FILL_CELL / 2, G, z + dz);
        }

        private void buildRoadEW(int x, int z) {
            for (int dx = 0; dx < FILL_CELL; dx++)
                for (int dz = -1; dz <= 1; dz++) set(x + dx, G, z + FILL_CELL / 2 + dz, STONE_BRICK);
            for (int dx = 2; dx < FILL_CELL; dx += 8) lampPost(x + dx, G, z + FILL_CELL / 2);
        }

        private void buildRoadJunction(int x, int z) {
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) set(x + FILL_CELL / 2 + dx, G, z + FILL_CELL / 2 + dz, STONE_BRICK);
            stamp(STATUE, x + FILL_CELL / 2 - 1, G, z + FILL_CELL / 2 - 1);
            for (int dx = -4; dx <= 4; dx += 4) for (int dz = -4; dz <= 4; dz += 4) lampPost(x + FILL_CELL / 2 + dx, G, z + FILL_CELL / 2 + dz);
        }

        private void lampPost(int x, int y, int z) {
            place(x, y + 1, z, OAK_LOG);
            place(x, y + 2, z, OAK_LOG);
            place(x, y + 3, z, GLOWSTONE);
        }

        private static int hash(int gx, int gz) {
            long v = (long) gx * 73856093L ^ (long) gz * 19349663L;
            v ^= v >> 13; v *= 0x5bd1e995L; v ^= v >> 15;
            return (int) v;
        }

        // ── 3D blueprint system ────────────────────────────────────────

        private int blockFor(char c) {
            switch (c) {
                case '#': return STONE_BRICK;
                case 'c': return COBBLE;
                case 'o': return PLANKS;
                case 'l': return OAK_LOG;
                case 'g': return GLASS;
                case 's': return STONE;
                case 'd': return DIRT;
                case 'a': return SAND;
                case 'G': return GLOWSTONE;
                case 'T': return TORCH;
                case 'W': return WOOL;
                case 'B': return BOOKSHELF;
                case 'b': return BRICK;
                case 'm': return MOSSY;
                case 'q': return GOLD_BLOCK;
                case 'r': return LAMP;
                case 'R': return REDSTONE_BLOCK;
                case 'X': return OBSIDIAN;
                case 'E': return END_STONE;
                case 'N': return NETHER_BRICKS;
                case 'i': return IRON_BLOCK;
                case 'k': return DIAMOND_BLOCK;
                case 'e': return EMERALD_BLOCK;
                case 'L': return LAPIS_BLOCK;
                case 'F': return FURNACE;
                case 'C': return CHEST;
                case 't': return CRAFT_TABLE;
                case 'z': return COAL_ORE;
                default: return 0;
            }
        }

        private void stamp(String[] layers, int x, int y, int z) {
            for (int ly = 0; ly < layers.length; ly++) {
                String[] rows = layers[ly].split("\\n", -1);
                for (int r = 0; r < rows.length; r++) {
                    String row = rows[r];
                    for (int c = 0; c < row.length(); c++) {
                        int b = blockFor(row.charAt(c));
                        if (b != 0) set(x + c, y + ly, z + r, b);
                    }
                }
            }
        }

        // ── Building blueprints (bottom→top stack of Z-row grids) ─────

        private static final String[] COTTAGE = {
            "ccccccccc\ncoooooooc\ncoooooooc\ncoooooooc\ncoooooooc\ncoooooooc\nccccccccc",
            "loooooool\no.t.F...o\ng.......g\no.C.B...o\ng.......g\noWWWWWWWo\nlooo.oool",
            "loooooool\no.......o\no.......o\no.......o\no.......o\no.......o\nlooo.oool",
            "ooooooooo\nooooooooo\nooooooooo\nooooooooo\nooooooooo\nooooooooo\nooooooooo",
            "ooooooooo\nooooooooo\nooooooooo\nooooooooo\nooooooooo\nooooooooo\nooooooooo",
            ".........\n..ooooo..\n..ooooo..\n..ooooo..\n..ooooo..\n..ooooo..\n.........",
        };

        private static final String[] HOUSE = {
            "sssssss\nsooooos\nsooooos\nsooooos\nsooooos\nsooooos\nsssssss",
            "l.....l\ns.t...s\ng.....g\ns.....s\ng.....g\ns.C...s\nsss.sss",
            "l.....l\ns.....s\ns.....s\ns.....s\ns.....s\ns.....s\nsss.sss",
            "sssssss\nsssssss\nsssssss\nsssssss\nsssssss\nsssssss\nsssssss",
        };

        private static final String[] WATCHTOWER = {
            "ccccc\ncc.cc\nccccc\nccccc\nccccc",
            "ccccc\ncc.cc\nccccc\nccccc\nccccc",
            "c.g.c\n.....\n.....\n.....\nc.g.c",
            "c...c\n.....\n.....\n.....\nc...c",
            "c.g.c\n.....\n.....\n.....\nc.g.c",
            "c...c\n.....\n.....\n.....\nc...c",
            "c.g.c\n.....\n.....\n.....\nc.g.c",
            "c...c\n.....\n.....\n.....\nc...c",
            "ccccc\nc.G.c\nccccc\nc...c\nccccc",
        };

        private static final String[] WELL = {
            "ccccc\nc...c\nc...c\nc...c\nccccc",
            "ccccc\nc...c\nc...c\nc...c\nccccc",
            "c...c\n.....\n.....\n.....\nc...c",
            "ooooo\nooooo\nooooo\nooooo\nooooo",
        };

        private static final String[] BARN = {
            "ooooooooo\nocoooooco\nocoooooco\nocoooooco\nooooooooo",
            "lllllllll\nlo.....ol\nlo.....ol\nlo.....ol\nlllll.lll",
            "ooooooooo\no.......o\no.......o\no.......o\nooooooooo",
            "ooooooooo\nooooooooo\nooooooooo\nooooooooo\nooooooooo",
            "ooooooooo\n.ooooo...\n.ooooo...\n.ooooo...\n.........",
        };

        private static final String[] WINDMILL = {
            "sssss\nsssss\nsssss\nsssss\nsssss",
            "sssss\ns...s\ns...s\ns...s\nsssss",
            "sssss\ns...s\ns...s\ns...s\nsssss",
            "s...s\n.....\n.....\n.....\ns...s",
            "s...s\n.....\n.....\n.....\ns...s",
            "s...s\n.....\n.....\n.....\ns...s",
            "sssss\ns.G.s\nsssss\nsssss\nsssss",
        };

        private static final String[] SHRINE = {
            "sssss\nsssss\nsssss\nsssss\nsssss",
            "sssss\nsGGGs\nsGqGs\nsGGGs\nsssss",
            "..s..\n..s..\n.sss.\n..s..\n..s..",
            "..G..\n.....\n.....\n.....\n.....",
        };

        private static final String[] STATUE = {
            "sss\nsss\nsss",
            "sss\nsss\nsss",
            ".s.\n.s.\n.s.",
            ".s.\nsss\n.s.",
            ".s.\n.s.\n.s.",
            "sss\nsss\nsss",
        };

        private static final String[] RUINS = {
            "#######\n#.....#\n#..m..#\n#.....#\n#..s..#\n#.....#\n#.....#",
            "#.....#\n.......\n..#....\n....m..\n....#..\n.......\n.......",
            "#.....#\n.......\n.......\n...#...\n.......\n.......\n.......",
        };

        private static final String[] STALL = {
            "ooooo\noccco\nooooo\noccco",
            "o...o\n.....\n.....\n.....",
            "ooooo\noW.Wo\nooooo\noo.oo",
        };

        private static final String[] CHAPEL = {
            "#######\n#######\n#######\n#######\n#######\n#######\n#######",
            "#######\n#.....#\n#..b..#\n#..q..#\n#..b..#\n#.....#\n##g#g##",
            "#######\n#.g.g.#\n#.....#\n#..G..#\n#.....#\n#.g.g.#\n##.#.##",
            "#######\n#######\n#######\n#######\n#######\n#######\n#######",
            ".......\n.......\n.......\n.......\n..###..\n..#.#..\n..###..",
            ".......\n.......\n.......\n.......\n..###..\n..#G#..\n..###..",
            ".......\n.......\n.......\n.......\n...#...\n..###..\n.......",
        };

        // ── Helpers ────────────────────────────────────────────────────

        private void set(int x, int y, int z, int b) { sink.set(x, y, z, b, 0); }

        private void place(int x, int y, int z, int b) { sink.set(x, y, z, b, 0); }

        private void place(int x, int y, int z, int b, int facing) { sink.set(x, y, z, b, facing); }

        private void water(int x, int y, int z) { set(x, y, z, WATER); }

        /** Stamps the 3x3 large cogwheel footprint (center 295 + 8 parts) with the given facing. */
        private void placeLargeCog(int cx, int cy, int cz, int facing) {
            place(cx, cy, cz, LARGE_COG, facing);
            for (int id = 422; id <= 429; id++) {
                int[] o = KineticManager.largeCogPartWorldOffset(id, facing);
                place(cx + o[0], cy + o[1], cz + o[2], id, facing);
            }
        }

        /** Stamps the 3x3 water-wheel footprint (center 296 + 8 parts) with the given facing. */
        private void placeWaterWheel(int cx, int cy, int cz, int facing) {
            place(cx, cy, cz, WHEEL, facing);
            for (int id = 430; id <= 437; id++) {
                int[] o = KineticManager.waterWheelPartWorldOffset(id, facing);
                place(cx + o[0], cy + o[1], cz + o[2], id, facing);
            }
        }

        private void floor(int x0, int z0, int x1, int z1, int y, int b) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(x, y, z, b);
        }

        private void hollow(int x0, int y0, int z0, int x1, int y1, int z1, int b) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(x, y0, z, b);
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(x, y1, z, b);
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) { set(x0, y, z, b); set(x1, y, z, b); }
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) { set(x, y, z0, b); set(x, y, z1, b); }
        }

        private void tower(int x, int z, int baseY, int height, int wall) {
            for (int h = 1; h <= height; h++) {
                for (int tx = -1; tx <= 1; tx++) {
                    for (int tz = -1; tz <= 1; tz++) {
                        boolean rim = (tx == -1 || tx == 1) || (tz == -1 || tz == 1);
                        boolean window = (h % 3 == 0) && ((tx == -1 && tz == 0) || (tx == 1 && tz == 0) || (tx == 0 && tz == -1));
                        if (rim && !window) place(x + tx, baseY + h, z + tz, wall);
                        else if (window) place(x + tx, baseY + h, z + tz, GLASS);
                    }
                }
            }
            for (int tx = -1; tx <= 1; tx++)
                for (int tz = -1; tz <= 1; tz++)
                    if (tx == -1 || tx == 1 || tz == -1 || tz == 1) place(x + tx, baseY + height + 1, z + tz, wall);
            place(x, baseY + height + 2, z, GLOWSTONE);
        }

        private void tree(int x, int z, int log, int leaf) {
            for (int h = 1; h <= 4; h++) place(x, G + h, z, log);
            for (int dy = 3; dy <= 5; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dz == 0 && dy == 3) continue;
                        if (Math.abs(dx) == 2 && Math.abs(dz) == 2 && ((dx + dz) & 1) == 0) continue;
                        place(x + dx, G + dy, z + dz, leaf);
                    }
        }

        private void chestAt(int x, int y, int z, String[] items, int[] counts) {
            ItemDefinitions.ItemStack[] inv = new ItemDefinitions.ItemStack[ChestManager.CHEST_SLOTS];
            for (int i = 0; i < items.length && i < inv.length; i++) {
                inv[i] = new ItemDefinitions.ItemStack(items[i], counts[i]);
            }
            chests.setInventory(x, y, z, inv);
        }
    }
}
