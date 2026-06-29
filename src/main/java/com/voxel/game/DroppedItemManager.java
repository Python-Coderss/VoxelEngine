package com.voxel.game;

import com.voxel.game.ItemDefinitions.ItemDefinition;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tracks all dropped items currently hovering in the world.
 *
 * Responsibilities:
 *  - Spawn a new drop when a block is broken (replaces direct-to-inventory addItem).
 *  - Bob animation: items drift up/down via cos(elapsed + phase) so multiple items
 *    don't bob in lockstep.
 *  - Automatic pickup: when the player walks within PICKUP_RADIUS, the item's full
 *    count is added to the inventory and the entry is marked for removal.
 *  - Upload-side packing: build a flat float[] of 8 floats per item (the same
 *    layout as the crafting-item SSBO entries) so the existing rendering shader
 *    can render dropped items without modification.
 *
 * Threading note: spawn/update/buildUpload are called from the logic thread;
 * the packed buffer's contents are consumed by the GL thread during the render
 * frame. Because the buffer is rebuilt every tick before being uploaded, no
 * extra synchronization is required (java arrays reads on the same memory are
 * safe across threads once the array is fully written — the synchronization
 * point is the glNamedBufferSubData call).
 */
public class DroppedItemManager {
    /** Compact array of active drops. Index 0 is reused first once a slot dies. */
    private final List<DroppedItem> items = new ArrayList<>();
    /** Reusable snapshot buffer for buildUpload — avoids per-frame allocation. */
    private final DroppedItem[] snapshotBuf = new DroppedItem[MAX_ITEMS];

    public static final int MAX_ITEMS = 64; // matches grown craftingItemSSBO capacity (64 * 32 bytes)
    public static final float PICKUP_RADIUS = 1.5f; // voxels — player centered on feet catches items
    /** Visual scale of the rendered miniature block. 0.25 = quarter-cube, visible yet small. */
    public static final float DROPPED_ITEM_SCALE = 0.25f;

    // ---- Per-drop physics (set at spawn once ground is found) ----
    /** Random per-item hover distance (item bottom -> ground top), uniform in [MIN, MAX]. */
    public static final float MIN_HOVER_ABOVE_GROUND = 0.125f;
    public static final float MAX_HOVER_ABOVE_GROUND = 0.25f;
    /** Voxels/sec²: pull on falling items. Minecart-ish feel without being too floaty. */
    public static final float GRAVITY = -20.0f;
    /** Terminal fall speed (voxels/sec). Negative — magnitude cap. */
    public static final float MAX_FALL_SPEED = -8.0f;
    /** Max downward voxel scan stops per spawn — avoids falling through unloaded voids. */
    public static final int GROUND_SEARCH_DEPTH = 32;

    private final GameContext ctx;
    private final Random rng = new Random();

    public DroppedItemManager(GameContext ctx) {
        this.ctx = ctx;
    }

    /** Number of drops currently being rendered. */
    public int getItemCount() { return items.size(); }

    /** Empty all drops (called on dimension switch). */
    public void clearAll() {
        items.clear();
    }

