package com.voxel.world;

import com.voxel.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Modern-era sites of the ancient builders — the civilization's final age,
 * before they were wiped out. Their older works are stone-brick strongholds
 * and the Far Lands testing facility, but at their peak they built like a
 * modern city: precast concrete towers with glass curtain walls, tiled
 * transit halls, marble plazas. These sites are preserved intact, frozen on
 * the eve of the wipeout — the lights are still in the ceilings, the consoles
 * still idle.
 *
 * <p>Sites are stamped near spawn at fixed, map-discoverable coordinates using
 * the same lazy runtime-stamping approach as {@link McsmStructures}. Every
 * site has several layout variants chosen deterministically from its name, so
 * each tower/hall/plaza is one of a family of designs rather than a single
 * copy.</p>
 *
 * <p>The builders write through {@link VoxelSink}; production uses the loaded
 * {@link ChunkManager}, tests use an in-memory sink.</p>
 */
public final class AncientBuilderModern {

    /** Player distance at which a site is built. */
    public static final float BUILD_RANGE = 220.0f;

    // ── Block IDs (mirror Main.registerBlock) ──
    private static final int GLOWSTONE = 17, CHEST = 118, STONE_BRICK = 131;
    private static final int COMMAND = 275, REPEATING = 277;
    /** Modern-era builder blocks (registered in Main as 920-927). */
    public static final int CONCRETE = 920, CONCRETE_DARK = 921,
            STEEL_BEAM = 922, CEILING_LIGHT = 923, TILE_BLOCK = 924,
            OFFICE_GLASS = 925, MARBLE = 926;

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
    }

    public static final Site GLASS_TOWER = new Site("glass_tower", 176, -168, 3);
    public static final Site METRO_HALL = new Site("metro_hall", -208, -48, 2);
    public static final Site RESEARCH_LAB = new Site("research_lab", -88, 208, 2);
    public static final Site BUILDER_PLAZA = new Site("builder_plaza", 88, 152, 3);

    public static final Site[] SITES = {GLASS_TOWER, METRO_HALL, RESEARCH_LAB, BUILDER_PLAZA};

    /** Where a builder writes blocks (ChunkManager in production). */
    public interface VoxelSink {
        void set(int x, int y, int z, int blockId);
    }

    private static final Set<String> built = new HashSet<>();

    private AncientBuilderModern() {
    }

    /** Clears the built-site flags (tests / fresh worlds). */
    public static void resetForTest() {
        built.clear();
    }

    /** True once the given site has been stamped this session. */
    public static boolean isBuilt(String site) {
        return built.contains(site);
    }

    /**
     * Builds any modern site the player has just come near. Called per logic
     * tick while the Overworld is active; the distance check is a handful of
     * comparisons until a site triggers.
     */
    public static void ensure(World world, final ChunkManager chunkManager,
                              float playerX, float playerZ) {
        if (world == null || chunkManager == null) return;
        for (final Site site : SITES) {
            if (built.contains(site.name)) continue;
            float dx = playerX - site.x, dz = playerZ - site.z;
            if (dx * dx + dz * dz > BUILD_RANGE * BUILD_RANGE) continue;
            built.add(site.name);
            int base = surfaceY(world, site.x, site.z);
            build(site, variantFor(site), base, new VoxelSink() {
                @Override
                public void set(int x, int y, int z, int blockId) {
                    chunkManager.setVoxel(x, y, z, blockId);
                }
            });
        }
    }

    /**
     * The layout variant a site uses. Deterministic from the site name, so a
     * given world always shows the same skyline, while different sites pick
     * independently from their family of designs.
     */
    public static int variantFor(Site site) {
        return Math.floorMod(site.name.hashCode() * 31 + 7, site.variants);
    }

    /** Surface height (first air block above the topmost solid) at a column. */
    static int surfaceY(World world, int x, int z) {
        for (int y = 110; y >= 8; y--) {
            if (world.getVoxel(x, y, z) != 0) return y + 1;
        }
        return 68;
    }

    /** Builds one site's chosen layout at the given ground level. */
    static void build(Site site, int variant, int baseY, VoxelSink s) {
        int v = Math.floorMod(variant, site.variants);
        if (site == GLASS_TOWER) buildGlassTower(s, site.x, site.z, baseY, v);
        else if (site == METRO_HALL) buildMetroHall(s, site.x, site.z, baseY, v);
        else if (site == RESEARCH_LAB) buildResearchLab(s, site.x, site.z, baseY, v);
        else buildBuilderPlaza(s, site.x, site.z, baseY, v);
    }

    // ── Glass Tower: concrete frame, glass curtain wall, lit lobby ────────
    //
    // Variants: 0 = 10 floors, 1 = 14 floors, 2 = 18 floors with a setback
    // crown. Floor height is 3 (2 clear + 1 slab); the facade is office glass
    // with steel corner columns and a concrete band every 4 floors.
    private static void buildGlassTower(VoxelSink s, int cx, int cz, int base, int variant) {
        int floors = 10 + variant * 4;
        boolean setback = variant >= 2;
        int floorH = 3;

        // Foundation slab and ground floor (marble lobby).
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                s.set(cx + dx, base - 1, cz + dz, CONCRETE);
                s.set(cx + dx, base, cz + dz, MARBLE);
            }
        }

        for (int f = 0; f < floors; f++) {
            int y0 = base + f * floorH;
            int half = setback && f >= floors - 3 ? 4 : 5;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == half;
                    boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
                    // Slab ceiling of this floor, lit from below.
                    if (f > 0 && ((dx + dz) & 3) == 0) {
                        s.set(cx + dx, y0, cz + dz, CEILING_LIGHT);
                    }
                    if (!edge) continue;
                    int wall = corner ? STEEL_BEAM
                            : (f % 4 == 3 ? CONCRETE : OFFICE_GLASS);
                    s.set(cx + dx, y0 + 1, cz + dz, wall);
                    s.set(cx + dx, y0 + 2, cz + dz, wall);
                }
            }
        }

        // Entrance: a two-block opening on the south facade with a steel lintel.
        for (int dx = -1; dx <= 1; dx++) {
            s.set(cx + dx, base + 1, cz - 5, 0);
            s.set(cx + dx, base + 2, cz - 5, 0);
            s.set(cx + dx, base + 3, cz - 5, STEEL_BEAM);
        }
        s.set(cx, base + 1, cz, CHEST); // lobby desk, left as it was

        // Rooftop: slab, beacon light, antenna mast.
        int roofY = base + floors * floorH;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) s.set(cx + dx, roofY, cz + dz, CONCRETE);
        }
        s.set(cx, roofY + 1, cz, CEILING_LIGHT);
        for (int y = roofY + 1; y <= roofY + 4; y++) s.set(cx + 3, y, cz + 3, STEEL_BEAM);
    }

    // ── Metro Hall: tiled platform hall over dark track trenches ──────────
    //
    // Variants: 0 = island platform, 1 = two side platforms. The hall runs
    // along Z with track trenches on both sides, steel girders carrying the
    // concrete roof, and a still-idle arrival console at the north end.
    private static void buildMetroHall(VoxelSink s, int cx, int cz, int base, int variant) {
        boolean island = variant == 0;

        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -15; dz <= 15; dz++) {
                boolean onPlatform = island ? Math.abs(dx) <= 3
                        : Math.abs(dx) >= 4;
                if (onPlatform) {
                    s.set(cx + dx, base, cz + dz, TILE_BLOCK);
                } else {
                    // Track trench: dark bed two below the platform, steel
                    // rails along the trench edges.
                    s.set(cx + dx, base - 2, cz + dz, CONCRETE_DARK);
                    if (Math.abs(dx) == (island ? 5 : 3)) {
                        s.set(cx + dx, base - 1, cz + dz, STEEL_BEAM);
                    }
                }
                // Roof over everything, lit every fourth bay.
                if (((dx + dz) & 3) == 0 && Math.abs(dx) <= 5) {
                    s.set(cx + dx, base + 5, cz + dz, CEILING_LIGHT);
                } else if (Math.abs(dx) <= 6) {
                    s.set(cx + dx, base + 5, cz + dz, CONCRETE);
                }
            }
        }

        // Steel girder rows holding the roof.
        for (int dz = -12; dz <= 12; dz += 6) {
            for (int y = base + 1; y <= base + 4; y++) {
                s.set(cx - 3, y, cz + dz, STEEL_BEAM);
                s.set(cx + 3, y, cz + dz, STEEL_BEAM);
            }
        }

        // End walls with wide doorways, skylight strips above.
        for (int dx = -6; dx <= 6; dx++) {
            for (int y = base + 1; y <= base + 4; y++) {
                boolean doorway = Math.abs(dx) <= 2 && y <= base + 3;
                int block = doorway ? 0 : (y == base + 4 ? OFFICE_GLASS : CONCRETE);
                s.set(cx + dx, y, cz - 15, block);
                s.set(cx + dx, y, cz + 15, block);
            }
        }

        // Arrival console: idle command tech from their final years.
        s.set(cx, base + 1, cz - 14, REPEATING);
        s.set(cx + 1, base + 1, cz - 14, CHEST);
    }

    // ── Research Lab: white tile shell, glass bays, marble floors ─────────
    //
    // Variants: 0 = single wing, 1 = wing plus east annex. Window bands of
    // office glass at bench height, glass partitions inside, and a lit
    // specimen plinth at the centre.
    private static void buildResearchLab(VoxelSink s, int cx, int cz, int base, int variant) {
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == 8
                        || Math.max(Math.abs(dx), Math.abs(dz)) == 6;
                s.set(cx + dx, base, cz + dz, MARBLE);
                for (int y = 1; y <= 5; y++) {
                    if (!edge) {
                        if (y == 5) s.set(cx + dx, base + y, cz + dz, CONCRETE);
                        continue;
                    }
                    boolean windowBand = y == 2 || y == 3;
                    boolean corner = Math.abs(dx) == 8 && Math.abs(dz) == 6;
                    int block = corner ? STEEL_BEAM
                            : windowBand ? OFFICE_GLASS : TILE_BLOCK;
                    if (y == 5) block = CONCRETE;
                    s.set(cx + dx, base + y, cz + dz, block);
                }
            }
        }

        // South entrance with a steel frame.
        for (int dx = -1; dx <= 1; dx++) {
            for (int y = 1; y <= 3; y++) s.set(cx + dx, base + y, cz - 6, 0);
            s.set(cx + dx, base + 4, cz - 6, STEEL_BEAM);
        }

        // Glass partitions: two rooms and a specimen plinth.
        for (int dz = -5; dz <= 5; dz++) {
            if (Math.abs(dz) == 1) continue;
            s.set(cx - 3, base + 1, cz + dz, OFFICE_GLASS);
            s.set(cx - 3, base + 2, cz + dz, OFFICE_GLASS);
        }
        s.set(cx + 2, base + 1, cz, MARBLE);
        s.set(cx + 2, base + 2, cz, MARBLE);
        s.set(cx + 2, base + 3, cz, CEILING_LIGHT);
        for (int dx = -7; dx <= 7; dx += 2) s.set(cx + dx, base + 4, cz, CEILING_LIGHT);

        // Variant 1: an east annex wing (archive stacks).
        if (variant == 1) {
            for (int dx = 9; dx <= 15; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    boolean edge = Math.max(Math.abs(dx - 12), Math.abs(dz)) == 4
                            || Math.abs(dz) == 3;
                    s.set(cx + dx, base, cz + dz, TILE_BLOCK);
                    for (int y = 1; y <= 4; y++) {
                        boolean windowBand = y == 2 && Math.abs(dz) == 3;
                        int block = y == 4 ? CONCRETE
                                : windowBand ? OFFICE_GLASS : TILE_BLOCK;
                        if (edge) s.set(cx + dx, base + y, cz + dz, block);
                    }
                }
            }
        }
    }

    // ── Builder Plaza: marble square, steel monument, lit corners ────────
    //
    // Variants: 0 = standing figure, 1 = figure with a raised arm, 2 =
    // obelisk crowned with a light orb. The plaza is the builders' civic
    // heart — untouched since the eve of the wipeout.
    private static void buildBuilderPlaza(VoxelSink s, int cx, int cz, int base, int variant) {
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                int block;
                if (Math.abs(dx) <= 1 && Math.abs(dz) > 2) block = CONCRETE_DARK;
                else if (Math.abs(dz) <= 1 && Math.abs(dx) > 2) block = CONCRETE_DARK;
                else if (Math.max(Math.abs(dx), Math.abs(dz)) == 12) block = CONCRETE;
                else block = MARBLE;
                s.set(cx + dx, base, cz + dz, block);
            }
        }

        // Corner lamps: steel posts carrying light panels.
        for (int[] p : new int[][]{{-10, -10}, {10, -10}, {-10, 10}, {10, 10}}) {
            for (int y = 1; y <= 3; y++) s.set(cx + p[0], base + y, cz + p[1], STEEL_BEAM);
            s.set(cx + p[0], base + 4, cz + p[1], CEILING_LIGHT);
        }

        // Pedestal.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                s.set(cx + dx, base + 1, cz + dz, MARBLE);
                s.set(cx + dx, base + 2, cz + dz, MARBLE);
            }
        }

        if (variant == 2) {
            // Obelisk: tapering steel stack under a glowing orb.
            for (int y = 3; y <= 10; y++) {
                int half = y >= 8 ? 0 : 1;
                for (int dx = -half; dx <= half; dx++) {
                    for (int dz = -half; dz <= half; dz++) {
                        s.set(cx + dx, base + y, cz + dz, STEEL_BEAM);
                    }
                }
            }
            s.set(cx, base + 11, cz, CEILING_LIGHT);
        } else {
            // Statue: legs, torso, head — variant 1 raises an arm to the sky.
            for (int y = 3; y <= 4; y++) {
                s.set(cx - 1, base + y, cz, STEEL_BEAM);
                s.set(cx + 1, base + y, cz, STEEL_BEAM);
            }
            for (int y = 5; y <= 8; y++) {
                for (int dx = -1; dx <= 1; dx++) s.set(cx + dx, base + y, cz, STEEL_BEAM);
            }
            int armY = variant == 1 ? 6 : 5;
            for (int y = armY; y <= armY + 2; y++) {
                s.set(cx - 2, base + y, cz, STEEL_BEAM);
            }
            for (int y = 5; y <= (variant == 1 ? 8 : 5); y++) {
                s.set(cx + 2, base + y, cz, STEEL_BEAM);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    s.set(cx + dx, base + 9, cz + dz, STEEL_BEAM);
                    s.set(cx + dx, base + 10, cz + dz, STEEL_BEAM);
                }
            }
        }

        // Benches along the paths.
        for (int[] p : new int[][]{{-6, -3}, {6, -3}, {-6, 3}, {6, 3}}) {
            s.set(cx + p[0], base + 1, cz + p[1], STEEL_BEAM);
            s.set(cx + p[0] + 1, base + 1, cz + p[1], STEEL_BEAM);
        }
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
