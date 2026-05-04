package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Spherical + directional-biased BFS lighting propagation.
 * Adds momentum-based flow and anisotropic branching.
 */
public class LightPropagationEngine {

    private static final float ATTENUATION_LINEAR = 0.14f;
    private static final float ATTENUATION_QUADRATIC = 0.05f;

    private final World world;
    private final BlockDataManager blockDataManager;

    public LightPropagationEngine(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
    }

    // ------------------------------------------------------------
    // Expanded directional kernel (multi-scale anisotropic stencil)
    // ------------------------------------------------------------
    private static final int[][] DIRS = buildDirs();

    private static int[][] buildDirs() {
        List<int[]> dirs = new ArrayList<>();

        // 26-neighborhood
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    dirs.add(new int[]{dx, dy, dz});
                }
            }
        }

        // axial 2-step directions (strong forward bias)
        int[] v = {-2, -1, 1, 2};
        for (int i : v) {
            dirs.add(new int[]{i, 0, 0});
            dirs.add(new int[]{0, i, 0});
            dirs.add(new int[]{0, 0, i});
        }

        // sparse long diagonals
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dy = -2; dy <= 2; dy += 2) {
                for (int dz = -2; dz <= 2; dz += 2) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    dirs.add(new int[]{dx, dy, dz});
                }
            }
        }

        return dirs.toArray(new int[0][]);
    }

    // ------------------------------------------------------------
    // BFS queue (momentum-aware)
    // ------------------------------------------------------------
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

    // ------------------------------------------------------------
    // Public entry
    // ------------------------------------------------------------
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

        if (sun != null) {
            applySun(sun, lightPool, indirection);
        }

        if (!blockLights.isEmpty()) {
            runBlockBFS(blockLights, lightPool, indirection, false);
            runBlockBFS(blockLights, lightPool, indirection, true);
        }
    }

    // ------------------------------------------------------------
    // Sun handled in shader
    // ------------------------------------------------------------
    private void applySun(LightSource sun, int[] lightPool, int[] indirection) {}

    // ------------------------------------------------------------
    // Main propagation
    // ------------------------------------------------------------
    private void runBlockBFS(List<LightSource> sources,
                             int[] lightPool,
                             int[] indirectionTable,
                             boolean isIndirect) {

        LongQueue queue = new LongQueue(65536);
        int[] chunkPool = world.getChunkPool();

        float intensityMultiplier = (isIndirect ? 0.8f : 1.0f) * 17.0f;
        float attLinear = isIndirect ? ATTENUATION_LINEAR * 0.5f : ATTENUATION_LINEAR;
        float attQuad = isIndirect ? ATTENUATION_QUADRATIC * 0.5f : ATTENUATION_QUADRATIC;

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

            queue.offer(pack(src.position.x, src.position.y, src.position.z, 0, i, 0));
        }

        while (!queue.isEmpty()) {

            long current = queue.poll();

            int cx = unpackX(current);
            int cy = unpackY(current);
            int cz = unpackZ(current);
            int srcID = unpackID(current);
            int prevDir = unpackDir(current);
            int cDist = unpackDist(current);

            LightSource source = sources.get(srcID);

            for (int dirIndex = 0; dirIndex < DIRS.length; dirIndex++) {

                int[] d = DIRS[dirIndex];

                int nx = cx + d[0];
                int ny = cy + d[1];
                int nz = cz + d[2];

                if (isBlocked(nx, ny, nz)) continue;
                
                int x = cx;
                int y = cy;
                int z = cz;
                // Prevent corner tunneling
                if (d[0] != 0 && d[1] != 0) {
                    if (isBlocked(cx + d[0], cy, cz) || isBlocked(cx, cy + d[1], cz)) {
                        continue;
                    }
                }
                if (d[0] != 0 && d[2] != 0) {
                    if (isBlocked(cx + d[0], cy, cz) || isBlocked(cx, cy, cz + d[2])) {
                        continue;
                    }
                }
                if (d[1] != 0 && d[2] != 0) {
                    if (isBlocked(cx, cy + d[1], cz) || isBlocked(cx, cy, cz + d[2])) {
                        continue;
                    }
                }
                int d_x = Integer.signum(d[0]);
                int d_y = Integer.signum(d[1]);
                int d_z = Integer.signum(d[2]);

                int absX = Math.abs(d[0]);
                int absY = Math.abs(d[1]);
                int absZ = Math.abs(d[2]);

                int steps = Math.max(absX, Math.max(absY, absZ));

                int errX = 0, errY = 0, errZ = 0;

                boolean blocked = false;

                for (int i = 0; i < steps; i++) {

                    errX += absX;
                    errY += absY;
                    errZ += absZ;

                    if (errX >= steps) {
                        x += d_x;
                        errX -= steps;
                    }
                    if (errY >= steps) {
                        y += d_y;
                        errY -= steps;
                    }
                    if (errZ >= steps) {
                        z += d_z;
                        errZ -= steps;
                    }

                    if (isBlocked(x, y, z)) {
                        blocked = true;
                        break;
                    }
                }                
                if (blocked) continue;

                float dist = cDist + steps;
                float distSq = dist * dist;
                


                float att = 1 / (1.0f + attLinear * dist + attQuad * distSq);

                int nr = (int)(source.color.x * source.intensity * intensityMultiplier * att);
                int ng = (int)(source.color.y * source.intensity * intensityMultiplier * att);
                int nb = (int)(source.color.z * source.intensity * intensityMultiplier * att);

                if (Math.max(nr, Math.max(ng, nb)) < 1) continue;

                int slot = getSlot(nx, ny, nz, indirectionTable);
                if (slot == -1) continue;

                if (updateVoxelLight(nx, ny, nz, nr, ng, nb, slot, lightPool)) {
                    queue.offer(pack(nx, ny, nz, cDist + steps, srcID, dirIndex));
                }
            }
        }
    }

    // ------------------------------------------------------------
    // voxel write
    // ------------------------------------------------------------
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

    // ------------------------------------------------------------
    // blocking check
    // ------------------------------------------------------------
    private boolean isBlocked(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 ||
            x >= 2048 || y >= 2048 || z >= 2048) return true;

        int cx16 = x >> 4;
        int cy16 = y >> 4;
        int cz16 = z >> 4;

        int slotIndex = cx16 + cy16 * 128 + cz16 * 16384;
        int slot = world.getIndirectionTable()[slotIndex];

        if (slot == -1) return true;

        int blockId = world.getChunkPool()[(slot << 12)
                | ((x & 15)
                | ((y & 15) << 4)
                | ((z & 15) << 8))];

        return blockId > 0 && blockDataManager.isFullBlock(blockId);
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------
    private int getSlot(int x, int y, int z, int[] indirection) {
        if (x < 0 || y < 0 || z < 0 ||
            x >= 2048 || y >= 2048 || z >= 2048) return -1;

        return indirection[(x >> 4) + (y >> 4) * 128 + (z >> 4) * 16384];
    }

    // ------------------------------------------------------------
    // packing
    // ------------------------------------------------------------
    private long pack(int x, int y, int z, int d, int id, int dir) {
        return ((long)(x & 0x7FF) << 53)
                | ((long)(y & 0x7FF) << 42)
                | ((long)(z & 0x7FF) << 31)
                | ((long)(d & 0xFFF) << 19)
                | ((long)(id & 0x7FF) << 8)
                | (dir & 0xFF);
    }

    private int unpackX(long p) { return (int)((p >> 53) & 0x7FF); }
    private int unpackY(long p) { return (int)((p >> 42) & 0x7FF); }
    private int unpackZ(long p) { return (int)((p >> 31) & 0x7FF); }
    private int unpackID(long p) { return (int)((p >> 8) & 0x7FF); }
    private int unpackDir(long p) { return (int)(p & 0xFF); }
    private int unpackDist(long p) {
        return (int)((p >> 19) & 0xFFF);
    }
}