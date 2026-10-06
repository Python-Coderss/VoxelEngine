package com.voxel.world;

import com.voxel.World;
import com.voxel.game.AncientBuilderPopulation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The surviving works of the ancient builders — the great sites of the
 * civilization as they stood on the eve of the extinction.
 *
 * <p>Where {@link AncientBuilderModern} shows the builders' final, glass-and-
 * concrete age, these are the older monumental works: the Grand Archive, the
 * Observatory, the Ancient Quarry, the Engine Works, the Watch Spire and the
 * Monument Garden. Each is stamped lazily near the player (the same idempotent
 * runtime-stamping model as {@link McsmStructures}) and each carries its own
 * deterministic family of layout variants, so no two sites repeat.</p>
 *
 * <p>Stamping a site also queues a population of living builders, which
 * {@link AncientBuilderPopulation} materialises on real ground nearby once the
 * player is close enough.</p>
 */
public final class AncientBuilderSites {

    /** Player distance at which a site is stamped. */
    public static final float BUILD_RANGE = 220.0f;

    // ── Blocks (mirror Main.registerBlock) ──
    private static final int GLASS = 3, OAK_LOG = 5, WATER = 15, GLOWSTONE = 17;
    private static final int LAVA = 21, STONE_BRICK = 131, MOSSY_COBBLE = 132;
    private static final int ANDESITE = 133, DIORITE = 134, BOOKSHELF = 136;
    private static final int IRON_BLOCK = 137, GOLD_BLOCK = 138, EMERALD_BLOCK = 140;
    private static final int COPPER_BLOCK = 143, ZINC_BLOCK = 145;
    private static final int STONE_BRICK_STAIRS = 202, STONE_BRICK_SLAB = 208;
    private static final int TORCH = 211, SPAWNER = 258, COBBLESTONE = 71;
    private static final int CHEST = 118, CRAFTING_TABLE = 115, ANVIL = 456;
    private static final int BEACON = 457, ENCHANTING_TABLE = 458, HOPPER = 461;
    private static final int CAULDRON = 462, BREWING_STAND = 463, END_ROD = 465;
    private static final int PRISMARINE = 467, SEA_LANTERN = 470, IRON_BARS = 471;
    private static final int RAIL_NS = 391, RAIL_EW = 392;
    /** Ancient-builder industrial blocks (Create ports + modern set). */
    private static final int SHAFT = 291, COGWHEEL = 294, LARGE_COGWHEEL = 295;
    private static final int STEAM_ENGINE = 396, BLAZE_BURNER = 394;
    private static final int MECHANICAL_PRESS = 407, DEPLOYER = 412, BELT = 413;
    private static final int ANDESITE_CASING = 262;
    public static final int CONCRETE = 920, CONCRETE_DARK = 921, STEEL_BEAM = 922,
            CEILING_LIGHT = 923, TILE_BLOCK = 924, OFFICE_GLASS = 925, MARBLE = 926;
    /** Void steel: the builders' own alloy, used for monuments and machines. */
    private static final int VOID_STEEL = 905;

    /** One fixed site with its family of layout variants. */
    public static final class Site {
        public final String name;
        public final int x, z;
        public final int variants;

        Site(String name, int x, int z, int variants) {
            this.name = name;
            this.x = x;
            this.z = z;
            this.variants = Math.max(1, variants);
        }

