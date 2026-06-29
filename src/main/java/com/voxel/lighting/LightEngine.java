package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.Direction;
import com.voxel.GameLogger;

import java.util.Set;
import java.util.HashSet;

/**
 * Per-type additive lighting engine.
 *
 * Sky light (EnumSkyBlock.SKY):
 *   Propagates top-down from the world ceiling. Each column is computed independently:
 *   starting from the highest block, sky light = 15 and diminishes by block opacity
 *   as it descends.
 *
 * Block light (per-type additive):
 *   Each light source type (defined by unique emissive × lightColor pair) propagates
 *   a SCALAR intensity (0-15) through BFS flood-fill. The per-type intensity field
 *   is accumulated into a temporary byte[] pool, then tinted by the type's lightColor
 *   and ADDED to the main light pool's RGB channels.
 *
 *   World gen: per-type batched BFS — all sources of each type in a section propagate
 *   together, then tint+add to main.
 *
 *   Runtime block changes: single-source BFS for the changed source, then tint+add
 *   (for placing) or tint+subtract (for breaking) from the main pool.
 *
 * Light is stored in the World.lightPool using a packed format (8 bits per channel):
 *   bits 0-7  = sky light (0-255)
 *   bits 8-15 = block light Red   (0-255)
 *   bits 16-23 = block light Green (0-255)
 *   bits 24-31 = block light Blue  (0-255)
 *
 * Block RGB is the additive sum of all per-type tinted contributions, NOT a max.
 *
 * LightEngine keeps internal logic at 0-15 intensity levels and scales
 * by ×17 when writing to pools, ÷17 when reading from pools.
 */

public class LightEngine {

    private final World world;
    private final BlockDataManager blockDataManager;
    private final byte[] tempField;  // reference to World.tempLightPool, reused for per-type BFS

    /** Maximum light value (both sky and block). */
    public static final int MAX_LIGHT = 15;

    /** Height limit for sky light computation. */
    public static final int WORLD_HEIGHT = 256;

    private final int bufSize; // World.REGION_SIZE * World.CHUNK_SIZE (2048)

    public LightEngine(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
        this.bufSize = World.REGION_SIZE * World.CHUNK_SIZE;
        this.tempField = world.getTempLightPool();
    }

    // ══════════════════════════════════════════════════════════════════
    //  SKY LIGHT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Generates sky light for an entire chunk column (all 16 sections, y = 0..255).
     * Starts from the world ceiling with sky=15 and propagates downward:
     * air keeps sky=15, block opacity decreases it.
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

