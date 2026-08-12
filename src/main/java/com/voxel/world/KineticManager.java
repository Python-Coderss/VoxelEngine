package com.voxel.world;

import com.voxel.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Create-style kinetic network.
 *
 * Water wheels generate rotation (when adjacent to water); shafts, cogs and the
 * wheel propagate it through 6-direction adjacency. A redstone-powered clutch
 * disengages (blocks propagation); a redstone-powered gearshift reverses the
 * direction of everything downstream. Every voxel of an active network gets a
 * kinetic flag written into bits 24-25 of its packed voxel data (bit 24 =
 * spinning, bit 25 = reversed), which the raytracer shader consumes to gate and
 * flip the animated cog/wheel textures.
 *
 * Thread-safety: the kinetic position set and last-flags map are concurrent;
 * the swap queue is drained on the GL thread (applySwaps) next to the redstone
 * lamp swaps. All world writes route through ChunkManager so dirty chunks get
 * re-uploaded to the GPU.
 *
 * Kinetic block IDs: shafts 291-293, cogwheel 294, large cogwheel 295, water
 * wheel 296, clutch 353/354, gearshift 355/356.
 */
public class KineticManager {
    public static final int BLOCK_SHAFT = 291;
    public static final int BLOCK_SHAFT_X = 292;
    public static final int BLOCK_SHAFT_Z = 293;
    public static final int BLOCK_COGWHEEL = 294;
    public static final int BLOCK_LARGE_COGWHEEL = 295;
    public static final int BLOCK_WATER_WHEEL = 296;
    public static final int BLOCK_CLUTCH = 353;
    public static final int BLOCK_CLUTCH_ON = 354;
    public static final int BLOCK_GEARSHIFT = 355;
    public static final int BLOCK_GEARSHIFT_ON = 356;
    // Steam engines: 396 idle, 397 active (heated by a lit blaze burner below).
    // Active engines are rotation sources, like spinning water wheels.
    public static final int BLOCK_STEAM_ENGINE = BlazeBurnerManager.BLOCK_STEAM_ENGINE;
    public static final int BLOCK_STEAM_ENGINE_ACTIVE = BlazeBurnerManager.BLOCK_STEAM_ENGINE_ACTIVE;

    // Voxel flag bits (bits 24-25 of the packed int)
    public static final int FLAG_SPINNING = 1;
    public static final int FLAG_REVERSE = 2;

    private static final int BLOCK_WATER = 15;