        /** Human-readable name for captions ("Grand Archive"). */
        public String displayName() {
            String[] words = name.split("_");
            StringBuilder out = new StringBuilder();
            for (String w : words) {
                if (w.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            return out.toString();
        }
    }

    public static final Site GRAND_ARCHIVE = new Site("grand_archive", 240, 240, 2);
    public static final Site OBSERVATORY = new Site("observatory", -240, 40, 2);
    public static final Site ANCIENT_QUARRY = new Site("ancient_quarry", 40, -240, 2);
    public static final Site ENGINE_WORKS = new Site("engine_works", -48, 264, 2);
    public static final Site WATCH_SPIRE = new Site("watch_spire", 264, -64, 2);
    public static final Site MONUMENT_GARDEN = new Site("monument_garden", -264, -240, 2);

    public static final Site[] SITES = {
            GRAND_ARCHIVE, OBSERVATORY, ANCIENT_QUARRY,
            ENGINE_WORKS, WATCH_SPIRE, MONUMENT_GARDEN
    };

    /** Where the builders write blocks (ChunkManager in production). */
    public interface VoxelSink {
        void set(int x, int y, int z, int blockId);
    }

    private static final Set<String> built = new HashSet<>();
    private static final java.util.List<Built> freshlyBuilt = new java.util.ArrayList<>();

    /** A site stamped this tick, so callers can reveal it to the player. */
    public static final class Built {
        public final String name;
        public final String displayName;
        public final int x, y, z;

        Built(Site site, int y) {
            this.name = site.name;
            this.displayName = site.displayName();
            this.x = site.x;
            this.y = y;
            this.z = site.z;
        }
    }

    private AncientBuilderSites() {
    }

    /** Clears the built-site flags (tests / fresh worlds). */
    public static void resetForTest() {
        built.clear();
        freshlyBuilt.clear();
    }

    /** Sites stamped since the last call (drained), for a reveal cutscene. */
    public static java.util.List<Built> consumeNewlyBuilt() {
        java.util.List<Built> out = new java.util.ArrayList<>(freshlyBuilt);
        freshlyBuilt.clear();
        return out;
    }

    /** Surface height at a column (public wrapper for reveal framing). */
    public static int surfaceYAt(World world, int x, int z) {
        return surfaceY(world, x, z);
    }

    /** True once the given site has been stamped this session. */
    public static boolean isBuilt(String site) {
        return built.contains(site);
    }

    /**
     * Stamps any site the player has just come near and queues its builders.
     * Called per logic tick while the Overworld is active.
     */
    public static void ensure(World world, final ChunkManager chunkManager,
                              float playerX, float playerZ) {
        if (world == null || chunkManager == null) return;
        for (final Site site : SITES) {
            if (built.contains(site.name)) continue;
            float dx = playerX - site.x, dz = playerZ - site.z;
            if (dx * dx + dz * dz > BUILD_RANGE * BUILD_RANGE) continue;
            built.add(site.name);
            final int base = surfaceY(world, site.x, site.z);
            freshlyBuilt.add(new Built(site, base));
            build(site, variantFor(site), base, new VoxelSink() {
                @Override
                public void set(int x, int y, int z, int blockId) {
                    chunkManager.setVoxel(x, y, z, blockId);
                }
            });
            AncientBuilderPopulation.queue(site,
                    new org.joml.Vector3i(site.x, base, site.z),
                    site.name.hashCode() * 31L + base);
        }
    }

    /** The layout variant a site uses (deterministic from its name). */
    public static int variantFor(Site site) {
        return Math.floorMod(site.name.hashCode() * 31 + 7, site.variants);
    }

    /** Surface height (first air block above the topmost solid). */
    static int surfaceY(World world, int x, int z) {
        for (int y = 110; y >= 8; y--) {
            if (world.getVoxel(x, y, z) != 0) return y + 1;
        }
        return 68;
    }

    /** Builds one site's chosen layout at the given ground level. */
    static void build(Site site, int variant, int baseY, VoxelSink s) {
        int v = Math.floorMod(variant, site.variants);
        if (site == GRAND_ARCHIVE) buildGrandArchive(s, site.x, site.z, baseY, v);
        else if (site == OBSERVATORY) buildObservatory(s, site.x, site.z, baseY, v);
        else if (site == ANCIENT_QUARRY) buildQuarry(s, site.x, site.z, baseY, v);
        else if (site == ENGINE_WORKS) buildEngineWorks(s, site.x, site.z, baseY, v);
        else if (site == WATCH_SPIRE) buildWatchSpire(s, site.x, site.z, baseY, v);
        else buildMonumentGarden(s, site.x, site.z, baseY, v);
    }

    // ── Grand Archive ─────────────────────────────────────────────────────
    // A pillared hall of records: marble floor, stone-brick colonnade with slab
    // capitals, shelf stacks in the aisles and a raised reading dais at the
    // centre beneath a lit oculus. Variant 1 adds a west storage wing.
    private static void buildGrandArchive(VoxelSink s, int cx, int cz, int base, int variant) {
        int r = 16;
        floor(s, cx - r, cz - r, cx + r, cz + r, base, MARBLE);
        floor(s, cx - r, cz - r, cx + r, cz + r, base - 1, STONE_BRICK);

        // Outer wall with tall windows.
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean edge = Math.abs(dx) == r || Math.abs(dz) == r;
                if (!edge) continue;
                for (int y = 1; y <= 7; y++) {
                    boolean window = (y == 3 || y == 4)
                            && (Math.floorMod(dx + dz, 4) == 0);
                    s.set(cx + dx, base + y, cz + dz, window ? GLASS : STONE_BRICK);
                }
                s.set(cx + dx, base + 8, cz + dz, STONE_BRICK_SLAB);
            }
        }
        // Entrances on all four faces.
        for (int d = -1; d <= 1; d++) {
            for (int y = 1; y <= 3; y++) {
                s.set(cx + d, base + y, cz - r, 0);
                s.set(cx + d, base + y, cz + r, 0);
                s.set(cx - r, base + y, cz + d, 0);
                s.set(cx + r, base + y, cz + d, 0);
            }
        }