                // Propagate sky light downward from world ceiling.
                // Air above the highest block keeps full sky=15.
                int skyLight = MAX_LIGHT;
                for (int y = bufMaxYRel - 1; y >= 0; y--) {
                    int slot = getSlotForWorldPos(wx, y, wz, ox, oy, oz);
                    if (slot == World.EMPTY) continue;

                    int blockId = world.getVoxel(wx, y, wz);
                    int ly = y & 15;

                    int currentSky = world.getSkyLight(slot, lx, ly, lz) / 17;
                    if (currentSky != skyLight) {
                        world.setSkyLight(slot, lx, ly, lz, skyLight * 17);
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

    /** Scalar BFS node: packs x|y|z|intensity into one long. */
    // Bit layout: z(11 bits, 0-10) | y(11 bits, 11-21) | x(11 bits, 22-32) | intensity(4 bits, 33-36)
    private static long packNodeScalar(int x, int y, int z, int intensity) {
        return ((long)(x & 0x7FF) << 22) | ((long)(y & 0x7FF) << 11) | ((long)(z & 0x7FF))
            | ((long)(intensity & 0xF) << 33);
    }
    private static int nodeX(long p) { return (int)((p >>> 22) & 0x7FF); }
    private static int nodeY(long p) { return (int)((p >>> 11) & 0x7FF); }
    private static int nodeZ(long p) { return (int)(p & 0x7FF); }
    private static int nodeIntensityScalar(long p) { return (int)((p >>> 33) & 0xF); }

    // ══════════════════════════════════════════════════════════════════
    //  TEMP FIELD HELPERS
    // ══════════════════════════════════════════════════════════════════

    /** Clears the temp field for a single chunk slot. */
    private void clearTempFieldSlot(int slot) {
        int start = slot << 12;
        int end = start + 4096;
        java.util.Arrays.fill(tempField, start, end, (byte) 0);
    }

    // ══════════════════════════════════════════════════════════════════
    //  SCALAR BFS — flood-fills from seeded sources into tempField
    // ══════════════════════════════════════════════════════════════════

    /**
     * Flood-fill BFS: propagates scalar intensity (0-15) from a seeded queue
     * into the temp byte field (stored as 0-255 = intensity × 17).
     *
     * @param queue Pre-seeded with source nodes via packNodeScalar
     * @param ox,oy,oz Buffer origin (pre-computed for perf)
     * @param dirtySlots Set to fill with affected slot indices
     */
    private void floodFillScalar(LongQueue queue, int ox, int oy, int oz, Set<Integer> dirtySlots) {
        int maxRel = bufSize - 1;
        Direction[] dirs = Direction.values();
        int[] mainPool = world.getLightPool();

        while (!queue.isEmpty()) {
            long node = queue.poll();
            int nx0 = nodeX(node), ny0 = nodeY(node), nz0 = nodeZ(node);
            int cur = nodeIntensityScalar(node);

            for (Direction dir : dirs) {
                int nx = nx0 + dir.x;
                int ny = ny0 + dir.y;
                int nz = nz0 + dir.z;

                int rnx = nx - ox, rny = ny - oy, rnz = nz - oz;
                if (rnx < 0 || rny < 0 || rnz < 0 || rnx > maxRel || rny > maxRel || rnz > maxRel) continue;

                int nSlot = world.getIndirectionTable()[(rnx >> 4) + (rny >> 4) * World.REGION_SIZE + (rnz >> 4) * World.REGION_SIZE * World.REGION_SIZE];
                if (nSlot == World.EMPTY) continue;

                int opacity = getBlockOpacity(world.getVoxel(nx, ny, nz));
                int step = Math.max(1, opacity);
                int next = Math.max(0, cur - step);
                if (next <= 0) continue;

                int nidx = (nSlot << 12) | ((rnx & 15) | ((rny & 15) << 4) | ((rnz & 15) << 8));
                int existing = tempField[nidx] & 0xFF;
                int next255 = next * 17;

                if (next255 > existing) {
                    tempField[nidx] = (byte) next255;
                    dirtySlots.add(nSlot);
                    if (next > 1) {
                        queue.add(packNodeScalar(nx, ny, nz, next));
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  APPLY TINT — add/subtract tinted temp field to/from main pool
    // ══════════════════════════════════════════════════════════════════

    /**
     * Reads the temp field for all dirty slots, applies the block's lightColor tint,
     * and either adds or subtracts the tinted contribution from the main light pool.
     * Clears the temp field for processed slots as it goes.
     *
     * @param blockId The block whose lightColor tint to apply
     * @param add true to add, false to subtract
     * @param dirtySlots Set of slot indices to process (also receives any new dirty slots from main pool changes)
     */
    private void applyTintToMain(int blockId, boolean add, Set<Integer> dirtySlots) {
        int lightColor = blockDataManager.getLightColor(blockId);
        int tr = (lightColor >> 16) & 0xFF;
        int tg = (lightColor >> 8) & 0xFF;
        int tb = lightColor & 0xFF;

        int[] mainPool = world.getLightPool();

        // Collect slots to remove; avoid ConcurrentModificationException by removing after iteration
        java.util.List<Integer> toRemove = new java.util.ArrayList<>();

        for (int slot : dirtySlots) {
            int base = slot << 12;
            boolean slotChanged = false;

            for (int i = 0; i < 4096; i++) {
                int idx = base | i;
                int rawIntensity = tempField[idx] & 0xFF;
                if (rawIntensity == 0) continue;

                // rawIntensity = intensity * 17, convert back to 0-15
                int level = rawIntensity / 17;
                // Clip to valid range (should be 0-15 already, but guard)
                if (level > 15) level = 15;

                // Compute tinted contribution in 0-255 range
                int cr = level * tr / 15;
                int cg = level * tg / 15;
                int cb = level * tb / 15;

                int current = mainPool[idx];
                int curR = (current >> 8) & 0xFF;
                int curG = (current >> 16) & 0xFF;
                int curB = (current >> 24) & 0xFF;

                int newR, newG, newB;
                if (add) {
                    newR = Math.min(255, curR + cr);
                    newG = Math.min(255, curG + cg);
                    newB = Math.min(255, curB + cb);
                } else {
                    newR = Math.max(0, curR - cr);
                    newG = Math.max(0, curG - cg);
                    newB = Math.max(0, curB - cb);
                }

                if (newR != curR || newG != curG || newB != curB) {
                    mainPool[idx] = (current & 0xFF) // preserve sky
                        | (newR << 8) | (newG << 16) | (newB << 24);
                    slotChanged = true;
                }
            }

            // Clear temp field for this slot so next type pass starts clean
            java.util.Arrays.fill(tempField, base, base + 4096, (byte) 0);

            // If main pool didn't actually change, mark for removal from dirty set
            if (!slotChanged) {
                toRemove.add(slot);
            }
        }

        dirtySlots.removeAll(toRemove);
    }

    // ══════════════════════════════════════════════════════════════════
    //  SINGLE-SOURCE BFS — for runtime block place/break
    // ══════════════════════════════════════════════════════════════════

    /**
     * Computes the scalar intensity field for a single light source at (x,y,z)
     * into the temp field. Used for runtime block changes where only one source
     * is added or removed.
     *
     * @return Set of slot indices that received temp field writes
     */
    public Set<Integer> computeSingleSourceContribution(int x, int y, int z, int intensity) {
        Set<Integer> dirtySlots = new HashSet<>();
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();

        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return dirtySlots;

        int lx = x & 15, ly = y & 15, lz = z & 15;
        int idx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        tempField[idx] = (byte) (intensity * 17);
        dirtySlots.add(slot);

        LongQueue queue = new LongQueue(256);
        queue.add(packNodeScalar(x, y, z, intensity));

        floodFillScalar(queue, ox, oy, oz, dirtySlots);
        return dirtySlots;
    }

    // ══════════════════════════════════════════════════════════════════
    //  PER-TYPE BATCH BFS — for world gen / section rebuilds
    // ══════════════════════════════════════════════════════════════════

    /**
     * Propagates block light from all emissive sources in a chunk section.
     * Groups sources by type (emissive × lightColor), then runs one scalar BFS
     * per type into the temp field, tints it by the type's color, and adds the
     * result to the main pool.
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
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();

        // Phase 1: Collect sources grouped by light type key.
        // Key = (emissive << 24) | (lightColor & 0xFFFFFF) — unique per emissive×color pair.
        java.util.Map<Integer, java.util.List<int[]>> sourcesByType = new java.util.HashMap<>();

        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int wx = worldBaseX + lx;
                    int wy = worldBaseY + ly;
                    int wz = worldBaseZ + lz;

                    int blockId = world.getVoxel(wx, wy, wz);
                    int emissive = blockDataManager.getEmissive(blockId);
                    if (emissive <= 0) continue;

                    int lightColor = blockDataManager.getLightColor(blockId);
                    int typeKey = (emissive << 24) | (lightColor & 0xFFFFFF);
                    int intensity = Math.min(emissive, 15);

                    sourcesByType.computeIfAbsent(typeKey, k -> new java.util.ArrayList<>())
                        .add(new int[]{wx, wy, wz, intensity, blockId});
                }
            }
        }

        if (sourcesByType.isEmpty()) return dirtySlots;

        // Phase 2: For each type, run scalar BFS, tint, add to main.
        for (java.util.Map.Entry<Integer, java.util.List<int[]>> entry : sourcesByType.entrySet()) {
            int typeKey = entry.getKey();
            int emissive = (typeKey >> 24) & 0xFF;
            int lightColor = typeKey & 0xFFFFFF;
            int blockId = entry.getValue().get(0)[4]; // representative blockId for tint

            // Clear temp field for this slot
            clearTempFieldSlot(slot);

            // Seed all sources of this type
            LongQueue queue = new LongQueue(256);
            Set<Integer> typeDirty = new HashSet<>();
            for (int[] src : entry.getValue()) {
                int sx = src[0], sy = src[1], sz = src[2];
                int intensity = src[3];

                int slx = sx & 15, sly = sy & 15, slz = sz & 15;
                int sidx = (slot << 12) | (slx | (sly << 4) | (slz << 8));
                int existing = tempField[sidx] & 0xFF;
                int intensity255 = intensity * 17;
                if (intensity255 > existing) {
                    tempField[sidx] = (byte) intensity255;
                    typeDirty.add(slot);
                }
                queue.add(packNodeScalar(sx, sy, sz, intensity));
            }

            // Flood-fill
            floodFillScalar(queue, ox, oy, oz, typeDirty);

            // Tint and add to main pool
            applyTintToMain(blockId, true, typeDirty);
            dirtySlots.addAll(typeDirty);
        }

        return dirtySlots;
    }

    /**
     * Handles a block change at (x,y,z).
     *
     * If the change involves an emissive source (old or new):
     *   - Run single-source BFS for the removed source (using oldBlockId) and SUBTRACT
     *   - Run single-source BFS for the placed source (using current block) and ADD
     *
     * If neither side is emissive (e.g., dirt → stone):
     *   - Full rebuild of the 3×3×3 section cube using per-type batch mode
     *
     * @param x,y,z       World coordinates of changed block
     * @param oldBlockId  Block ID that was at this position before the change
     * @return Set of dirty slot indices
     */
    public Set<Integer> onBlockChanged(int x, int y, int z, int oldBlockId) {
        Set<Integer> dirtySlots = new HashSet<>();
        int newBlockId = world.getVoxel(x, y, z);
        int oldEmissive = blockDataManager.getEmissive(oldBlockId);
        int newEmissive = blockDataManager.getEmissive(newBlockId);

        if (oldEmissive > 0 || newEmissive > 0) {
            // ── Light source changed: single-source add/subtract ──
            if (oldEmissive > 0) {
                int intensity = Math.min(oldEmissive, 15);
                Set<Integer> contrib = computeSingleSourceContribution(x, y, z, intensity);
                applyTintToMain(oldBlockId, false, contrib);
                dirtySlots.addAll(contrib);
            }
            if (newEmissive > 0) {
                int intensity = Math.min(newEmissive, 15);
                Set<Integer> contrib = computeSingleSourceContribution(x, y, z, intensity);
                applyTintToMain(newBlockId, true, contrib);
                dirtySlots.addAll(contrib);
            }
        } else {
            // ── Non-emissive block change: full rebuild of 3×3×3 section cube ──
            int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();
            int cx = x >> 4, cy = y >> 4, cz = z >> 4;

            // Cache and clear block light in main pool for all 27 sections
            int[][][] cachedSlots = new int[3][3][3];
            for (int dcx = -1; dcx <= 1; dcx++) {
                for (int dcy = -1; dcy <= 1; dcy++) {
                    for (int dcz = -1; dcz <= 1; dcz++) {
                        int slot = getSlotForSection(cx + dcx, cy + dcy, cz + dcz, ox, oy, oz);
                        cachedSlots[dcx + 1][dcy + 1][dcz + 1] = slot;
                        if (slot != World.EMPTY) {
                            world.clearLightPoolSlot(slot);
                            dirtySlots.add(slot);
                        }
                    }
                }
            }

            // Re-propagate all 27 sections using per-type batch
            for (int dcx = -1; dcx <= 1; dcx++) {
                for (int dcy = -1; dcy <= 1; dcy++) {
                    for (int dcz = -1; dcz <= 1; dcz++) {
                        int slot = cachedSlots[dcx + 1][dcy + 1][dcz + 1];
                        if (slot != World.EMPTY) {
                            dirtySlots.addAll(propagateBlockLight(cx + dcx, cy + dcy, cz + dcz, slot));
                        }
                    }
                }
            }

            // ── Regenerate sky light for affected columns ──
            // clearLightPoolSlot zeroed sky light in all 27 sections; block light
            // was restored above, but sky light must be regenerated separately.
            // The 3×3 area in X/Z covers 9 unique chunk columns.
            java.util.Set<Long> columnsDone = new java.util.HashSet<>();
            for (int dcx = -1; dcx <= 1; dcx++) {
                for (int dcz = -1; dcz <= 1; dcz++) {
                    int colCX = cx + dcx;
                    int colCZ = cz + dcz;
                    long colKey = ((long) colCX << 32) | (colCZ & 0xFFFFFFFFL);
                    if (!columnsDone.add(colKey)) continue;

                    Integer[] colSlots = new Integer[16];
                    boolean anyLoaded = false;
                    for (int dcy = 0; dcy < 16; dcy++) {
                        colSlots[dcy] = getSlotForSection(colCX, dcy, colCZ, ox, oy, oz);
                        if (colSlots[dcy] != World.EMPTY) anyLoaded = true;
                    }
                    if (anyLoaded) {
                        dirtySlots.addAll(generateSkyLight(colCX, colCZ, colSlots));
                    }
                }
            }
        }

        return dirtySlots;
    }

    /** Fast slot lookup from absolute section coords + pre-computed buffer origin. */
    private int getSlotForSection(int scx, int scy, int scz, int ox, int oy, int oz) {
        int wx = scx << 4, wy = scy << 4, wz = scz << 4;
        int rx = wx - ox, ry = wy - oy, rz = wz - oz;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= bufSize || ry >= bufSize || rz >= bufSize) return World.EMPTY;
        int idx = (rx >> 4) + (ry >> 4) * World.REGION_SIZE + (rz >> 4) * World.REGION_SIZE * World.REGION_SIZE;
        int slot = world.getIndirectionTable()[idx];
        return slot == World.EMPTY ? World.EMPTY : slot;
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
    //  OCCLUSION BAKER
    // ══════════════════════════════════════════════════════════════════

    /** 14-directional occlusion sample vectors (matches LightPropagationEngine). */
    public static final float[][] OCC_DIRS = {
        {0.0f, 1.0f, 0.0f},
        {0.707f, 0.707f, 0.0f}, {-0.707f, 0.707f, 0.0f}, {0.0f, 0.707f, 0.707f}, {0.0f, 0.707f, -0.707f},
        {0.5f, 0.707f, 0.5f}, {-0.5f, 0.707f, 0.5f}, {0.5f, 0.707f, -0.5f}, {-0.5f, 0.707f, -0.5f},
        {0.866f, 0.5f, 0.0f}, {-0.866f, 0.5f, 0.0f}, {0.0f, 0.5f, 0.866f}, {0.0f, 0.5f, -0.866f},
        {0.0f, 0.3f, 0.0f}
    };

    /**
     * Bakes 14-directional sky occlusion for every voxel in a chunk section.
     * Stores results in the World's occlusionPool as bitmask shorts.
     */
    public void bakeChunkOcclusion(int slot, int cx, int cy, int cz) {
        short[] occPool = world.getOcclusionPool();
        int baseIdx = slot << 12;
        for (int ly = 15; ly >= 0; ly--) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int wx = (cx << 4) + lx, wy = (cy << 4) + ly, wz = (cz << 4) + lz;
                    int idx = baseIdx | (lx | (ly << 4) | (lz << 8));
                    if (isFullBlock(wx, wy, wz)) { occPool[idx] = 0; continue; }
                    int m = 0;
                    for (int d = 0; d < 14; d++) {
                        if (checkSkyVisibility(wx, wy, wz, d)) m |= (1 << d);
                    }
                    occPool[idx] = (short) m;
                }
            }
        }
    }

    private boolean checkSkyVisibility(int x, int y, int z, int dirIdx) {
        float[] d = OCC_DIRS[dirIdx];
        float cx = x + 0.5f, cy = y + 0.5f, cz = z + 0.5f;
        for (int i = 1; i < 32; i++) {
            int nx = (int)(cx + d[0] * i), ny = (int)(cy + d[1] * i), nz = (int)(cz + d[2] * i);
            if (ny >= bufSize) return true;
            if (isFullBlock(nx, ny, nz)) return false;
        }
        return true;
    }

    /** Returns true if the voxel at (x,y,z) is a full solid block (opaque, no transparency). */
    private boolean isFullBlock(int x, int y, int z) {
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();
        int rx = x - ox, ry = y - oy, rz = z - oz;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= bufSize || ry >= bufSize || rz >= bufSize) return false;
        int slot = world.getIndirectionTable()[(rx >> 4) + (ry >> 4) * World.REGION_SIZE + (rz >> 4) * World.REGION_SIZE * World.REGION_SIZE];
        if (slot == World.EMPTY) return false;
        int id = world.getChunkPool()[(slot << 12) | ((rx & 15) | ((ry & 15) << 4) | ((rz & 15) << 8))] & 0xFFFF;
        return id > 0 && blockDataManager.isFullBlock(id);
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
