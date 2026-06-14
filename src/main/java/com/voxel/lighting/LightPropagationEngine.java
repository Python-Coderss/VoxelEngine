package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.Direction;

import java.util.*;

/**
 * 14-Directional Occlusion Baker + Per-Source BFS Lighting Engine.
 * Each light source gets its own small deterministic BFS.
 * Results accumulate additively in the light pool.
 * Removal subtracts the same deterministic BFS result.
 */
public class LightPropagationEngine {
    private final World world;
    private final BlockDataManager blockDataManager;

    public static final float[][] OCC_DIRS = {
        {0.0f, 1.0f, 0.0f},
        {0.707f, 0.707f, 0.0f}, {-0.707f, 0.707f, 0.0f}, {0.0f, 0.707f, 0.707f}, {0.0f, 0.707f, -0.707f},
        {0.5f, 0.707f, 0.5f}, {-0.5f, 0.707f, 0.5f}, {0.5f, 0.707f, -0.5f}, {-0.5f, 0.707f, -0.5f},
        {0.866f, 0.5f, 0.0f}, {-0.866f, 0.5f, 0.0f}, {0.0f, 0.5f, 0.866f}, {0.0f, 0.5f, -0.866f},
        {0.0f, 0.3f, 0.0f}
    };

    public LightPropagationEngine(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
    }

    // --- LongQueue (ring buffer) ---
    private static class LongQueue {
        private long[] array;
        private int head, tail, size, mask;
        LongQueue(int capacity) {
            int cap = 1; while (cap < capacity) cap <<= 1;
            array = new long[cap]; mask = cap - 1;
        }
        void offer(long v) {
            if (size == array.length) {
                long[] na = new long[array.length << 1];
                for (int i = 0; i < size; i++) na[i] = array[(head + i) & mask];
                array = na; mask = array.length - 1; head = 0; tail = size;
            }
            array[tail] = v; tail = (tail + 1) & mask; size++;
        }
        long poll() { long v = array[head]; head = (head + 1) & mask; size--; return v; }
        boolean isEmpty() { return size == 0; }
    }

    // --- MergeMap (open-addressing) ---
    private static class MergeMap {
        private static final long EMPTY = -1L;
        private long[] keys, values;
        private int[] usedIndices;
        private int usedCount;
        private long[] iterKeys;
        private int iterCount;
        private int mask;

        MergeMap(int capacity) {
            int cap = 1; while (cap < capacity) cap <<= 1;
            int ic = 1; while (ic < capacity) ic <<= 1;
            keys = new long[cap]; values = new long[cap];
            usedIndices = new int[cap];
            iterKeys = new long[ic];
            Arrays.fill(keys, EMPTY);
            mask = cap - 1;
        }

        private int hash(long key) { long h = key * 0x9E3779B97F4A7C15L; return (int)(h ^ (h >>> 33)); }

        long get(long key) {
            int idx = hash(key) & mask;
            while (keys[idx] != EMPTY) { if (keys[idx] == key) return values[idx]; idx = (idx + 1) & mask; }
            return EMPTY;
        }
        boolean contains(long key) { return get(key) != EMPTY; }

        void put(long key, long value) {
            // iterKeys grows independently: iterCount can exceed hash occupancy
            if (iterCount >= iterKeys.length) {
                long[] ni = new long[iterKeys.length << 1];
                System.arraycopy(iterKeys, 0, ni, 0, iterCount);
                iterKeys = ni;
            }
            if (usedCount >= (mask >> 1)) grow();
            int idx = hash(key) & mask;
            while (keys[idx] != EMPTY && keys[idx] != key) idx = (idx + 1) & mask;
            if (keys[idx] == EMPTY) {
                keys[idx] = key;
                usedIndices[usedCount++] = idx;
                iterKeys[iterCount++] = key;
            }
            values[idx] = value;
        }

        long getByIter(int i) { return get(iterKeys[i]); }
        int iterCount() { return iterCount; }

        void clear() {
            for (int i = 0; i < usedCount; i++) keys[usedIndices[i]] = EMPTY;
            usedCount = 0; iterCount = 0;
        }

        private void grow() {
            long[] ok = keys, ov = values;
            int oc = mask + 1, nc = oc << 1;
            keys = new long[nc]; values = new long[nc];
            usedIndices = new int[nc];
            Arrays.fill(keys, EMPTY);
            mask = nc - 1; usedCount = 0;
            for (int i = 0; i < oc; i++) {
                if (ok[i] != EMPTY) {
                    int idx = hash(ok[i]) & mask;
                    while (keys[idx] != EMPTY) idx = (idx + 1) & mask;
                    keys[idx] = ok[i]; values[idx] = ov[i];
                    usedIndices[usedCount++] = idx;
                }
            }
        }
    }

