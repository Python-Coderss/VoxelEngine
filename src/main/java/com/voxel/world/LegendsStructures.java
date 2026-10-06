package com.voxel.world;

import com.voxel.World;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minecraft Legends campaign sites (§7): the Well of Fate, the Great Hog's
 * horde camp, the legendary hero outpost and a defended village. Stamped
 * lazily into the Overworld at fixed sites, the same idempotent pattern as
 * {@link McsmStructures}, so the map fills in as the player explores.
 *
 * <p>These are the Overworld campaign beats translated to places: the Well is
 * a banner-ringed shrine, the Horde camp a dark ringed arena, the hero outpost
 * a pike-ringed standard, and the village a walled town.</p>
 */
public final class LegendsStructures {

    public static final float BUILD_RANGE = 220.0f;

    public static final int[] WELL_OF_FATE = {-512, 128};
    public static final int[] GREAT_HOG_CAMP = {512, -128};
    public static final int[] HERO_OUTPOST = {128, -512};
    public static final int[] DEFENDED_VILLAGE = {-128, 512};

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
            new SiteDef("well_of_fate", WELL_OF_FATE,
                    (s, cx, cz, b) -> SiteShapes.outpost(s, cx, cz, b, 12, 1, 5, 138, 457)),
            new SiteDef("great_hog_camp", GREAT_HOG_CAMP,
                    (s, cx, cz, b) -> SiteShapes.arena(s, cx, cz, b, 13, 905, 91, 17, 71)),
            new SiteDef("hero_outpost", HERO_OUTPOST,
                    (s, cx, cz, b) -> SiteShapes.keep(s, cx, cz, b, 9, 7, 137, 72, 17)),
            new SiteDef("defended_village", DEFENDED_VILLAGE,
                    (s, cx, cz, b) -> SiteShapes.town(s, cx, cz, b, 11, 72, 131, 5, 17)));

    private static final Set<String> built = new HashSet<>();

    private LegendsStructures() {
    }

    public static void resetForTest() {
        built.clear();
    }

    public static boolean isBuilt(String site) {
        return built.contains(site);
    }

    /** Number of Legends sites (tests / tools). */
    public static int siteCount() {
        return SITES.size();
    }

    /** Stamps any Legends site the player has come near. */
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
