package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.Direction;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.*;

/**
 * The LightPropagationEngine is responsible for calculating how light spreads
 * through the voxel world. It uses a Breadth-First Search (BFS) algorithm to
 * simulate light attenuation (fading) as it moves away from sources.
 */
public class LightPropagationEngine {
    // Tuning parameters for light fading
    private static final float ATTENUATION_LINEAR = 0.08f;
    private static final float ATTENUATION_QUADRATIC = 0.02f;
    
    private final World world;
    private final BlockDataManager blockDataManager;
    
    /**
     * Constructs the light engine.
     * @param world The world to propagate light in.
     * @param blockDataManager The manager for block properties.
     */
    public LightPropagationEngine(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
    }

    /**
     * A highly optimized long-based queue for BFS to avoid object allocation overhead.
     * Storing 5 values into a single 64-bit long.
     */
    private static class LongQueue {
        private long[] array;
        private int head = 0, tail = 0, size = 0, mask;

        public LongQueue(int capacity) {
            int cap = 1; while (cap < capacity) cap <<= 1;
            array = new long[cap]; mask = cap - 1;
        }

        public void offer(long v) {
            if (size == array.length) { // Resize if full
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

    /**
     * Main entry point to calculate lighting for the entire world.
     * @param sources A list of all light sources (Sun, Torches, etc.).
     */
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

        // 1. Fast Global Sun Pass (Calculated at the chunk level for speed)
        if (sun != null) applySun(sun, lightPool, indirection);

        // 2. High-Resolution Voxel BFS for Block Lights (Direct lighting)
        if (!blockLights.isEmpty()) runBlockBFS(blockLights, lightPool, indirection, false);

        // 3. Radiance Cascade for Indirect Lighting (Lower resolution, bounces)
        if (!blockLights.isEmpty()) runBlockBFS(blockLights, lightPool, indirection, true);

        long end = System.currentTimeMillis();
        System.out.println("Lighting optimization took: " + (end - start) + "ms");
    }

    /**
     * Quickly applies global sunlight to all loaded chunks.
     * Modified to apply light per-voxel for smoother transitions and 8-bit precision.
     */
    private void applySun(LightSource sun, int[] lightPool, int[] indirection) {
        float sunR = sun.color.x * sun.intensity * 17.0f; // Scale 15 to ~255
        float sunG = sun.color.y * sun.intensity * 17.0f;
        float sunB = sun.color.z * sun.intensity * 17.0f;

        for (int i = 0; i < indirection.length; i++) {
            int slot = indirection[i];
            if (slot == -1) continue;

            int cx = (i % 128) * 16;
            int cy = ((i / 128) % 128) * 16;
            int cz = (i / 16384) * 16;

            int poolOffset = slot << 12;
            for (int vx = 0; vx < 16; vx++) {
                for (int vy = 0; vy < 16; vy++) {
                    for (int vz = 0; vz < 16; vz++) {
                        float dist = (float) Math.sqrt(Math.pow(cx + vx - sun.position.x, 2) + 
                                                       Math.pow(cy + vy - sun.position.y, 2) + 
                                                       Math.pow(cz + vz - sun.position.z, 2));
                        float attenuation = 1.0f / (1.0f + 0.0002f * dist);

                        int r = Math.min(255, (int)(sunR * attenuation));
                        int g = Math.min(255, (int)(sunG * attenuation));
                        int b = Math.min(255, (int)(sunB * attenuation));

                        int packed = r | (g << 8) | (b << 16);
                        lightPool[poolOffset + (vx | (vy << 4) | (vz << 8))] = packed;
                    }
                }
            }
        }
    }

    /**
     * Uses Breadth-First Search to accurately propagate light from point sources (block lights).
     * Updated for 8-bit precision.
     */
    private void runBlockBFS(List<LightSource> sources, int[] lightPool, int[] indirectionTable, boolean isIndirect) {
        LongQueue queue = new LongQueue(65536);
        int[] chunkPool = world.getChunkPool();
        float intensityMultiplier = (isIndirect ? 0.8f : 1.0f) * 17.0f; 
        float attLinear = isIndirect ? ATTENUATION_LINEAR * 0.3f : ATTENUATION_LINEAR;
        float attQuad = isIndirect ? ATTENUATION_QUADRATIC * 0.3f : ATTENUATION_QUADRATIC;

        for (int i = 0; i < sources.size(); i++) {
            LightSource src = sources.get(i);
            int slot = getSlot(src.position.x, src.position.y, src.position.z, indirectionTable);
            if (slot == -1) continue;

            updateVoxelLight(src.position.x, src.position.y, src.position.z,
                              (int)(src.color.x * src.intensity * intensityMultiplier),
                              (int)(src.color.y * src.intensity * intensityMultiplier),
                              (int)(src.color.z * src.intensity * intensityMultiplier), slot, lightPool);

            queue.offer(pack(src.position.x, src.position.y, src.position.z, 0, i));
        }

        while (!queue.isEmpty()) {
            long current = queue.poll();
            int cx = unpackX(current), cy = unpackY(current), cz = unpackZ(current), cDist = unpackDist(current), srcID = unpackID(current);
            LightSource source = sources.get(srcID);

            int maxRadius = (int)source.radius;
            if (cDist >= maxRadius) continue;

            for (Direction dir : Direction.values()) {
                int nx = cx + dir.x, ny = cy + dir.y, nz = cz + dir.z;
                if (nx < 0 || ny < 0 || nz < 0 || nx >= 2048 || ny >= 2048 || nz >= 2048) continue;

                int slot = indirectionTable[(nx >> 4) + (ny >> 4) * 128 + (nz >> 4) * 16384];
                if (slot == -1) continue;

                int blockId = chunkPool[(slot << 12) | ((nx & 15) | ((ny & 15) << 4) | ((nz & 15) << 8))];
                if (blockId > 0 && blockDataManager.isFullBlock(blockId)) continue;

                int nDist = cDist + 1;
                float att = 1.0f / (1.0f + attLinear * nDist + attQuad * nDist * nDist);
                int nr = (int)(source.color.x * source.intensity * intensityMultiplier * att);
                int ng = (int)(source.color.y * source.intensity * intensityMultiplier * att);
                int nb = (int)(source.color.z * source.intensity * intensityMultiplier * att);

                if (Math.max(nr, Math.max(ng, nb)) < 1) continue;

                if (updateVoxelLight(nx, ny, nz, nr, ng, nb, slot, lightPool)) {
                    queue.offer(pack(nx, ny, nz, nDist, srcID));
                }
            }
        }
    }

    /**
     * Updates the light value at a specific voxel if the new values are brighter.
     * Updated for 8-bit precision (0-255).
     */
    private boolean updateVoxelLight(int x, int y, int z, int r, int g, int b, int slot, int[] lightPool) {
        int idx = (slot << 12) | ((x & 15) | ((y & 15) << 4) | ((z & 15) << 8));
        int packed = lightPool[idx];

        int cr = packed & 0xFF, cg = (packed >> 8) & 0xFF, cb = (packed >> 16) & 0xFF;

        r = Math.min(255, r); g = Math.min(255, g); b = Math.min(255, b);

        if (r > cr || g > cg || b > cb) {
            lightPool[idx] = Math.max(r, cr) | (Math.max(g, cg) << 8) | (Math.max(b, cb) << 16);
            return true;
        }
        return false;
    }

    /** Helper to get chunk slot from world coordinates. */
    private int getSlot(int x, int y, int z, int[] indirection) {
        if (x < 0 || y < 0 || z < 0 || x >= 2048 || y >= 2048 || z >= 2048) return -1;
        return indirection[(x >> 4) + (y >> 4) * 128 + (z >> 4) * 16384];
    }

    // --- Packing Helpers to store multiple integers in a single long ---
    // Bits: [X:11][Y:11][Z:11][Dist:12][SourceID:19] = 64 bits
    private long pack(int x, int y, int z, int d, int id) {
        return ((long)(x&0x7FF)<<53)|((long)(y&0x7FF)<<42)|((long)(z&0x7FF)<<31)|((long)(d&0xFFF)<<19)|(id&0x7FFFFL);
    }
    private int unpackX(long p) { return (int)((p >> 53) & 0x7FF); }
    private int unpackY(long p) { return (int)((p >> 42) & 0x7FF); }
    private int unpackZ(long p) { return (int)((p >> 31) & 0x7FF); }
    private int unpackDist(long p) { return (int)((p >> 19) & 0xFFF); }
    private int unpackID(long p) { return (int)(p & 0x7FFFFL); }
}
