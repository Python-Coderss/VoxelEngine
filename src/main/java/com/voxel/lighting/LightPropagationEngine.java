package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Spherical BFS lighting propagation (26-neighbor flood fill).
 */
public class LightPropagationEngine {

    private static final float ATTENUATION_LINEAR = 0.08f;
    private static final float ATTENUATION_QUADRATIC = 0.02f;

    private final World world;
    private final BlockDataManager blockDataManager;

    public LightPropagationEngine(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
    }

    // ----------------------------
    // 26-direction spherical kernel
    // ----------------------------
    private static final int[][] DIRS_26 = new int[26][3];

    static {
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    DIRS_26[i++] = new int[]{dx, dy, dz};
                }
            }
        }
    }

    // ----------------------------
    // BFS queue
    // ----------------------------
    private static class LongQueue {
        private long[] array;
        private int head = 0, tail = 0, size = 0, mask;

        public LongQueue(int capacity) {
            int cap = 1;
            while (cap < capacity) cap <<= 1;
            array = new long[cap];
            mask = cap - 1;
        }

        public void offer(long v) {
            if (size == array.length) {
                long[] newArray = new long[array.length << 1];
                for (int i = 0; i < size; i++) {
                    newArray[i] = array[(head + i) & mask];
                }
                array = newArray;
                mask = array.length - 1;
                head = 0;
                tail = size;
            }
            array[tail] = v;
            tail = (tail + 1) & mask;
            size++;
        }

        public long poll() {
            long v = array[head];
            head = (head + 1) & mask;
            size--;
            return v;
        }

        public boolean isEmpty() {
            return size == 0;
        }
    }

    // ----------------------------
    // Main entry
    // ----------------------------
    public void propagateAllLights(List<LightSource> sources) {

        int[] lightPool = world.getLightPool();
        int[] indirection = world.getIndirectionTable();

        LightSource sun = null;
        List<LightSource> blockLights = new ArrayList<>();

        for (LightSource s : sources) {
            if (s.type == LightType.SUN) {
                sun = s;
            } else {
                blockLights.add(s);
            }
        }

        // Sun handled in shader
        if (sun != null) {
            applySun(sun, lightPool, indirection);
        }

        if (!blockLights.isEmpty()) {
            runBlockBFS(blockLights, lightPool, indirection, false);
            runBlockBFS(blockLights, lightPool, indirection, true);
        }
    }

    // ----------------------------
    // No-op sun (shader handles it)
    // ----------------------------
    private void applySun(LightSource sun, int[] lightPool, int[] indirection) {
        // intentionally empty
    }

    // ----------------------------
    // Spherical BFS propagation
    // ----------------------------
    private void runBlockBFS(List<LightSource> sources,
                             int[] lightPool,
                             int[] indirectionTable,
                             boolean isIndirect) {

        LongQueue queue = new LongQueue(65536);
        int[] chunkPool = world.getChunkPool();

        float intensityMultiplier = (isIndirect ? 0.8f : 1.0f) * 17.0f;
        float attLinear = isIndirect ? ATTENUATION_LINEAR * 0.3f : ATTENUATION_LINEAR;
        float attQuad = isIndirect ? ATTENUATION_QUADRATIC * 0.3f : ATTENUATION_QUADRATIC;

        for (int i = 0; i < sources.size(); i++) {
            LightSource src = sources.get(i);

            int slot = getSlot(src.position.x, src.position.y, src.position.z, indirectionTable);
            if (slot == -1) continue;

            updateVoxelLight(
                    src.position.x,
                    src.position.y,
                    src.position.z,
                    (int)(src.color.x * src.intensity * intensityMultiplier),
                    (int)(src.color.y * src.intensity * intensityMultiplier),
                    (int)(src.color.z * src.intensity * intensityMultiplier),
                    slot,
                    lightPool
            );

            queue.offer(pack(src.position.x, src.position.y, src.position.z, 0, i));
        }

        while (!queue.isEmpty()) {
            long current = queue.poll();

            int cx = unpackX(current);
            int cy = unpackY(current);
            int cz = unpackZ(current);
            int cDist = unpackDist(current);
            int srcID = unpackID(current);

            LightSource source = sources.get(srcID);

            for (int[] d : DIRS_26) {

                int nx = cx + d[0];
                int ny = cy + d[1];
                int nz = cz + d[2];

                if (nx < 0 || ny < 0 || nz < 0 ||
                    nx >= 2048 || ny >= 2048 || nz >= 2048) {
                    continue;
                }

                int cx16 = nx >> 4;
                int cy16 = ny >> 4;
                int cz16 = nz >> 4;

                int slotIndex = cx16 + cy16 * 128 + cz16 * 16384;
                int slot = indirectionTable[slotIndex];

                if (slot == -1) continue;

                int blockId = chunkPool[(slot << 12)
                        | ((nx & 15)
                        | ((ny & 15) << 4)
                        | ((nz & 15) << 8))];

                if (blockId > 0 && blockDataManager.isFullBlock(blockId)) {
                    continue;
                }

                // ----------------------------
                // TRUE spherical distance
                // ----------------------------
                int dx = nx - source.position.x;
                int dy = ny - source.position.y;
                int dz = nz - source.position.z;

                float distSq = dx * dx + dy * dy + dz * dz;
                float dist = (float)Math.sqrt(distSq);

                float att = 1.0f / (1.0f + attLinear * dist + attQuad * distSq);

                int nr = (int)(source.color.x * source.intensity * intensityMultiplier * att);
                int ng = (int)(source.color.y * source.intensity * intensityMultiplier * att);
                int nb = (int)(source.color.z * source.intensity * intensityMultiplier * att);

                if (Math.max(nr, Math.max(ng, nb)) < 1) continue;

                if (updateVoxelLight(nx, ny, nz, nr, ng, nb, slot, lightPool)) {
                    queue.offer(pack(nx, ny, nz, cDist + 1, srcID));
                }
            }
        }
    }

    // ----------------------------
    // voxel lighting write
    // ----------------------------
    private boolean updateVoxelLight(int x, int y, int z,
                                     int r, int g, int b,
                                     int slot,
                                     int[] lightPool) {

        int idx = (slot << 12)
                | ((x & 15)
                | ((y & 15) << 4)
                | ((z & 15) << 8));

        int packed = lightPool[idx];

        int cr = packed & 0xFF;
        int cg = (packed >> 8) & 0xFF;
        int cb = (packed >> 16) & 0xFF;

        r = Math.min(255, r);
        g = Math.min(255, g);
        b = Math.min(255, b);

        if (r > cr || g > cg || b > cb) {
            lightPool[idx] =
                    Math.max(r, cr)
                    | (Math.max(g, cg) << 8)
                    | (Math.max(b, cb) << 16);
            return true;
        }

        return false;
    }

    // ----------------------------
    // helpers
    // ----------------------------
    private int getSlot(int x, int y, int z, int[] indirection) {
        if (x < 0 || y < 0 || z < 0 ||
            x >= 2048 || y >= 2048 || z >= 2048) {
            return -1;
        }

        return indirection[(x >> 4) + (y >> 4) * 128 + (z >> 4) * 16384];
    }

    // ----------------------------
    // packing
    // ----------------------------
    private long pack(int x, int y, int z, int d, int id) {
        return ((long)(x & 0x7FF) << 53)
                | ((long)(y & 0x7FF) << 42)
                | ((long)(z & 0x7FF) << 31)
                | ((long)(d & 0xFFF) << 19)
                | (id & 0x7FFFFL);
    }

    private int unpackX(long p) { return (int)((p >> 53) & 0x7FF); }
    private int unpackY(long p) { return (int)((p >> 42) & 0x7FF); }
    private int unpackZ(long p) { return (int)((p >> 31) & 0x7FF); }
    private int unpackDist(long p) { return (int)((p >> 19) & 0xFFF); }
    private int unpackID(long p) { return (int)(p & 0x7FFFFL); }
}