package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.Direction;
import com.voxel.GameLogger;

import java.util.Set;
import java.util.HashSet;

/**
 * Minecraft-style dual-channel lighting engine.
 *
 * Sky light (EnumSkyBlock.SKY):
 *   Propagates top-down from the world ceiling. Each column is computed independently:
 *   starting from the highest block, sky light = 15 and diminishes by block opacity
 *   as it descends.
 *
 * Block light (EnumSkyBlock.BLOCK):
 *   Flood-fills outward from emissive block sources (torches, glowstone, etc.).
 *   Each step away from the source reduces light by 1. Opaque blocks block propagation.
 *   Light values range from 0 to 15.
 *
 * Light is stored in the existing World.lightPool using a packed format:
 *   bits 0-3 = sky light (0-15)
 *   bits 4-7 = block light (0-15)
 */
public class LightEngine {

    private final World world;
    private final BlockDataManager blockDataManager;

    /** Maximum light value (both sky and block). */
    public static final int MAX_LIGHT = 15;

    /** Height limit for sky light computation. */
    public static final int WORLD_HEIGHT = 256;

    private final int bufSize; // World.REGION_SIZE * World.CHUNK_SIZE (2048)

    public LightEngine(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
        this.bufSize = World.REGION_SIZE * World.CHUNK_SIZE;
    }

