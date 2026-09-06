package com.voxel.game;

import com.voxel.Player;
import com.voxel.World;
import com.voxel.world.ChunkManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla TNT: ignites when clicked with flint and steel (or a fire charge),
 * waits out a short fuse, then detonates — destroying blocks in a sphere and
 * damaging the player with distance falloff, like a creeper blast but wider.
 *
 * <p>The TNT block id comes from the resource pack auto-loader (like the
 * redstone switches), so it is configured at startup. Primed TNT keeps its
 * normal block id; the fuse lives in this manager until it detonates.
 */
public final class TntBlock {

    /** Fuse length in seconds before detonation. */
    public static final float FUSE_SECONDS = 1.6f;
    /** Blast radius in blocks. */
    public static final float EXPLOSION_RADIUS = 4.0f;
    /** Bedrock is indestructible, explosions must skip it. */
    private static final int BEDROCK = 7;
    /** Maximum explosion damage at point-blank range. */
    private static final float EXPLOSION_DAMAGE = 24.0f;

    private static volatile int tntId = 0;

    /** Packed position → seconds of fuse remaining. Thread-safe. */
    private static final Map<Long, Float> fuses = new ConcurrentHashMap<>();

    private TntBlock() { }

    /** Locks in the block id the resource pack gave TNT (0 = TNT unavailable). */
    public static void configure(int id) {
        tntId = id;
    }

    public static int tntId() {
        return tntId;
    }

    public static boolean isTnt(int blockId) {
        return tntId > 0 && blockId == tntId;
    }

    /** Starts the fuse on a TNT block. Returns false if there is no TNT there. */
    public static boolean ignite(World world, int x, int y, int z) {
        if (tntId <= 0 || world == null || world.getVoxel(x, y, z) != tntId) return false;
        fuses.put(pack(x, y, z), FUSE_SECONDS);
        return true;
    }

    /** Number of primed TNT blocks (exposed for tests). */
    public static int primedCount() {
        return fuses.size();
    }

    /** Clears the fuse map (tests). */
    public static void resetForTest() {
        fuses.clear();
    }

    /**
     * Advances every primed fuse by {@code dt} seconds and detonates any that
     * reach zero. Called once per logic tick from Main.
     */
    public static void tick(World world, ChunkManager chunkManager, Player player, float dt) {
        if (fuses.isEmpty()) return;
        java.util.List<Long> detonated = new java.util.ArrayList<>();
        for (Map.Entry<Long, Float> e : fuses.entrySet()) {
            float left = e.getValue() - dt;
            if (left <= 0.0f) {
                detonated.add(e.getKey());
            } else {
                e.setValue(left);
            }
        }
        for (long key : detonated) {
            fuses.remove(key);
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            // The block may have been mined away mid-fuse (then nothing explodes).
            if (world == null || chunkManager == null || world.getVoxel(x, y, z) != tntId) continue;
            detonate(world, chunkManager, player, x, y, z);
        }
    }

    /**
     * Removes the TNT and destroys blocks in a rough sphere with distance
     * falloff, then damages the player (falling off with distance).
     */
    public static void detonate(World world, ChunkManager chunkManager, Player player,
                                int x, int y, int z) {
        if (world == null || chunkManager == null) return;
        chunkManager.setVoxel(x, y, z, 0);

        int r = (int) Math.ceil(EXPLOSION_RADIUS);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > EXPLOSION_RADIUS) continue;
                    // The core of the blast always destroys; the fringe is chancy.
                    if (dist > EXPLOSION_RADIUS - 1.4f && Math.random() < 0.5f) continue;
                    int block = world.getVoxel(x + dx, y + dy, z + dz);
                    if (block != 0 && block != BEDROCK) {
                        chunkManager.setVoxel(x + dx, y + dy, z + dz, 0);
                    }
                }
            }
        }

        if (player != null) {
            float px = com.voxel.utils.FixedPoint.toFloat(player.getFixedX());
            float py = com.voxel.utils.FixedPoint.toFloat(player.getFixedY());
            float pz = com.voxel.utils.FixedPoint.toFloat(player.getFixedZ());
            float ddx = px - (x + 0.5f), ddy = py - (y + 0.5f), ddz = pz - (z + 0.5f);
            float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            float dmg = EXPLOSION_DAMAGE * Math.max(0.0f, 1.0f - dist / (EXPLOSION_RADIUS * 1.5f));
            if (dmg > 0.0f) player.takeDamage(dmg);
        }
    }

    // ---- Position packing (21 bits per axis) — same scheme as RedstoneManager ----
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFFL) << 42) | ((long) (y & 0x1FFFFFL) << 21) | (z & 0x1FFFFFL);
    }

    private static int unpackX(long key) {
        long v = (key >> 42) & 0x1FFFFFL;
        return (v & 0x100000L) != 0 ? (int) (v | 0xFFFFFFFFFFE00000L) : (int) v;
    }

    private static int unpackY(long key) {
        long v = (key >> 21) & 0x1FFFFFL;
        return (v & 0x100000L) != 0 ? (int) (v | 0xFFFFFFFFFFE00000L) : (int) v;
    }

    private static int unpackZ(long key) {
        long v = key & 0x1FFFFFL;
        return (v & 0x100000L) != 0 ? (int) (v | 0xFFFFFFFFFFE00000L) : (int) v;
    }
}
