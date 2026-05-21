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

    /** Queued neighbor-update notifications from the GL thread (lamp swaps).
     *  Each entry is [x, y, z] — the changed block whose neighbors should be notified.
     *  Processed at the start of the next tickLamps() on the logic thread. */
    private final ConcurrentLinkedQueue<int[]> pendingNeighborUpdates = new ConcurrentLinkedQueue<>();

    /** Player position reference for redstone ore activation. Updated by Main each tick. */
    private volatile float playerX, playerY, playerZ;

    // ---- Position packing (21 bits per axis → 63 bits, range ±1,048,576) ----
    private static long pack(int x, int y, int z) {
        long ux = x & 0x1FFFFFL;          // 21 bits
        long uy = y & 0x1FFFFFL;          // 21 bits
        long uz = z & 0x1FFFFFL;          // 21 bits
        return (ux << 42) | (uy << 21) | uz;
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

    public RedstoneManager(World world, ChunkManager chunkManager) {
        this.world = world;
        this.chunkManager = chunkManager;
        RedstoneLogger.init();
        RedstoneLogger.log("RedstoneManager created, world=" + world + " chunkManager=" + chunkManager);
    }

    // ========================================================================
    //  Block-change notification
    // ========================================================================

    /**
     * Notify all 6 neighbors of a change at (x,y,z).
     * Call this after a block change to propagate updates to adjacent redstone components.
     */
    public void notifyNeighbors(int x, int y, int z) {
        RedstoneLogger.log("notifyNeighbors", x, y, z, "notifying all 6 neighbors");
        onBlockChanged(x - 1, y, z);
        onBlockChanged(x + 1, y, z);
        onBlockChanged(x, y - 1, z);
        onBlockChanged(x, y + 1, z);
        onBlockChanged(x, y, z - 1);
        onBlockChanged(x, y, z + 1);
    }

    /**
     * Called after any block is placed or broken.
     * Only triggers a network rebuild if a redstone-relevant block was involved.
     */
    public void onBlockChanged(int x, int y, int z) {
        int block = world.getVoxel(x, y, z);
        long key = pack(x, y, z);

        boolean wasComponent = components.contains(key);
        boolean isComponent = isRedstoneComponent(block);

        String action;
        if (isComponent) {
            components.add(key);
            action = "ADDED to components (block=" + block + ")";
        } else {
            components.remove(key);
            lampLitState.remove(key);
            action = "REMOVED from components (block=" + block + ")";
        }
        RedstoneLogger.log("onBlockChanged", x, y, z, action + " wasComp=" + wasComponent + " needsRebuild=" + (isComponent || wasComponent));

        // Also check adjacent positions — a neighbour may have become a component
        // or stopped being one due to this change.
        if (isComponent || wasComponent) {
            needsRebuild = true;
        }
    }

    // ---- Connection bitmask constants ----
    // Stored in bits 4-7 of the voxel extra data (bits 20-23 of packed value)
    // bit 0: -X (West), bit 1: +X (East), bit 2: -Z (North), bit 3: +Z (South)
    private static final int CONN_WEST  = 1;
    private static final int CONN_EAST  = 2;
    private static final int CONN_NORTH = 4;
    private static final int CONN_SOUTH = 8;

    private boolean isRedstoneComponent(int block) {
        return block == BLOCK_REDSTONE_BLOCK
            || block == BLOCK_REDSTONE_TORCH
            || block == BLOCK_REDSTONE_LAMP
            || block == BLOCK_REDSTONE_WIRE
            || block == BLOCK_REDSTONE_LAMP_ON
            || block == BLOCK_REDSTONE_ORE;
    }

    /**
     * Computes the 4-bit connection mask for a redstone wire at (x,y,z).
     * Checks horizontal neighbors AND slope-up connections (wire on adjacent
     * block 1 level higher, with a solid block beneath it).
     */
    private int computeConnections(int x, int y, int z) {
        int mask = 0;
        if (isConnectedTo(x, y, z, -1, 0)) mask |= CONN_WEST;
        if (isConnectedTo(x, y, z, 1, 0))  mask |= CONN_EAST;
        if (isConnectedTo(x, y, z, 0, -1)) mask |= CONN_NORTH;
        if (isConnectedTo(x, y, z, 0, 1))  mask |= CONN_SOUTH;
        return mask;
    }

    /**
     * Checks if wire at (x,y,z) should visually/logically connect in direction (dx, dz).
     * Three connection types:
     *   1. Direct: redstone component at (x+dx, y, z+dz)
     *   2. Slope up: redstone component at (x+dx, y+1, z+dz) with solid block below
     *   3. Slope down: redstone component at (x+dx, y-1, z+dz) — checked from the other wire's perspective
     */
    private boolean isConnectedTo(int x, int y, int z, int dx, int dz) {
        // 1. Direct connection at same Y level
        int neighbor = world.getVoxel(x + dx, y, z + dz);
        if (isRedstoneComponent(neighbor)) return true;

        // 2. Slope up: component is on the block above, with a solid block below it
        int adjacentBlock = world.getVoxel(x + dx, y, z + dz);
        if (adjacentBlock > 0 && !isRedstoneComponent(adjacentBlock)) {
            int above = world.getVoxel(x + dx, y + 1, z + dz);
            if (isRedstoneComponent(above)) return true;
        }

        // 3. Slope down: current wire sits on a solid block → component below at (x+dx, y-1, z+dz)
        int blockBelowThis = world.getVoxel(x, y - 1, z);
        if (blockBelowThis > 0 && !isRedstoneComponent(blockBelowThis)) {
            int below = world.getVoxel(x + dx, y - 1, z + dz);
            if (isRedstoneComponent(below)) return true;
        }

        return false;
    }

    // ========================================================================
    //  Connection mask query (for shader / rendering)
    // ========================================================================

    /**
     * Returns the 4-bit connection mask for a redstone wire at (x,y,z).
     * The mask is extracted from the voxel extra data (bits 4-7).
     * Returns 0 for non-wire blocks or if no wire exists.
     */
    public int getConnectionMask(int x, int y, int z) {
        int raw = world.getRawVoxel(x, y, z);
        if ((raw & 0xFFFF) != BLOCK_REDSTONE_WIRE) return 0;
        return (raw >> 20) & 0xF;
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

    /** True if the block directly below is powered (used by torch logic).
     *  Checks if any neighbor of the block below the torch has power — a solid block
     *  itself never gets a power level, but adjacent wires/sources power it indirectly. */
    public boolean isBlockBelowPowered(int x, int y, int z) {
        return hasPoweredNeighbor(x, y - 1, z);
    }

    // ========================================================================
    //  Player position for ore activation
    // ========================================================================

    /** Called each tick so redstone ore can check player proximity. */
    public void setPlayerPosition(float x, float y, float z) {
        // Only log if position changed significantly (reduce noise)
        if (Math.abs(this.playerX - x) > 0.1f || Math.abs(this.playerY - y) > 0.1f || Math.abs(this.playerZ - z) > 0.1f) {
            RedstoneLogger.log("setPlayerPosition", (int)x, (int)y, (int)z, "player moved");
        }
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
        // Process any pending neighbor updates queued from the GL thread (lamp swaps)
        int[] pending;
        int pendingCount = 0;
        while ((pending = pendingNeighborUpdates.poll()) != null) {
            RedstoneLogger.log("tickLamps/pendingNeighborUpdate", pending[0], pending[1], pending[2], "notifying neighbors of lamp swap");
            notifyNeighbors(pending[0], pending[1], pending[2]);
            pendingCount++;
        }
        if (pendingCount > 0) {
            RedstoneLogger.log("tickLamps: processed " + pendingCount + " pending neighbor updates total");
        }

        // Tick torch cooldowns
        torchCooldown.replaceAll((k, v) -> v > 0 ? v - 1 : 0);

        if (needsRebuild) {
            RedstoneLogger.log("tickLamps: needsRebuild=true, componentCount=" + components.size() + " starting rebuild");
            needsRebuild = false;
            rebuildNetwork();
            // After rebuild, encode power levels into voxel data for wire blocks
            encodeWirePowerLevels();
            RedstoneLogger.log("tickLamps: rebuild+encode done, powerLevels size=" + powerLevels.size());
        }
        evaluateLampStates();
    }

    // ========================================================================
    //  Apply queued block changes (called from GL / main loop thread)
    // ========================================================================

    /**
     * Encodes power level AND connection mask into the voxel data for each
     * redstone wire block.
     * Extra byte layout (bits 16-23 of packed int):
     *   bits 0-3: power level (0-15)
     *   bits 4-7: connection mask (4 bits: W, E, N, S)
     * Called after rebuildNetwork() on the logic thread.
     */
    private void encodeWirePowerLevels() {
        int wiresEncoded = 0;
        for (long key : components) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);
            if (block != BLOCK_REDSTONE_WIRE) continue;

            int power = powerLevels.getOrDefault(key, 0);
            int connections = computeConnections(x, y, z);
            // Pack: low 4 bits = power, high 4 bits = connections
            int extra = (power & 0xF) | ((connections & 0xF) << 4);
            RedstoneLogger.log("encodeWirePowerLevels", x, y, z, "power=" + power + " connections=" + connections);
            chunkManager.setVoxelWithData(x, y, z, BLOCK_REDSTONE_WIRE, extra);
            wiresEncoded++;
        }
        if (wiresEncoded > 0) {
            RedstoneLogger.log("encodeWirePowerLevels: encoded " + wiresEncoded + " wires");
        } else {
            RedstoneLogger.log("encodeWirePowerLevels: no wires to encode (componentCount=" + components.size() + ")");
        }
    }

    /**
     * Must be called from the main (GL) thread to safely apply lamp
     * block-ID swaps that were queued by the logic thread.
     * Also queues neighbor notifications so the next logic tick
     * can propagate the block change to adjacent redstone components.
     */
    public void applyLampChanges() {
        int[] change;
        int count = 0;
        while ((change = lampChangeQueue.poll()) != null) {
            int x = change[0], y = change[1], z = change[2], id = change[3];
            String idName = (id == BLOCK_REDSTONE_LAMP_ON) ? "LAMP_ON" : (id == BLOCK_REDSTONE_LAMP) ? "LAMP_OFF" : String.valueOf(id);
            RedstoneLogger.log("applyLampChanges", x, y, z, "swapping to " + idName);
            chunkManager.setVoxel(x, y, z, id);
            pendingNeighborUpdates.add(new int[]{x, y, z});
            count++;
        }
        if (count > 0) {
            RedstoneLogger.log("applyLampChanges: applied " + count + " lamp swaps");
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

        int sourcesFound = 0;
        // Phase 1: Find non-torch sources (torches depend on populated powerLevels).
        for (long key : components) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);
            switch (block) {
                case BLOCK_REDSTONE_BLOCK:
                    powerLevels.put(key, MAX_POWER);
                    queue.add(new PowerNode(x, y, z, MAX_POWER));
                    RedstoneLogger.log("rebuildNetwork/Phase1", x, y, z, "REDSTONE_BLOCK source, power=15");
                    sourcesFound++;
                    break;
                case BLOCK_REDSTONE_ORE:
                    // Redstone ore only emits power when a player is nearby (< 5 blocks)
                    float dx = playerX - (x + 0.5f);
                    float dy = playerY - (y + 0.5f);
                    float dz = playerZ - (z + 0.5f);
                    if (dx * dx + dy * dy + dz * dz < 25.0f) {
                        powerLevels.put(key, 7);
                        queue.add(new PowerNode(x, y, z, 7));
                        RedstoneLogger.log("rebuildNetwork/Phase1", x, y, z, "REDSTONE_ORE source (player nearby), power=7");
                        sourcesFound++;
                    } else {
                        RedstoneLogger.log("rebuildNetwork/Phase1", x, y, z, "REDSTONE_ORE skipped (player too far)");
                    }
                    break;
                default:
                    String name = (block == BLOCK_REDSTONE_WIRE) ? "WIRE" : (block == BLOCK_REDSTONE_LAMP) ? "LAMP" : (block == BLOCK_REDSTONE_LAMP_ON) ? "LAMP_ON" : (block == BLOCK_REDSTONE_TORCH) ? "TORCH" : "block="+block;
                    RedstoneLogger.log("rebuildNetwork/Phase1", x, y, z, name + " is not a source, skipping");
                    // torches deferred to Phase 3
            }
        }
        RedstoneLogger.log("rebuildNetwork: Phase1 found " + sourcesFound + " sources, starting BFS from " + queue.size() + " nodes");

        // Phase 2: BFS propagation from non-torch sources.
        int bfsCount = bfsPropagate(queue);
        RedstoneLogger.log("rebuildNetwork: Phase2 BFS propagated " + bfsCount + " nodes");

        int torchesOn = 0, torchesOff = 0, torchesCooldown = 0;
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
                RedstoneLogger.log("rebuildNetwork/Phase3", x, y, z, "TORCH on cooldown, skipping");
                torchesCooldown++;
                continue;
            }

            // Torch is ON only if the block below is NOT powered
            if (!isBlockBelowPowered(x, y, z)) {
                powerLevels.put(key, MAX_POWER);
                queue.add(new PowerNode(x, y, z, MAX_POWER));
                RedstoneLogger.log("rebuildNetwork/Phase3", x, y, z, "TORCH turned ON (block below NOT powered)");
                torchesOn++;
            } else {
                // Block below IS powered → torch turns off, set cooldown
                torchCooldown.put(key, TORCH_COOLDOWN_TICKS);
                RedstoneLogger.log("rebuildNetwork/Phase3", x, y, z, "TORCH turned OFF (block below IS powered)");
                torchesOff++;
            }
            // else: torch is OFF — do not add to powerLevels
        }
        RedstoneLogger.log("rebuildNetwork: Phase3 torches: " + torchesOn + " ON, " + torchesOff + " OFF, " + torchesCooldown + " cooldown");

        // Phase 4: BFS again so newly activated torches propagate.
        int bfsCount2 = bfsPropagate(queue);
        RedstoneLogger.log("rebuildNetwork: Phase4 BFS propagated " + bfsCount2 + " nodes");
        RedstoneLogger.log("rebuildNetwork: done, total powerLevels=" + powerLevels.size());
    }

    /** Run BFS propagation from every node currently in the queue. Returns number of nodes processed. */
    private int bfsPropagate(Deque<PowerNode> queue) {
        int count = 0;
        while (!queue.isEmpty()) {
            PowerNode cur = queue.poll();
            count++;
            if (cur.power <= 1) continue;    // exhausted signal

            int np = cur.power - 1;
            // Propagate to standard 6 neighbors (connection-checking for wires)
            propagate(queue, cur.x, cur.y, cur.z, cur.x - 1, cur.y, cur.z, np);
            propagate(queue, cur.x, cur.y, cur.z, cur.x + 1, cur.y, cur.z, np);
            propagate(queue, cur.x, cur.y, cur.z, cur.x, cur.y - 1, cur.z, np);
            propagate(queue, cur.x, cur.y, cur.z, cur.x, cur.y + 1, cur.z, np);
            propagate(queue, cur.x, cur.y, cur.z, cur.x, cur.y, cur.z - 1, np);
            propagate(queue, cur.x, cur.y, cur.z, cur.x, cur.y, cur.z + 1, np);

            // If current node is a wire, also propagate via slope connections
            int thisBlock = world.getVoxel(cur.x, cur.y, cur.z);
            if (thisBlock == BLOCK_REDSTONE_WIRE) {
                propagateSlope(queue, cur.x, cur.y, cur.z, np);
            }
        }
        return count;
    }

    /**
     * Propagate power via slopes: if there's a solid block at (x+dx, y, z+dz)
     * and redstone on top at (x+dx, y+1, z+dz), propagate there.
     */
    private void propagateSlope(Deque<PowerNode> queue, int x, int y, int z, int power) {
        int[][] dirs = {{-1,0}, {1,0}, {0,-1}, {0,1}};
        for (int[] d : dirs) {
            int dx = d[0], dz = d[1];

            // --- Slope UP: solid block at (x+dx, y, z+dz) → wire on top at (x+dx, y+1, z+dz) ---
            int belowBlock = world.getVoxel(x + dx, y, z + dz);
            if (belowBlock > 0 && belowBlock != BLOCK_REDSTONE_WIRE && !isRedstoneComponent(belowBlock)) {
                propagate(queue, x, y, z, x + dx, y + 1, z + dz, power);
            }

            // --- Slope DOWN: current wire sits on a solid block → wire below at (x+dx, y-1, z+dz) ---
            int blockBelowThis = world.getVoxel(x, y - 1, z);
            if (blockBelowThis > 0 && blockBelowThis != BLOCK_REDSTONE_WIRE && !isRedstoneComponent(blockBelowThis)) {
                int downNeighbor = world.getVoxel(x + dx, y - 1, z + dz);
                if (isRedstoneComponent(downNeighbor)) {
                    propagate(queue, x, y, z, x + dx, y - 1, z + dz, power);
                }
            }
        }
    }

    /**
     * Attempt to propagate power from (sx,sy,sz) into (tx,ty,tz).
     * Only wires and lamps accept power.
     * For wire targets, checks the connection mask to skip unconnected neighbors.
     */
    private void propagate(Deque<PowerNode> queue, int sx, int sy, int sz, int tx, int ty, int tz, int power) {
        int block = world.getVoxel(tx, ty, tz);
        if (block != BLOCK_REDSTONE_WIRE && block != BLOCK_REDSTONE_LAMP && block != BLOCK_REDSTONE_LAMP_ON)
            return;

        // For wire targets, check if this specific neighbor connection exists.
        // Skip unconnected neighbors (horizontal only — vertical always propagates).
        if (block == BLOCK_REDSTONE_WIRE && sy == ty) {
            int conn = computeConnections(tx, ty, tz);
            int dx = sx - tx, dz = sz - tz;
            boolean hasConnection = false;
            if (dx == -1 && (conn & CONN_WEST) != 0) hasConnection = true;   // source is west of target
            if (dx == 1 && (conn & CONN_EAST) != 0) hasConnection = true;    // source is east of target
            if (dz == -1 && (conn & CONN_NORTH) != 0) hasConnection = true;  // source is north of target
            if (dz == 1 && (conn & CONN_SOUTH) != 0) hasConnection = true;   // source is south of target
            
            // Note: slope-up/down connections are handled by propagateSlope()
            // which is called from bfsPropagate for wire nodes.
            if (!hasConnection) return;
        }

        long key = pack(tx, ty, tz);
        int old = powerLevels.getOrDefault(key, 0);
        if (power > old) {
            String name = (block == BLOCK_REDSTONE_WIRE) ? "WIRE" : "LAMP";
            RedstoneLogger.log("propagate", tx, ty, tz, name + " old=" + old + " new=" + power);
            powerLevels.put(key, power);
            queue.add(new PowerNode(tx, ty, tz, power));
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
        int lampsChecked = 0, lampsChanged = 0;
        for (long key : components) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);

            boolean isLamp = (block == BLOCK_REDSTONE_LAMP || block == BLOCK_REDSTONE_LAMP_ON);
            if (!isLamp) continue;
            lampsChecked++;

            boolean shouldBeLit = hasPoweredNeighbor(x, y, z);
            Boolean wasLit = lampLitState.get(key);
            String pl = "(" + getPowerLevel(x-1,y,z) + "," + getPowerLevel(x+1,y,z) + "," + getPowerLevel(x,y-1,z) + "," + getPowerLevel(x,y+1,z) + "," + getPowerLevel(x,y,z-1) + "," + getPowerLevel(x,y,z+1) + ")";
            if (wasLit != null && wasLit == shouldBeLit) {
                RedstoneLogger.log("evaluateLamp", x, y, z, "no change (wasLit=" + wasLit + " shouldBeLit=" + shouldBeLit + " neighborPowers=" + pl + ")");
                continue;   // no change
            }

            int newBlock = shouldBeLit ? BLOCK_REDSTONE_LAMP_ON : BLOCK_REDSTONE_LAMP;
            lampChangeQueue.add(new int[]{x, y, z, newBlock});
            lampLitState.put(key, shouldBeLit);
            RedstoneLogger.log("evaluateLamp", x, y, z, "CHANGED wasLit=" + wasLit + " shouldBeLit=" + shouldBeLit + " neighborPowers=" + pl + " newBlock=" + (shouldBeLit ? "LAMP_ON" : "LAMP_OFF"));
            lampsChanged++;
        }
        if (lampsChecked > 0) {
            RedstoneLogger.log("evaluateLampStates: checked " + lampsChecked + " lamps, " + lampsChanged + " changed");
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
