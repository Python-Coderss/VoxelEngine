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

    /** Result of a downward ground search for a dropped item. */
    private static final class GroundResult {
        final float groundTopY;
        final int supportBlockY;
        final boolean found;
        GroundResult(float groundTopY, int supportBlockY, boolean found) {
            this.groundTopY = groundTopY;
            this.supportBlockY = supportBlockY;
            this.found = found;
        }
    }

    /**
     * Scan downward from {@code startY} in column ({@code colX}, {@code colZ}) for the
     * first non-air block. Returns its top-face Y, block Y, and found flag.
     */
    private GroundResult findGroundBelow(int colX, int colZ, float startY) {
        if (ctx.world == null) return new GroundResult(startY, Integer.MIN_VALUE, false);
        for (int dy = 1; dy <= GROUND_SEARCH_DEPTH; dy++) {
            int probeY = (int)Math.floor(startY) - dy;
            if (probeY < 0) break;
            if (ctx.world.getVoxel(colX, probeY, colZ) != 0) {
                return new GroundResult(probeY + 1.0f, probeY, true);
            }
        }
        return new GroundResult(startY, Integer.MIN_VALUE, false);
    }

    /** Number of drops currently being rendered. */
    public int getItemCount() { return items.size(); }

    /** Empty all drops (called on dimension switch). */
    public void clearAll() {
        items.clear();
    }

    /** Immutable snapshot of one dropped item for persistence. */
    public static final class DropSnapshot {
        public final String itemId; public final int count;
        public final float x, y, z;
        public DropSnapshot(String itemId, int count, float x, float y, float z) {
            this.itemId = itemId; this.count = count; this.x = x; this.y = y; this.z = z;
        }
    }

    /** Snapshot for persistence (autosave / dimension switch / shutdown). */
    public java.util.List<DropSnapshot> getSnapshot() {
        synchronized (items) {
            java.util.List<DropSnapshot> out = new java.util.ArrayList<>(items.size());
            for (DroppedItem di : items) {
                if (di.alive && di.count > 0) {
                    out.add(new DropSnapshot(di.itemId, di.count, di.baseX, di.baseY, di.baseZ));
                }
            }
            return out;
        }
    }

    /**
     * Restore a persisted drop at an absolute world position. Unlike {@link #spawn}
     * this does not require a registered item drop model and re-runs the ground
     * search so the item settles correctly after load.
     */
    public void restore(String itemId, int count, float x, float y, float z) {
        if (itemId == null || count <= 0 || ctx.world == null) return;
        ItemDefinition def = ctx.itemDefinitions != null ? ctx.itemDefinitions.getDefinition(itemId) : null;
        int blockId = def != null ? def.dropBlockId : -1;
        int colX = (int) Math.floor(x), colZ = (int) Math.floor(z);
        float phase = (float)(rng.nextDouble() * Math.PI * 2.0);
        GroundResult ground = findGroundBelow(colX, colZ, y + 1.0f);
        float hover = MIN_HOVER_ABOVE_GROUND;
        float restY = ground.groundTopY + hover + 0.5f * DROPPED_ITEM_SCALE;
        DroppedItem di = new DroppedItem(itemId, blockId, count, x, y, z, phase,
                System.nanoTime(), restY, ground.groundTopY, hover,
                colX, ground.supportBlockY, colZ, ground.found);
        synchronized (items) {
            if (items.size() >= MAX_ITEMS) return;
            items.add(di);
        }
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
        if (def == null || def.dropBlockId <= 0) {
            // Items without a drop model — skip dropping for now.
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
        GroundResult ground = findGroundBelow(blockX, blockZ, blockY);
        float groundTopY = ground.groundTopY; // fallback: no ground found, item hovers at spawn
        float hover = MIN_HOVER_ABOVE_GROUND
                    + rng.nextFloat() * (MAX_HOVER_ABOVE_GROUND - MIN_HOVER_ABOVE_GROUND);
        float halfScale = 0.5f * DROPPED_ITEM_SCALE;
        // restY positions the item so bottom face = groundTopY + hover.
        float restY = groundTopY + hover + halfScale;
        // baseY starts at spawn (broken-block center); fallback restY == y means no fall.
        DroppedItem di = new DroppedItem(itemId, def.dropBlockId, count, x, y, z, phase, spawnTimeNs,
                                         restY, groundTopY, hover,
                                         blockX, ground.supportBlockY, blockZ, ground.found);
        synchronized (items) {
            // Pool cap exceeded — fail soft (just don't drop). Future: drop oldest or compress stacks.
            if (items.size() >= MAX_ITEMS) {
                return;
            }
            items.add(di);
        }
    }

    /**
     * Called when a block is destroyed. Checks all grounded dropped items: if the
     * destroyed block was supporting an item, the item becomes un-grounded and
     * searches for new ground beneath its current column. If no ground is found,
     * the item will fall indefinitely (into a void/chasm).
     */
    public void onBlockDestroyed(int x, int y, int z) {
        synchronized (items) {
            for (DroppedItem di : items) {
                if (!di.grounded || !di.hasSupportBlock) continue;
                if (di.supportBlockX == x && di.supportBlockY == y && di.supportBlockZ == z) {
                    di.grounded = false;
                    di.vy = 0f; // start falling from rest
                    // Re-search for ground beneath the item's current column
                    int ix = (int)Math.floor(di.baseX);
                    int iz = (int)Math.floor(di.baseZ);
                    GroundResult newGround = findGroundBelow(ix, iz, di.baseY);
                    if (newGround.found) {
                        di.groundTopY = newGround.groundTopY;
                        di.supportBlockX = ix;
                        di.supportBlockY = newGround.supportBlockY;
                        di.supportBlockZ = iz;
                        di.hasSupportBlock = true;
                        float halfScale = 0.5f * DROPPED_ITEM_SCALE;
                        di.restY = newGround.groundTopY + di.hoverHeight + halfScale;
                    } else {
                        di.hasSupportBlock = false;
                        // No ground found — item falls indefinitely (void/abyss)
                        di.restY = Float.NEGATIVE_INFINITY;
                    }
                }
            }
        }
    }

    /**
     * Push all dropped items caught inside an encased-fan beam.
     * The beam occupies the {@code length} voxels adjacent to the fan at
     * ({@code fanX},{@code fanY},{@code fanZ}) along direction ({@code dx},{@code dy},{@code dz}).
     * Items are moved by {@code delta} voxels this tick (caller pre-scales by dt);
     * movement stops against solid blocks. After moving to a new column the item
     * re-searches for ground beneath it so it settles naturally.
     */
    public void pushBeam(int fanX, int fanY, int fanZ, int dx, int dy, int dz, int length, float delta) {
        if (delta <= 0f || length <= 0) return;
        synchronized (items) {
            for (DroppedItem di : items) {
                if (!di.alive) continue;
                int ix = (int) Math.floor(di.baseX);
                int iy = (int) Math.floor(di.baseY);
                int iz = (int) Math.floor(di.baseZ);
                // Distance along the beam (1..length) and alignment with the beam column
                int along = (ix - fanX) * dx + (iy - fanY) * dy + (iz - fanZ) * dz;
                if (along < 1 || along > length) continue;
                if (dx == 0 && ix != fanX) continue;
                if (dy == 0 && iy != fanY) continue;
                if (dz == 0 && iz != fanZ) continue;

                float nx = di.baseX + dx * delta;
                float ny = di.baseY + dy * delta;
                float nz = di.baseZ + dz * delta;
                // Don't push items into solid blocks
                if (ctx.world != null &&
                    ctx.world.getVoxel((int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz)) != 0) {
                    continue;
                }
                boolean columnChanged = (int) Math.floor(nx) != ix || (int) Math.floor(nz) != iz;
                di.baseX = nx;
                di.baseZ = nz;
                if (dy != 0) {
                    di.baseY = ny;
                    di.grounded = false;
                }
                if (columnChanged || dy != 0) {
                    // Re-anchor to the ground beneath the new column
                    GroundResult ground = findGroundBelow((int) Math.floor(di.baseX), (int) Math.floor(di.baseZ), di.baseY + 1.0f);
                    di.grounded = false;
                    di.vy = 0f;
                    if (ground.found) {
                        di.groundTopY = ground.groundTopY;
                        di.supportBlockX = (int) Math.floor(di.baseX);
                        di.supportBlockY = ground.supportBlockY;
                        di.supportBlockZ = (int) Math.floor(di.baseZ);
                        di.hasSupportBlock = true;
                        di.restY = ground.groundTopY + di.hoverHeight + 0.5f * DROPPED_ITEM_SCALE;
                    } else {
                        di.hasSupportBlock = false;
                        di.restY = Float.NEGATIVE_INFINITY;
                    }
                }
            }
        }
    }

    /**
     * True when a live dropped item of {@code itemId} is resting in the voxel
     * cell ({@code x},{@code y},{@code z}). Used by machines to peek at inputs
     * before consuming them (e.g. press alloying needs both metals present).
     */
    public boolean hasItemInCell(int x, int y, int z, String itemId) {
        synchronized (items) {
            for (DroppedItem di : items) {
                if (!di.alive) continue;
                if (!di.itemId.equals(itemId)) continue;
                if ((int) Math.floor(di.baseX) == x
                        && (int) Math.floor(di.baseY) == y
                        && (int) Math.floor(di.baseZ) == z) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove up to {@code need} of {@code itemId} from a single stack resting in
     * the cell ({@code x},{@code y},{@code z}). Returns false when no single
     * stack there has enough (nothing is removed then).
     */
    public boolean consumeFromCell(int x, int y, int z, String itemId, int need) {
        synchronized (items) {
            for (DroppedItem di : items) {
                if (!di.alive) continue;
                if (!di.itemId.equals(itemId)) continue;
                if ((int) Math.floor(di.baseX) != x
                        || (int) Math.floor(di.baseY) != y
                        || (int) Math.floor(di.baseZ) != z) {
                    continue;
                }
                if (di.count < need) return false;
                if (di.count > need) {
                    di.count -= need;
                } else {
                    di.alive = false;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Move items resting in the cell directly above a belt conveyor along its
     * facing (dx, dz). Items are pushed {@code delta} voxels this tick and
     * re-anchor to whatever ground lies beneath their new column, so they roll
     * off the belt end and settle naturally.
     */
    public void moveOnBelt(int bx, int by, int bz, int dx, int dz, float delta) {
        if (delta <= 0f) return;
        synchronized (items) {
            for (DroppedItem di : items) {
                if (!di.alive) continue;
                int ix = (int) Math.floor(di.baseX);
                int iy = (int) Math.floor(di.baseY);
                int iz = (int) Math.floor(di.baseZ);
                if (ix != bx || iy != by + 1 || iz != bz) continue;

                float nx = di.baseX + dx * delta;
                float nz = di.baseZ + dz * delta;
                if (ctx.world != null) {
                    // Blocked by a solid block at belt level or item level ahead.
                    if (ctx.world.getVoxel((int) Math.floor(nx), by, (int) Math.floor(nz)) != 0) continue;
                    if (ctx.world.getVoxel((int) Math.floor(nx), by + 1, (int) Math.floor(nz)) != 0) continue;
                }
                boolean columnChanged = (int) Math.floor(nx) != ix || (int) Math.floor(nz) != iz;
                di.baseX = nx;
                di.baseZ = nz;
                if (columnChanged) {
                    GroundResult ground = findGroundBelow((int) Math.floor(di.baseX), (int) Math.floor(di.baseZ), di.baseY + 1.0f);
                    di.grounded = false;
                    di.vy = 0f;
                    if (ground.found) {
                        di.groundTopY = ground.groundTopY;
                        di.supportBlockX = (int) Math.floor(di.baseX);
                        di.supportBlockY = ground.supportBlockY;
                        di.supportBlockZ = (int) Math.floor(di.baseZ);
                        di.hasSupportBlock = true;
                        di.restY = ground.groundTopY + di.hoverHeight + 0.5f * DROPPED_ITEM_SCALE;
                    } else {
                        di.hasSupportBlock = false;
                        di.restY = Float.NEGATIVE_INFINITY;
                    }
                }
            }
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
            if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
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
        return buildUpload(out, 0, 0, 0, 0);
    }

    /**
     * Build upload with world-offset subtraction so the shader receives buffer-relative
     * positions (always in [0,2048] range → full float32 sub-block precision).
     *
     * @param baseIndex number of crafting-grid entries already packed at the head of
     *                  {@code out}; dropped items are appended after them (8 floats each)
     *                  so they never overwrite the crafting-grid slice.
     */
    public int buildUpload(float[] out, int baseIndex, int wox, int woy, int woz) {
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
        int written = 0;
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
            int idx = (baseIndex + written) * 8;
            out[idx] = di.baseX - wox;
            out[idx + 1] = di.baseY + bobY - woy;
            out[idx + 2] = di.baseZ - woz;
            out[idx + 3] = Float.intBitsToFloat(di.blockId);
            out[idx + 4] = DROPPED_ITEM_SCALE;
            out[idx + 5] = spinAngle;       // blockInfo.y — Y-axis spin (radians). 0 → static.
            out[idx + 6] = 0f;
            out[idx + 7] = 0f;
            written++;
        }
        return written;
    }
}
