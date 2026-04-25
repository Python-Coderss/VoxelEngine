package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.Direction;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.*;

public class LightPropagationEngine {
    private static final float ATTENUATION_LINEAR = 0.15f;
    private static final float ATTENUATION_QUADRATIC = 0.05f;
    
    private final World world;
    
    public LightPropagationEngine(World world) {
        this.world = world;
    }

    private static class LongQueue {
        private long[] array;
        private int head = 0, tail = 0, size = 0, mask;
        public LongQueue(int capacity) {
            int cap = 1; while (cap < capacity) cap <<= 1;
            array = new long[cap]; mask = cap - 1;
        }
        public void offer(long v) {
            if (size == array.length) {
                long[] newArray = new long[array.length << 1];
                for (int i = 0; i < size; i++) newArray[i] = array[(head + i) & mask];
                array = newArray; mask = array.length - 1; head = 0; tail = size;
            }
            array[tail] = v; tail = (tail + 1) & mask; size++;
        }
        public long poll() {
            long v = array[head]; head = (head + 1) & mask; size--; return v;
        }
        public boolean isEmpty() { return size == 0; }
    }

    public void propagateAllLights(List<LightSource> sources) {
        long start = System.currentTimeMillis();
        int[] lightPool = world.getLightPool();
        int[] indirection = world.getIndirectionTable();
        
        LightSource sun = null;
        List<LightSource> blockLights = new ArrayList<>();
        for (LightSource s : sources) {
            if (s.type == LightType.SUN) sun = s;
            else blockLights.add(s);
        }

        // 1. Fast Global Sun Pass (Chunk-level)
        if (sun != null) applySun(sun, lightPool, indirection);

        // 2. High-Resolution Voxel BFS for Block Lights
        if (!blockLights.isEmpty()) runBlockBFS(blockLights, lightPool, indirection);

        long end = System.currentTimeMillis();
        System.out.println("Lighting optimization took: " + (end - start) + "ms");
    }

    private void applySun(LightSource sun, int[] lightPool, int[] indirection) {
        float sunR = sun.color.x * sun.intensity;
        float sunG = sun.color.y * sun.intensity;
        float sunB = sun.color.z * sun.intensity;

        for (int i = 0; i < indirection.length; i++) {
            int slot = indirection[i];
            if (slot == -1) continue;
            int cx = i % 128, cy = (i / 128) % 128, cz = i / 16384;
            float dist = (float) Math.sqrt(Math.pow(cx*16+8 - sun.position.x, 2) + 
                                           Math.pow(cy*16+8 - sun.position.y, 2) + 
                                           Math.pow(cz*16+8 - sun.position.z, 2));
            float attenuation = 1.0f / (1.0f + 0.0002f * dist);
            int packed = ((int)(sunR * attenuation)) | ((int)(sunG * attenuation) << 4) | ((int)(sunB * attenuation) << 8);
            Arrays.fill(lightPool, slot << 12, (slot << 12) + 4096, packed);
        }
    }

    private void runBlockBFS(List<LightSource> sources, int[] lightPool, int[] indirectionTable) {
        LongQueue queue = new LongQueue(65536);
        int[] chunkPool = world.getChunkPool();

        for (int i = 0; i < sources.size(); i++) {
            LightSource src = sources.get(i);
            int slot = getSlot(src.position.x, src.position.y, src.position.z, indirectionTable);
            if (slot == -1) continue;
            updateVoxelLight(src.position.x, src.position.y, src.position.z, (int)(src.color.x * src.intensity), 
                             (int)(src.color.y * src.intensity), (int)(src.color.z * src.intensity), slot, lightPool);
            queue.offer(pack(src.position.x, src.position.y, src.position.z, 0, i));
        }

        while (!queue.isEmpty()) {
            long current = queue.poll();
            int cx = unpackX(current), cy = unpackY(current), cz = unpackZ(current), cDist = unpackDist(current), srcID = unpackID(current);
            LightSource source = sources.get(srcID);
            if (cDist >= source.radius) continue;

            for (Direction dir : Direction.values()) {
                int nx = cx + dir.x, ny = cy + dir.y, nz = cz + dir.z;
                if (nx < 0 || ny < 0 || nz < 0 || nx >= 2048 || ny >= 2048 || nz >= 2048) continue;
                int slot = indirectionTable[(nx >> 4) + (ny >> 4) * 128 + (nz >> 4) * 16384];
                if (slot == -1 || chunkPool[(slot << 12) | ((nx & 15) | ((ny & 15) << 4) | ((nz & 15) << 8))] > 0) continue;

                int nDist = cDist + 1;
                float att = 1.0f / (1.0f + ATTENUATION_LINEAR * nDist + ATTENUATION_QUADRATIC * nDist * nDist);
                int nr = (int)(source.color.x * source.intensity * att), ng = (int)(source.color.y * source.intensity * att), nb = (int)(source.color.z * source.intensity * att);
                if (Math.max(nr, Math.max(ng, nb)) < 1) continue;

                if (updateVoxelLight(nx, ny, nz, nr, ng, nb, slot, lightPool)) queue.offer(pack(nx, ny, nz, nDist, srcID));
            }
        }
    }

    private boolean updateVoxelLight(int x, int y, int z, int r, int g, int b, int slot, int[] lightPool) {
        int idx = (slot << 12) | ((x & 15) | ((y & 15) << 4) | ((z & 15) << 8));
        int packed = lightPool[idx];
        int cr = packed & 15, cg = (packed >> 4) & 15, cb = (packed >> 8) & 15;
        if (r > cr || g > cg || b > cb) {
            lightPool[idx] = Math.max(r, cr) | (Math.max(g, cg) << 4) | (Math.max(b, cb) << 8);
            return true;
        }
        return false;
    }

    private int getSlot(int x, int y, int z, int[] indirection) {
        if (x < 0 || y < 0 || z < 0 || x >= 2048 || y >= 2048 || z >= 2048) return -1;
        return indirection[(x >> 4) + (y >> 4) * 128 + (z >> 4) * 16384];
    }
    private long pack(int x, int y, int z, int d, int id) { return ((long)(x&0x7FF)<<53)|((long)(y&0x7FF)<<42)|((long)(z&0x7FF)<<31)|((long)(d&0xFFF)<<19)|(id&0x7FFFFL); }
    private int unpackX(long p) { return (int)((p >> 53) & 0x7FF); }
    private int unpackY(long p) { return (int)((p >> 42) & 0x7FF); }
    private int unpackZ(long p) { return (int)((p >> 31) & 0x7FF); }
    private int unpackDist(long p) { return (int)((p >> 19) & 0xFFF); }
    private int unpackID(long p) { return (int)(p & 0x7FFFFL); }
}
