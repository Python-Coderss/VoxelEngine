package com.voxel.world;

import com.voxel.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Create-style blaze burner. Burners burn solid fuel (coal, charcoal, blaze
 * rods/powder) and swap between the unlit (394) and lit (395) block states so
 * the lit texture glows. A steam engine placed directly above a lit burner is
 * heated: the burner manager swaps it between the idle (396) and active (397)
 * states, and KineticManager treats active engines as rotation sources.
 *
 * Block-ID swaps are queued on the logic thread and applied on the GL thread
 * (drainSwaps) next to KineticManager.applySwaps — the same pattern the
 * redstone lamps use.
 */
public class BlazeBurnerManager {

    public static final int BLOCK_BLAZE_BURNER = 394;
    public static final int BLOCK_BLAZE_BURNER_LIT = 395;
    public static final int BLOCK_STEAM_ENGINE = 396;
    public static final int BLOCK_STEAM_ENGINE_ACTIVE = 397;

    /** Fuel burn speed: fuel units are logic ticks (20/s). */
    private static final float TICKS_PER_SECOND = 20.0f;

    public static boolean isBlazeBurner(int block) {
        return block == BLOCK_BLAZE_BURNER || block == BLOCK_BLAZE_BURNER_LIT;
    }

    public static boolean isSteamEngine(int block) {
        return block == BLOCK_STEAM_ENGINE || block == BLOCK_STEAM_ENGINE_ACTIVE;
    }

    private final World world;
    private final ChunkManager chunkManager;

    private final java.util.Set<Long> burnerPositions = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> enginePositions = ConcurrentHashMap.newKeySet();
    /** Remaining fuel in ticks for each tracked burner position. */
    private final Map<Long, Integer> fuel = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<int[]> swapQueue = new ConcurrentLinkedQueue<>();

    public BlazeBurnerManager(World world, ChunkManager chunkManager) {
        this.world = world;
        this.chunkManager = chunkManager;
    }

    /** Called after any block change (placement/break) to track burners and engines. */
    public void onBlockChanged(int x, int y, int z) {
        int block = world.getVoxel(x, y, z);
        long key = pack(x, y, z);
        if (isBlazeBurner(block)) {
            burnerPositions.add(key);
            if (!fuel.containsKey(key)) fuel.put(key, 0);
        } else {
            burnerPositions.remove(key);
            fuel.remove(key);
        }
        if (isSteamEngine(block)) {
            enginePositions.add(key);
        } else {
            enginePositions.remove(key);
        }
    }

    /** Add fuel to a burner. Returns the new fuel value in ticks. */
    public int addFuel(int x, int y, int z, int fuelTicks) {
        long key = pack(x, y, z);
        int cur = fuel.getOrDefault(key, 0);
        int next = Math.min(20000, cur + fuelTicks);
        fuel.put(key, next);
        burnerPositions.add(key);
        return next;
    }

    public int getFuel(int x, int y, int z) {
        return fuel.getOrDefault(pack(x, y, z), 0);
    }

    /** Is the burner at (x,y,z) currently producing heat? */
    public boolean isLit(int x, int y, int z) {
        return world.getVoxel(x, y, z) == BLOCK_BLAZE_BURNER_LIT;
    }

    /** Called every logic tick. */
    public void tick(float dt) {
        float tickCost = dt * TICKS_PER_SECOND;

        // 1. Burn fuel and request lit/unlit swaps.
        for (long key : burnerPositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            if (world.getVoxel(x, y, z) <= 0) continue;

            int cur = fuel.getOrDefault(key, 0);
            int next = cur;
            if (cur > 0) {
                next = Math.max(0, cur - (int) Math.ceil(tickCost));
                fuel.put(key, next);
            }
            int want = next > 0 ? BLOCK_BLAZE_BURNER_LIT : BLOCK_BLAZE_BURNER;
            int block = world.getVoxel(x, y, z);
            if (block != want) swapQueue.add(new int[]{x, y, z, want});
        }

        // 2. Steam engines: active only when the block directly below is a lit burner.
        for (long key : enginePositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            if (world.getVoxel(x, y, z) <= 0) continue;
            boolean heated = world.getVoxel(x, y - 1, z) == BLOCK_BLAZE_BURNER_LIT;
            int want = heated ? BLOCK_STEAM_ENGINE_ACTIVE : BLOCK_STEAM_ENGINE;
            int block = world.getVoxel(x, y, z);
            if (block != want) swapQueue.add(new int[]{x, y, z, want});
        }
    }

    /** Must be called from the GL thread next to KineticManager.applySwaps(). */
    public void drainSwaps() {
        int[] change;
        while ((change = swapQueue.poll()) != null) {
            chunkManager.setVoxel(change[0], change[1], change[2], change[3]);
        }
    }

    // ── Position packing (matches KineticManager) ──

    private static long pack(int x, int y, int z) {
        long ux = x & 0x1FFFFFL;
        long uy = y & 0x1FFFFFL;
        long uz = z & 0x1FFFFFL;
        return (ux << 42) | (uy << 21) | uz;
    }

    private static int unpackX(long key) { return (int) ((key >> 42) & 0x1FFFFFL); }
    private static int unpackY(long key) { return (int) ((key >> 21) & 0x1FFFFFL); }
    private static int unpackZ(long key) { return (int) (key & 0x1FFFFFL); }
}
