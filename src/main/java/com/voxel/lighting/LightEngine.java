package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.GameLogger;
import com.voxel.world.WorldGenLogger;

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

    // ── Held photons ──
    // When a flood-fill hits an unloaded chunk (EMPTY slot), the photon that
    // would have entered it is parked here instead of dropped. When the target
    // chunk loads, resumeHeldPhotons() re-seeds the flood from the parked state
    // (same intensity, direction tally, tint), so light from a loaded chunk
    // "waits at the boundary" and continues the moment the chunk appears — no
    // chunk-boundary light seams. Sky-light photons from the horizontal fan BFS
    // are parked separately (max-based field, no tint).
    private static final int MAX_HELD_PHOTONS = 65536;
    // Sky fan parks at EVERY boundary cell of a column (up to ~65k: 2 columns ×
    // 2048 height × 16 width per pass) — but nearly all are open-field no-ops
    // (the target column's own vertical sweep gives full sky anyway; the resume
    // drops them without writing). The useful ones — light bending around
    // overhangs/corners at gameplay heights — park first because the fan seeds
    // sections in ascending-Y order, so a small cap keeps the band near ground
    // level while keeping the per-pass park + per-load resume scan cheap.
    private static final int MAX_HELD_SKY_PHOTONS = 8192;
    private final Object heldLock = new Object();
    private final java.util.List<HeldPhoton> heldPhotons = new java.util.ArrayList<>();
    private final java.util.List<HeldSkyPhoton> heldSkyPhotons = new java.util.ArrayList<>();

    /** Maximum light value (both sky and block). */
    public static final int MAX_LIGHT = 15;

    /**
     * Gamma used to decode the normalized block-light level before it enters
     * the linear scene-lighting calculations. The raytracer mirrors this value
     * in its GLSL helper; output encoding is performed separately with sRGB.
     */
    public static final float BLOCK_LIGHT_GAMMA = 2.2f;

    /**
     * Converts a raw 0..255 light-pool channel to linear 0..255 intensity.
     * Both endpoints are preserved: 0 stays dark and 255 stays full brightness.
     */
    public static float decodeBlockLight(float encodedChannel) {
        float normalized = Math.max(0.0f, Math.min(1.0f, encodedChannel / 255.0f));
        return (float) Math.pow(normalized, BLOCK_LIGHT_GAMMA) * 255.0f;
    }

    /** Exact scalar sRGB encoding used by the final shader output. */
    public static float linearToSrgb(float linear) {
        float clamped = Math.max(0.0f, linear);
        return clamped <= 0.0031308f
                ? clamped * 12.92f
                : 1.055f * (float) Math.pow(clamped, 1.0f / 2.4f) - 0.055f;
    }

    /**
     * Additive headroom for block-light tint contributions.
     *
     * Block RGB is the additive sum of every overlapping per-type tint, each of
     * which can be up to 255 in its brightest channel. With no headroom a single
     * max-intensity source already saturates its channel, so two overlapping
     * lights clip at 255 and their hue shifts toward white (colour clamping).
     *
     * Dividing each contribution by this factor keeps the tint RATIO intact
     * (hue preserved) while leaving room for up to this many full sources to
     * sum before any channel hits 255. The raytracer compensates with a matching
     * ×HEADROOM gain on block light so overall brightness is unchanged.
     */
    public static final int LIGHT_TINT_HEADROOM = 4;

    /**
     * Vertical run of clear (air / non-full) blocks that counts as direct sun.
     * Any contiguous run of at least this many transparent voxels relights to
     * full sky light — even when a block covers the top of the run, so shafts
     * and 8-tall rooms under thin roofs still read as sunlit.
     */
    public static final int SUN_CLEAR_RUN = 8;

    /** Height limit for sky light computation (now dynamic via buffer size). */
    public static final int WORLD_HEIGHT = 2048;

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
     * Generates sky light for an entire chunk column.
     * Starts from the world ceiling with sky=15 and propagates downward:
     * air keeps sky=15, block opacity decreases it.
     *
     * @param cx    Absolute chunk X coordinate
     * @param cz    Absolute chunk Z coordinate
     * @param slots Map of cy → slot for the loaded sections in this column
     * @return Set of dirty slot indices
     */
    public Set<Integer> generateSkyLight(int cx, int cz, java.util.NavigableMap<Integer, Integer> slots) {
        Set<Integer> dirtySlots = new HashSet<>();

        int worldBaseX = cx << 4;
        int worldBaseZ = cz << 4;
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();
        int bufMaxYRel = oy + bufSize;

        int changedVoxels = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = worldBaseX + lx;
                int wz = worldBaseZ + lz;

                // Propagate sky light downward from world ceiling.
                // Air above the highest block keeps full sky=15. Any
                // SUN_CLEAR_RUN block vertical line of air or non-full blocks
                // (water, slabs, leaves, glass…) counts as THE SUN: once a
                // contiguous clear run reaches SUN_CLEAR_RUN, the light resets
                // to full — sunlight flows down shafts, through water columns,
                // and into rooms under thin roofs instead of dimming through
                // them. The run keeps counting below a full block, so 8 clear
                // voxels under a cover still count as the sun.
                int skyLight = MAX_LIGHT;
                int clearRun = SUN_CLEAR_RUN; // world ceiling counts as a full clear run
                for (int y = bufMaxYRel - 1; y >= 0; y--) {
                    int slot = getSlotForWorldPos(wx, y, wz, ox, oy, oz);
                    if (slot == World.EMPTY) continue;

                    int blockId = world.getVoxel(wx, y, wz);
                    int ly = y & 15;

                    int currentSky = world.getSkyLight(slot, lx, ly, lz) / 17;
                    if (currentSky != skyLight) {
                        world.setSkyLight(slot, lx, ly, lz, skyLight * 17);
                        dirtySlots.add(slot);
                        changedVoxels++;
                    }

                    if (blockId > 0 && blockDataManager.isFullBlockFast(blockId)) {
                        // Opaque solid: blocks light and restarts the clear run.
                        // Deliberately NOT followed by a break — air below can
                        // still accumulate SUN_CLEAR_RUN clear voxels and relight.
                        clearRun = 0;
                        skyLight = Math.max(0, skyLight - getBlockOpacity(blockId));
                    } else {
                        // Air / non-full block: part of a clear vertical line.
                        // SUN_CLEAR_RUN consecutive = the sun is visible here,
                        // even when a block covers the top of the run.
                        clearRun++;
                        if (clearRun >= SUN_CLEAR_RUN) skyLight = MAX_LIGHT;
                    }
                }
            }
        }

        // Phase 2: Horizontal sky light spread
        // After vertical sweep, propagate sky light outward in a 5×5 grid
        // (all 24 cells within ±2), skipping blocked cells. Decay: ~13%
        // brightness reduction per hop (ring-1 = 1 hop, ring-2 = 2 hops).
        int hChanged = propagateSkyLightHorizontal(cx, cz, slots, dirtySlots);

        WorldGenLogger.logChunk("LIGHT_SKY", cx, -1, cz,
            "dirty=" + dirtySlots.size() + " changedVoxels=" + changedVoxels
            + " horizSpread=" + hChanged);
        return dirtySlots;
    }

    /**
     * Horizontal sky light spread: BFS queue from sky-lit voxels outward in a
     * full 5×5 grid (all 24 cells within ±2 in X/Z) so sunlight fans into
     * overhangs, 2-deep pockets, and jagged Far Lands terrain. Brightness
     * loses ~13% per hop (×28/30 ≈ 0.933): ring-1 cells get one hop, ring-2
     * cells get two — the total decay stays distance-consistent, but cells up
     * to 2 blocks away light in a single hop, so light bends around corners.
     * Blocking rule ("if it's not blocked"): the target cell must not be
     * opaque, and ring-2 cardinal cells also need their straight intermediate
     * cell passable — light never punches straight through a 1-block wall,
     * but it does bend around single pillars and thin corners.
     */
    private int propagateSkyLightHorizontal(int cx, int cz, java.util.NavigableMap<Integer, Integer> slots,
                                             Set<Integer> dirtySlots) {
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();

        LongQueue queue = new LongQueue(1024);
        int worldBaseX = cx << 4;
        int worldBaseZ = cz << 4;

        // Seed queue with all valid sky light sources in this column
        for (java.util.Map.Entry<Integer, Integer> entry : slots.entrySet()) {
            int cy = entry.getKey();
            int slot = entry.getValue();
            if (slot == World.EMPTY) continue;

            int worldBaseY = cy << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int intensity = world.getSkyLight(slot, lx, ly, lz) / 17;
                        if (intensity > 1) {
                            int rx = (worldBaseX + lx) - ox;
                            int ry = (worldBaseY + ly) - oy;
                            int rz = (worldBaseZ + lz) - oz;
                            queue.add(packNodeScalar(rx, ry, rz, intensity));
                        }
                    }
                }
            }
        }

        // Sky-light fan spreads into the 5×5 neighbourhood; photons that land on
        // unloaded chunks are parked (heldSkyPhotons) and resumed when those
        // chunks load.
        return skyFanBFS(queue, ox, oy, oz, dirtySlots);
    }

    /**
     * Horizontal sky light fan: BFS from sky-lit voxels across a full 5×5 grid
     * (all 24 cells within ±2 in X/Z) so sunlight bends around overhangs and
     * thin corners. Brightness loses ~13% per hop (×28/30 ≈ 0.933): ring-1
     * cells get one hop, ring-2 cells two. Blocking rule: the target cell must
     * not be opaque, and ring-2 cardinal cells also need their straight
     * intermediate cell passable — light never punches straight through a
     * 1-block wall. Photons targeting unloaded chunks are parked and resumed by
     * {@link #resumeHeldPhotons()} once the chunk loads.
     */
    private int skyFanBFS(LongQueue queue, int ox, int oy, int oz, Set<Integer> dirtySlots) {
        int maxRel = bufSize - 1;

        // 5×5 fan: all 24 offsets in the ±2 square around a lit voxel.
        int[][] fan5x5 = new int[24][2];
        int fi = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                fan5x5[fi][0] = dx;
                fan5x5[fi][1] = dz;
                fi++;
            }
        }
        int totalChanges = 0;

        // Process horizontal spread via BFS
        while (!queue.isEmpty()) {
            long node = queue.poll();
            int rx = nodeX(node), ry = nodeY(node), rz = nodeZ(node);
            int cur = nodeIntensityScalar(node);

            // Decay: ~13% per hop (×28/30 ≈ 0.933). Ring-1 cells take one hop,
            // ring-2 cells two — same total decay as before, just wider reach.
            int next1 = (cur * 28) / 30;
            if (next1 <= 0) continue;
            int next2 = (next1 * 28) / 30;

            for (int[] off : fan5x5) {
                int dx = off[0], dz = off[1];
                int nx = rx + dx;
                int nz = rz + dz;

                if (nx < 0 || nz < 0 || ry < 0 || nx > maxRel || nz > maxRel || ry > maxRel) continue;

                int cheb = Math.max(Math.abs(dx), Math.abs(dz)); // 1 = ring-1, 2 = ring-2
                int next = (cheb == 1) ? next1 : next2;

                // Blocking: a straight 2-hop run can't pass through an opaque wall.
                if (cheb == 2 && (dx == 0 || dz == 0)) {
                    int midX = rx + dx / 2, midZ = rz + dz / 2;
                    int midOpacity = getBlockOpacity(world.getVoxel(midX + ox, ry + oy, midZ + oz));
                    if (midOpacity >= MAX_LIGHT) continue;
                }

                int nSlot = world.getIndirectionTable()[(nx >> 4) + (ry >> 4) * World.REGION_SIZE + (nz >> 4) * World.REGION_SIZE * World.REGION_SIZE];
                if (nSlot == World.EMPTY) {
                    // Unloaded chunk: park the sky photon; it resumes when the
                    // target chunk loads (resumeHeldPhotons re-runs the fan).
                    if (next > 1) {
                        parkSkyPhoton(nx + ox, ry + oy, nz + oz, rx + ox, ry + oy, rz + oz,
                                next, cheb, dx, dz);
                    }
                    continue;
                }

                int absX = nx + ox, absY = ry + oy, absZ = nz + oz;
                int opacity = getBlockOpacity(world.getVoxel(absX, absY, absZ));
                int actNext = Math.max(0, next - opacity);
                if (actNext <= 0) continue;

                int lx = nx & 15, ly = ry & 15, lz = nz & 15;
                int existing = world.getSkyLight(nSlot, lx, ly, lz) / 17;

                if (actNext > existing) {
                    world.setSkyLight(nSlot, lx, ly, lz, actNext * 17);
                    dirtySlots.add(nSlot);
                    totalChanges++;
                    if (actNext > 1) {
                        queue.add(packNodeScalar(nx, ry, nz, actNext));
                    }
                }
            }
        }

        return totalChanges;
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

    /**
     * Scalar BFS node: packs buffer-RELATIVE x|y|z|intensity into one long.
     * Relative coords are in [0, 2047], fitting in 11 bits each.
     * Using relative coords ensures correctness at extreme world positions (Far Lands).
     */
    // Bit layout: z(11 bits, 0-10) | y(11 bits, 11-21) | x(11 bits, 22-32) | intensity(4 bits, 33-36)
    private static long packNodeScalar(int rx, int ry, int rz, int intensity) {
        return ((long)(rx & 0x7FF) << 22) | ((long)(ry & 0x7FF) << 11) | ((long)(rz & 0x7FF))
            | ((long)(intensity & 0xF) << 33);
    }
    private static int nodeX(long p) { return (int)((p >>> 22) & 0x7FF); }
    private static int nodeY(long p) { return (int)((p >>> 11) & 0x7FF); }
    private static int nodeZ(long p) { return (int)(p & 0x7FF); }
    private static int nodeIntensityScalar(long p) { return (int)((p >>> 33) & 0xF); }

    // ── Direction-tracking state (block-light flood fill) ──
    // The scalar node above uses bits 0-36. Block-light BFS additionally packs
    // path state into bits 37-63:
    //   lastAxis : bits 37-38 (0=none, 1=x, 2=y, 3=z)
    //   lastSign : bit 39     (0=negative, 1=positive)
    //   tally[i] : bits 40 + i*4 .. 43 + i*4 (4 bits each), i = 0..5 → +x,-x,+y,-y,+z,-z
    private static final int AXIS_NONE = 0, AXIS_X = 1, AXIS_Y = 2, AXIS_Z = 3;
    private static final int SIGN_NEG = 0, SIGN_POS = 1;
    private static final long TALLY_MASK = 0xFFFFFF0000000000L;

    // Direction.values() order: NORTH(-z), SOUTH(+z), EAST(+x), WEST(-x), UP(+y), DOWN(-y)
    private static final int[] DIR_AXIS  = {AXIS_Z, AXIS_Z, AXIS_X, AXIS_X, AXIS_Y, AXIS_Y};
    private static final int[] DIR_SIGN  = {SIGN_NEG, SIGN_POS, SIGN_POS, SIGN_NEG, SIGN_POS, SIGN_NEG};
    private static final int[] DIR_TALLY = {5, 4, 0, 1, 2, 3};
    // Precomputed 6-dir unit offsets (same order as above) so the flood fill
    // inner loop never allocates Direction.values() nor dereferences the enum.
    private static final int[] DIR_DX = {0, 0, 1, -1, 0, 0};
    private static final int[] DIR_DY = {0, 0, 0, 0, 1, -1};
    private static final int[] DIR_DZ = {-1, 1, 0, 0, 0, 0};

    private static long packNodeTracked(int rx, int ry, int rz, int intensity,
                                        int lastAxis, int lastSign, long tallyBits) {
        return ((long)(rx & 0x7FF) << 22)
             | ((long)(ry & 0x7FF) << 11)
             | ((long)(rz & 0x7FF))
             | ((long)(intensity & 0xF) << 33)
             | ((long)(lastAxis & 0x3) << 37)
             | ((long)(lastSign & 0x1) << 39)
             | tallyBits;
    }

    private static int nodeLastAxis(long p) { return (int)((p >>> 37) & 0x3); }
    private static int nodeLastSign(long p) { return (int)((p >>> 39) & 0x1); }
    private static int nodeTally(long p, int i) { return (int)((p >>> (40 + i * 4)) & 0xF); }

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
     * Attenuation uses the furthest distance travelled on any single axis
     * (Chebyshev distance) rather than distance-to-source or path length.
     * Each node tracks a 6-way step tally (+x,-x,+y,-y,+z,-z, tallied
     * separately — never netted back to the source), and the last axis used.
     * A step that reverses along the last-used axis halves the brightness.
     *
     * All coordinates in the queue are BUFFER-RELATIVE (0..bufSize-1).
     * This avoids 11-bit overflow at extreme world positions (Far Lands).
     *
     * @param queue Pre-seeded with source nodes via packNodeScalar (relative coords)
     * @param ox,oy,oz Buffer origin (pre-computed for perf)
     * @param dirtySlots Set to fill with affected slot indices
     * @param lightBlockId Source block ID — tint applied on resume if a photon
     *                     is parked at an unloaded chunk boundary
     * @param subtract true when the flood represents a removed source; parked
     *                 photons then subtract on resume (net-zero with the add)
     */
    private void floodFillScalar(LongQueue queue, int ox, int oy, int oz, Set<Integer> dirtySlots,
                                 int lightBlockId, boolean subtract) {
        int maxRel = bufSize - 1;
        // Cache the world tables and the flat opacity array up front: the hot loop
        // runs millions of times with thousands of light sources, so we avoid a
        // HashMap lookup + String.toLowerCase per neighbour and read the block ID
        // straight from the chunk pool using the slot we already resolved.
        int[] indirectionTable = world.getIndirectionTable();
        int[] chunkPool = world.getChunkPool();
        int[] opacityArr = blockDataManager.getOpacityArray();

        while (!queue.isEmpty()) {
            long node = queue.poll();
            // Unpack buffer-relative coordinates
            int rnx0 = nodeX(node), rny0 = nodeY(node), rnz0 = nodeZ(node);
            int cur = nodeIntensityScalar(node);
            int lastAxis = nodeLastAxis(node);
            int lastSign = nodeLastSign(node);

            long tallyBits = node & TALLY_MASK;
            // Chebyshev distance = furthest travelled on any single axis.
            int t0 = (int)((tallyBits >>> 40) & 0xF);
            int t1 = (int)((tallyBits >>> 44) & 0xF);
            int t2 = (int)((tallyBits >>> 48) & 0xF);
            int t3 = (int)((tallyBits >>> 52) & 0xF);
            int t4 = (int)((tallyBits >>> 56) & 0xF);
            int t5 = (int)((tallyBits >>> 60) & 0xF);
            int oldMax = Math.max(Math.max(t0, t1), Math.max(Math.max(t2, t3), Math.max(t4, t5)));

            for (int d = 0; d < 6; d++) {
                int dx = DIR_DX[d], dy = DIR_DY[d], dz = DIR_DZ[d];
                int rnx = rnx0 + dx;
                int rny = rny0 + dy;
                int rnz = rnz0 + dz;

                // Direct bounds check on relative coords
                if (rnx < 0 || rny < 0 || rnz < 0 || rnx > maxRel || rny > maxRel || rnz > maxRel) continue;

                int axis = DIR_AXIS[d];
                int sign = DIR_SIGN[d];
                int ti = DIR_TALLY[d];
                int t = nodeTally(node, ti);
                int nt = t + 1;
                // Distance grows only when this step pushes past the furthest
                // extent reached on any axis (Chebyshev, not Manhattan).
                int distIncrease = (t == oldMax) ? 1 : 0;
                long newTallyBits = (tallyBits & ~(0xFL << (40 + ti * 4))) | ((long) nt << (40 + ti * 4));

                int nSlot = indirectionTable[(rnx >> 4) + (rny >> 4) * World.REGION_SIZE + (rnz >> 4) * World.REGION_SIZE * World.REGION_SIZE];
                if (nSlot == World.EMPTY) {
                    // Unloaded chunk: park the photon instead of dropping it. The
                    // target cell's opacity is unknown, so keep the arrival
                    // intensity; resumeHeldPhotons() applies decay and writes once
                    // the section is loaded.
                    if (cur > 1) {
                        parkHeldPhoton(rnx + ox, rny + oy, rnz + oz, cur, t, oldMax,
                                axis, sign, newTallyBits, lastAxis, lastSign, lightBlockId, subtract);
                    }
                    continue;
                }

                // Read the block ID directly from the chunk pool (no second bounds
                // check or indirection-table hop), then look up its opacity in the
                // flat array.
                int tgtBlockId = chunkPool[(nSlot << 12) | ((rnx & 15) | ((rny & 15) << 4) | ((rnz & 15) << 8))] & 0xFFFF;
                int opacity = (tgtBlockId > 0 && tgtBlockId < opacityArr.length) ? opacityArr[tgtBlockId] : 0;

                int next = cur - distIncrease - opacity;
                // Reversing along the last-used axis halves the brightness.
                if (lastAxis != AXIS_NONE && lastAxis == axis && lastSign != sign) {
                    next >>= 1;
                }
                next = Math.max(0, next);
                if (next <= 0) continue;

                int nidx = (nSlot << 12) | ((rnx & 15) | ((rny & 15) << 4) | ((rnz & 15) << 8));
                int existing = tempField[nidx] & 0xFF;
                int next255 = next * 17;

                if (next255 > existing) {
                    tempField[nidx] = (byte) next255;
                    dirtySlots.add(nSlot);
                    if (next > 1) {
                        queue.add(packNodeTracked(rnx, rny, rnz, next, axis, sign, newTallyBits));
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
        int lightColor = blockDataManager.getLightColorFast(blockId);
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

                // Tinted contribution, scaled by LIGHT_TINT_HEADROOM so
                // overlapping sources add without saturating a channel. The
                // tint ratio (hue) is unchanged — only the magnitude shrinks.
                // Round the headroom-scaled channel instead of truncating it.
                // A full 255 channel divided by four is 63.75; truncation made
                // a maximum light top out at 252 after the shader restored the
                // headroom, subtly lowering the bright endpoint.
                int cr = Math.round(level * tr / (15.0f * LIGHT_TINT_HEADROOM));
                int cg = Math.round(level * tg / (15.0f * LIGHT_TINT_HEADROOM));
                int cb = Math.round(level * tb / (15.0f * LIGHT_TINT_HEADROOM));

                int current = mainPool[idx];
                int curR = (current >>> 8) & 0xFF;
                int curG = (current >>> 16) & 0xFF;
                int curB = (current >>> 24) & 0xFF;

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
                    // Mask every channel before shifting so a value can never
                    // overflow past 8 bits and bleed into a neighbouring channel.
                    mainPool[idx] = (current & 0xFF) // preserve sky
                        | ((newR & 0xFF) << 8)
                        | ((newG & 0xFF) << 16)
                        | ((newB & 0xFF) << 24);
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
    //  HELD PHOTONS — light that waits at unloaded chunk boundaries
    // ══════════════════════════════════════════════════════════════════

    /** A block-light photon parked at an unloaded chunk boundary (see floodFillScalar). */
    private static final class HeldPhoton {
        final int tx, ty, tz;        // target cell (world coords) the photon wanted to enter
        final int cur;               // arrival intensity before the target cell's opacity
        final int t;                 // tally of the step direction on the parent node
        final int oldMax;            // parent's Chebyshev extent (for distIncrease)
        final int axis, sign;        // step direction taken toward the target
        final long newTallyBits;     // parent tally with the step counted (resumes Chebyshev tracking)
        final int lastAxis, lastSign;// parent node's direction (for reversal halving)
        final int blockId;           // light source type (tint on resume)
        final boolean subtract;      // true = source was removed; resume must SUBTRACT

        HeldPhoton(int tx, int ty, int tz, int cur, int t, int oldMax,
                   int axis, int sign, long newTallyBits,
                   int lastAxis, int lastSign, int blockId, boolean subtract) {
            this.tx = tx; this.ty = ty; this.tz = tz;
            this.cur = cur;
            this.t = t; this.oldMax = oldMax;
            this.axis = axis; this.sign = sign;
            this.newTallyBits = newTallyBits;
            this.lastAxis = lastAxis; this.lastSign = lastSign;
            this.blockId = blockId; this.subtract = subtract;
        }
    }

    /** A sky-light photon parked at an unloaded chunk boundary (see skyFanBFS). */
    private static final class HeldSkyPhoton {
        final int tx, ty, tz;        // target cell (world coords)
        final int px, py, pz;        // parent cell (world coords) — for the ring-2 mid-cell check
        final int next;              // decayed arrival intensity (before the target's opacity)
        final int cheb, dx, dz;      // fan hop metadata

        HeldSkyPhoton(int tx, int ty, int tz, int px, int py, int pz, int next, int cheb, int dx, int dz) {
            this.tx = tx; this.ty = ty; this.tz = tz;
            this.px = px; this.py = py; this.pz = pz;
            this.next = next;
            this.cheb = cheb; this.dx = dx; this.dz = dz;
        }
    }

    private void parkHeldPhoton(int tx, int ty, int tz, int cur, int t, int oldMax,
                                int axis, int sign, long newTallyBits,
                                int lastAxis, int lastSign, int blockId, boolean subtract) {
        synchronized (heldLock) {
            if (heldPhotons.size() >= MAX_HELD_PHOTONS) return; // safety cap
            heldPhotons.add(new HeldPhoton(tx, ty, tz, cur, t, oldMax, axis, sign,
                    newTallyBits, lastAxis, lastSign, blockId, subtract));
        }
    }

    private void parkSkyPhoton(int tx, int ty, int tz, int px, int py, int pz,
                               int next, int cheb, int dx, int dz) {
        synchronized (heldLock) {
            if (heldSkyPhotons.size() >= MAX_HELD_SKY_PHOTONS) return; // cap: see above
            heldSkyPhotons.add(new HeldSkyPhoton(tx, ty, tz, px, py, pz, next, cheb, dx, dz));
        }
    }

    /** Drops all held photons (used before a full rebuild — its fresh floods re-park). */
    public void clearHeldPhotons() {
        synchronized (heldLock) {
            heldPhotons.clear();
            heldSkyPhotons.clear();
        }
    }

    /** Number of block-light photons currently parked at unloaded chunk boundaries. */
    public int heldPhotonCount() {
        synchronized (heldLock) { return heldPhotons.size(); }
    }

    /** Number of sky-light photons currently parked at unloaded chunk boundaries. */
    public int heldSkyPhotonCount() {
        synchronized (heldLock) { return heldSkyPhotons.size(); }
    }

    /**
     * Resumes light propagation that was parked at unloaded chunk boundaries.
     * Called after a chunk (re)loads: every held photon whose target cell is now
     * loaded re-enters the flood with its exact saved state (intensity, direction
     * tally, tint type) and continues from there. Photons whose target is still
     * unloaded stay parked; photons the buffer has moved away from are dropped.
     *
     * Block-light photons run the same scalar BFS + tint pipeline as a fresh
     * flood, so held light behaves identically to light propagated normally
     * (a removed source parks subtract-photons that cancel the add — net zero).
     * Sky-light photons re-enter the horizontal fan BFS; the column's own
     * vertical sweep already ran, and the max-based field means the fan only
     * ever brightens (it never fights the vertical result).
     *
     * @return Set of dirty slot indices
     */
    public Set<Integer> resumeHeldPhotons() {
        Set<Integer> dirty = new HashSet<>();
        java.util.List<HeldPhoton> blockPending;
        java.util.List<HeldSkyPhoton> skyPending;
        synchronized (heldLock) {
            if (heldPhotons.isEmpty() && heldSkyPhotons.isEmpty()) return dirty;
            blockPending = new java.util.ArrayList<>(heldPhotons);
            skyPending = new java.util.ArrayList<>(heldSkyPhotons);
            heldPhotons.clear();
            heldSkyPhotons.clear();
        }

        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();
        int maxRel = bufSize - 1;
        int[] indirectionTable = world.getIndirectionTable();
        int[] chunkPool = world.getChunkPool();
        int[] opacityArr = blockDataManager.getOpacityArray();
        java.util.List<HeldPhoton> blockStillWaiting = new java.util.ArrayList<>();
        java.util.List<HeldSkyPhoton> skyStillWaiting = new java.util.ArrayList<>();

        // ── Block light: group by (blockId, subtract) so one flood+tint pass ──
        // ── serves all photons of a type (same batching as the live floods).  ──
        java.util.Map<Long, java.util.List<HeldPhoton>> byType = new java.util.LinkedHashMap<>();
        for (HeldPhoton p : blockPending) {
            long key = ((long) p.blockId << 1) | (p.subtract ? 1 : 0);
            byType.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(p);
        }

        for (java.util.Map.Entry<Long, java.util.List<HeldPhoton>> e : byType.entrySet()) {
            int blockId = (int) (e.getKey() >> 1);
            boolean subtract = (e.getKey() & 1) == 1;
            java.util.List<HeldPhoton> group = e.getValue();

            LongQueue queue = new LongQueue(256);
            Set<Integer> typeDirty = new HashSet<>();
            // Seeds: {packed resumed node, target slot}. The node already carries
            // the target coords + intensity + direction state for the continuing
            // flood — exactly what the live BFS would have enqueued.
            java.util.List<long[]> seeds = new java.util.ArrayList<>();
            java.util.Set<Integer> touchedSlots = new java.util.HashSet<>();

            for (HeldPhoton p : group) {
                int rx = p.tx - ox, ry = p.ty - oy, rz = p.tz - oz;
                if (rx < 0 || ry < 0 || rz < 0 || rx > maxRel || ry > maxRel || rz > maxRel) {
                    continue; // buffer moved away from the target — photon lost
                }
                int nSlot = indirectionTable[(rx >> 4) + (ry >> 4) * World.REGION_SIZE + (rz >> 4) * World.REGION_SIZE * World.REGION_SIZE];
                if (nSlot == World.EMPTY) {
                    blockStillWaiting.add(p); // chunk still not loaded — keep holding
                    continue;
                }

                // The target cell is now loaded: apply the decay the live flood
                // would have applied when entering it (opacity + Chebyshev
                // growth + reversal halving), then write and continue.
                int tgtBlockId = chunkPool[(nSlot << 12) | ((rx & 15) | ((ry & 15) << 4) | ((rz & 15) << 8))] & 0xFFFF;
                int opacity = (tgtBlockId > 0 && tgtBlockId < opacityArr.length) ? opacityArr[tgtBlockId] : 0;
                int distIncrease = (p.t == p.oldMax) ? 1 : 0;
                int next = p.cur - distIncrease - opacity;
                if (p.lastAxis != AXIS_NONE && p.lastAxis == p.axis && p.lastSign != p.sign) {
                    next >>= 1;
                }
                next = Math.max(0, next);
                if (next <= 0) continue; // decayed to nothing — drop

                seeds.add(new long[]{packNodeTracked(rx, ry, rz, next, p.axis, p.sign, p.newTallyBits), nSlot});
                touchedSlots.add(nSlot);
            }

            if (!seeds.isEmpty()) {
                // Temp field for freshly-loaded slots may hold stale bytes from a
                // recycled slot index — clean before seeding so writes land.
                for (int s : touchedSlots) clearTempFieldSlot(s);
                for (long[] seed : seeds) {
                    int slot = (int) seed[1];
                    long node = seed[0];
                    int nidx = (slot << 12) | ((nodeX(node) & 15) | ((nodeY(node) & 15) << 4) | ((nodeZ(node) & 15) << 8));
                    int next255 = nodeIntensityScalar(node) * 17;
                    if (next255 > (tempField[nidx] & 0xFF)) {
                        tempField[nidx] = (byte) next255;
                        typeDirty.add(slot);
                    }
                    if (nodeIntensityScalar(node) > 1) queue.add(node);
                }
                floodFillScalar(queue, ox, oy, oz, typeDirty, blockId, subtract);
                applyTintToMain(blockId, !subtract, typeDirty);
                dirty.addAll(typeDirty);
            }
        }

        // ── Sky light: re-enter the horizontal fan BFS ──
        LongQueue skyQueue = new LongQueue(256);
        Set<Integer> skyDirty = new HashSet<>();
        for (HeldSkyPhoton p : skyPending) {
            int rx = p.tx - ox, ry = p.ty - oy, rz = p.tz - oz;
            if (rx < 0 || ry < 0 || rz < 0 || rx > maxRel || ry > maxRel || rz > maxRel) continue;
            int nSlot = indirectionTable[(rx >> 4) + (ry >> 4) * World.REGION_SIZE + (rz >> 4) * World.REGION_SIZE * World.REGION_SIZE];
            if (nSlot == World.EMPTY) {
                skyStillWaiting.add(p); // still unloaded — keep holding
                continue;
            }

            // Same blocking rule as the live fan: ring-2 cardinal hops need the
            // intermediate cell passable.
            if (p.cheb == 2 && (p.dx == 0 || p.dz == 0)) {
                int midX = p.px + p.dx / 2, midZ = p.pz + p.dz / 2;
                if (getBlockOpacity(world.getVoxel(midX, p.py, midZ)) >= MAX_LIGHT) continue;
            }
            int tgtBlockId = chunkPool[(nSlot << 12) | ((rx & 15) | ((ry & 15) << 4) | ((rz & 15) << 8))] & 0xFFFF;
            int opacity = (tgtBlockId > 0 && tgtBlockId < opacityArr.length) ? opacityArr[tgtBlockId] : 0;
            int actNext = Math.max(0, p.next - opacity);
            if (actNext <= 0) continue;

            int lx = rx & 15, ly = ry & 15, lz = rz & 15;
            int existing = world.getSkyLight(nSlot, lx, ly, lz) / 17;
            if (actNext > existing) {
                world.setSkyLight(nSlot, lx, ly, lz, actNext * 17);
                skyDirty.add(nSlot);
                if (actNext > 1) skyQueue.add(packNodeScalar(rx, ry, rz, actNext));
            }
        }
        if (!skyDirty.isEmpty()) {
            skyFanBFS(skyQueue, ox, oy, oz, skyDirty);
            dirty.addAll(skyDirty);
        }

        // Re-park photons whose target is STILL unloaded (the chunk that just
        // loaded may not be the one they were waiting for).
        synchronized (heldLock) {
            heldPhotons.addAll(blockStillWaiting);
            heldSkyPhotons.addAll(skyStillWaiting);
        }
        return dirty;
    }

    // ══════════════════════════════════════════════════════════════════
    //  SINGLE-SOURCE BFS — for runtime block place/break
    // ══════════════════════════════════════════════════════════════════

    /**
     * Computes the scalar intensity field for a single light source at (x,y,z)
     * into the temp field. Used for runtime block changes where only one source
     * is added or removed. Photons that reach an unloaded chunk boundary are
     * parked for {@link #resumeHeldPhotons()}.
     *
     * @param blockId  Source block ID (tint type for parked photons)
     * @param subtract true when the source was removed (parked photons subtract on resume)
     * @return Set of slot indices that received temp field writes
     */
    public Set<Integer> computeSingleSourceContribution(int x, int y, int z, int intensity,
                                                        int blockId, boolean subtract) {
        Set<Integer> dirtySlots = new HashSet<>();
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();

        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return dirtySlots;

        int lx = x & 15, ly = y & 15, lz = z & 15;
        int idx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        tempField[idx] = (byte) (intensity * 17);
        dirtySlots.add(slot);

        LongQueue queue = new LongQueue(256);
        // Convert to buffer-relative for packing (avoids 11-bit overflow at extreme coords)
        int rx = x - ox, ry = y - oy, rz = z - oz;
        queue.add(packNodeScalar(rx, ry, rz, intensity));

        floodFillScalar(queue, ox, oy, oz, dirtySlots, blockId, subtract);

        WorldGenLogger.logPos("LIGHT_SINGLE_SRC", x, y, z,
            "intensity=" + intensity + " dirty=" + dirtySlots.size());
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
        int[] chunkPool = world.getChunkPool();
        int[] emissiveArr = blockDataManager.getEmissiveArray();
        int[] lightColorArr = blockDataManager.getLightColorArray();

        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int wx = worldBaseX + lx;
                    int wy = worldBaseY + ly;
                    int wz = worldBaseZ + lz;

                    int blockId = chunkPool[(slot << 12) | (lx | (ly << 4) | (lz << 8))] & 0xFFFF;
                    int emissive = (blockId > 0 && blockId < emissiveArr.length) ? emissiveArr[blockId] : 0;
                    if (emissive <= 0) continue;

                    int lightColor = (blockId < lightColorArr.length) ? lightColorArr[blockId] : 0xFFFFFF;
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

            WorldGenLogger.logChunk("LIGHT_BLOCK", cx, cy, cz,
                "sources=" + entry.getValue().size() + " emissive=" + emissive
                + " color=#" + Integer.toHexString(lightColor));

            // Clear temp field for this slot
            clearTempFieldSlot(slot);

            // Seed all sources of this type (use buffer-relative coords for packing)
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
                // Convert to buffer-relative for BFS (avoids 11-bit overflow at extreme coords)
                int rsx = sx - ox, rsy = sy - oy, rsz = sz - oz;
                queue.add(packNodeScalar(rsx, rsy, rsz, intensity));
            }

            // Flood-fill (photons hitting unloaded chunks are parked and resumed
            // when those chunks load)
            floodFillScalar(queue, ox, oy, oz, typeDirty, blockId, false);

            // Tint and add to main pool
            applyTintToMain(blockId, true, typeDirty);
            dirtySlots.addAll(typeDirty);
        }

        return dirtySlots;
    }

    /**
     * Block-light pass over a set of columns (chunkKey -> section slots).
     * Collects every emissive source across ALL given sections, groups them by
     * light type, then floods each type exactly once. This replaces the
     * per-section batching which re-flooded overlapping volume and — for a dense
     * field of same-type sources spanning sections (a nether lava lake) — summed
     * the light twice. One flood per type is both faster and more correct.
     */
    public Set<Integer> propagateBlockLightRegion(java.util.Map<Long, java.util.NavigableMap<Integer, Integer>> columns) {
        Set<Integer> dirtySlots = new HashSet<>();
        int ox = world.getOffsetX(), oy = world.getOffsetY(), oz = world.getOffsetZ();

        // Clean the temp field for every source section so stale values from an
        // earlier pass can't short-circuit this flood.
        for (java.util.NavigableMap<Integer, Integer> slots : columns.values()) {
            for (int slot : slots.values()) {
                if (slot != World.EMPTY) clearTempFieldSlot(slot);
            }
        }

        // Phase 1: collect sources grouped by type across the whole region.
        java.util.Map<Integer, java.util.List<int[]>> sourcesByType = new java.util.HashMap<>();
        int[] chunkPool = world.getChunkPool();
        int[] emissiveArr = blockDataManager.getEmissiveArray();
        int[] lightColorArr = blockDataManager.getLightColorArray();
        for (java.util.Map.Entry<Long, java.util.NavigableMap<Integer, Integer>> column : columns.entrySet()) {
            long key = column.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            int worldBaseX = cx << 4;
            int worldBaseZ = cz << 4;
            for (java.util.Map.Entry<Integer, Integer> se : column.getValue().entrySet()) {
                int cy = se.getKey();
                int slot = se.getValue();
                int worldBaseY = cy << 4;
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            int wx = worldBaseX + lx;
                            int wy = worldBaseY + ly;
                            int wz = worldBaseZ + lz;
                            int blockId = chunkPool[(slot << 12) | (lx | (ly << 4) | (lz << 8))] & 0xFFFF;
                            int emissive = (blockId > 0 && blockId < emissiveArr.length) ? emissiveArr[blockId] : 0;
                            if (emissive <= 0) continue;
                            int lightColor = (blockId < lightColorArr.length) ? lightColorArr[blockId] : 0xFFFFFF;
                            int typeKey = (emissive << 24) | (lightColor & 0xFFFFFF);
                            int intensity = Math.min(emissive, 15);
                            sourcesByType.computeIfAbsent(typeKey, k -> new java.util.ArrayList<>())
                                .add(new int[]{wx, wy, wz, intensity, blockId});
                        }
                    }
                }
            }
        }

        if (sourcesByType.isEmpty()) return dirtySlots;

        // Phase 2: flood + tint once per type.
        for (java.util.Map.Entry<Integer, java.util.List<int[]>> entry : sourcesByType.entrySet()) {
            int blockId = entry.getValue().get(0)[4];
            int typeKey = entry.getKey();
            WorldGenLogger.logChunk("LIGHT_BLOCK_REGION", 0, 0, 0,
                "sources=" + entry.getValue().size() + " type=#" + Integer.toHexString(typeKey));

            LongQueue queue = new LongQueue(256);
            Set<Integer> typeDirty = new HashSet<>();
            for (int[] src : entry.getValue()) {
                int sx = src[0], sy = src[1], sz = src[2];
                int intensity = src[3];

                int slot = getSlotForWorldPos(sx, sy, sz, ox, oy, oz);
                if (slot == World.EMPTY) continue;
                int slx = sx & 15, sly = sy & 15, slz = sz & 15;
                int sidx = (slot << 12) | (slx | (sly << 4) | (slz << 8));
                int existing = tempField[sidx] & 0xFF;
                int intensity255 = intensity * 17;
                if (intensity255 > existing) {
                    tempField[sidx] = (byte) intensity255;
                    typeDirty.add(slot);
                }
                int rsx = sx - ox, rsy = sy - oy, rsz = sz - oz;
                queue.add(packNodeScalar(rsx, rsy, rsz, intensity));
            }

            floodFillScalar(queue, ox, oy, oz, typeDirty, blockId, false);
            applyTintToMain(blockId, true, typeDirty);
            dirtySlots.addAll(typeDirty);
        }

        return dirtySlots;
    }

    /** Single-column convenience wrapper for {@link #propagateBlockLightRegion}. */
    public Set<Integer> propagateBlockLightColumn(int cx, int cz, java.util.NavigableMap<Integer, Integer> slots) {
        java.util.Map<Long, java.util.NavigableMap<Integer, Integer>> one = new java.util.HashMap<>(1);
        one.put(((long) cx << 32) | (cz & 0xFFFFFFFFL), slots);
        return propagateBlockLightRegion(one);
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
    public Set<Integer> onBlockChanged(int x, int y, int z, int oldBlockId, Set<Integer> lightPending) {
        Set<Integer> dirtySlots = new HashSet<>();
        int newBlockId = world.getVoxel(x, y, z);
        int oldEmissive = blockDataManager.getEmissive(oldBlockId);
        int newEmissive = blockDataManager.getEmissive(newBlockId);

        WorldGenLogger.logPos("LIGHT_CHANGE", x, y, z,
            "old=" + oldBlockId + "(" + blockDataManager.getName(oldBlockId) + ") emiss=" + oldEmissive
            + " new=" + newBlockId + "(" + blockDataManager.getName(newBlockId) + ") emiss=" + newEmissive);

        if (oldEmissive > 0 || newEmissive > 0) {
            // ── Light source changed: single-source add/subtract ──
            if (oldEmissive > 0) {
                int intensity = Math.min(oldEmissive, 15);
                Set<Integer> contrib = computeSingleSourceContribution(x, y, z, intensity, oldBlockId, true);
                lightPending.addAll(contrib);
                applyTintToMain(oldBlockId, false, contrib);
                dirtySlots.addAll(contrib);
            }
            if (newEmissive > 0) {
                int intensity = Math.min(newEmissive, 15);
                Set<Integer> contrib = computeSingleSourceContribution(x, y, z, intensity, newBlockId, false);
                lightPending.addAll(contrib);
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
                            // Mark pending BEFORE zeroing the pool: never leave a
                            // window where the pool already reads as zeros but the
                            // render thread would still upload it.
                            lightPending.add(slot);
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
            // The 3×3 area in X/Z covers 9 unique chunk columns.
            java.util.Set<Long> columnsDone = new java.util.HashSet<>();
            for (int dcx = -1; dcx <= 1; dcx++) {
                for (int dcz = -1; dcz <= 1; dcz++) {
                    int colCX = cx + dcx;
                    int colCZ = cz + dcz;
                    long colKey = ((long) colCX << 32) | (colCZ & 0xFFFFFFFFL);
                    if (!columnsDone.add(colKey)) continue;

                    java.util.NavigableMap<Integer, Integer> colSlots = new java.util.TreeMap<>();
                    boolean anyLoaded = false;
                    // Scan all possible Y sections in the buffer for this column
                    int bufMinY = oy >> 4;
                    int bufMaxY = bufMinY + World.REGION_SIZE;
                    for (int scy = bufMinY; scy < bufMaxY; scy++) {
                        int slot = getSlotForSection(colCX, scy, colCZ, ox, oy, oz);
                        if (slot != World.EMPTY) {
                            colSlots.put(scy, slot);
                            anyLoaded = true;
                        }
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
    public Set<Integer> rebuildAllLighting(java.util.Map<Long, java.util.NavigableMap<Integer, Integer>> loadedChunks) {
        Set<Integer> allDirty = new HashSet<>();

        // Held photons describe light from sources that may no longer exist —
        // drop them; the rebuild's fresh floods re-park from current state.
        clearHeldPhotons();

        // Phase 1: Clear all light in loaded chunks
        for (java.util.NavigableMap<Integer, Integer> slots : loadedChunks.values()) {
            for (int slot : slots.values()) {
                world.clearLightPoolSlot(slot);
            }
        }

        // Phase 2: Sky light for all columns
        GameLogger.log("LIGHT Sky light generation...");
        int colDone = 0;
        int totalCols = loadedChunks.size();
        for (java.util.Map.Entry<Long, java.util.NavigableMap<Integer, Integer>> entry : loadedChunks.entrySet()) {
            long key = entry.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Set<Integer> dirty = generateSkyLight(cx, cz, entry.getValue());
            allDirty.addAll(dirty);
            colDone++;
            if (colDone % 50 == 0) {
                System.out.print("\r  Sky light: " + colDone + "/" + totalCols + " columns");
            }
        }
        System.out.println("\r  Sky light: " + colDone + "/" + totalCols + " columns done");

        // Phase 3: Block light — one global pass grouped by light type. Flooding
        // each type once across the whole loaded region (instead of once per
        // section) keeps dense source fields (nether lava lakes) from re-flooding
        // overlapping volume or double-counting same-type light.
        GameLogger.log("LIGHT Block light propagation (global by type)...");
        long blockT0 = System.currentTimeMillis();
        Set<Integer> blockDirty = propagateBlockLightRegion(loadedChunks);
        allDirty.addAll(blockDirty);
        System.out.println("\r  Block light: " + blockDirty.size() + " dirty slots in "
            + (System.currentTimeMillis() - blockT0) + "ms");

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
        return id > 0 && blockDataManager.isFullBlockFast(id);
    }

    // ══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════

    private int getBlockOpacity(int blockId) {
        if (blockId <= 0) return 0;
        return blockDataManager.getOpacityFast(blockId);
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
