package com.voxel.game;

import com.voxel.entity.VillagerEntity;
import com.voxel.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.*;

/**
 * VillagerVillageManager - Manages village state and coordinates villager activities.
 * Tracks villages, assigns building tasks, and manages villager populations.
 *
 * <p>Villagers are spawned here rather than during chunk generation: the
 * structure generator only queues a village, and the population appears once
 * the ground around it is loaded and the player is close enough. That keeps
 * villagers from being dropped into columns that did not exist yet (which is
 * why generated villages used to stand empty).</p>
 */
public class VillagerVillageManager {

    /** A single village with its center, buildings, and villager list. */
    public static class Village {
        public Vector3i center;
        public int radius;
        public List<VillagerEntity> villagers = new ArrayList<>();
        public List<Vector3i> buildingOrigins = new ArrayList<>();
        public boolean needsRepairs = false;
        public boolean hasWalls = false;
        public long lastRaidTime = 0;

        /** Villager News studio anchor stand position (feet cell); null for legacy villages. */
        public Vector3i newsAnchorPos;
        /** Studio camera/TV block position; null for legacy villages. */
        public Vector3i newsTvPos;

        public Village(Vector3i center, int radius) {
            this.center = new Vector3i(center);
            this.radius = radius;
        }
    }

    /** A generated village whose villagers have not been placed yet. */
    private static final class PendingVillage {
        final Vector3i center;
        final int radius;
        final long seed;

        PendingVillage(Vector3i center, int radius, long seed) {
            this.center = new Vector3i(center);
            this.radius = radius;
            this.seed = seed;
        }
    }

    /** Player distance at which a pending village's population is materialised. */
    private static final int SPAWN_RADIUS = 160;
    /** Sample ring used to confirm the terrain around a village is loaded. */
    private static final int LOAD_SAMPLE_OFFSET = 8;

    private final Map<String, Village> villages = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<PendingVillage> pendingSpawns = new ArrayList<>();
    private int nextVillagerId = 40000;

    /** Register a village at the given position. */
    public Village registerVillage(Vector3i center, int radius) {
        String key = villageKey(center.x, center.z);
        Village v = new Village(center, radius);
        villages.put(key, v);
        return v;
    }

    /**
     * Queue a generated village for population. Called by the structure
     * generator during chunk generation; the actual spawn happens in
     * {@link #tick} once the ground exists.
     */
    public void queueVillageSpawn(Vector3i center, int radius, long seed) {
        synchronized (pendingSpawns) {
            pendingSpawns.add(new PendingVillage(center, radius, seed));
        }
    }

    /** Number of villages still waiting for their villagers. */
    public int getPendingVillageCount() {
        synchronized (pendingSpawns) {
            return pendingSpawns.size();
        }
    }

    /**
     * Spawn the population of any pending village whose ground is loaded and
     * which is close enough to the player to matter. Call once per logic tick.
     *
     * @param world        the active world (used for surface + loading checks)
     * @param playerX      player X
     * @param playerZ      player Z
     * @param textureManager used to build the villager models (may be null headless)
     * @param entityManager  where spawned villagers are added
     */
    public void tick(World world, float playerX, float playerZ,
                     com.voxel.utils.TextureManager textureManager,
                     com.voxel.entity.EntityManager entityManager) {
        if (world == null || textureManager == null || entityManager == null) return;
        List<PendingVillage> snapshot;
        synchronized (pendingSpawns) {
            if (pendingSpawns.isEmpty()) return;
            snapshot = new ArrayList<>(pendingSpawns);
        }
        for (PendingVillage pending : snapshot) {
            float dx = playerX - pending.center.x;
            float dz = playerZ - pending.center.z;
            if (dx * dx + dz * dz > (float) SPAWN_RADIUS * SPAWN_RADIUS) continue;
            if (!groundLoaded(world, pending.center)) continue;
            populateVillage(world, pending, textureManager, entityManager);
            synchronized (pendingSpawns) {
                pendingSpawns.remove(pending);
            }
        }
    }

    /**
     * True when the village centre and its sample ring all have real ground.
     * An ungenerated column has no surface (findSurface returns -1), which is
     * exactly the case that used to drop villagers out of the world.
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

    /** Place a village's initial villagers on real ground and give them jobs. */
    private void populateVillage(World world, PendingVillage pending,
                                 com.voxel.utils.TextureManager textureManager,
                                 com.voxel.entity.EntityManager entityManager) {
        Village village = registerVillage(
                new Vector3i(pending.center.x, pending.center.y, pending.center.z),
                pending.radius);
        Random rand = new Random(pending.seed);
        int count = 4 + rand.nextInt(4); // 4-7 villagers
        for (int i = 0; i < count; i++) {
            float sx = pending.center.x + (rand.nextFloat() - 0.5f) * 14f;
            float sz = pending.center.z + (rand.nextFloat() - 0.5f) * 14f;
            VillagerEntity villager = spawnVillager(world, sx, sz, village, textureManager,
                    entityManager);
            if (villager != null) {
                village.villagers.add(villager);
            }
        }
    }