    /**
     * Spawn a dropped item at the broken block's center; gravity will then carry it down to
     * settle in a small hover window above the ground beneath. The item's restY / groundTopY
     * are determined here (one-shot ground search); the actual fall physics happens in
     * {@link #update}.
     * @param itemId item-id string (e.g. "oak_log")
     * @param count drop count (1 for most blocks, 4 for redstone_ore, etc.)
     * @param blockX broken block world x
     * @param blockY broken block world y
     * @param blockZ broken block world z
     */
    public void spawn(String itemId, int count, int blockX, int blockY, int blockZ) {
        if (itemId == null || count <= 0) return;
        ItemDefinition def = ctx.itemDefinitions != null ? ctx.itemDefinitions.getDefinition(itemId) : null;
        if (def == null || def.blockId <= 0) {
            // Items without a block rendering (rare) — skip dropping for now.
            return;
        }
        // Spawn centered on the broken block (Y at block center, X/Z at column center) —
        // gravity then pulls the item down to whatever ground lies beneath this column.
        float x = blockX + 0.5f;
        float y = blockY + 0.5f; // block center: user-specified spawn position
        float z = blockZ + 0.5f;
        float phase = (float)(rng.nextDouble() * Math.PI * 2.0);
        long spawnTimeNs = System.nanoTime();

        // Ground search: scan the column directly below the broken block (skip the now-empty
        // broken-block cell itself). The first non-air voxel below is the ground candidate.
        // Cap at GROUND_SEARCH_DEPTH so we don't fall into unloaded voids; if nothing is
        // found, the item stays at its spawn height (no fall).
        float groundTopY = y; // fallback: no ground found, item hovers at spawn
        if (ctx.world != null) {
            for (int dy = 1; dy <= GROUND_SEARCH_DEPTH; dy++) {
                int probeY = blockY - dy;
                if (ctx.world.getVoxel(blockX, probeY, blockZ) != 0) {
                    groundTopY = probeY + 1.0f; // top face of the solid voxel we landed on
                    break;
                }
            }
        }
        float hover = MIN_HOVER_ABOVE_GROUND
                    + rng.nextFloat() * (MAX_HOVER_ABOVE_GROUND - MIN_HOVER_ABOVE_GROUND);
        float halfScale = 0.5f * DROPPED_ITEM_SCALE;
        // restY positions the item so bottom face = groundTopY + hover.
        float restY = groundTopY + hover + halfScale;
        // baseY starts at spawn (broken-block center); fallback restY == y means no fall.
        DroppedItem di = new DroppedItem(itemId, def.blockId, count, x, y, z, phase, spawnTimeNs,
                                         restY, groundTopY, hover);
        synchronized (items) {
            // Pool cap exceeded — fail soft (just don't drop). Future: drop oldest or compress stacks.
            if (items.size() >= MAX_ITEMS) {
                return;
            }
            items.add(di);
        }
    }

    /**
     * Per-tick update: integrate gravity for in-flight drops, then pickup check.
     *
     *  - Falling items: integrate vy with terminal-velocity cap, advance baseY; snap to
     *    restY once the item reaches its allotted hover above the ground found at
     *    spawn (see {@link #spawn}). Lock vy and switch grounded = true.
     *  - Grounded items: position no longer changes from gravity; bob amplitude is
     *    applied atomically in {@link #buildUpload} from {@link System#nanoTime()}.
     *
     * The pickup check runs in a second pass over the same list so a drop landing
     * the same tick can be collected immediately. dt is the per-tick delta in
     * seconds; bob timing uses wall-clock elsewhere to stay smooth regardless of
     * tick rate.
     */
    public void update(float dt, Vector3f playerPos) {
        if (items.isEmpty()) return;
        DroppedItem picked = null;
        synchronized (items) {
            // First pass: gravity integration + landing snap. Run before pickup so a
            // freshly settled item can be picked up the same tick it lands.
            for (int i = 0; i < items.size(); i++) {
                DroppedItem di = items.get(i);
                if (di.grounded || !di.alive) continue;
                // Semi-implicit Euler with terminal velocity. Negative gravity; vy <= 0.
                di.vy = Math.max(di.vy + GRAVITY * dt, MAX_FALL_SPEED);
                di.baseY += di.vy * dt;
                // Snap when baseY reaches restY (= groundTopY + hover + halfScale), or
                // tries to overshoot on a long-tick lag spike.
                if (di.baseY <= di.restY) {
                    di.baseY = di.restY;
                    di.vy = 0f;
                    di.grounded = true;
                }
            }
            // Second pass (back-to-front): pickup check. Works for both falling and
            // grounded items so a drop passing through the player is also collectable.
            for (int i = items.size() - 1; i >= 0; i--) {
                DroppedItem di = items.get(i);
                if (!di.alive) {
                    items.remove(i);
                    continue;
                }
                // Pickup check: horizontal "walk over" radius, vertical window forgiving
                // enough to catch a falling item passing through.
                float dx = playerPos.x - di.baseX;
                float dz = playerPos.z - di.baseZ;
                float horizDist2 = dx * dx + dz * dz;
                float itemCenterY = di.baseY + 0.5f * DROPPED_ITEM_SCALE;
                float playerTorsoY = playerPos.y + 1.0f; // feet -> mid-torso offset
                float dy = playerTorsoY - itemCenterY;
                if (horizDist2 < PICKUP_RADIUS * PICKUP_RADIUS && Math.abs(dy) < 1.6f) {
                    picked = di;
                    di.alive = false;
                    items.remove(i);
                    break; // one pickup per tick per player is plenty
                }
            }
        }
        // Outside the lock: deposit into inventory and notify the status consumer.
        if (picked != null) {
            if (ctx.playerInventory != null && !ctx.playerInventory.addItem(picked.itemId, picked.count)) {
                // Inventory full — re-insert the item so it stays on the ground.
                synchronized (items) {
                    picked.alive = true;
                    if (items.size() < MAX_ITEMS) items.add(picked);
                }
                return;
            }
            if (ctx.statusConsumer != null) ctx.statusConsumer.accept("Picked up: " + picked.itemId.replace('_', ' '));
        }
    }