    // --- OCCLUSION BAKER ---

    public void bakeChunkOcclusion(int slot, int cx, int cy, int cz) {
        short[] occPool = world.getOcclusionPool();
        int baseIdx = slot << 12;
        for (int ly = 15; ly >= 0; ly--) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int wx = (cx << 4) + lx, wy = (cy << 4) + ly, wz = (cz << 4) + lz;
                    int idx = baseIdx | (lx | (ly << 4) | (lz << 8));
                    if (isBlocked(wx, wy, wz)) { occPool[idx] = 0; continue; }
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
            if (ny >= 2048) return true;
            if (isBlocked(nx, ny, nz)) return false;
        }
        return true;
    }

    private boolean isBlocked(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= 2048 || y >= 2048 || z >= 2048) return false;
        int id = world.getVoxel(x, y, z);
        return id > 0 && blockDataManager.isFullBlock(id);
    }

    // --- PER-SOURCE BFS ---

    private static final float STEP_ATTENUATION = 0.80f;  // was 0.85 — faster falloff = fewer BFS waves
    private static final int MAX_CHUNK_RADIUS = 10;  // was 15 — smaller radius = less work per source

    // Pre-allocated Direction array to avoid allocation in hot loop
    private static final Direction[] DIRS = Direction.values();

    /**
     * Run deterministic BFS from a single light source.
     * add=true: accumulate into lightPool (clamped add).
     * add=false: subtract from lightPool (clamped to 0).
     * @return set of chunk slot indices that changed
     */
    public Set<Integer> runSingleSourceBFS(LightSource src, int[] lightPool, int[] indirectionTable, boolean add) {
        int sx = (int)src.position.x, sy = (int)src.position.y, sz = (int)src.position.z;
        if (add && isSurrounded(sx, sy, sz)) return Collections.emptySet();

        int scx = sx >> 4, scy = sy >> 4, scz = sz >> 4; // source chunk coords for radius limit

        int[] chunkPool = world.getChunkPool();
        int bmx = world.getOffsetX(), bmy = world.getOffsetY(), bmz = world.getOffsetZ();
        int bMx = bmx + 2048, bMy = bmy + 2048, bMz = bmz + 2048;

        int r = (int)(src.color.x * src.intensity);
        int g = (int)(src.color.y * src.intensity);
        int b = (int)(src.color.z * src.intensity);
        if (r < 1 && g < 1 && b < 1) return Collections.emptySet();
        if (sx < bmx || sy < bmy || sz < bmz || sx >= bMx || sy >= bMy || sz >= bMz) return Collections.emptySet();

        Set<Integer> affected = new HashSet<>();
        int ss = getSlot(sx, sy, sz, indirectionTable);
        if (ss == -1) return Collections.emptySet();
        if (updateVoxelLight(sx, sy, sz, r, g, b, ss, lightPool, add)) affected.add(ss);

        LongQueue cw = new LongQueue(512), nw = new LongQueue(512);
        MergeMap mm = new MergeMap(512);
        cw.offer(packNode(sx, sy, sz, 13, r, g, b));

        while (!cw.isEmpty()) {
            while (!cw.isEmpty()) {
                long node = cw.poll();
            
                int cx = unpackX(node), cy = unpackY(node), cz = unpackZ(node);
                int state = unpackState(node);
                int cr = unpackR(node), cg = unpackG(node), cb = unpackB(node);
                int nr = (int)(cr * STEP_ATTENUATION), ng = (int)(cg * STEP_ATTENUATION), nb = (int)(cb * STEP_ATTENUATION);
                if (nr < 1 && ng < 1 && nb < 1) continue;
                int tx = state / 9, ty = (state / 3) % 3, tz = state % 3;
                int tritX = tx - 1, tritY = ty - 1, tritZ = tz - 1;

                // Use static array to avoid Direction.values() allocation per BFS wave
                for (Direction dir : DIRS) {
                    if (tritX != 0 && dir.x == -tritX) continue;
                    if (tritY != 0 && dir.y == -tritY) continue;
                    if (tritZ != 0 && dir.z == -tritZ) continue;

                    int nx = cx + dir.x, ny = cy + dir.y, nz = cz + dir.z;
                    // Chunk-radius limit: skip if neighbor is too far from source
                    int ncx = nx >> 4, ncy = ny >> 4, ncz = nz >> 4;
                    if (Math.abs(ncx - scx) > MAX_CHUNK_RADIUS || Math.abs(ncy - scy) > MAX_CHUNK_RADIUS || Math.abs(ncz - scz) > MAX_CHUNK_RADIUS) continue;
                    if (nx < bmx || ny < bmy || nz < bmz || nx >= bMx || ny >= bMy || nz >= bMz) continue;
                    int rnx = nx - bmx, rny = ny - bmy, rnz = nz - bmz;
                    int tableIdx = (rnx >> 4) + (rny >> 4) * 128 + (rnz >> 4) * 16384;
                    int slot = indirectionTable[tableIdx];
                    if (slot == -1) continue;
                    int bid = chunkPool[(slot << 12) | ((rnx & 15) | ((rny & 15) << 4) | ((rnz & 15) << 8))] & 0xFFFF;
                    if (bid > 0 && blockDataManager.isFullBlock(bid)) continue;

                    int ntx = (dir.x != 0) ? ((dir.x == -1) ? 0 : 2) : tx;
                    int nty = (dir.y != 0) ? ((dir.y == -1) ? 0 : 2) : ty;
                    int ntz = (dir.z != 0) ? ((dir.z == -1) ? 0 : 2) : tz;
                    int ns = ntx * 9 + nty * 3 + ntz;
                    long pk = posKey(nx, ny, nz);

                    if (mm.contains(pk)) {
                        long ex = mm.get(pk);
                        int es = unpackState(ex), er = unpackR(ex), eg = unpackG(ex), eb = unpackB(ex);
                        int ms = mergeAxisStates(ns, es);
                        mm.put(pk, packNode(nx, ny, nz, ms, nr > er ? nr : er, ng > eg ? ng : eg, nb > eb ? nb : eb));
                    } else {
                        mm.put(pk, packNode(nx, ny, nz, ns, nr, ng, nb));
                    }
                }
            }
            for (int i = 0; i < mm.iterCount(); i++) {
                long node = mm.getByIter(i);
                int x = unpackX(node), y = unpackY(node), z = unpackZ(node);
                int slot = getSlot(x, y, z, indirectionTable);
                if (slot == -1) continue;
                if (updateVoxelLight(x, y, z, unpackR(node), unpackG(node), unpackB(node), slot, lightPool, add)) {
                    nw.offer(node); affected.add(slot);
                }
            }
            mm.clear();
            LongQueue tmp = cw; cw = nw; nw = tmp;
        }
        return affected;
    }

    public boolean isSurrounded(int x, int y, int z) {
        int[] chunkPool = world.getChunkPool();
        int[] indirection = world.getIndirectionTable();
        int bmx = world.getOffsetX(), bmy = world.getOffsetY(), bmz = world.getOffsetZ();
        int bMx = bmx + 2048, bMy = bmy + 2048, bMz = bmz + 2048;
        for (Direction dir : DIRS) {
            int nx = x + dir.x, ny = y + dir.y, nz = z + dir.z;
            if (nx < bmx || ny < bmy || nz < bmz || nx >= bMx || ny >= bMy || nz >= bMz) return false;
            int rnx = nx - bmx, rny = ny - bmy, rnz = nz - bmz;
            int slot = indirection[(rnx >> 4) + (rny >> 4) * 128 + (rnz >> 4) * 16384];
            if (slot == -1) return false;
            int bid = chunkPool[(slot << 12) | ((rnx & 15) | ((rny & 15) << 4) | ((rnz & 15) << 8))] & 0xFFFF;
            if (bid == 0 || (!blockDataManager.isFullBlock(bid) && !blockDataManager.isEmissive(bid))) return false;
        }
        return true;
    }

    public int propagateAllLights(List<LightSource> sources) {
        long start = System.currentTimeMillis();
        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        List<LightSource> bl = new ArrayList<>();
        for (LightSource s : sources) { if (s.type != LightType.SUN) bl.add(s); }
        int done = 0, skipped = 0;
        Set<Integer> all = new HashSet<>();
        for (LightSource src : bl) {
            Set<Integer> af = runSingleSourceBFS(src, lp, it, true);
            if (!af.isEmpty()) { done++; all.addAll(af); }
            else skipped++;
        }
        long end = System.currentTimeMillis();
        System.out.println("Lighting: " + done + " sources" + (skipped > 0 ? " (" + skipped + " skipped)" : "") + ", " + all.size() + " slots, " + (end - start) + "ms");
        return done;
    }

    /**
     * Runs per-source BFS with progress bar. Same as propagateAllLights but prints progress.
     */
    public int propagateAllLightsWithProgress(List<LightSource> sources) {
        long start = System.currentTimeMillis();
        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        List<LightSource> bl = new ArrayList<>();
        for (LightSource s : sources) { if (s.type != LightType.SUN) bl.add(s); }
        int done = 0, skipped = 0, total = bl.size();
        Set<Integer> all = new HashSet<>();
        int lastPct = -1;
        for (int i = 0; i < total; i++) {
            LightSource src = bl.get(i);
            Set<Integer> af = runSingleSourceBFS(src, lp, it, true);
            if (!af.isEmpty()) { done++; all.addAll(af); }
            else skipped++;
            int pct = (i + 1) * 100 / total;
            if (pct != lastPct) {
                lastPct = pct;
                int barLen = pct / 2;
                StringBuilder bar = new StringBuilder("\r  [");
                for (int j = 0; j < 50; j++) bar.append(j < barLen ? '=' : j == barLen ? '>' : ' ');
                bar.append("] ").append(pct).append("% ").append(i + 1).append('/').append(total);
                System.out.print(bar.toString());
            }
        }
        System.out.println();
        long end = System.currentTimeMillis();
        System.out.println("Lighting: " + done + " sources" + (skipped > 0 ? " (" + skipped + " skipped)" : "") + ", " + all.size() + " slots, " + (end - start) + "ms");
        return done;
    }

    private static final int LP_MASK = 0x3FF;   // 10 bits per channel (0–1023)
    private static final int LP_SHIFT_G = 10;
    private static final int LP_SHIFT_B = 20;

    private boolean updateVoxelLight(int x, int y, int z, int r, int g, int b, int slot, int[] lightPool, boolean add) {
        int idx = (slot << 12) | ((x & 15) | ((y & 15) << 4) | ((z & 15) << 8));
        int p = lightPool[idx];
        int cr = p & LP_MASK, cg = (p >> LP_SHIFT_G) & LP_MASK, cb = (p >> LP_SHIFT_B) & LP_MASK;
        int nr = add ? Math.min(LP_MASK, cr + r) : Math.max(0, cr - r);
        int ng = add ? Math.min(LP_MASK, cg + g) : Math.max(0, cg - g);
        int nb = add ? Math.min(LP_MASK, cb + b) : Math.max(0, cb - b);
        if (nr != cr || ng != cg || nb != cb) { lightPool[idx] = nr | (ng << LP_SHIFT_G) | (nb << LP_SHIFT_B); return true; }
        return false;
    }

    private int getSlot(int x, int y, int z, int[] indirection) {
        int rx = x - world.getOffsetX(), ry = y - world.getOffsetY(), rz = z - world.getOffsetZ();
        if (rx < 0 || ry < 0 || rz < 0 || rx >= 2048 || ry >= 2048 || rz >= 2048) return -1;
        int s = indirection[(rx >> 4) + (ry >> 4) * 128 + (rz >> 4) * 16384];
        return s == World.EMPTY ? -1 : s;
    }

    private static int mergeAxisStates(int a, int b) {
        int ax = a/9, ay = (a/3)%3, az = a%3;
        int bx = b/9, by = (b/3)%3, bz = b%3;
        // Balanced trits: stored 1 = trit 0 = init/exhausted;
        // stored 0 = trit -1 (came from -dir); stored 2 = trit 1 (came from +dir)
        int mx = ax==1 ? bx : bx==1 ? ax : ax==bx ? ax : 1;
        int my = ay==1 ? by : by==1 ? ay : ay==by ? ay : 1;
        int mz = az==1 ? bz : bz==1 ? az : az==bz ? az : 1;
        return mx*9 + my*3 + mz;
    }

    private static long posKey(int x, int y, int z) { return ((long)(x & 0x7FF) << 22) | ((long)(y & 0x7FF) << 11) | (long)(z & 0x7FF); }
    // 8 bits per color channel in packed node: individual source contribution is always ≤255
    // (emissive ≤255 × color ≤1.0). The 10-bit pool stores accumulated multi-source values.
    private static long packNode(int x, int y, int z, int s, int r, int g, int b) {
        return ((long)(x & 0x7FF) << 53) | ((long)(y & 0x7FF) << 42) | ((long)(z & 0x7FF) << 31)
             | ((long)(s & 0x1F) << 26) | ((long)(r & 0xFF) << 18) | ((long)(g & 0xFF) << 10) | ((long)(b & 0xFF) << 2);
    }
    private static int unpackX(long p) { return (int)((p >>> 53) & 0x7FF); }
    private static int unpackY(long p) { return (int)((p >>> 42) & 0x7FF); }
    private static int unpackZ(long p) { return (int)((p >>> 31) & 0x7FF); }
    private static int unpackState(long p) { return (int)((p >>> 26) & 0x1F); }
    private static int unpackR(long p) { return (int)((p >>> 18) & 0xFF); }
    private static int unpackG(long p) { return (int)((p >>> 10) & 0xFF); }
    private static int unpackB(long p) { return (int)((p >>> 2) & 0xFF); }
}