        // Colonnade: pillars at each fourth cell, slab capitals, torch sconces.
        for (int dx = -r + 3; dx <= r - 3; dx += 4) {
            for (int dz = -r + 3; dz <= r - 3; dz += 4) {
                pillar(s, cx + dx, cz + dz, base + 1, 6, VOID_STEEL, STEEL_BEAM);
                s.set(cx + dx + 1, base + 5, cz + dz, TORCH);
            }
        }

        // Shelf stacks down both long aisles.
        for (int dx = -r + 2; dx <= r - 2; dx += 3) {
            for (int y = 1; y <= 3; y++) {
                s.set(cx + dx, base + y, cz - 6, BOOKSHELF);
                s.set(cx + dx, base + y, cz + 6, BOOKSHELF);
            }
        }

        // Reading dais: marble steps, an enchanting lectern and a lit oculus.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                s.set(cx + dx, base + (d <= 1 ? 2 : 1), cz + dz, MARBLE);
            }
        }
        s.set(cx, base + 3, cz, ENCHANTING_TABLE);
        s.set(cx - 2, base + 3, cz, CHEST);
        s.set(cx + 2, base + 3, cz, CHEST);
        for (int dx = -4; dx <= 4; dx += 2) {
            for (int dz = -4; dz <= 4; dz += 2) {
                s.set(cx + dx, base + 9, cz + dz, CEILING_LIGHT);
            }
        }

        // Variant 1: a west storage wing behind the shelves.
        if (variant == 1) {
            for (int dx = -(r + 10); dx <= -r - 1; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    boolean edge = Math.abs(dz) == 6 || dx == -(r + 10);
                    s.set(cx + dx, base, cz + dz, MOSSY_COBBLE);
                    for (int y = 1; y <= 4; y++) {
                        s.set(cx + dx, base + y, cz + dz, edge ? STONE_BRICK : 0);
                    }
                    s.set(cx + dx, base + 5, cz + dz, STONE_BRICK_SLAB);
                }
            }
            for (int dz = -3; dz <= 3; dz += 3) {
                s.set(cx - r, base + 1, cz + dz, 0);
                s.set(cx - r, base + 2, cz + dz, 0);
                s.set(cx - r - 4, base + 1, cz + dz, CHEST);
                s.set(cx - r - 6, base + 1, cz + dz, CHEST);
            }
            for (int dx = -(r + 9); dx <= -r - 2; dx += 3) {
                s.set(cx + dx, base + 5, cz, CEILING_LIGHT);
            }
        }
    }

    // ── Observatory ───────────────────────────────────────────────────────
    // A round stone ring under a stepped glass dome, an instrument column at
    // the centre and a floor chart of inlaid stars. Variant 1 adds an eastern
    // instrument annex.
    private static void buildObservatory(VoxelSink s, int cx, int cz, int base, int variant) {
        int r = 13;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                s.set(cx + dx, base - 1, cz + dz, STONE_BRICK);
                // Chart floor: dark tile with sea-lantern stars.
                boolean star = Math.floorMod(dx * 7 + dz * 13, 11) == 0;
                s.set(cx + dx, base, cz + dz, star ? SEA_LANTERN : TILE_BLOCK);
                // Ring wall with a glass band.
                boolean edge = d2 > (r - 2) * (r - 2);
                if (!edge) continue;
                for (int y = 1; y <= 5; y++) {
                    boolean window = y == 3 || y == 4;
                    s.set(cx + dx, base + y, cz + dz,
                            window && Math.floorMod(dx + dz, 3) == 0 ? GLASS : STONE_BRICK);
                }
            }
        }
        // Dome: concentric glass rings tapering to a light at the apex.
        for (int y = 5; y <= 10; y++) {
            int rr = r - (y - 5) * 2;
            if (rr <= 0) {
                s.set(cx, base + 11, cz, GLOWSTONE);
                s.set(cx, base + 12, cz, CEILING_LIGHT);
                break;
            }
            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    if (Math.abs(dx) != rr && Math.abs(dz) != rr) continue;
                    if (dx * dx + dz * dz > rr * rr + rr) continue;
                    s.set(cx + dx, base + y, cz + dz, GLASS);
                }
            }
        }
        // Instrument column: void-steel mast with a gold ring and a tilted tube.
        pillar(s, cx, cz, base + 1, 6, VOID_STEEL, GOLD_BLOCK);
        s.set(cx, base + 7, cz, GOLD_BLOCK);
        for (int i = 1; i <= 4; i++) {
            s.set(cx + i, base + 7 + i, cz, STEEL_BEAM);
        }
        s.set(cx + 4, base + 10, cz, SEA_LANTERN);
        s.set(cx - 3, base + 1, cz, CHEST);
        s.set(cx + 3, base + 1, cz, CRAFTING_TABLE);
        s.set(cx, base + 1, cz - 5, CAULDRON);
        s.set(cx, base + 1, cz + 5, BREWING_STAND);

        // Entrance on the south face.
        for (int d = -1; d <= 1; d++) {
            for (int y = 1; y <= 3; y++) s.set(cx + d, base + y, cz + r, 0);
        }

        // Variant 1: eastern instrument annex with an enchantment bench.
        if (variant == 1) {
            for (int dx = r + 1; dx <= r + 11; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    boolean edge = Math.abs(dz) == 5 || dx == r + 11;
                    s.set(cx + dx, base - 1, cz + dz, STONE_BRICK);
                    s.set(cx + dx, base, cz + dz, MARBLE);
                    for (int y = 1; y <= 4; y++) {
                        s.set(cx + dx, base + y, cz + dz, edge ? STONE_BRICK : 0);
                    }
                    s.set(cx + dx, base + 5, cz + dz, STONE_BRICK_SLAB);
                }
            }
            s.set(cx + r, base + 1, cz, 0);
            s.set(cx + r, base + 2, cz, 0);
            s.set(cx + r + 4, base + 1, cz, ENCHANTING_TABLE);
            s.set(cx + r + 7, base + 1, cz - 2, CHEST);
            s.set(cx + r + 7, base + 1, cz + 2, ANVIL);
            for (int dx = r + 2; dx <= r + 10; dx += 3) {
                s.set(cx + dx, base + 5, cz, CEILING_LIGHT);
            }
        }
    }

    // ── Ancient Quarry ────────────────────────────────────────────────────
    // A terraced open pit with rail spurs, ore-bearing walls, a steel crane and
    // a stone store at the rim. Variant 1 works a deeper second terrace.
    private static void buildQuarry(VoxelSink s, int cx, int cz, int base, int variant) {
        int levels = variant == 1 ? 4 : 3;
        for (int level = 0; level < levels; level++) {
            int r = 18 - level * 3;
            int y = base - level * 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d = Math.max(Math.abs(dx), Math.abs(dz));
                    if (d > r) continue;
                    if (d == r) {
                        // Ore walls at terrace edges.
                        int ore = oreFor(dx * 31 + dz * 17 + level);
                        s.set(cx + dx, y, cz + dz, ore);
                    } else if (d == r - 1) {
                        s.set(cx + dx, y, cz + dz, COBBLESTONE);
                    } else {
                        s.set(cx + dx, y, cz + dz, 0);
                        s.set(cx + dx, y - 1, cz + dz, STONE_BRICK);
                    }
                }
            }
            // Rail spur down the middle of the terrace.
            for (int dx = -r + 1; dx <= r - 1; dx++) {
                s.set(cx + dx, y, cz, Math.abs(dx) % 2 == 0 ? RAIL_EW : RAIL_EW);
            }
        }

        // Stone store on the rim: chests, anvils, a crafting bench.
        floor(s, cx + 20, cz + 6, cx + 26, cz + 12, base, MOSSY_COBBLE);
        for (int dx = 20; dx <= 26; dx++) {
            for (int dz = 6; dz <= 12; dz++) {
                boolean edge = dx == 20 || dx == 26 || dz == 6 || dz == 12;
                if (!edge) continue;
                for (int y = 1; y <= 3; y++) {
                    s.set(cx + dx, base + y, cz + dz, STONE_BRICK);
                }
                s.set(cx + dx, base + 4, cz + dz, STONE_BRICK_SLAB);
            }
        }
        s.set(cx + 21, base + 1, cz + 7, CHEST);
        s.set(cx + 25, base + 1, cz + 7, CHEST);
        s.set(cx + 23, base + 1, cz + 11, CRAFTING_TABLE);
        s.set(cx + 21, base + 1, cz + 11, ANVIL);

        // Crane: void-steel mast and boom over the pit, with a hopper bucket.
        pillar(s, cx - 16, cz - 16, base + 1, 8, VOID_STEEL, STEEL_BEAM);
        for (int i = 1; i <= 8; i++) {
            s.set(cx - 16 + i, base + 8, cz - 16, STEEL_BEAM);
        }
        s.set(cx - 8, base + 7, cz - 16, HOPPER);
        if (variant == 1) {
            pillar(s, cx + 16, cz - 16, base + 1, 8, VOID_STEEL, STEEL_BEAM);
            for (int i = 1; i <= 8; i++) {
                s.set(cx + 16 - i, base + 8, cz - 16, STEEL_BEAM);
            }
            s.set(cx + 8, base + 7, cz - 16, HOPPER);
        }
        s.set(cx - 20, base + 1, cz + 2, TORCH);
        s.set(cx + 20, base + 1, cz - 2, TORCH);
    }

    private static int oreFor(int hash) {
        int idx = Math.floorMod(hash, 7);
        switch (idx) {
            case 0: return 61;  // coal
            case 1: return 81;  // iron
            case 2: return 82;  // gold
            case 3: return 142; // copper
            case 4: return 144; // zinc
            case 5: return 26;  // redstone
            default: return 85; // lapis
        }
    }

    // ── Engine Works ──────────────────────────────────────────────────────
    // The builders' industrial hall: a steel frame over an andesite-cased shop
    // floor, driven by create-tech shafts, cogwheels, presses and a steam
    // engine. Variant 1 raises an overhead gantry.
    private static void buildEngineWorks(VoxelSink s, int cx, int cz, int base, int variant) {
        int rx = 12, rz = 9;
        floor(s, cx - rx, cz - rz, cx + rx, cz + rz, base, CONCRETE);
        floor(s, cx - rx, cz - rz, cx + rx, cz + rz, base - 1, ANDESITE_CASING);

        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                boolean edge = Math.abs(dx) == rx || Math.abs(dz) == rz;
                if (!edge) continue;
                for (int y = 1; y <= 6; y++) {
                    boolean window = y >= 4;
                    s.set(cx + dx, base + y, cz + dz,
                            window && Math.floorMod(dx + dz, 3) == 0
                                    ? OFFICE_GLASS : STEEL_BEAM);
                }
            }
        }
        // Roof with light panels.
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                s.set(cx + dx, base + 7, cz + dz,
                        Math.floorMod(dx + dz, 4) == 0 ? CEILING_LIGHT : CONCRETE);
            }
        }
        // Door on the south wall.
        for (int d = -2; d <= 2; d++) {
            for (int y = 1; y <= 3; y++) s.set(cx + d, base + y, cz - rz, 0);
        }

        // Machine line: casings, shafts, cogwheels, a press and a belt run.
        for (int dx = -6; dx <= 6; dx++) {
            s.set(cx + dx, base + 1, cz - 3, ANDESITE_CASING);
            s.set(cx + dx, base + 1, cz + 3, BELT);
        }
        for (int dx = -6; dx <= 6; dx += 4) {
            s.set(cx + dx, base + 2, cz - 3, SHAFT);
            s.set(cx + dx, base + 2, cz + 3, COGWHEEL);
        }
        s.set(cx - 8, base + 1, cz - 3, STEAM_ENGINE);
        s.set(cx - 8, base + 1, cz - 2, BLAZE_BURNER);
        s.set(cx - 7, base + 3, cz - 3, SHAFT);
        s.set(cx - 7, base + 3, cz - 1, LARGE_COGWHEEL);
        s.set(cx + 8, base + 2, cz - 3, MECHANICAL_PRESS);
        s.set(cx + 9, base + 2, cz + 3, DEPLOYER);
        s.set(cx + 10, base + 1, cz, CHEST);
        s.set(cx + 11, base + 1, cz - 1, CHEST);
        s.set(cx - 11, base + 1, cz + 1, IRON_BLOCK);
        s.set(cx - 11, base + 1, cz - 1, COPPER_BLOCK);
        s.set(cx - 11, base + 2, cz, ZINC_BLOCK);

        // Variant 1: overhead gantry running the length of the hall.
        if (variant == 1) {
            int gy = base + 5;
            for (int dx = -rx + 1; dx <= rx - 1; dx++) {
                s.set(cx + dx, gy, cz - 5, STEEL_BEAM);
                s.set(cx + dx, gy, cz + 5, STEEL_BEAM);
            }
            for (int dx = -rx + 1; dx <= rx - 1; dx += 6) {
                s.set(cx + dx, gy - 1, cz - 5, SHAFT);
                s.set(cx + dx, gy - 1, cz + 5, COGWHEEL);
            }
        }
    }

    // ── Watch Spire ───────────────────────────────────────────────────────
    // A tall stone watchtower: tapering shell, lit arrow slits, a spiral of
    // stone stairs, a battlement of iron bars and a beacon at the crown.
    // Variant 1 is taller with a second beacon.
    private static void buildWatchSpire(VoxelSink s, int cx, int cz, int base, int variant) {
        int half = 4;
        int height = variant == 1 ? 24 : 18;
        for (int y = 1; y <= height; y++) {
            int h = y >= height - 4 ? half - 1 : half;
            for (int dx = -h; dx <= h; dx++) {
                for (int dz = -h; dz <= h; dz++) {
                    boolean edge = Math.abs(dx) == h || Math.abs(dz) == h;
                    if (!edge) continue;
                    boolean slit = y % 4 == 2 && (Math.abs(dx) == h ^ Math.abs(dz) == h);
                    s.set(cx + dx, base + y, cz + dz,
                            slit ? GLASS : (y > height - 4 ? MARBLE : STONE_BRICK));
                }
            }
            // Spiral stair inside the shell.
            int sx = ((y / 2) % 3) - 1;
            int sz = ((y / 2) % 3) - 1;
            s.set(cx + sx, base + y, cz + sz, STONE_BRICK_STAIRS);
        }
        // Base plinth and door.
        for (int dx = -half - 2; dx <= half + 2; dx++) {
            for (int dz = -half - 2; dz <= half + 2; dz++) {
                s.set(cx + dx, base - 1, cz + dz, STONE_BRICK);
                s.set(cx + dx, base, cz + dz, MOSSY_COBBLE);
            }
        }
        for (int d = -1; d <= 1; d++) {
            for (int y = 1; y <= 3; y++) s.set(cx + d, base + y, cz - half - 1, 0);
        }
        // Battlement + crown.
        int top = base + height;
        for (int dx = -half; dx <= half; dx++) {
            s.set(cx + dx, top + 1, cz - half, IRON_BARS);
            s.set(cx + dx, top + 1, cz + half, IRON_BARS);
            s.set(cx - half, top + 1, cz + dx, IRON_BARS);
            s.set(cx + half, top + 1, cz + dx, IRON_BARS);
        }
        for (int dx = -half + 1; dx <= half - 1; dx++) {
            for (int dz = -half + 1; dz <= half - 1; dz++) {
                s.set(cx + dx, top, cz + dz, MARBLE);
            }
        }
        s.set(cx, top + 1, cz, BEACON);
        s.set(cx - 2, top + 1, cz, CHEST);
        s.set(cx - 2, top + 2, cz, TORCH);
        s.set(cx + 2, top + 1, cz, CEILING_LIGHT);
        if (variant == 1) {
            s.set(cx + 2, top + 3, cz, BEACON);
            s.set(cx, top + 2, cz, CEILING_LIGHT);
        }
    }

    // ── Monument Garden ───────────────────────────────────────────────────
    // A civic garden: marble walks, a fountain, a statue line, hedges and
    // flower beds under glowstone lamps. Variant 1 mirrors the fountain and
    // raises an arch.
    private static void buildMonumentGarden(VoxelSink s, int cx, int cz, int base, int variant) {
        int r = 18;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                int block;
                if (Math.abs(dx) <= 1 || Math.abs(dz) <= 1) block = MARBLE;
                else if (Math.floorMod(dx * dx + dz, 9) == 0) block = TILE_BLOCK;
                else block = (d == r) ? CONCRETE : PRISMARINE;
                s.set(cx + dx, base, cz + dz, block);
            }
        }
        // Hedges and flower beds around the rim.
        for (int i = -r + 2; i <= r - 2; i += 4) {
            s.set(cx + i, base + 1, cz - r + 1, OAK_LOG);
            s.set(cx + i, base + 1, cz + r - 1, OAK_LOG);
            s.set(cx - r + 1, base + 1, cz + i, OAK_LOG);
            s.set(cx + r - 1, base + 1, cz + i, OAK_LOG);
            s.set(cx + i + 1, base + 1, cz - r + 1, 122 /* rose */);
            s.set(cx + i + 1, base + 1, cz + r - 1, 62 /* tulip */);
        }

        fountain(s, cx, cz, base, variant == 1 ? 10 : 0);

        // Statue line: void-steel figures on marble plinths.
        for (int i = -8; i <= 8; i += 8) {
            if (i == 0) continue;
            s.set(cx + i, base + 1, cz + 5, MARBLE);
            s.set(cx + i, base + 2, cz + 5, MARBLE);
            s.set(cx + i, base + 3, cz + 5, VOID_STEEL);
            s.set(cx + i, base + 4, cz + 5, VOID_STEEL);
            s.set(cx + i, base + 5, cz + 5, GOLD_BLOCK);
        }
        // Corner lamps.
        for (int[] p : new int[][]{{-14, -14}, {14, -14}, {-14, 14}, {14, 14}}) {
            for (int y = 1; y <= 3; y++) s.set(cx + p[0], base + y, cz + p[1], VOID_STEEL);
            s.set(cx + p[0], base + 4, cz + p[1], GLOWSTONE);
        }

        // Variant 1: a commemorative arch over the north walk.
        if (variant == 1) {
            int az = cz - 12;
            for (int y = 1; y <= 6; y++) {
                s.set(cx - 4, base + y, az, MARBLE);
                s.set(cx + 4, base + y, az, MARBLE);
            }
            for (int dx = -4; dx <= 4; dx++) {
                s.set(cx + dx, base + 7, az, MARBLE);
                s.set(cx + dx, base + 8, az, STEEL_BEAM);
            }
            s.set(cx, base + 9, az, CEILING_LIGHT);
            s.set(cx, base + 6, az, SEA_LANTERN);
        }
    }

    /** Tiered fountain; {@code mirrorOffset} > 0 adds a second, mirrored basin. */
    private static void fountain(VoxelSink s, int cx, int cz, int base, int mirrorOffset) {
        basin(s, cx, cz, base);
        if (mirrorOffset > 0) basin(s, cx, cz - mirrorOffset, base);
    }

    private static void basin(VoxelSink s, int cx, int cz, int base) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d == 4) s.set(cx + dx, base, cz + dz, MARBLE);
                else s.set(cx + dx, base, cz + dz, WATER);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                s.set(cx + dx, base + 1, cz + dz, MARBLE);
            }
        }
        s.set(cx, base + 2, cz, SEA_LANTERN);
        s.set(cx, base + 3, cz, WATER);
    }

    // ── shared helpers ────────────────────────────────────────────────────

    private static void floor(VoxelSink s, int x0, int z0, int x1, int z1, int y, int b) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) s.set(x, y, z, b);
        }
    }

    private static void pillar(VoxelSink s, int x, int z, int y0, int height,
                               int shaft, int cap) {
        for (int y = y0; y < y0 + height; y++) s.set(x, y, z, shaft);
        s.set(x, y0 + height, z, cap);
    }

    /** In-memory sink for tests and tools. */
    public static final class RecordingSink implements VoxelSink {
        public final Map<String, Integer> voxels = new HashMap<>();

        @Override
        public void set(int x, int y, int z, int blockId) {
            voxels.put(x + "," + y + "," + z, blockId);
        }

        public int get(int x, int y, int z) {
            Integer v = voxels.get(x + "," + y + "," + z);
            return v == null ? 0 : v;
        }
    }
}
