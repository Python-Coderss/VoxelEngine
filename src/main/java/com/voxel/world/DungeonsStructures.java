package com.voxel.world;

import com.voxel.World;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minecraft Dungeons 1 &amp; 2 campaign sites (§7). Dungeons 1's journey from
 * Creeper Woods to the Obsidian Pinnacle, plus the island realms; then Dungeons
 * 2's Sift-linked campaign, whose stops sit beside the rifts that lead into the
 * new dimension.
 *
 * <p>Same lazy, idempotent stamping pattern as the other site families — a
 * distinct silhouette per level so the map reads as a campaign trail.</p>
 */
public final class DungeonsStructures {

    public static final float BUILD_RANGE = 220.0f;

    // ── Dungeons 1 ──
    public static final int[] CREEPER_WOODS = {640, 128};
    public static final int[] PUMPKIN_PASTURES = {-640, 128};
    public static final int[] SOGGY_SWAMP = {640, -128};
    public static final int[] CACTI_CANYON = {-640, -128};
    public static final int[] DESERT_TEMPLE = {128, 640};
    public static final int[] HIGHBLOCK_HALLS = {-128, 640};
    public static final int[] FIERY_FORGE = {128, -640};
    public static final int[] OBSIDIAN_PINNACLE = {-128, -640};
    public static final int[] ISLAND_REALMS = {512, 512};
    // ── Dungeons 2 (Sift-linked) ──
    public static final int[] RIFT_STOP = {-512, -512};
    public static final int[] ILLAGER_KEEP = {512, -512};

    private interface Shape {
        void stamp(SiteShapes.Sink s, int cx, int cz, int base);
    }

    private static final class SiteDef {
        final String name;
        final int[] at;
        final Shape shape;

        SiteDef(String name, int[] at, Shape shape) {
            this.name = name;
            this.at = at;
            this.shape = shape;
        }
    }

    private static final List<SiteDef> SITES = Arrays.asList(
            new SiteDef("creeper_woods", CREEPER_WOODS,
                    (s, cx, cz, b) -> SiteShapes.forestCamp(s, cx, cz, b, 14, 1, 5, 4, 17)),
            new SiteDef("pumpkin_pastures", PUMPKIN_PASTURES,
                    (s, cx, cz, b) -> SiteShapes.pumpkinField(s, cx, cz, b, 13, 1, 13, 42, 131)),
            new SiteDef("soggy_swamp", SOGGY_SWAMP,
                    (s, cx, cz, b) -> SiteShapes.swampHut(s, cx, cz, b, 12, 15, 5, 72, 132)),
            new SiteDef("cacti_canyon", CACTI_CANYON,
                    (s, cx, cz, b) -> SiteShapes.canyon(s, cx, cz, b, 12, 135, 72, 91)),
            new SiteDef("desert_temple", DESERT_TEMPLE,
                    (s, cx, cz, b) -> SiteShapes.temple(s, cx, cz, b, 9, 14, 138, 470)),
            new SiteDef("highblock_halls", HIGHBLOCK_HALLS,
                    (s, cx, cz, b) -> SiteShapes.keep(s, cx, cz, b, 12, 10, 131, 133, 17)),
            new SiteDef("fiery_forge", FIERY_FORGE,
                    (s, cx, cz, b) -> SiteShapes.forge(s, cx, cz, b, 10, 905, 21, 137, 17)),
            new SiteDef("obsidian_pinnacle", OBSIDIAN_PINNACLE,
                    (s, cx, cz, b) -> SiteShapes.spire(s, cx, cz, b, 26, 16, 904, 17)),
            new SiteDef("island_realms", ISLAND_REALMS,
                    (s, cx, cz, b) -> SiteShapes.skyIsland(s, cx, cz, b, 14, 1, 13, 18, 17)),
            new SiteDef("rift_stop", RIFT_STOP,
                    (s, cx, cz, b) -> SiteShapes.riftHall(s, cx, cz, b, 11, 900, 933, 934, 17)),
            new SiteDef("illager_keep", ILLAGER_KEEP,
                    (s, cx, cz, b) -> SiteShapes.keep(s, cx, cz, b, 12, 9, 905, 900, 17)));

    private static final Set<String> built = new HashSet<>();

    private DungeonsStructures() {
    }

    public static void resetForTest() {
        built.clear();
    }

    public static boolean isBuilt(String site) {
        return built.contains(site);
    }

    /** Number of Dungeons sites (tests / tools). */
    public static int siteCount() {
        return SITES.size();
    }

    public static void ensure(World world, ChunkManager chunkManager,
                              float playerX, float playerZ) {
        if (world == null || chunkManager == null) return;
        for (SiteDef def : SITES) {
            if (built.contains(def.name)) continue;
            float dx = playerX - def.at[0], dz = playerZ - def.at[1];
            if (dx * dx + dz * dz > BUILD_RANGE * BUILD_RANGE) continue;
            built.add(def.name);
            int base = surfaceY(world, def.at[0], def.at[1]);
            def.shape.stamp(chunkManager::setVoxel, def.at[0], def.at[1], base);
        }
    }

    static int surfaceY(World world, int x, int z) {
        for (int y = 110; y >= 8; y--) {
            if (world.getVoxel(x, y, z) != 0) return y + 1;
        }
        return 68;
    }
}