    private static final int[][] DIRS = {
        {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    public static boolean isKinetic(int block) {
        return (block >= BLOCK_SHAFT && block <= BLOCK_WATER_WHEEL)
            || block == BLOCK_CLUTCH || block == BLOCK_CLUTCH_ON
            || block == BLOCK_GEARSHIFT || block == BLOCK_GEARSHIFT_ON
            || block == BLOCK_STEAM_ENGINE || block == BLOCK_STEAM_ENGINE_ACTIVE;
    }

    public static boolean isClutch(int block) {
        return block == BLOCK_CLUTCH || block == BLOCK_CLUTCH_ON;
    }

    public static boolean isGearshift(int block) {
        return block == BLOCK_GEARSHIFT || block == BLOCK_GEARSHIFT_ON;
    }

    public static boolean isWaterWheel(int block) {
        return block == BLOCK_WATER_WHEEL;
    }

    private final World world;
    private final ChunkManager chunkManager;
    private final RedstoneManager redstoneManager;

    private final Set<Long> kineticPositions = ConcurrentHashMap.newKeySet();
    // Concurrent: onBlockChanged runs on the GL thread while tick()/writeFlags run on
    // the logic thread (same GL/logic split as RedstoneManager's component maps).
    private final Map<Long, Integer> lastFlags = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<int[]> swapQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> scannedColumns = ConcurrentHashMap.newKeySet();
    private int rescanCooldown = 0;

    private static long pack(int x, int y, int z) {
        long ux = x & 0x1FFFFFL;
        long uy = y & 0x1FFFFFL;
        long uz = z & 0x1FFFFFL;
        return (ux << 42) | (uy << 21) | uz;
    }

    private static int unpackX(long key) { return (int) ((key >> 42) & 0x1FFFFFL); }
    private static int unpackY(long key) { return (int) ((key >> 21) & 0x1FFFFFL); }
    private static int unpackZ(long key) { return (int) (key & 0x1FFFFFL); }

    public KineticManager(World world, ChunkManager chunkManager, RedstoneManager redstoneManager) {
        this.world = world;
        this.chunkManager = chunkManager;
        this.redstoneManager = redstoneManager;
    }

    /** Called after any block change (placement/break) to track kinetic positions. */
    public void onBlockChanged(int x, int y, int z) {
        long key = pack(x, y, z);
        if (isKinetic(world.getVoxel(x, y, z))) {
            kineticPositions.add(key);
        } else {
            kineticPositions.remove(key);
            lastFlags.remove(key);
        }
    }

    /**
     * Incrementally discovers kinetic blocks in newly loaded chunk columns
     * (covers dimension switches and world reloads, where this manager starts
     * with an empty position set). Each column is scanned once; ~65k lookups.
     */
    private void rescanIncremental() {
        if (--rescanCooldown > 0) return;
        rescanCooldown = 5; // every 5 logic ticks (~80ms)
        if (chunkManager.getLoadedChunks() == null) return;
        for (long colKey : chunkManager.getLoadedChunks().keySet()) {
            if (!scannedColumns.add(colKey)) continue;
            int cx = (int) (colKey >> 32);
            int cz = (int) colKey;
            int wx = cx * 16, wz = cz * 16;
            for (int x = wx; x < wx + 16; x++) {
                for (int z = wz; z < wz + 16; z++) {
                    for (int y = 0; y < 256; y++) {
                        if (isKinetic(world.getVoxel(x, y, z))) {
                            kineticPositions.add(pack(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * Called from the logic thread every tick, AFTER redstoneManager.tickLamps()
     * so clutch/gearshift power states reflect the latest network.
     */
    public void tick() {
        rescanIncremental();

        // 1. Refresh clutch/gearshift powered states. The swaps are applied on the GL
        // thread (applySwaps), so a just-powered clutch gates its network one tick
        // later — the same swap pattern the redstone lamps use.
        // (block-ID swaps applied on GL thread)
        for (long key : kineticPositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);
            if (block <= 0) continue;
            if (isClutch(block)) {
                int want = redstoneManager.hasPoweredNeighbor(x, y, z) ? BLOCK_CLUTCH_ON : BLOCK_CLUTCH;
                if (block != want) swapQueue.add(new int[]{x, y, z, want});
            } else if (isGearshift(block)) {
                int want = redstoneManager.hasPoweredNeighbor(x, y, z) ? BLOCK_GEARSHIFT_ON : BLOCK_GEARSHIFT;
                if (block != want) swapQueue.add(new int[]{x, y, z, want});
            }
        }

        // 2. Clear stale flags, then BFS every network that contains a spinning water wheel.
        for (long key : kineticPositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            if (world.getVoxel(x, y, z) > 0) writeFlags(x, y, z, 0);
        }
        Set<Long> visited = new HashSet<>();
        for (long key : kineticPositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);
            boolean isSource = (block == BLOCK_WATER_WHEEL && hasAdjacentWater(x, y, z))
                || block == BLOCK_STEAM_ENGINE_ACTIVE;
            if (isSource) {
                bfsNetwork(x, y, z, visited);
            }
        }
    }

    private void bfsNetwork(int sx, int sy, int sz, Set<Long> visited) {
        long startKey = pack(sx, sy, sz);
        if (visited.contains(startKey)) return;
        visited.add(startKey);
        Deque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{sx, sy, sz, 0});
        while (!queue.isEmpty()) {
            long[] cur = queue.poll();
            int x = (int) cur[0], y = (int) cur[1], z = (int) cur[2];
            boolean reverse = cur[3] != 0;
            writeFlags(x, y, z, reverse ? (FLAG_SPINNING | FLAG_REVERSE) : FLAG_SPINNING);
            for (int[] off : DIRS) {
                int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                long nkey = pack(nx, ny, nz);
                if (visited.contains(nkey)) continue;
                int nb = world.getVoxel(nx, ny, nz);
                if (!isKinetic(nb) || nb <= 0) continue;
                if (nb == BLOCK_CLUTCH_ON) continue; // powered clutch disengages
                boolean nrev = reverse;
                if (nb == BLOCK_GEARSHIFT_ON) nrev = !reverse;
                visited.add(nkey);
                queue.add(new long[]{nx, ny, nz, nrev ? 1 : 0});
            }
        }
    }

    private void writeFlags(int x, int y, int z, int flags) {
        long key = pack(x, y, z);
        Integer last = lastFlags.get(key);
        if (last != null && last.intValue() == flags) return;
        lastFlags.put(key, flags);
        int block = world.getVoxel(x, y, z);
        if (block > 0) {
            chunkManager.setVoxelWithFlags(x, y, z, block, 0, flags);
        }
    }

    private boolean hasAdjacentWater(int x, int y, int z) {
        int[][] offs = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, -1, 0}};
        for (int[] off : offs) {
            if (world.getVoxel(x + off[0], y + off[1], z + off[2]) == BLOCK_WATER) return true;
        }
        return false;
    }

    /** Must be called from the GL thread next to redstoneManager.applyLampChanges(). */
    public void applySwaps() {
        int[] change;
        while ((change = swapQueue.poll()) != null) {
            chunkManager.setVoxel(change[0], change[1], change[2], change[3]);
        }
    }

}
