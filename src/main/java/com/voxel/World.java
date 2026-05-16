package com.voxel;

import org.joml.Vector3i;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * The World class represents the entire voxel world.
 * It uses a two-level structure for efficient storage:
 * 1. An Indirection Table (128x128x128) that maps regions of space to "chunks".
 * 2. A Chunk Pool that contains the actual voxel data for each allocated chunk.
 *
 * This structure allows for a very large world (2048x2048x2048) without using
 * massive amounts of memory for empty space (air).
 */
public class World {
    // Constants for world dimensions and structure
    public static final int CHUNK_SIZE = 16; // Each chunk is 16x16x16 voxels
    public static final int REGION_SIZE = 128; // The world is 128x128x128 chunks (total 2048 voxels in each dimension)
    public static final int POOL_SIZE = 16384; // Maximum number of chunks that can be stored in memory
    public static final int EMPTY = 0xFFFFFFFF; // Value representing an unallocated/empty chunk slot

    // The indirection table maps a chunk's position in the world to its index in the chunk pool.
    // Index = cx + cy * 128 + cz * 128^2
    private final int[] indirectionTable;

    // The chunk pool stores the voxel IDs (e.g., 1 for grass, 2 for stone).
    // It's a flat array where each chunk occupies CHUNK_SIZE^3 integers.
    private final int[] chunkPool;

    // The bitmask pool stores 1 bit per voxel to indicate solidity (1 = solid, 0 = air).
    // Each chunk (16x16x16 = 4096 voxels) requires 4096 bits = 128 integers.
    private final int[] bitmaskPool;

    // The occlusion pool stores a 14-bit mask for directional sky visibility
    // 1 short per voxel = 4096 shorts per chunk.
    private final short[] occlusionPool;

    /**
     * Initializes the world with empty tables and pools.
     */
    public World() {
        // Initialize the indirection table with the EMPTY value
        indirectionTable = new int[REGION_SIZE * REGION_SIZE * REGION_SIZE];
        for (int i = 0; i < indirectionTable.length; i++) indirectionTable[i] = EMPTY;
        
        int voxelsPerChunk = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        // Allocate memory for the voxel and light pools
        chunkPool = new int[POOL_SIZE * voxelsPerChunk];
        bitmaskPool = new int[POOL_SIZE * (voxelsPerChunk / 32)];
        occlusionPool = new short[POOL_SIZE * voxelsPerChunk];
    }

    /**
     * Gets the voxel ID at the specified world coordinates.
     * @return The voxel ID, or 0 if out of bounds or empty.
     */
    public int getVoxel(int x, int y, int z) {
        // Check if the coordinates are within world boundaries
        if (x < 0 || y < 0 || z < 0 || x >= REGION_SIZE * CHUNK_SIZE || y >= REGION_SIZE * CHUNK_SIZE || z >= REGION_SIZE * CHUNK_SIZE) return 0;
        
        // Convert world coordinates to chunk coordinates
        int cx = x / CHUNK_SIZE;
        int cy = y / CHUNK_SIZE;
        int cz = z / CHUNK_SIZE;
        
        // Look up the chunk slot in the indirection table
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int slot = indirectionTable[tableIdx];

        // If the slot is empty, there are no voxels here
        if (slot == EMPTY) return 0;
        
        // Convert world coordinates to local coordinates within the chunk
        int lx = x % CHUNK_SIZE;
        int ly = y % CHUNK_SIZE;
        int lz = z % CHUNK_SIZE;
        
        // Calculate the index in the flat chunk pool array
        int poolIdx = (slot * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE) + (lx + ly * CHUNK_SIZE + lz * CHUNK_SIZE * CHUNK_SIZE);
        return chunkPool[poolIdx];
    }

    /**
     * Checks if the voxel at the given position is opaque (not air).
     */
    public boolean isOpaque(Vector3i pos) {
        return getVoxel(pos.x, pos.y, pos.z) > 0;
    }

    /**
     * Checks if the chunk at the given position is currently loaded/allocated.
     */
    public boolean isLoaded(Vector3i pos) {
        if (pos.x < 0 || pos.y < 0 || pos.z < 0 || pos.x >= REGION_SIZE * CHUNK_SIZE || pos.y >= REGION_SIZE * CHUNK_SIZE || pos.z >= REGION_SIZE * CHUNK_SIZE) return false;
        int cx = pos.x / CHUNK_SIZE;
        int cy = pos.y / CHUNK_SIZE;
        int cz = pos.z / CHUNK_SIZE;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        return indirectionTable[tableIdx] != EMPTY;
    }

    /**
     * Checks if the chunk at the given position is currently loaded/allocated.
     */

    // Standard getters for internal data structures
    public int[] getIndirectionTable() { return indirectionTable; }
    public int[] getChunkPool() { return chunkPool; }
    public int[] getBitmaskPool() { return bitmaskPool; }
    public short[] getOcclusionPool() { return occlusionPool; }

    /**
     * Assigns a chunk slot to a specific region in the indirection table.
     */
    public void setChunkSlot(int cx, int cy, int cz, int slot) {
        if (cx < 0 || cy < 0 || cz < 0 || cx >= REGION_SIZE || cy >= REGION_SIZE || cz >= REGION_SIZE) return;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        indirectionTable[tableIdx] = slot;
    }

    /**
     * Clears the chunk slot at the given coordinates in the indirection table.
     */
    public void clearChunkSlot(int cx, int cy, int cz) {
        if (cx < 0 || cy < 0 || cz < 0 || cx >= REGION_SIZE || cy >= REGION_SIZE || cz >= REGION_SIZE) return;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        indirectionTable[tableIdx] = EMPTY;
    }

    /**
     * Sets a voxel ID directly into a chunk pool slot at local coordinates.
     */
    public void setVoxelInPool(int slot, int lx, int ly, int lz, int type) {
        int bitIdx = lx + ly * CHUNK_SIZE + lz * CHUNK_SIZE * CHUNK_SIZE;
        int poolIdx = (slot * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE) + bitIdx;
        chunkPool[poolIdx] = type;

        // Update Bitmask (1 bit per voxel)
        int wordIdx = (slot * 128) + (bitIdx / 32);
        if (type > 0) {
            bitmaskPool[wordIdx] |= (1 << (bitIdx % 32));
        } else {
            bitmaskPool[wordIdx] &= ~(1 << (bitIdx % 32));
        }
    }

    /**
     * Clears all voxels in a given chunk slot.
     */
    public void clearChunkPoolSlot(int slot) {
        int voxelsPerChunk = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        int startIdx = slot * voxelsPerChunk;
        for (int i = 0; i < voxelsPerChunk; i++) {
            chunkPool[startIdx + i] = 0;
            occlusionPool[startIdx + i] = 0;
        }

        int startBitWord = slot * 128;
        for (int i = 0; i < 128; i++) {
            bitmaskPool[startBitWord + i] = 0;
        }
    }

    /**
     * Sets a voxel ID at world coordinates. Does nothing if the chunk is not allocated.
     */
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