    /**
     * Spawn one villager at a column, standing on its real surface. Columns
     * with no ground (not generated) or a liquid surface are skipped.
     *
     * @return the spawned villager, or null when the column is unsuitable
     */
    private VillagerEntity spawnVillager(World world, float x, float z, Village village,
                                         com.voxel.utils.TextureManager textureManager,
                                         com.voxel.entity.EntityManager entityManager) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int sy = findSurface(world, bx, bz);
        if (sy < 0 || sy >= 127) return null;
        if (world.getVoxel(bx, sy + 1, bz) != 0) return null; // inside something
        VillagerEntity villager = new VillagerEntity(nextVillagerId++,
                new Vector3f(bx + 0.5f, sy + 1, bz + 0.5f), textureManager);
        villager.setWorld(world);
        villager.setVillage(village.center, village.radius);
        // Take a trade from the job sites this village actually built.
        villager.assignJobSiteIfAny();
        entityManager.addEntity(villager);
        return villager;
    }

    /** Check if a village exists near these coordinates. */
    public Village findNearestVillage(float x, float z) {
        Village nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (Village v : villages.values()) {
            float dx = x - v.center.x;
            float dz = z - v.center.z;
            float dist = dx * dx + dz * dz;
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = v;
            }
        }
        return nearest;
    }

    /** Find a village close enough to contain this position. */
    public Village findVillageAt(float x, float z) {
        for (Village v : villages.values()) {
            float dx = x - v.center.x;
            float dz = z - v.center.z;
            if (dx * dx + dz * dz < v.radius * v.radius) {
                return v;
            }
        }
        return null;
    }

    /** Spawn villagers for a village if it doesn't have enough. */
    public void ensureVillagerPopulation(Village village, World world,
                                          com.voxel.utils.TextureManager textureManager,
                                          com.voxel.entity.EntityManager entityManager) {
        if (village == null || world == null || textureManager == null || entityManager == null) {
            return;
        }
        int targetPop = 2 + village.buildingOrigins.size(); // 2 base + 1 per building
        Random rand = new Random(village.center.hashCode());
        while (village.villagers.size() < targetPop) {
            float sx = village.center.x + (rand.nextFloat() - 0.5f) * village.radius * 0.8f;
            float sz = village.center.z + (rand.nextFloat() - 0.5f) * village.radius * 0.8f;
            VillagerEntity villager = spawnVillager(world, sx, sz, village,
                    textureManager, entityManager);
            if (villager == null) {
                // Nothing walkable in that column; try the village centre once
                // and give up rather than looping forever.
                villager = spawnVillager(world, village.center.x, village.center.z,
                        village, textureManager, entityManager);
                if (villager == null) return;
            }
            village.villagers.add(villager);
        }
    }

    /** Assign villagers to watch a TV. Returns channel being watched. */
    public int gatherVillagersAtTV(Village village, int tvX, int tvY, int tvZ,
                                    VillagerTVSystem tvSystem, int channel) {
        tvSystem.setChannel(tvX, tvY, tvZ, channel);
        for (VillagerEntity v : village.villagers) {
            // Only gather available villagers (not fleeing, mating, or already watching)
            if (v.isAvailable()) {
                v.startWatchingTV(new Vector3i(tvX, tvY, tvZ), channel);
                tvSystem.addViewer(tvX, tvY, tvZ, v);
            }
        }
        return channel;
    }

    /** Dismiss villagers from watching TV. */
    public void dismissVillagersFromTV(Village village, int tvX, int tvY, int tvZ,
                                        VillagerTVSystem tvSystem) {
        for (VillagerEntity v : village.villagers) {
            if (v.isWatchingTV()) {
                v.stopWatchingTV();
                tvSystem.removeViewer(tvX, tvY, tvZ, v);
            }
        }
    }

    /** Assign a building project to all villagers in the village. */
    public void assignBuildingProject(Village village, Vector3i origin,
                                       int width, int depth, int height) {
        // Distribute building tasks among villagers
        int villagerCount = village.villagers.size();
        if (villagerCount == 0) return;

        // Split the house into sections for each villager
        int sectionsPerVillager = Math.max(1, (width * depth * height) / (villagerCount * 10));

        // For simplicity, first villager gets the whole house
        if (!village.villagers.isEmpty()) {
            VillagerEntity builder = village.villagers.get(0);
            // Pick a builder or the first non-nitwit
            for (VillagerEntity v : village.villagers) {
                if (v.getProfession() == VillagerEntity.Profession.BUILDER) {
                    builder = v;
                    break;
                }
            }
            builder.queueBuildHouse(origin, width, depth, height);
        }

        village.buildingOrigins.add(new Vector3i(origin));
    }

    /** Assign wall building to villagers. */
    public void assignWallProject(Village village, Vector3i start, int length,
                                   int height, int direction) {
        if (!village.villagers.isEmpty()) {
            VillagerEntity builder = village.villagers.get(0);
            for (VillagerEntity v : village.villagers) {
                if (v.getProfession() == VillagerEntity.Profession.BUILDER) {
                    builder = v;
                    break;
                }
            }
            builder.queueBuildWall(start, length, height, direction);
            village.hasWalls = true;
        }
    }

    /** Topmost solid block in a column, or -1 when the column has no ground. */
    static int findSurface(World world, int x, int z) {
        if (world == null) return -1;
        for (int y = 127; y >= 0; y--) {
            if (world.getVoxel(x, y, z) > 0) return y;
        }
        return -1;
    }

    public Collection<Village> getAllVillages() { return villages.values(); }

    private static String villageKey(int x, int z) {
        return (x >> 8) + "," + (z >> 8);
    }
}
