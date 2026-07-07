package com.voxel.game;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create-inspired Encased Fan (block ID 263).
 *
 * When powered by redstone, the fan blows a beam along its facing direction
 * (encoded in voxel extra-data bits 16-18, same scheme as pistons). Dropped
 * items caught in the beam are pushed away from the fan, letting players build
 * simple item conveyors out of fans + redstone.
 *
 * Fans register themselves via {@link #onBlockChanged} (called from
 * BlockInteraction on place/break). Stale entries (e.g. after a world reload
 * or dimension switch) are lazily dropped in {@link #tick} when the block at
 * the recorded position is no longer a fan.
 *
 * Thread-safety: the position set is a concurrent set because place/break can
 * happen on the GL thread while tick() runs on the logic thread.
 */
public class EncasedFanSystem {
    public static final int BLOCK_ENCASED_FAN = 263;
    /** Max beam length in blocks; the beam stops early at the first solid block. */
    public static final int RANGE = 8;
    /** Push speed applied to items in the beam (voxels/sec). */
    public static final float PUSH_SPEED = 3.0f;

    // Direction -> (dx, dy, dz), same order as pistons: down, up, north, south, west, east
    private static final int[][] DIR_OFFSETS = {
        { 0, -1,  0},
        { 0,  1,  0},
        { 0,  0, -1},
        { 0,  0,  1},
        {-1,  0,  0},
        { 1,  0,  0},
    };

    private final GameContext ctx;
    private final Set<Long> fans = ConcurrentHashMap.newKeySet();

    public EncasedFanSystem(GameContext ctx) {
        this.ctx = ctx;
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private static int unpack(long key, int shift) {
        int v = (int) ((key >> shift) & 0x1FFFFFL);
        return (v & 0x100000) != 0 ? v | ~0x1FFFFF : v;
    }

    /** Call after any block place/break so fans can register/unregister themselves. */
    public void onBlockChanged(int x, int y, int z) {
        if (ctx.world == null) return;
        if (ctx.world.getVoxel(x, y, z) == BLOCK_ENCASED_FAN) {
            fans.add(pack(x, y, z));
        } else {
            fans.remove(pack(x, y, z));
        }
    }

    /** Ticked from the logic thread. Pushes dropped items caught in powered fan beams. */
    public void tick(float dt) {
        if (fans.isEmpty()) return;
        if (ctx.world == null || ctx.redstoneManager == null || ctx.droppedItemManager == null) return;

        for (long key : fans) {
            int x = unpack(key, 0), y = unpack(key, 21), z = unpack(key, 42);
            // Lazy cleanup: stale position (block replaced, dimension switched, ...)
            if (ctx.world.getVoxel(x, y, z) != BLOCK_ENCASED_FAN) {
                fans.remove(key);
                continue;
            }
            if (!ctx.redstoneManager.hasPoweredNeighbor(x, y, z)) continue;

            int dir = (ctx.world.getRawVoxel(x, y, z) >> 16) & 0x7;
            if (dir > 5) dir = 1; // default to up
            int[] off = DIR_OFFSETS[dir];

            // Beam length: stop at the first solid block
            int length = 0;
            for (int i = 1; i <= RANGE; i++) {
                if (ctx.world.getVoxel(x + off[0] * i, y + off[1] * i, z + off[2] * i) != 0) break;
                length = i;
            }
            if (length == 0) continue;

            ctx.droppedItemManager.pushBeam(x, y, z, off[0], off[1], off[2], length, PUSH_SPEED * dt);
        }
    }
}
