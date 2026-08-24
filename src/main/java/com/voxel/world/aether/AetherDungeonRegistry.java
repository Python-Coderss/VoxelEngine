package com.voxel.world.aether;

import com.voxel.entity.*;
import com.voxel.Player;
import org.joml.Vector3f;
import java.util.*;

/**
 * Tracks Aether dungeon mob/boss spawn points generated during worldgen and
 * materializes the entities when a player gets close. Handles boss defeat
 * rewards (unlocking the boss doorway) too.
 */
public final class AetherDungeonRegistry {

    public enum DungeonType { BRONZE, SILVER, GOLD }

    /** One spawn point recorded at worldgen time. */
    public static class SpawnPoint {
        public final DungeonType dungeon;
        public final String kind;      // "sentry", "mimic", "valkyrie", "cockatrice"
        public final Vector3f pos;
        public boolean spawned = false;

        SpawnPoint(DungeonType d, String kind, float x, float y, float z) {
            this.dungeon = d; this.kind = kind;
            this.pos = new Vector3f(x, y, z);
        }
    }

    /** One dungeon instance (its boss room). */
    public static class Dungeon {
        public final DungeonType type;
        public final Vector3f bossPos;
        public final List<Long> doorBlocks = new ArrayList<>();
        public Entity boss = null;
        public boolean bossSpawned = false;
        public boolean unlocked = false;

        Dungeon(DungeonType type, float x, float y, float z) {
            this.type = type;
            this.bossPos = new Vector3f(x, y, z);
        }
    }

    private static final Map<Long, Dungeon> dungeonsByBossKey = new LinkedHashMap<>();
    private static final Map<Long, SpawnPoint> spawnPoints = new LinkedHashMap<>();
    /** Bosses currently alive, by entity id, so we can watch for their deaths. */
    private static final Map<Integer, Dungeon> liveBosses = new HashMap<>();
    /** Persisted boss flags awaiting their dungeon's (re)registration at worldgen. */
    private static final Map<Long, boolean[]> pendingState = new HashMap<>();

    private static long key(float x, float y, float z) {
        return (((long) Math.floor(x / 64.0f)) & 0xFFFFF)
             | ((((long) Math.floor(y / 32.0f)) & 0xFFFFF) << 20)
             | ((((long) Math.floor(z / 64.0f)) & 0xFFFFF) << 40);
    }

    public static void reset() {
        dungeonsByBossKey.clear();
        spawnPoints.clear();
        liveBosses.clear();
    }

    public static void addSpawnPoint(DungeonType d, String kind, float x, float y, float z) {
        spawnPoints.putIfAbsent(key(x, y, z), new SpawnPoint(d, kind, x, y, z));
    }

    public static Dungeon addDungeon(DungeonType type, float bx, float by, float bz) {
        Dungeon d = new Dungeon(type, bx, by, bz);
        // Restore persisted progress (boss defeated / doorway unlocked) if the
        // world save carried state for this exact boss room.
        boolean[] saved = pendingState.remove(stateKey(bx, by, bz));
        if (saved != null) {
            d.bossSpawned = saved[0];
            d.unlocked = saved[1];
        }
        dungeonsByBossKey.put(key(bx, by, bz), d);
        return d;
    }

    // ── Persistence ──

    private static long stateKey(float x, float y, float z) {
        return ((((long) Math.floor(x)) & 0xFFFFFF))
             | ((((long) Math.floor(y)) & 0xFF) << 24)
             | ((((long) Math.floor(z)) & 0xFFFFFF) << 32);
    }

