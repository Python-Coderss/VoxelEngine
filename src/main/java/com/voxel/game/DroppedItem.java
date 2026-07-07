package com.voxel.game;

/**
 * A single item dropped in the world after a block break. Items hover in place,
 * bob gently on the Y axis, and are picked up automatically when the player walks
 * within pickup range.
 *
 * Lifecycle:
 *  - Created by {@link DroppedItemManager#spawn} when a block is broken.
 *  - Updated every tick (bob phase advances; alive flag stays true until picked up).
 *  - Marked dead when picked up by the player; the slot is reused or compacted.
 *
 * Position semantics:
 *  - {@code baseY} is the Y at which the item hovers (typically brokenBlockY + 1.0).
 *  - Actual rendered Y = baseY + sin(elapsed * phase) * BOB_AMPLITUDE.
 *    Bob phase is generated uniquely per spawn, so multiple items in proximity
 *    do not bob in lockstep.
 */
public final class DroppedItem {
    public static final float BOB_AMPLITUDE = 0.05f; // vertical bob (voxels). Was 0.10; reduced so
                                                       // the rest gap above ground stays in the
                                                       // configured [0.125, 0.25] window.
    public static final float BOB_FREQ = 2.0f;      // radians/sec — ~0.32 Hz
    /** Spin rate for the Y-axis rotation: 15 RPM = π/2 rad/sec. */
    public static final float SPIN_RAD_PER_SEC = (float)(15.0 * 2.0 * Math.PI / 60.0);

    public final String itemId;
    /** Horizontal position. Mutable — encased fans can push items sideways.
     *  Volatile so the render thread's buildUpload sees consistent values. */
    public volatile float baseX;
    public volatile float baseZ;
    /** Vertical center of the item. Mutable per frame (gravity fall), then locked by `grounded`.
     *  Declared volatile so the render thread's {@code buildUpload} reads each tick see a
     *  consistent, JMM-visible value without re-acquiring the manager's monitor. */
    public volatile float baseY;
    /** Cached blockId for renderer; -1 if item has no registered block (e.g. tool). */
    public final int blockId;
    public final float bobPhase;
    public final int count;
    /** Spawn time (System.nanoTime) so each item spins independently from its drop instant. */
    public final long spawnTimeNs;
    /** Toggled false by pickup on the logic thread; read by the render thread's
     *  {@code buildUpload} to decide whether to skip the entry. Volatile gives a
     *  JMM-visible read without re-acquiring the manager's monitor. */
    public volatile boolean alive = true;

    // ---- Physics ----
    /** Vertical velocity (voxels/sec). Negative = falling. */
    public float vy = 0f;
    /** True once the item has settled onto the ground beneath it (parity: lock vy, lock baseY at restY). */
    public boolean grounded = false;
    /** Y where the item should come to rest after gravity settles it. baseY converges here on landing.
     *  Mutable — updated when the supporting block is destroyed and new ground is found. */
    public float restY;
    /** Top-face Y of the ground voxel beneath the item (or the original spawn Y if no ground found).
     *  Mutable — updated when the supporting block is destroyed and new ground is found. */
    public float groundTopY;
    /** Constant per-item randomized hover distance (item bottom -> ground top). Manager's [MIN, MAX] range. */
    public final float hoverHeight;
    /** World position of the solid block beneath this item (the supporting block).
     *  When that block is destroyed, the item should become un-grounded and fall.
     *  Mutable — updated when the supporting block is destroyed and new ground is found. */
    public int supportBlockX, supportBlockY, supportBlockZ;
    /** Whether a supporting block was found during spawn. False if the item spawned
     *  mid-air with no ground below (e.g., broken block over a void). */
    public boolean hasSupportBlock;

    public DroppedItem(String itemId, int blockId, int count, float x, float y, float z, float bobPhase, long spawnTimeNs,
                       float restY, float groundTopY, float hoverHeight,
                       int supportBlockX, int supportBlockY, int supportBlockZ, boolean hasSupportBlock) {
        this.itemId = itemId;
        this.blockId = blockId;
        this.count = count;
        this.baseX = x;
        this.baseY = y;
        this.baseZ = z;
        this.bobPhase = bobPhase;
        this.spawnTimeNs = spawnTimeNs;
        this.restY = restY;
        this.groundTopY = groundTopY;
        this.hoverHeight = hoverHeight;
        this.supportBlockX = supportBlockX;
        this.supportBlockY = supportBlockY;
        this.supportBlockZ = supportBlockZ;
        this.hasSupportBlock = hasSupportBlock;
    }
}
