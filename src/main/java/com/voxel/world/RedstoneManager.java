package com.voxel.world;

import com.voxel.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages redstone power propagation.
 *
 * Thread-safety: powerLevels and components use ConcurrentHashMap/ConcurrentHashMap.newKeySet()
 * so the logic thread (onBlockChanged, rebuildNetwork) and the GL thread (getPowerLevel, etc.)
 * can safely read/write. Lamp block-ID swaps are queued (lampChangeQueue) by the logic thread
 * and applied by the GL thread (applyLampChanges) to avoid racing on the world chunk pool.
 */
public class RedstoneManager {
    // ---- Block IDs ----
    public static final int BLOCK_REDSTONE_BLOCK  = 25;  // solid power source (always level 15)
    public static final int BLOCK_REDSTONE_ORE     = 26;  // power source (level 7 when active)
    public static final int BLOCK_REDSTONE_TORCH   = 27;  // power source (15 when active, 0 when block below is powered)
    public static final int BLOCK_REDSTONE_LAMP    = 28;  // lamp off
    public static final int BLOCK_REDSTONE_WIRE    = 29;  // redstone dust
    public static final int BLOCK_REDSTONE_LAMP_ON = 30;  // lamp on

    private static final int MAX_POWER = 15;
    private static final int TORCH_COOLDOWN_TICKS = 2;  // Prevent rapid oscillation

    private final World world;
    private final ChunkManager chunkManager;

    /** Packed position → current power level (0-MAX_POWER). Thread-safe. */
    private final Map<Long, Integer> powerLevels = new ConcurrentHashMap<>();

    /** All redstone-component positions. Thread-safe. */
    private final Set<Long> components = ConcurrentHashMap.newKeySet();

    /** Queue of lamp block-ID swaps: [x, y, z, newBlockId]. Written by logic thread, consumed by GL thread. */
    private final ConcurrentLinkedQueue<int[]> lampChangeQueue = new ConcurrentLinkedQueue<>();

    /** Last known lamp state: packed → true if currently lit. Prevents redundant swaps. */
    private final Map<Long, Boolean> lampLitState = new ConcurrentHashMap<>();

    /** Torch cooldown: packed → ticks remaining before torch can toggle state again. */
    private final Map<Long, Integer> torchCooldown = new ConcurrentHashMap<>();

    /** True when a redstone component was added/removed and rebuildNetwork() is needed. */
    private volatile boolean needsRebuild = false;

    /** Player position reference for redstone ore activation. Updated by Main each tick. */
    private volatile float playerX, playerY, playerZ;

    // ---- Position packing (27 bits per axis → 54+27+1 = 64 bit long) ----
    // Since coordinates may be negative we store signed ints in unsigned slots
    private static long pack(int x, int y, int z) {
        long ux = x & 0x7FFFFFFL;          // 27 bits
        long uy = y & 0x7FFFFFFL;          // 27 bits
        long uz = z & 0x7FFFFFFL;          // 27 bits
        return (ux << 37) | (uy << 10) | uz;
    }

    private static int unpackX(long key) {
        long v = (key >> 37) & 0x7FFFFFFL;
        return (v & 0x4000000L) != 0 ? (int) (v | 0xFFFFFFFFF8000000L) : (int) v;
    }

    private static int unpackY(long key) {
        long v = (key >> 10) & 0x7FFFFFFL;
        return (v & 0x4000000L) != 0 ? (int) (v | 0xFFFFFFFFF8000000L) : (int) v;
    }

    private static int unpackZ(long key) {
        long v = key & 0x7FFFFFFL;
        return (v & 0x4000000L) != 0 ? (int) (v | 0xFFFFFFFFF8000000L) : (int) v;
    }

    public RedstoneManager(World world, ChunkManager chunkManager) {
        this.world = world;
        this.chunkManager = chunkManager;
    }

    // ========================================================================
    //  Block-change notification
    // ========================================================================

