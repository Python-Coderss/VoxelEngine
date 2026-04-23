package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.Direction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;
import org.joml.Vector3i;


public class LightPropagationEngine {
    private static final float ATTENUATION_LINEAR = 0.15f;
    private static final float ATTENUATION_QUADRATIC = 0.05f;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float[] ATTENUATION_TABLE = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            ATTENUATION_TABLE[i] = 1.0f / (1.0f + ATTENUATION_LINEAR * i + ATTENUATION_QUADRATIC * i * i);
        }
    }
    
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
        float sunX = sun.position.x;
        float sunY = sun.position.y;
        float sunZ = sun.position.z;

        for (int cz = 0; cz < 128; cz++) {
            float dz = cz * 16 + 8 - sunZ;
            float dz2 = dz * dz;
            int baseZ = cz * 16384;
            for (int cy = 0; cy < 128; cy++) {
                float dy = cy * 16 + 8 - sunY;
                float dy2 = dy * dy;
                int baseY = baseZ + (cy << 7);
                for (int cx = 0; cx < 128; cx++) {
                    int slot = indirection[baseY | cx];
                    if (slot == -1) continue;
                    float dx = cx * 16 + 8 - sunX;
                    float dist = (float) Math.sqrt(dx * dx + dy2 + dz2);
                    float attenuation = 1.0f / (1.0f + 0.0002f * dist);
                    int packed = ((int)(sunR * attenuation)) | ((int)(sunG * attenuation) << 4) | ((int)(sunB * attenuation) << 8);
                    int startIdx = slot << 12;
                    Arrays.fill(lightPool, startIdx, startIdx + 4096, packed);
                }
            }
        }
    }

    private void runBlockBFS(List<LightSource> sources, int[] lightPool, int[] indirectionTable) {
        LongQueue queue = new LongQueue(65536);
        int[] chunkPool = world.getChunkPool();
        int numSources = sources.size();
        
        int[] srcR = new int[numSources];
        int[] srcG = new int[numSources];
        int[] srcB = new int[numSources];
        int[] srcRadius = new int[numSources];
        
        for (int i = 0; i < numSources; i++) {
            LightSource src = sources.get(i);
            int r = (int)(src.color.x * src.intensity);
            int g = (int)(src.color.y * src.intensity);
            int b = (int)(src.color.z * src.intensity);
            srcR[i] = r;
            srcG[i] = g;
            srcB[i] = b;
            srcRadius[i] = (int)src.radius;
            
            int slot = getSlot(src.position.x, src.position.y, src.position.z, indirectionTable);
            if (slot == -1) continue;

            int idx = (slot << 12) | (src.position.x & 15) | ((src.position.y & 15) << 4) | ((src.position.z & 15) << 8);
            int packed = lightPool[idx];
            int cr = packed & 15, cg = (packed >> 4) & 15, cb = (packed >> 8) & 15;
            if (r > cr || g > cg || b > cb) {
                lightPool[idx] = (r > cr ? r : cr) | ((g > cg ? g : cg) << 4) | ((b > cb ? b : cb) << 8);
                queue.offer(pack(src.position.x, src.position.y, src.position.z, 0, i));
            }
        }

        while (!queue.isEmpty()) {
            long current = queue.poll();
            int cx = unpackX(current), cy = unpackY(current), cz = unpackZ(current), cDist = unpackDist(current), srcID = unpackID(current);
            if (cDist >= srcRadius[srcID]) continue;

            int nDist = cDist + 1;
            float att = ATTENUATION_TABLE[nDist];
            int nr = (int)(srcR[srcID] * att);
            int ng = (int)(srcG[srcID] * att);
            int nb = (int)(srcB[srcID] * att);
            if (nr < 1 && ng < 1 && nb < 1) continue;

            int lx = cx & 15, ly = cy & 15, lz = cz & 15;
            int chunkIdx = (cx >> 4) | ((cy >> 4) << 7) | ((cz >> 4) << 14);
            int currentSlot = indirectionTable[chunkIdx];

            for (Direction dir : DIRECTIONS) {
                int nx = cx + dir.x, ny = cy + dir.y, nz = cz + dir.z;
                if (nx < 0 || ny < 0 || nz < 0 || nx >= 2048 || ny >= 2048 || nz >= 2048) continue;
                
                int nlx = lx + dir.x, nly = ly + dir.y, nlz = lz + dir.z;
                int nSlot;
                if (nlx >= 0 && nlx < 16 && nly >= 0 && nly < 16 && nlz >= 0 && nlz < 16) {
                    nSlot = currentSlot;
                } else {
                    nSlot = indirectionTable[(nx >> 4) | ((ny >> 4) << 7) | ((nz >> 4) << 14)];
                }

                if (nSlot == -1) continue;
                int voxelIdx = (nSlot << 12) | (nlx & 15) | ((nly & 15) << 4) | ((nlz & 15) << 8);
                if (chunkPool[voxelIdx] > 0) continue;

                int packed = lightPool[voxelIdx];
                int cr = packed & 15, cg = (packed >> 4) & 15, cb = (packed >> 8) & 15;
                if (nr > cr || ng > cg || nb > cb) {
                    lightPool[voxelIdx] = (nr > cr ? nr : cr) | ((ng > cg ? ng : cg) << 4) | ((nb > cb ? nb : cb) << 8);
                    queue.offer(pack(nx, ny, nz, nDist, srcID));
                }
            }
        }
    }

    private boolean updateVoxelLight(int x, int y, int z, int r, int g, int b, int slot, int[] lightPool) {
        int idx = (slot << 12) | ((x & 15) | ((y & 15) << 4) | ((z & 15) << 8));
        int packed = lightPool[idx];
        int cr = packed & 15, cg = (packed >> 4) & 15, cb = (packed >> 8) & 15;
        if (r > cr || g > cg || b > cb) {
            lightPool[idx] = (r > cr ? r : cr) | ((g > cg ? g : cg) << 4) | ((b > cb ? b : cb) << 8);
            return true;
        }
        return false;
    }

    private int getSlot(int x, int y, int z, int[] indirection) {
        if (x < 0 || y < 0 || z < 0 || x >= 2048 || y >= 2048 || z >= 2048) return -1;
        return indirection[(x >> 4) | ((y >> 4) << 7) | ((z >> 4) << 14)];
    }
    private long pack(int x, int y, int z, int d, int id) { return ((long)(x&0x7FF)<<53)|((long)(y&0x7FF)<<42)|((long)(z&0x7FF)<<31)|((long)(d&0xFFF)<<19)|(id&0x7FFFFL); }
    private int unpackX(long p) { return (int)((p >> 53) & 0x7FF); }
    private int unpackY(long p) { return (int)((p >> 42) & 0x7FF); }
    private int unpackZ(long p) { return (int)((p >> 31) & 0x7FF); }
    private int unpackDist(long p) { return (int)((p >> 19) & 0xFFF); }
    private int unpackID(long p) { return (int)(p & 0x7FFFFL); }
}
