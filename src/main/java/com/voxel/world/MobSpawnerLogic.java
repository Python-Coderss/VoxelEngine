package com.voxel.world;

import com.voxel.entity.BlazeEntity;
import com.voxel.entity.Entity;
import com.voxel.entity.EntityManager;
import com.voxel.Player;
import com.voxel.utils.BlockDataManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Mob spawner logic.
 *
 * <p>Tracks the active spawner positions and runs a per-tick spawn
 * timer. When a spawner is ready (10..30 seconds after the last
 * spawn), up to 4 blazes are emitted into the world at the spawner
 * position.</p>
 *
 * <p>This is a simplified version of Mojang's spawner:</p>
 * <ul>
 *   <li>Ignores player distance check (always tries to spawn).</li>
 *   <li>Ignores light-level requirement.</li>
 *   <li>Ignores solidity checks (assumes the tower room is hollow).</li>
 *   <li>Skips if the active mob count near the spawner is already ≥ 4.</li>
 * </ul>
 *
 * <p>For a single-spawner-per-room case this is sufficient: the player
 * walks in, breaks the spawner (or kills enough blazes), and leaves.</p>
 */
public final class MobSpawnerLogic {

    /** Seconds between spawn waves per spawner (Mojang: 10..30). */
    private static final float SPAWN_INTERVAL_SECONDS = 12.0f;
    /** Maximum blazes the spawner will keep alive before pausing. */
    private static final int MAX_NEARBY_MOBS = 4;
    /** Horizontal radius (blocks) inside which blazes count toward MAX_NEARBY_MOBS. */
    private static final float SPAWN_RADIUS = 8.0f;

    /** Tracks known spawner positions. Keyed via 64-bit world coordinate. */
    private static final Set<Long> knownSpawners = new HashSet<>();

    /** Per-spawner timer (seconds since last spawn wave). */
    private static final java.util.Map<Long, Float> timers = new java.util.HashMap<>();

    private MobSpawnerLogic() {}

    /** Test hook: clear all known spawners + timers. */
    public static void resetForTests() {
        knownSpawners.clear();
        timers.clear();
    }

    /** Hard cap on tracked spawners — prevents unbounded growth while streaming. */
    private static final int MAX_TRACKED_SPAWNERS = 4096;
    /** Only spawners within this range of the player are ticked/tracked live. */
    private static final float ACTIVE_RADIUS = 64.0f;

    public static Set<Long> getKnownSpawners() { return knownSpawners; }

    /**
     * Per-tick scan: for every tracked spawner near the player, increment its
     * timer; if the timer crosses SPAWN_INTERVAL_SECONDS and the local mob count
     * is below MAX_NEARBY_MOBS, emit a fresh BlazeEntity. Spawners far from the
     * player are skipped entirely so streaming thousands of chunks cannot pile
     * up timers or entities (the old behavior caused runaway entity/memory
     * growth, e.g. the Aether-entry OOM).
     */
    public static void tick(com.voxel.World world,
                            BlockDataManager bdm,
                            EntityManager entityManager,
                            Player nearestPlayer) {
        if (knownSpawners.isEmpty()) return;

        float px = Float.NaN, py = Float.NaN, pz = Float.NaN;
        if (nearestPlayer != null) {
            px = nearestPlayer.getPosition().x;
            py = nearestPlayer.getPosition().y;
            pz = nearestPlayer.getPosition().z;
        }

        Long[] keys = knownSpawners.toArray(new Long[0]);
        for (Long spawnerKey : keys) {
            int[] xyz = decodeKey(spawnerKey);
            int sx = xyz[0], sy = xyz[1], sz = xyz[2];
            // Skip spawners far from the player (no timers, no spawns).
            if (!Float.isNaN(px)) {
                float dx = sx - px, dy = sy - py, dz = sz - pz;
                if (dx * dx + dy * dy + dz * dz > ACTIVE_RADIUS * ACTIVE_RADIUS) continue;
            }
            // Confirm the spawner block is still there. Players can break
            // them, so we prune silently.
            int blockId = world.getVoxel(sx, sy, sz);
            if (bdm.getName(blockId) == null
                    || !bdm.getName(blockId).contains("spawner")) {
                knownSpawners.remove(spawnerKey);
                timers.remove(spawnerKey);
                continue;
            }
            float timer = timers.getOrDefault(spawnerKey, SPAWN_INTERVAL_SECONDS);
            // We don't have a dt here; approximate with 0.05 (Player.TICK_RATE).
            timer += 0.05f;
            if (timer < SPAWN_INTERVAL_SECONDS) {
                timers.put(spawnerKey, timer);
                continue;
            }
            // Enforce MAX_NEARBY_MOBS by actually counting live blazes around
            // the spawner — otherwise entities accumulate without limit.
            if (countNearbyBlazes(entityManager, sx, sy, sz) >= MAX_NEARBY_MOBS) {
                timers.put(spawnerKey, SPAWN_INTERVAL_SECONDS / 2f);
                continue;
            }
            timers.put(spawnerKey, 0.0f);
            BlazeEntity blaze = new BlazeEntity(
                    50_000 + entityManager.getEntityCount(),
                    new org.joml.Vector3f(sx + 0.5f, sy + 1.0f, sz + 0.5f),
                    /* textureManager */ null,
                    nearestPlayer);
            blaze.setWorld(world);
            entityManager.addEntity(blaze);
        }
    }

    private static int countNearbyBlazes(EntityManager em, int x, int y, int z) {
        if (em == null) return 0;
        int count = 0;
        for (int i = 0; i < em.getEntityCount(); i++) {
            Entity e = em.getEntity(i);
            if (!(e instanceof BlazeEntity)) continue;
            float dx = e.getPosX() - x, dy = e.getPosY() - y, dz = e.getPosZ() - z;
            if (dx * dx + dy * dy + dz * dz <= SPAWN_RADIUS * SPAWN_RADIUS) count++;
        }
        return count;
    }

    public static void track(int x, int y, int z) {
        if (knownSpawners.size() >= MAX_TRACKED_SPAWNERS) {
            // Evict an arbitrary old entry (deterministic iteration order of a
            // HashSet is not guaranteed, but any eviction bounds memory).
            java.util.Iterator<Long> it = knownSpawners.iterator();
            if (it.hasNext()) {
                Long evicted = it.next();
                it.remove();
                timers.remove(evicted);
            }
        }
        knownSpawners.add(key(x, y, z));
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFFFFL)
                | (((long) y & 0xFFFFFL) << 24)
                | (((long) z & 0xFFFFFFFL) << 44);
    }

    private static int[] decodeKey(long key) {
        return new int[] {
                (int) (key & 0xFFFFFFFL),
                (int) ((key >> 24) & 0xFFFFFL),
                (int) ((key >> 44) & 0xFFFFFFFL)
        };
    }
}