    /**
     * Called after any block is placed or broken.
     * Only triggers a network rebuild if a redstone-relevant block was involved.
     */
    public void onBlockChanged(int x, int y, int z) {
        int block = world.getVoxel(x, y, z);
        long key = pack(x, y, z);

        boolean wasComponent = components.contains(key);
        boolean isComponent = isRedstoneComponent(block);

        if (isComponent) {
            components.add(key);
        } else {
            components.remove(key);
            lampLitState.remove(key);
        }

        // Also check adjacent positions — a neighbour may have become a component
        // or stopped being one due to this change.
        if (isComponent || wasComponent) {
            needsRebuild = true;
        }
    }

    private boolean isRedstoneComponent(int block) {
        return block == BLOCK_REDSTONE_BLOCK
            || block == BLOCK_REDSTONE_TORCH
            || block == BLOCK_REDSTONE_LAMP
            || block == BLOCK_REDSTONE_WIRE
            || block == BLOCK_REDSTONE_LAMP_ON
            || block == BLOCK_REDSTONE_ORE;
    }

    // ========================================================================
    //  Power-level queries (thread-safe reads)
    // ========================================================================

    public int getPowerLevel(int x, int y, int z) {
        return powerLevels.getOrDefault(pack(x, y, z), 0);
    }

    /** True if at least one adjacent block is powered. */
    public boolean hasPoweredNeighbor(int x, int y, int z) {
        return getPowerLevel(x - 1, y, z) > 0
            || getPowerLevel(x + 1, y, z) > 0
            || getPowerLevel(x, y - 1, z) > 0
            || getPowerLevel(x, y + 1, z) > 0
            || getPowerLevel(x, y, z - 1) > 0
            || getPowerLevel(x, y, z + 1) > 0;
    }

    /** True if the block directly below is powered (used by torch logic). */
    public boolean isBlockBelowPowered(int x, int y, int z) {
        return getPowerLevel(x, y - 1, z) > 0;
    }

    // ========================================================================
    //  Player position for ore activation
    // ========================================================================

    /** Called each tick so redstone ore can check player proximity. */
    public void setPlayerPosition(float x, float y, float z) {
        this.playerX = x;
        this.playerY = y;
        this.playerZ = z;
    }

    // ========================================================================
    //  Periodic update (called from logic thread)
    // ========================================================================

    /**
     * Called every logic tick. Rebuilds the redstone network if needed,
     * then determines lamp states and queues block-ID swaps.
     */
    public void tickLamps() {
        // Tick torch cooldowns
        torchCooldown.replaceAll((k, v) -> v > 0 ? v - 1 : 0);

        if (needsRebuild) {
            needsRebuild = false;
            rebuildNetwork();
        }
        evaluateLampStates();
    }

    // ========================================================================
    //  Apply queued block changes (called from GL / main loop thread)
    // ========================================================================

    /**
     * Must be called from the main (GL) thread to safely apply lamp
     * block-ID swaps that were queued by the logic thread.
     */
    public void applyLampChanges() {
        int[] change;
        while ((change = lampChangeQueue.poll()) != null) {
            int x = change[0], y = change[1], z = change[2], id = change[3];
            chunkManager.setVoxel(x, y, z, id);
        }
    }

    // ========================================================================
    //  Network rebuild (internal)
    // ========================================================================

