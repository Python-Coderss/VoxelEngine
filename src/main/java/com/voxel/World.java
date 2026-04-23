package com.voxel;

import org.joml.Vector3i;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;

public class World {
    public static final int CHUNK_SIZE = 16;
    public static final int REGION_SIZE = 128;
    public static final int POOL_SIZE = 16384;
    public static final int EMPTY = 0xFFFFFFFF;

    private final int[] indirectionTable;
    private final int[] chunkPool;
    private final int[] lightPool; // Packed RGB light

    public World() {
        indirectionTable = new int[REGION_SIZE * REGION_SIZE * REGION_SIZE];
        for (int i = 0; i < indirectionTable.length; i++) indirectionTable[i] = EMPTY;
        
        int voxelsPerChunk = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        chunkPool = new int[POOL_SIZE * voxelsPerChunk];
        lightPool = new int[POOL_SIZE * voxelsPerChunk];
    }

    public int getVoxel(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= REGION_SIZE * CHUNK_SIZE || y >= REGION_SIZE * CHUNK_SIZE || z >= REGION_SIZE * CHUNK_SIZE) return 0;
        
        int cx = x / CHUNK_SIZE;
        int cy = y / CHUNK_SIZE;
        int cz = z / CHUNK_SIZE;
        
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int slot = indirectionTable[tableIdx];
        if (slot == EMPTY) return 0;
        
        int lx = x % CHUNK_SIZE;
        int ly = y % CHUNK_SIZE;
        int lz = z % CHUNK_SIZE;
        
        int poolIdx = (slot * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE) + (lx + ly * CHUNK_SIZE + lz * CHUNK_SIZE * CHUNK_SIZE);
        return chunkPool[poolIdx];
    }

    public boolean isOpaque(Vector3i pos) {
        return getVoxel(pos.x, pos.y, pos.z) > 0;
    }

    public boolean isLoaded(Vector3i pos) {
        if (pos.x < 0 || pos.y < 0 || pos.z < 0 || pos.x >= REGION_SIZE * CHUNK_SIZE || pos.y >= REGION_SIZE * CHUNK_SIZE || pos.z >= REGION_SIZE * CHUNK_SIZE) return false;
        int cx = pos.x / CHUNK_SIZE;
        int cy = pos.y / CHUNK_SIZE;
        int cz = pos.z / CHUNK_SIZE;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        return indirectionTable[tableIdx] != EMPTY;
    }

    public void setLight(int x, int y, int z, int packedLight) {
        int cx = x / CHUNK_SIZE;
        int cy = y / CHUNK_SIZE;
        int cz = z / CHUNK_SIZE;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int slot = indirectionTable[tableIdx];
        if (slot == EMPTY) return;

        int lx = x % CHUNK_SIZE;
        int ly = y % CHUNK_SIZE;
        int lz = z % CHUNK_SIZE;
        int poolIdx = (slot * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE) + (lx + ly * CHUNK_SIZE + lz * CHUNK_SIZE * CHUNK_SIZE);
        lightPool[poolIdx] = packedLight;
    }

    public int[] getIndirectionTable() { return indirectionTable; }
    public int[] getChunkPool() { return chunkPool; }
    public int[] getLightPool() { return lightPool; }

    public void setChunkSlot(int cx, int cy, int cz, int slot) {
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        indirectionTable[tableIdx] = slot;
    }

    public void setVoxelInPool(int slot, int lx, int ly, int lz, int type) {
        int poolIdx = (slot * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE) + (lx + ly * CHUNK_SIZE + lz * CHUNK_SIZE * CHUNK_SIZE);
        chunkPool[poolIdx] = type;
    }

    public void setVoxel(int x, int y, int z, int type) {
        if (x < 0 || y < 0 || z < 0 || x >= REGION_SIZE * CHUNK_SIZE || y >= REGION_SIZE * CHUNK_SIZE || z >= REGION_SIZE * CHUNK_SIZE) return;
        int cx = x / CHUNK_SIZE;
        int cy = y / CHUNK_SIZE;
        int cz = z / CHUNK_SIZE;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int slot = indirectionTable[tableIdx];
        if (slot == EMPTY) return;
        int lx = x % CHUNK_SIZE;
        int ly = y % CHUNK_SIZE;
        int lz = z % CHUNK_SIZE;
        setVoxelInPool(slot, lx, ly, lz, type);
    }
}
