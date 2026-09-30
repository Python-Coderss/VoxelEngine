package com.voxel.game;

import com.voxel.Player;
import com.voxel.World;
import com.voxel.world.ChunkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Formidi-Bomb from Minecraft: Story Mode — Ivor's clay mega-charge.
 * Ignites like TNT (flint and steel / fire charge) with a longer fuse, then
 * detonates in a blast several times wider than TNT and chain-ignites every
 * other bomb (and TNT) within a few blocks, like the F-Bombs in the story.
 */
public final class FormidiBomb {

    /** Fuse length in seconds before detonation. */
    public static final float FUSE_SECONDS = 2.4f;
    /** Blast radius in blocks (TNT is 4). */
    public static final float EXPLOSION_RADIUS = 8.0f;
    /** Radius in which neighbouring bombs are chain-ignited. */
    public static final float CHAIN_RADIUS = 5.0f;
    /** Maximum explosion damage at point-blank range. */
    public static final float EXPLOSION_DAMAGE = 48.0f;
    /** Bedrock is indestructible, explosions must skip it. */
    private static final int BEDROCK = 7;

    private static volatile int formidiId = 0;

    /** Packed position → seconds of fuse remaining. Thread-safe. */
    private static final Map<Long, Float> fuses = new ConcurrentHashMap<>();

    private FormidiBomb() { }

    /** Locks in the block id of the Formidi-Bomb (0 = unavailable). */
    public static void configure(int id) {
        formidiId = id;
    }

    public static int formidiId() {
        return formidiId;
    }

    public static boolean isFormidi(int blockId) {
        return formidiId > 0 && blockId == formidiId;
    }

    /** Starts the fuse on a Formidi-Bomb. Returns false if there is none there. */
    public static boolean ignite(World world, int x, int y, int z) {
        if (formidiId <= 0 || world == null || world.getVoxel(x, y, z) != formidiId) return false;
        fuses.put(pack(x, y, z), FUSE_SECONDS);
        return true;
    }

    /** Number of primed Formidi-Bombs (exposed for tests). */
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
        List<Long> detonated = new ArrayList<>();
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
            if (world == null || chunkManager == null || world.getVoxel(x, y, z) != formidiId) continue;
            detonate(world, chunkManager, player, x, y, z);
        }
    }

    /**
     * Removes the bomb, destroys blocks in a wide sphere, damages the player
     * with distance falloff, and chain-ignites neighbouring bombs and TNT.
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
                    if (dist > EXPLOSION_RADIUS - 1.6f && Math.random() < 0.5) continue;
                    int block = world.getVoxel(x + dx, y + dy, z + dz);
                    if (block != 0 && block != BEDROCK) {
                        chunkManager.setVoxel(x + dx, y + dy, z + dz, 0);
                    }
                }
            }
        }

        // Chain ignition: bombs and TNT near the blast cook off moments later.
        int cr = (int) Math.ceil(CHAIN_RADIUS);
        for (int dx = -cr; dx <= cr; dx++) {
            for (int dy = -cr; dy <= cr; dy++) {
                for (int dz = -cr; dz <= cr; dz++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    int block = world.getVoxel(nx, ny, nz);
                    if (isFormidi(block)) {
                        fuses.put(pack(nx, ny, nz), (float) (0.3 + Math.random() * 0.5));
                    } else if (TntBlock.isTnt(block)) {
                        TntBlock.ignite(world, nx, ny, nz);
                    }
                }
            }
        }

        if (player != null) {
            float px = com.voxel.utils.FixedPoint.toFloat(player.getFixedX());
            float py = com.voxel.utils.FixedPoint.toFloat(player.getFixedY());
            float pz = com.voxel.utils.FixedPoint.toFloat(player.getFixedZ());
            float dx = px - x, dy = (py + 1) - y, dz = pz - z;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < EXPLOSION_RADIUS + 2.0f) {
                float damage = EXPLOSION_DAMAGE * Math.max(0f, 1.0f - dist / (EXPLOSION_RADIUS + 2.0f));
                player.takeDamage(damage);
            }
        }
    }

    // ---- Position packing (21 bits per axis) — same scheme as TntBlock ----
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