    /**
     * Rebuilds the entire redstone power network:
     *   1) Collect non-torch sources (redstone blocks, active ore)
     *   2) BFS propagation through wires and lamps
     *   3) Evaluate torches: ON if block below is NOT powered, OFF otherwise
     *   4) BFS again from torches that turned ON
     */
    private void rebuildNetwork() {
        powerLevels.clear();

        Deque<PowerNode> queue = new ArrayDeque<>();

        // Phase 1: Find non-torch sources (torches depend on populated powerLevels).
        for (long key : components) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);
            switch (block) {
                case BLOCK_REDSTONE_BLOCK:
                    powerLevels.put(key, MAX_POWER);
                    queue.add(new PowerNode(x, y, z, MAX_POWER));
                    break;
                case BLOCK_REDSTONE_ORE:
                    // Redstone ore only emits power when a player is nearby (< 5 blocks)
                    float dx = playerX - (x + 0.5f);
                    float dy = playerY - (y + 0.5f);
                    float dz = playerZ - (z + 0.5f);
                    if (dx * dx + dy * dy + dz * dz < 25.0f) {
                        powerLevels.put(key, 7);
                        queue.add(new PowerNode(x, y, z, 7));
                    }
                    break;
                // torches deferred to Phase 3
            }
        }

        // Phase 2: BFS propagation from non-torch sources.
        bfsPropagate(queue);

        // Phase 3: Evaluate torches against now-populated powerLevels.
        for (long key : components) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            if (world.getVoxel(x, y, z) != BLOCK_REDSTONE_TORCH) continue;

            // Skip torches still on cooldown (prevents rapid oscillation)
            if (torchCooldown.getOrDefault(key, 0) > 0) {
                // Keep torch in its current state during cooldown
                if (powerLevels.containsKey(key)) {
                    queue.add(new PowerNode(x, y, z, powerLevels.get(key)));
                }
                continue;
            }

            // Torch is ON only if the block below is NOT powered
            if (!isBlockBelowPowered(x, y, z)) {
                powerLevels.put(key, MAX_POWER);
                queue.add(new PowerNode(x, y, z, MAX_POWER));
            } else {
                // Block below IS powered → torch turns off, set cooldown
                torchCooldown.put(key, TORCH_COOLDOWN_TICKS);
            }
            // else: torch is OFF — do not add to powerLevels
        }

        // Phase 4: BFS again so newly activated torches propagate.
        bfsPropagate(queue);
    }

    /** Run BFS propagation from every node currently in the queue. */
    private void bfsPropagate(Deque<PowerNode> queue) {
        while (!queue.isEmpty()) {
            PowerNode cur = queue.poll();
            if (cur.power <= 1) continue;    // exhausted signal

            int np = cur.power - 1;
            propagate(queue, cur.x - 1, cur.y, cur.z, np);
            propagate(queue, cur.x + 1, cur.y, cur.z, np);
            propagate(queue, cur.x, cur.y - 1, cur.z, np);
            propagate(queue, cur.x, cur.y + 1, cur.z, np);
            propagate(queue, cur.x, cur.y, cur.z - 1, np);
            propagate(queue, cur.x, cur.y, cur.z + 1, np);
        }
    }

    /** Attempt to propagate power into (x,y,z). Only wires and lamps accept power. */
    private void propagate(Deque<PowerNode> queue, int x, int y, int z, int power) {
        int block = world.getVoxel(x, y, z);
        if (block != BLOCK_REDSTONE_WIRE && block != BLOCK_REDSTONE_LAMP && block != BLOCK_REDSTONE_LAMP_ON)
            return;

        long key = pack(x, y, z);
        int old = powerLevels.getOrDefault(key, 0);
        if (power > old) {
            powerLevels.put(key, power);
            queue.add(new PowerNode(x, y, z, power));
        }
    }

    // ========================================================================
    //  Lamp state evaluation (internal – called from logic thread)
    // ========================================================================

    /**
     * Checks every lamp position and queues a block-ID swap if the lamp's
     * lit state differs from the last known state.
     */
    private void evaluateLampStates() {
        for (long key : components) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);

            boolean isLamp = (block == BLOCK_REDSTONE_LAMP || block == BLOCK_REDSTONE_LAMP_ON);
            if (!isLamp) continue;

            boolean shouldBeLit = hasPoweredNeighbor(x, y, z);
            Boolean wasLit = lampLitState.get(key);
            if (wasLit != null && wasLit == shouldBeLit) continue;   // no change

            int newBlock = shouldBeLit ? BLOCK_REDSTONE_LAMP_ON : BLOCK_REDSTONE_LAMP;
            lampChangeQueue.add(new int[]{x, y, z, newBlock});
            lampLitState.put(key, shouldBeLit);
        }
    }

    // ========================================================================
    //  BFS node
    // ========================================================================

    private static class PowerNode {
        final int x, y, z, power;
        PowerNode(int x, int y, int z, int power) {
            this.x = x; this.y = y; this.z = z; this.power = power;
        }
    }
}
