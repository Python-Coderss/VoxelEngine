package com.voxel.game;

import com.voxel.World;
import com.voxel.entity.AncientBuilderEntity;
import com.voxel.world.AncientBuilderSites;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Brings the ancient builders back to their sites.
 *
 * <p>A stamped {@link AncientBuilderSites} site queues a population here; the
 * builders are only materialised once the ground under the site exists and the
 * player is close enough — the same queue-then-populate contract villages use,
 * which keeps builders from being dropped into ungenerated columns.</p>
 *
 * <p>Every builder is role-assigned from the site it tends (sentries on the
 * spire, scholars in the archive and observatory, engineers in the quarry and
 * engine works, architects and curators on the monuments), carries the site as
 * its home anchor, and speaks only captions.</p>
 */
public final class AncientBuilderPopulation {

    /** Player distance at which a site's builders are materialised. */
    public static final int SPAWN_RADIUS = 180;
    /** Sample ring used to confirm the ground around a site is loaded. */
    private static final int LOAD_SAMPLE_OFFSET = 8;
    /** Upper bound on builders raised per site. */
    public static final int MAX_PER_SITE = 6;

    private static final class Pending {
        final AncientBuilderSites.Site site;
        final Vector3i center;
        final long seed;

        Pending(AncientBuilderSites.Site site, Vector3i center, long seed) {
            this.site = site;
            this.center = new Vector3i(center);
            this.seed = seed;
        }
    }

    private static final List<Pending> pending = new ArrayList<>();
    private static int nextBuilderId = 60000;

    private AncientBuilderPopulation() {
    }

    /** Queue a stamped site for population. */
    public static synchronized void queue(AncientBuilderSites.Site site,
                                          Vector3i center, long seed) {
        if (site == null || center == null) return;
        pending.add(new Pending(site, center, seed));
    }

    /** Number of sites still waiting for their builders. */
    public static synchronized int getPendingCount() {
        return pending.size();
    }

    /** Clears pending sites and id allocation (tests / fresh worlds). */
    public static synchronized void resetForTest() {
        pending.clear();
        nextBuilderId = 60000;
    }

    /**
     * Materialise the builders of any pending site whose ground is loaded and
     * which is close enough to the player to matter. Call once per logic tick.
     */
    public static void tick(World world, float playerX, float playerZ,
                            com.voxel.utils.TextureManager textureManager,
                            com.voxel.entity.EntityManager entityManager) {
        if (world == null || entityManager == null) return;
        List<Pending> snapshot;
        synchronized (AncientBuilderPopulation.class) {
            if (pending.isEmpty()) return;
            snapshot = new ArrayList<>(pending);
        }
        for (Pending p : snapshot) {
            float dx = playerX - p.center.x;
            float dz = playerZ - p.center.z;
            if (dx * dx + dz * dz > (float) SPAWN_RADIUS * SPAWN_RADIUS) continue;
            if (!groundLoaded(world, p.center)) continue;
            populate(world, p, textureManager, entityManager);
            synchronized (AncientBuilderPopulation.class) {
                pending.remove(p);
            }
        }
    }

    /** Spread the site's builders on real ground around the site anchor. */
    private static void populate(World world, Pending p,
                                 com.voxel.utils.TextureManager textureManager,
                                 com.voxel.entity.EntityManager entityManager) {
        Random rand = new Random(p.seed);
        int count = 3 + rand.nextInt(MAX_PER_SITE - 2); // 3-6 builders
        for (int i = 0; i < count; i++) {
            float sx = p.center.x + (rand.nextFloat() - 0.5f) * 22f;
            float sz = p.center.z + (rand.nextFloat() - 0.5f) * 22f;
            int bx = (int) Math.floor(sx);
            int bz = (int) Math.floor(sz);
            int sy = findSurface(world, bx, bz);
            if (sy < 0 || sy >= 127) continue;
            if (world.getVoxel(bx, sy + 1, bz) != 0) continue; // inside something
            AncientBuilderEntity builder = new AncientBuilderEntity(nextBuilderId++,
                    new Vector3f(bx + 0.5f, sy + 1, bz + 0.5f), textureManager);
            builder.setWorld(world);
            builder.setRole(roleFor(p.site.name, i));
            builder.setHomeSite(p.center, p.site.displayName());
            entityManager.addEntity(builder);
        }
    }

    /**
     * The role a builder at a site takes. Deterministic from the site name and
     * the builder's index so a site always staffs the same way.
     */
    static AncientBuilderEntity.Role roleFor(String siteName, int index) {
        AncientBuilderEntity.Role[] mix = rolesFor(siteName);
        return mix[Math.floorMod(index, mix.length)];
    }

    static AncientBuilderEntity.Role[] rolesFor(String siteName) {
        if ("grand_archive".equals(siteName)) {
            return new AncientBuilderEntity.Role[]{
                    AncientBuilderEntity.Role.SCHOLAR,
                    AncientBuilderEntity.Role.CURATOR,
                    AncientBuilderEntity.Role.SCHOLAR};
        }
        if ("observatory".equals(siteName)) {
            return new AncientBuilderEntity.Role[]{
                    AncientBuilderEntity.Role.SCHOLAR,
                    AncientBuilderEntity.Role.SCHOLAR,
                    AncientBuilderEntity.Role.ENGINEER};
        }
        if ("ancient_quarry".equals(siteName)) {
            return new AncientBuilderEntity.Role[]{
                    AncientBuilderEntity.Role.ENGINEER,
                    AncientBuilderEntity.Role.ARCHITECT,
                    AncientBuilderEntity.Role.ENGINEER};
        }
        if ("engine_works".equals(siteName)) {
            return new AncientBuilderEntity.Role[]{
                    AncientBuilderEntity.Role.ENGINEER,
                    AncientBuilderEntity.Role.ENGINEER,
                    AncientBuilderEntity.Role.CURATOR};
        }
        if ("watch_spire".equals(siteName)) {
            return new AncientBuilderEntity.Role[]{
                    AncientBuilderEntity.Role.SENTINEL,
                    AncientBuilderEntity.Role.SENTINEL,
                    AncientBuilderEntity.Role.CURATOR};
        }
        return new AncientBuilderEntity.Role[]{
                AncientBuilderEntity.Role.ARCHITECT,
                AncientBuilderEntity.Role.CURATOR,
                AncientBuilderEntity.Role.ARCHITECT};
    }

    /**
     * True when the site anchor and its sample ring all have real ground. An
     * ungenerated column has no surface and blocks the spawn.
     */
    static boolean groundLoaded(World world, Vector3i center) {
        if (world == null || center == null) return false;
        if (findSurface(world, center.x, center.z) < 0) return false;
        for (int i = 0; i < 4; i++) {
            int ox = center.x + ((i & 1) == 0 ? -LOAD_SAMPLE_OFFSET : LOAD_SAMPLE_OFFSET);
            int oz = center.z + (i < 2 ? -LOAD_SAMPLE_OFFSET : LOAD_SAMPLE_OFFSET);
            if (findSurface(world, ox, oz) < 0) return false;
        }
        return true;
    }

    /** Topmost solid block in a column, or -1 when the column has no ground. */
    static int findSurface(World world, int x, int z) {
        if (world == null) return -1;
        for (int y = 127; y >= 0; y--) {
            if (world.getVoxel(x, y, z) > 0) return y;
        }
        return -1;
    }
}