    /** Serialize all known dungeon progress for the world save. */
    public static org.json.JSONArray exportState() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Dungeon d : dungeonsByBossKey.values()) {
            if (!d.bossSpawned && !d.unlocked) continue; // nothing to remember
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", d.type.name());
            o.put("x", d.bossPos.x);
            o.put("y", d.bossPos.y);
            o.put("z", d.bossPos.z);
            o.put("bossSpawned", d.bossSpawned);
            o.put("unlocked", d.unlocked);
            arr.put(o);
        }
        return arr;
    }

    /** Queue persisted progress; entries merge in when worldgen re-registers them. */
    public static void importState(org.json.JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.getJSONObject(i);
            pendingState.put(stateKey(o.getFloat("x"), o.getFloat("y"), o.getFloat("z")),
                    new boolean[]{o.getBoolean("bossSpawned"), o.getBoolean("unlocked")});
        }
    }

    /** Test/worldgen hook: how many dungeons have been placed. */
    public static int getDungeonCount() { return dungeonsByBossKey.size(); }
    public static java.util.Collection<Dungeon> getDungeons() { return dungeonsByBossKey.values(); }

    /**
     * Per-frame tick: spawn dungeon mobs near the player, spawn bosses when
     * their room is entered, and unlock doors when a boss dies.
     */
    public static void tick(com.voxel.World world, EntityManager em,
                            com.voxel.utils.TextureManager tm, Player player,
                            com.voxel.world.DimensionType activeDimension,
                            com.voxel.world.DimensionType aetherDimension,
                            float dt) {
        if (activeDimension != aetherDimension || world == null || player == null) return;
        Vector3f pp = player.getPosition();

        pruneFarEntries(pp);

        // --- Regular dungeon mobs ---
        for (SpawnPoint sp : spawnPoints.values()) {
            if (sp.spawned) continue;
            if (pp.distanceSquared(sp.pos) > 40.0f * 40.0f) continue;
            sp.spawned = true;
            EnemyEntity e = createMob(sp.kind, nextId(em), sp.pos, tm, player);
            if (e != null) {
                e.dimension = aetherDimension;
                e.setWorld(world);
                em.addEntity(e);
            }
        }

        // --- Bosses ---
        for (Dungeon d : dungeonsByBossKey.values()) {
            if (!d.bossSpawned && pp.distanceSquared(d.bossPos) < 24.0f * 24.0f) {
                d.bossSpawned = true;
                EnemyEntity boss = createBoss(d, nextId(em), tm, player);
                if (boss != null) {
                    boss.dimension = aetherDimension;
                    boss.setWorld(world);
                    em.addEntity(boss);
                    d.boss = boss;
                    liveBosses.put(boss.id, d);
                }
            }
            // --- Boss defeated → unlock doorway ---
            if (d.bossSpawned && !d.unlocked && d.boss instanceof EnemyEntity
                    && ((EnemyEntity) d.boss).isDead()) {
                d.unlocked = true;
                for (Long packedL : d.doorBlocks) {
                    long packed = packedL.longValue();
                    world.setVoxel(unpackX(packed), unpackY(packed), unpackZ(packed), 0);
                }
                liveBosses.remove(d.boss.id);
                d.boss = null;
            }
        }
    }

    /**
     * Memory guard: dungeons/spawn points recorded during streaming are only
     * useful near the player, and generation is deterministic — regenerating a
     * chunk re-registers its dungeon. Drop entries far outside the active area
     * so long Aether sessions can't grow the static maps without bound (the
     * old unbounded growth contributed to the Aether-entry OOM).
     */
    private static void pruneFarEntries(Vector3f pp) {
        final float KEEP_RADIUS = 512f;
        final float KEEP_SQ = KEEP_RADIUS * KEEP_RADIUS;
        if (spawnPoints.size() > 4096) {
            spawnPoints.values().removeIf(sp -> sp.spawned
                    || sp.pos.distanceSquared(pp) > KEEP_SQ);
        }
        if (dungeonsByBossKey.size() > 1024) {
            dungeonsByBossKey.values().removeIf(d ->
                    !d.bossSpawned && !d.unlocked
                    && d.bossPos.distanceSquared(pp) > KEEP_SQ);
        }
    }

    private static int nextId(EntityManager em) { return 90_000 + em.getEntityCount(); }

    private static EnemyEntity createMob(String kind, int id, Vector3f pos,
                                         com.voxel.utils.TextureManager tm, Player p) {
        switch (kind) {
            case "sentry":     return new SentryEntity(id, pos, tm, p);
            case "mimic":      return new MimicEntity(id, pos, tm, p);
            case "valkyrie":   return new ValkyrieEntity(id, pos, tm, p);
            case "cockatrice": return new CockatriceEntity(id, pos, tm, p);
            default:           return null;
        }
    }

    private static EnemyEntity createBoss(Dungeon d, int id,
                                          com.voxel.utils.TextureManager tm, Player p) {
        switch (d.type) {
            case BRONZE: return new SliderEntity(id, d.bossPos, tm, p);
            case SILVER: return new ValkyrieQueenEntity(id, d.bossPos, tm, p);
            case GOLD:   return new SunSpiritEntity(id, d.bossPos, tm, p);
            default:     return null;
        }
    }

    // Packed block coordinates for door blocks
    public static long pack(int x, int y, int z) {
        return ((long)(x & 0x1FFFFFFF))
             | (((long)(y & 0xFFF)) << 29)
             | (((long)(z & 0x1FFFFFFF)) << 41);
    }
    public static int unpackX(long v) { return (int)(v << 3) >> 3; }
    public static int unpackY(long v) { return (int)((v >> 29) & 0xFFF); }
    public static int unpackZ(long v) { return (int)(v >> 41); }

    private AetherDungeonRegistry() {}
}