    // ══════════════════════════════════════════════════════════════════
    //  SKY LIGHT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Generates sky light for an entire chunk column (all 16 sections, y = 0..255).
     * Computes top-down per (x,z) column: starts at the highest block, sets 15,
     * then decreases by block opacity as it descends.
     *
     * @param cx    Absolute chunk X coordinate
     * @param cz    Absolute chunk Z coordinate
     * @param slots The 16 pool slots for this chunk column [y=0..15]
     * @return Set of dirty slot indices
     */
    public Set<Integer> generateSkyLight(int cx, int cz, Integer[] slots) {
        Set<Integer> dirtySlots = new HashSet<>();

        int worldBaseX = cx << 4;
        int worldBaseZ = cz << 4;
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();
        int bufMaxYRel = oy + bufSize;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = worldBaseX + lx;
                int wz = worldBaseZ + lz;

                // Find top non-transparent block in this column (within buffer bounds)
                int topY = findTopBlockY(wx, wz);

                // Propagate sky light downward from topY
                int skyLight = MAX_LIGHT;
                for (int y = Math.min(topY, bufMaxYRel - 1); y >= 0; y--) {
                    int slot = getSlotForWorldPos(wx, y, wz, ox, oy, oz);
                    if (slot == World.EMPTY) continue;

                    int blockId = world.getVoxel(wx, y, wz);
                    int ly = y & 15;

                    int currentSky = world.getSkyLight(slot, lx, ly, lz);
                    if (currentSky != skyLight) {
                        world.setSkyLight(slot, lx, ly, lz, skyLight);
                        dirtySlots.add(slot);
                    }

                    if (skyLight <= 0) break;

                    int opacity = getBlockOpacity(blockId);
                    skyLight = Math.max(0, skyLight - opacity);
                }
            }
        }
        return dirtySlots;
    }

    /**
     * Finds the Y coordinate of the topmost non-transparent block in the column.
     * Scans downward from the world height limit.
     */
    private int findTopBlockY(int wx, int wz) {
        int bufMaxYRel = world.getOffsetY() + bufSize;
        for (int y = bufMaxYRel - 1; y >= 0; y--) {
            int blockId = world.getVoxel(wx, y, wz);
            if (blockId > 0 && blockDataManager.isFullBlock(blockId)) {
                return y + 1; // sky light starts above the top block
            }
        }
        // No blocks found — sky reaches the bottom with full brightness
        return bufMaxYRel - 1;
    }

    // ══════════════════════════════════════════════════════════════════
    //  BLOCK LIGHT — optimized with primitive LongQueue + inlined bounds
    // ══════════════════════════════════════════════════════════════════

    /** Primitive long ring-buffer for BFS nodes. Packs x|y|z|dist into one long. */
    private static class LongQueue {
        private long[] elements;
        private int head, tail, size, mask;

        LongQueue(int capacity) {
            int cap = 1;
            while (cap < capacity) cap <<= 1;
            elements = new long[cap];
            mask = cap - 1;
        }

        void add(long v) {
            if (size == elements.length) {
                long[] na = new long[elements.length << 1];
                for (int i = 0; i < size; i++) na[i] = elements[(head + i) & mask];
                elements = na;
                mask = elements.length - 1;
                head = 0;
                tail = size;
            }
            elements[tail] = v;
            tail = (tail + 1) & mask;
            size++;
        }

        long poll() {
            long v = elements[head];
            head = (head + 1) & mask;
            size--;
            return v;
        }

        boolean isEmpty() { return size == 0; }
    }

    /** Bit layout: z(11 bits, 0-10) | y(11 bits, 11-21) | x(11 bits, 22-32) | dist(4 bits, 33-36) */
    private static long packNode(int x, int y, int z, int dist) {
        return ((long)(x & 0x7FF) << 22) | ((long)(y & 0x7FF) << 11) | ((long)(z & 0x7FF)) | ((long)(dist & 0xF) << 33);
    }
    private static int nodeX(long p) { return (int)((p >>> 22) & 0x7FF); }
    private static int nodeY(long p) { return (int)((p >>> 11) & 0x7FF); }
    private static int nodeZ(long p) { return (int)(p & 0x7FF); }
    private static int nodeDist(long p) { return (int)((p >>> 33) & 0xF); }

    /**
     * Propagates block light from all emissive sources in a chunk section.
     * Flood-fill BFS: light diminishes by 1+opacity per step, full blocks block propagation.
     *
     * @param cx   Absolute chunk X coordinate
     * @param cy   Absolute chunk section Y coordinate
     * @param cz   Absolute chunk Z coordinate
     * @param slot Pool slot for this section
     * @return Set of dirty slot indices
     */
    public Set<Integer> propagateBlockLight(int cx, int cy, int cz, int slot) {
        Set<Integer> dirtySlots = new HashSet<>();
        int worldBaseX = cx << 4;
        int worldBaseY = cy << 4;
        int worldBaseZ = cz << 4;

        LongQueue queue = new LongQueue(256);

        // Pre-compute buffer bounds (stable for the entire propagation)
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();
        int maxRel = bufSize - 1;

        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int wx = worldBaseX + lx;
                    int wy = worldBaseY + ly;
                    int wz = worldBaseZ + lz;

                    int emissive = blockDataManager.getEmissive(world.getVoxel(wx, wy, wz));
                    if (emissive > 0) {
                        int lightLevel = Math.min(emissive, MAX_LIGHT);
                        world.setBlockLight(slot, lx, ly, lz, lightLevel);
                        dirtySlots.add(slot);
                        queue.add(packNode(wx, wy, wz, lightLevel));
                    }
                }
            }
        }

        // Flood-fill BFS with inlined bounds check
        Direction[] dirs = Direction.values();
        while (!queue.isEmpty()) {
            long node = queue.poll();
            int nx0 = nodeX(node), ny0 = nodeY(node), nz0 = nodeZ(node);
            int dist = nodeDist(node);

            for (Direction dir : dirs) {
                int nx = nx0 + dir.x;
                int ny = ny0 + dir.y;
                int nz = nz0 + dir.z;

                // Inline bounds + slot lookup (avoids two method calls per neighbor)
                int rnx = nx - ox, rny = ny - oy, rnz = nz - oz;
                if (rnx < 0 || rny < 0 || rnz < 0 || rnx > maxRel || rny > maxRel || rnz > maxRel) continue;

                int nSlot = world.getChunkSlot(nx, ny, nz);
                if (nSlot == World.EMPTY) continue;

                int opacity = getBlockOpacity(world.getVoxel(nx, ny, nz));
                int nextLevel = dist - Math.max(1, opacity);
                if (nextLevel <= 0) continue;

                int nlx = nx & 15, nly = ny & 15, nlz = nz & 15;
                int current = world.getBlockLight(nSlot, nlx, nly, nlz);
                if (current < nextLevel) {
                    world.setBlockLight(nSlot, nlx, nly, nlz, nextLevel);
                    dirtySlots.add(nSlot);
                    if (nextLevel > 1) {
                        queue.add(packNode(nx, ny, nz, nextLevel));
                    }
                }
            }
        }

        return dirtySlots;
    }

    /**
     * Runs block light BFS from a single source position (for block placement/removal).
     * Uses incremental darken+flood instead of brute-force clear+rebuild for real-time performance.
     *
     * @param x World X coordinate of changed block
     * @param y World Y coordinate of changed block
     * @param z World Z coordinate of changed block
     * @return Set of dirty slot indices
     */
    public Set<Integer> checkBlockLight(int x, int y, int z) {
        Set<Integer> dirtySlots = new HashSet<>();
        int slot = getSlotForWorldPos(x, y, z);
        if (slot == World.EMPTY) return dirtySlots;

        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();
        int maxRel = bufSize - 1;

        int lx = x & 15, ly = y & 15, lz = z & 15;
        int currentLight = world.getBlockLight(slot, lx, ly, lz);
        int blockId = world.getVoxel(x, y, z);
        int emissive = blockDataManager.getEmissive(blockId);

        LongQueue removeQueue = new LongQueue(64);
        LongQueue addQueue = new LongQueue(64);

        // Capture initial state
        if (emissive > 0) {
            int lightLevel = Math.min(emissive, MAX_LIGHT);
            world.setBlockLight(slot, lx, ly, lz, lightLevel);
            dirtySlots.add(slot);
            addQueue.add(packNode(x, y, z, lightLevel));
        } else {
            world.setBlockLight(slot, lx, ly, lz, 0);
            dirtySlots.add(slot);
            if (currentLight > 0) {
                removeQueue.add(packNode(x, y, z, currentLight));
            }
        }

        Direction[] dirs = Direction.values();

        // 1. Darken previous light boundaries (un-propagate)
        while (!removeQueue.isEmpty()) {
            long node = removeQueue.poll();
            int nx0 = nodeX(node), ny0 = nodeY(node), nz0 = nodeZ(node);
            int lightLevel = nodeDist(node);

            for (Direction dir : dirs) {
                int nx = nx0 + dir.x, ny = ny0 + dir.y, nz = nz0 + dir.z;
                int rnx = nx - ox, rny = ny - oy, rnz = nz - oz;
                if (rnx < 0 || rny < 0 || rnz < 0 || rnx > maxRel || rny > maxRel || rnz > maxRel) continue;

                int nSlot = world.getChunkSlot(nx, ny, nz);
                if (nSlot == World.EMPTY) continue;

                int nlx = nx & 15, nly = ny & 15, nlz = nz & 15;
                int nLight = world.getBlockLight(nSlot, nlx, nly, nlz);

                if (nLight != 0 && nLight < lightLevel) {
                    world.setBlockLight(nSlot, nlx, nly, nlz, 0);
                    dirtySlots.add(nSlot);
                    removeQueue.add(packNode(nx, ny, nz, nLight));
                } else if (nLight >= lightLevel) {
                    addQueue.add(packNode(nx, ny, nz, nLight));
                }
            }
        }

        // 2. Flood fill returning light + emissive (re-propagate)
        while (!addQueue.isEmpty()) {
            long node = addQueue.poll();
            int nx0 = nodeX(node), ny0 = nodeY(node), nz0 = nodeZ(node);
            int dist = nodeDist(node);

            for (Direction dir : dirs) {
                int nx = nx0 + dir.x, ny = ny0 + dir.y, nz = nz0 + dir.z;
                int rnx = nx - ox, rny = ny - oy, rnz = nz - oz;
                if (rnx < 0 || rny < 0 || rnz < 0 || rnx > maxRel || rny > maxRel || rnz > maxRel) continue;

                int nSlot = world.getChunkSlot(nx, ny, nz);
                if (nSlot == World.EMPTY) continue;

                int opacity = getBlockOpacity(world.getVoxel(nx, ny, nz));
                int expectedLight = dist - Math.max(1, opacity);
                if (expectedLight <= 0) continue;

                int nlx = nx & 15, nly = ny & 15, nlz = nz & 15;
                int current = world.getBlockLight(nSlot, nlx, nly, nlz);

                if (current < expectedLight) {
                    world.setBlockLight(nSlot, nlx, nly, nlz, expectedLight);
                    dirtySlots.add(nSlot);
                    addQueue.add(packNode(nx, ny, nz, expectedLight));
                }
            }
        }
        return dirtySlots;
    }

    // ══════════════════════════════════════════════════════════════════
    //  FULL REBUILD (all chunks)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Rebuilds all sky light and block light from scratch for the given loaded chunks.
     *
     * @param loadedChunks Map of chunkKey -> slots
     * @return Total number of dirty slots
     */
    public Set<Integer> rebuildAllLighting(java.util.Map<Long, Integer[]> loadedChunks) {
        Set<Integer> allDirty = new HashSet<>();

        // Phase 1: Clear all light in loaded chunks
        for (Integer[] slots : loadedChunks.values()) {
            for (int cy = 0; cy < 16; cy++) {
                world.clearLightPoolSlot(slots[cy]);
            }
        }

        // Phase 2: Sky light for all columns
        GameLogger.log("LIGHT Sky light generation...");
        int colDone = 0;
        for (java.util.Map.Entry<Long, Integer[]> entry : loadedChunks.entrySet()) {
            long key = entry.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Set<Integer> dirty = generateSkyLight(cx, cz, entry.getValue());
            allDirty.addAll(dirty);
            colDone++;
            if (colDone % 50 == 0) {
                System.out.print("\r  Sky light: " + colDone + "/" + loadedChunks.size() + " columns");
            }
        }
        System.out.println("\r  Sky light: " + colDone + "/" + loadedChunks.size() + " columns done");

        // Phase 3: Block light for all sections
        GameLogger.log("LIGHT Block light propagation...");
        int secDone = 0;
        int totalSecs = loadedChunks.size() * 16;
        for (java.util.Map.Entry<Long, Integer[]> entry : loadedChunks.entrySet()) {
            long key = entry.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Integer[] slots = entry.getValue();
            for (int cy = 0; cy < 16; cy++) {
                Set<Integer> dirty = propagateBlockLight(cx, cy, cz, slots[cy]);
                allDirty.addAll(dirty);
            }
            secDone += 16;
            if (secDone % 800 == 0) {
                System.out.print("\r  Block light: " + secDone + "/" + totalSecs + " sections");
            }
        }
        System.out.println("\r  Block light: " + secDone + "/" + totalSecs + " sections done");

        return allDirty;
    }

    // ══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════

    private int getBlockOpacity(int blockId) {
        if (blockId <= 0) return 0;
        return blockDataManager.getOpacity(blockId);
    }

    private int getSlotForWorldPos(int x, int y, int z) {
        return world.getChunkSlot(x, y, z);
    }

    /** Faster overload with pre-computed buffer origin — skips World.getOffsetX/Y/Z calls. */
    private int getSlotForWorldPos(int x, int y, int z, int ox, int oy, int oz) {
        int rx = x - ox, ry = y - oy, rz = z - oz;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= bufSize || ry >= bufSize || rz >= bufSize) return World.EMPTY;
        return world.getIndirectionTable()[(rx >> 4) + (ry >> 4) * World.REGION_SIZE + (rz >> 4) * World.REGION_SIZE * World.REGION_SIZE];
    }
}
