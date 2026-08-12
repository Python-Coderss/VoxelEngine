package com.voxel.world;

import com.voxel.World;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Create-style copper tank. Fluid level is encoded directly in the block ID:
 * 398 = empty, 399-403 = levels 1-5 (1000 mB each, 5000 mB capacity). Filling
 * with a water bucket and draining with an empty bucket swap the block to the
 * matching level variant on the GL thread (drainSwaps), so no per-position
 * state map is needed and levels survive chunk save/load for free.
 */
public class CopperTankManager {

    public static final int BLOCK_COPPER_TANK = 398;
    public static final int BLOCK_COPPER_TANK_FULL = 403;
    public static final int TANK_CAPACITY = 5000;
    public static final int TANK_BUCKET = 1000;
    public static final int MAX_LEVEL = 5;

    public static boolean isCopperTank(int block) {
        return block >= BLOCK_COPPER_TANK && block <= BLOCK_COPPER_TANK_FULL;
    }

    /** Fluid in mB for a tank block ID. */
    public static int fluidForBlock(int block) {
        if (!isCopperTank(block)) return 0;
        return (block - BLOCK_COPPER_TANK) * TANK_BUCKET;
    }

    /** Block ID for a fluid amount in mB (clamped to 0..capacity). */
    public static int blockForFluid(int mB) {
        int level = (Math.max(0, Math.min(TANK_CAPACITY, mB)) + TANK_BUCKET / 2) / TANK_BUCKET;
        return BLOCK_COPPER_TANK + Math.min(MAX_LEVEL, level);
    }

    private final World world;
    private final ChunkManager chunkManager;

    private final ConcurrentLinkedQueue<int[]> swapQueue = new ConcurrentLinkedQueue<>();

    public CopperTankManager(World world, ChunkManager chunkManager) {
        this.world = world;
        this.chunkManager = chunkManager;
    }

    /**
     * Try to add one bucket of fluid. Returns true if the tank accepted it
     * (was not already full).
     */
    public boolean fill(int x, int y, int z) {
        int block = world.getVoxel(x, y, z);
        if (!isCopperTank(block)) return false;
        int fluid = fluidForBlock(block);
        if (fluid >= TANK_CAPACITY) return false;
        swapQueue.add(new int[]{x, y, z, blockForFluid(fluid + TANK_BUCKET)});
        return true;
    }

    /**
     * Try to remove one bucket of fluid. Returns true if the tank had fluid.
     */
    public boolean drain(int x, int y, int z) {
        int block = world.getVoxel(x, y, z);
        if (!isCopperTank(block)) return false;
        int fluid = fluidForBlock(block);
        if (fluid <= 0) return false;
        swapQueue.add(new int[]{x, y, z, blockForFluid(fluid - TANK_BUCKET)});
        return true;
    }

    /** Must be called from the GL thread next to KineticManager.applySwaps(). */
    public void drainSwaps() {
        int[] change;
        while ((change = swapQueue.poll()) != null) {
            chunkManager.setVoxel(change[0], change[1], change[2], change[3]);
        }
    }
}