    /**
     * Flatten all live drops into 8-float-per-item buffer entries compatible with
     * the existing CraftingItem shader struct (position.xyz, position.w=blockId,
     * blockInfo.x=scale, blockInfo.yzw=padding).
     *
     * Threading note: this is called from the render thread (in {@code Main.loop})
     * while {@link #update} runs on the logic thread. We snapshot the items array
     * once at entry to avoid {@link java.util.ConcurrentModificationException} from
     * an in-flight {@code items.remove(i)} on the logic thread.
     *
     * @param out existing float[] buffer; sized >= MAX_ITEMS * 8 (managed by caller)
     * @return number of valid entries written into {@code out} starting at index 0
     */
    public int buildUpload(float[] out) {
        // Atomic snapshot — buildUpload must not see a torn read or live remove().
        // Synchronized with update() to avoid ConcurrentModificationException.
        DroppedItem[] snapshot = snapshotBuf;
        int n;
        synchronized (items) {
            n = items.size();
            if (n > snapshot.length) n = snapshot.length;
            // snapshotBuf is sized to MAX_ITEMS, so toArray fills in place; we own the buffer.
            Object[] returned = items.toArray(snapshot);
            n = Math.min(returned.length, n);
        }
        final long nowNs = System.nanoTime();
        final float tSec = nowNs / 1_000_000_000.0f;
        final float twoPi = (float)(2.0 * Math.PI);
        for (int i = 0; i < n; i++) {
            DroppedItem di = snapshot[i];
            if (di == null || !di.alive) continue;
            // Bob: vertical Y oscillation with a per-item phase offset.
            float bobY = (float)Math.sin(tSec * DroppedItem.BOB_FREQ + di.bobPhase) * DroppedItem.BOB_AMPLITUDE;
            // Spin: Y-axis rotation at the configured RPM, normalized to [0, 2π) for float
            // precision stability. Each item's spin starts at t=0 at its spawnTimeNs, so
            // multiple drops don't all spin in phase.
            double elapsedSec = (nowNs - di.spawnTimeNs) / 1_000_000_000.0;
            double rawAngle = elapsedSec * DroppedItem.SPIN_RAD_PER_SEC;
            float spinAngle = (float)(rawAngle - Math.floor(rawAngle / twoPi) * twoPi);
            int idx = i * 8;
            out[idx] = di.baseX;
            out[idx + 1] = di.baseY + bobY;
            out[idx + 2] = di.baseZ;
            out[idx + 3] = Float.intBitsToFloat(di.blockId);
            out[idx + 4] = DROPPED_ITEM_SCALE;
            out[idx + 5] = spinAngle;       // blockInfo.y — Y-axis spin (radians). 0 → static.
            out[idx + 6] = 0f;
            out[idx + 7] = 0f;
        }
        return n;
    }
}
