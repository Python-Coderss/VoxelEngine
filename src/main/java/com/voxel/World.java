package com.voxel;

import org.joml.Vector3i;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    public static final int REGION_SIZE = 128; // The world buffer is 128x128x128 chunks (total 2048 voxels in each dimension)
    public static final int EMPTY = 0xFFFFFFFF; // Value representing an unallocated/empty chunk slot

    // The indirection table maps a chunk's position in the world to its index in the chunk pool.
    // Index = rx + ry * 128 + rz * 128^2, where rx,ry,rz are buffer-relative chunk coords.
    private final int[] indirectionTable;

    // The chunk pool stores the voxel IDs (e.g., 1 for grass, 2 for stone).
    // It's a flat array where each chunk occupies CHUNK_SIZE^3 integers.
    private final int[] chunkPool;

    // The bitmask pool stores 1 bit per voxel to indicate solidity (1 = solid, 0 = air).
    // Each chunk (16x16x16 = 4096 voxels) requires 4096 bits = 128 integers.
    private final int[] bitmaskPool;

    // The light pool stores packed sky+block RGB light values (8 bits per channel, 0-255).
    // bits 0-7 = sky, bits 8-15 = block R, bits 16-23 = block G, bits 24-31 = block B.
    // Block RGB is the additive sum of all per-type contributions.
    // 1 int per voxel = 4096 ints per chunk.
    private final int[] lightPool;

    // Temp light field: byte per voxel for per-type scalar intensity BFS.
    // Reused for each type's BFS pass during world gen, or for single-source
    // add/subtract during runtime block changes. Holds intensity 0-255.
    private final byte[] tempLightPool;

    // The occlusion pool stores a 14-bit mask for directional sky visibility
    // 1 short per voxel = 4096 shorts per chunk.
    private final short[] occlusionPool;

    // Directional SDF pool: 8 bytes per chunk section, ONLY used for chunks
    // with zero solid voxels ("empty-loaded" chunks). Each byte encodes the
    // distance the ray can travel along a single direction before hitting a
    // solid voxel, entity AABB, or a non-empty neighbor chunk.
    //   byte = round(distance_in_voxels * 8), capped at 255 (31.875 voxels).
    //   16 voxels (full chunk run, no obstacles) is encoded as 128.
    // Layout: 6 meaningful bytes per slot + 2 unused padding bytes.
    //   byte 0 = +X, byte 1 = -X, byte 2 = +Y, byte 3 = -Y,
    //   byte 4 = +Z, byte 5 = -Z, bytes 6-7 unused (zero).
    // GPU packing: 2 uints per slot (8 bytes), shader reads via bit masks.
    // Chunks with at least one solid voxel have all bytes == 0; the shader
    // detects this and falls back to plain per-voxel DDA for those.
    private final byte[] dirSdfPool;

    /** Maximum number of chunks that can be stored in memory. Set at construction. */
    private final int poolSize;

    // Structure/feature writes may target a section that has not been allocated
    // yet. Keep those writes instead of silently dropping them; ChunkManager
    // persists them as semi-generated chunk data and applies them when the target
    // section becomes available.
    private final ConcurrentMap<DeferredVoxelKey, Integer> deferredVoxelWrites = new ConcurrentHashMap<>();

    // Sliding window offset: absolute world coordinate of the buffer's minimum corner.
    // All coordinates passed to public methods are treated as absolute world coords.
    private volatile int offsetX = 0;
    private volatile int offsetY = 0;
    private volatile int offsetZ = 0;

    /**
     * Initializes the world with empty tables and pools.
     * @param poolSize Maximum number of 16³ chunks to allocate (affects RAM: ~400 MB per 16384).
     */
    public World(int poolSize) {
        this.poolSize = poolSize;
        indirectionTable = new int[REGION_SIZE * REGION_SIZE * REGION_SIZE];
        for (int i = 0; i < indirectionTable.length; i++) indirectionTable[i] = EMPTY;

        int voxelsPerChunk = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        chunkPool = new int[poolSize * voxelsPerChunk];
        lightPool = new int[poolSize * voxelsPerChunk];
        tempLightPool = new byte[poolSize * voxelsPerChunk];
        bitmaskPool = new int[poolSize * (voxelsPerChunk / 32)];
        occlusionPool = new short[poolSize * voxelsPerChunk];
        dirSdfPool = new byte[poolSize * 8];
    }

    /** @return the maximum number of chunks this world supports. */
    public int getPoolSize() { return poolSize; }

    // ---- Sliding window offset ----

    /**
     * Returns the buffer origin in absolute block coordinates.
     */
    public int getOffsetX() { return offsetX; }
    public int getOffsetY() { return offsetY; }
    public int getOffsetZ() { return offsetZ; }

    /**
     * Sets the buffer origin, clearing the indirection table.
     * All existing chunk data in the pool is preserved — callers must
     * re-register chunks that fall within the new buffer.
     */
    public void setOrigin(int newOffsetX, int newOffsetY, int newOffsetZ) {
        this.offsetX = newOffsetX;
        this.offsetY = newOffsetY;
        this.offsetZ = newOffsetZ;
        java.util.Arrays.fill(indirectionTable, EMPTY);
    }

    // ---- Coordinate conversion ----
    // Pre-allocated reusable arrays for relative coordinate results.
    // Avoids allocating int[3] on every getVoxel/setVoxel call.
    private static final ThreadLocal<int[]> REL_COORDS = ThreadLocal.withInitial(() -> new int[3]);

    /**
     * Converts absolute block coords to buffer-relative block coords.
     * Returns true if the result is within the buffer (0..2047).
     * Stores results in the provided array, or uses a thread-local array if null.
     */
    private boolean toRelative(int absX, int absY, int absZ, int[] out) {
        int rx = absX - offsetX;
        int ry = absY - offsetY;
        int rz = absZ - offsetZ;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= REGION_SIZE * CHUNK_SIZE || ry >= REGION_SIZE * CHUNK_SIZE || rz >= REGION_SIZE * CHUNK_SIZE) {
            return false;
        }
        out[0] = rx;
        out[1] = ry;
        out[2] = rz;
        return true;
    }

    private boolean toRelative(int absX, int absY, int absZ) {
        int rx = absX - offsetX;
        int ry = absY - offsetY;
        int rz = absZ - offsetZ;
        return rx >= 0 && ry >= 0 && rz >= 0 && rx < REGION_SIZE * CHUNK_SIZE && ry < REGION_SIZE * CHUNK_SIZE && rz < REGION_SIZE * CHUNK_SIZE;
    }

    private int toRelativeChunkCX(int absCX) { return absCX - (offsetX >> 4); }
    private int toRelativeChunkCY(int absCY) { return absCY - (offsetY >> 4); }
    private int toRelativeChunkCZ(int absCZ) { return absCZ - (offsetZ >> 4); }

    private boolean isChunkInBuffer(int relCX, int relCY, int relCZ) {
        return relCX >= 0 && relCY >= 0 && relCZ >= 0 && relCX < REGION_SIZE && relCY < REGION_SIZE && relCZ < REGION_SIZE;
    }

    // ---- Core accessors ----

    /**
     * Gets the voxel ID at the specified absolute world coordinates.
     * The lower 16 bits contain the block type; upper bits may hold
     * extra data (e.g. redstone power level).
     * @return The voxel type (block ID), or 0 if out of bounds or empty.
     */
    public int getVoxel(int x, int y, int z) {
        int rx = x - offsetX;
        int ry = y - offsetY;
        int rz = z - offsetZ;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= REGION_SIZE * CHUNK_SIZE || ry >= REGION_SIZE * CHUNK_SIZE || rz >= REGION_SIZE * CHUNK_SIZE) return 0;

        int cx = rx >> 4;
        int cy = ry >> 4;
        int cz = rz >> 4;

        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int slot = indirectionTable[tableIdx];
        if (slot == EMPTY) return 0;

        int poolIdx = (slot << 12) | ((rx & 15) | ((ry & 15) << 4) | ((rz & 15) << 8));
        return chunkPool[poolIdx] & 0xFFFF;  // lower 16 bits = block type
    }

    /**
     * Checks if the voxel at the given position is opaque (not air).
     */
    public boolean isOpaque(Vector3i pos) {
        return getVoxel(pos.x, pos.y, pos.z) > 0;
    }

    /**
     * Returns the 7-bit flag field stored in bits 24..30 of the chunk pool
     * entry, or 0 for unloaded chunks. Used by per-block metadata features
     * such as the End Portal Frame's eye-insertion mask.
     */
    public int getVoxelFlags(int x, int y, int z) {
        return (getRawVoxel(x, y, z) >> 24) & 0x7F;
    }

    /**
     * Returns the 8-bit extra byte stored in bits 16..23 of the chunk pool
     * entry, or 0 for unloaded chunks. Used by redstone, rail-shape, and
     * any other feature that needs a sub-id channel.
     */
    public int getVoxelExtra(int x, int y, int z) {
        return (getRawVoxel(x, y, z) >> 16) & 0xFF;
    }

    /**
     * Checks if the chunk at the given absolute position is currently loaded/allocated.
     */
    public boolean isLoaded(Vector3i pos) {
        int[] rel = new int[3];
        if (!toRelative(pos.x, pos.y, pos.z, rel)) return false;
        int cx = rel[0] >> 4;
        int cy = rel[1] >> 4;
        int cz = rel[2] >> 4;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        return indirectionTable[tableIdx] != EMPTY;
    }

    // ---- Getters for GPU upload ----

    public int[] getIndirectionTable() { return indirectionTable; }
    public int[] getChunkPool() { return chunkPool; }
    public int[] getBitmaskPool() { return bitmaskPool; }
    public int[] getLightPool() { return lightPool; }
    public short[] getOcclusionPool() { return occlusionPool; }
    public byte[] getTempLightPool() { return tempLightPool; }
    public byte[] getDirSdfPool() { return dirSdfPool; }
    public int getPoolSizeForAlloc() { return poolSize; }

    /**
     * Clears the directional SDF pool for a given chunk slot (8 bytes).
     * After this call the slot's 6 direction distance bytes are zeroed; the
     * shader interprets all-zero bytes as "no SDF available — use plain DDA".
     */
    public void clearDirSdfPoolSlot(int slot) {
        int offset = slot * 8;
        dirSdfPool[offset]     = 0;
        dirSdfPool[offset + 1] = 0;
        dirSdfPool[offset + 2] = 0;
        dirSdfPool[offset + 3] = 0;
        dirSdfPool[offset + 4] = 0;
        dirSdfPool[offset + 5] = 0;
    }

    /**
     * Writes 6 directional distance bytes for a chunk slot. Each byte is the
     * pre-encoded distance in voxels * 8 (e.g. a full chunk run of 16 voxels
     * → 128). Padding bytes 6-7 are zeroed.
     */
    public void setDirSdfSlot(int slot, byte b0, byte b1, byte b2, byte b3, byte b4, byte b5) {
        int offset = slot * 8;
        dirSdfPool[offset]     = b0;
        dirSdfPool[offset + 1] = b1;
        dirSdfPool[offset + 2] = b2;
        dirSdfPool[offset + 3] = b3;
        dirSdfPool[offset + 4] = b4;
        dirSdfPool[offset + 5] = b5;
    }

    /**
     * Returns the raw voxelPool int for a chunk-slot+local-coord cell.
     * Used by SDF generation (and any future per-cell inspection tools).
     */
    public int getRawVoxelInSlot(int slot, int lx, int ly, int lz) {
        return chunkPool[(slot << 12) | (lx | (ly << 4) | (lz << 8))];
    }

    /**
     * Assigns a chunk slot to a specific region in the indirection table.
     * Takes absolute chunk coordinates and converts to buffer-relative internally.
     */
    public void setChunkSlot(int absCX, int absCY, int absCZ, int slot) {
        int rx = toRelativeChunkCX(absCX);
        int ry = toRelativeChunkCY(absCY);
        int rz = toRelativeChunkCZ(absCZ);
        if (!isChunkInBuffer(rx, ry, rz)) return;
        int tableIdx = rx + ry * REGION_SIZE + rz * REGION_SIZE * REGION_SIZE;
        indirectionTable[tableIdx] = slot;
    }

    /**
     * Clears the chunk slot at the given absolute chunk coordinates.
     */
    public void clearChunkSlot(int absCX, int absCY, int absCZ) {
        int rx = toRelativeChunkCX(absCX);
        int ry = toRelativeChunkCY(absCY);
        int rz = toRelativeChunkCZ(absCZ);
        if (!isChunkInBuffer(rx, ry, rz)) return;
        int tableIdx = rx + ry * REGION_SIZE + rz * REGION_SIZE * REGION_SIZE;
        indirectionTable[tableIdx] = EMPTY;
    }

    /**
     * Sets a voxel ID directly into a chunk pool slot at local coordinates.
     * Stores the type in the lower 16 bits with no extra data.
     */
    public void setVoxelInPool(int slot, int lx, int ly, int lz, int type) {
        setVoxelInPool(slot, lx, ly, lz, type, 0);
    }

    /**
     * Sets a voxel ID with extra data packed into the upper bits.
     * The type occupies the lower 16 bits, and extra data occupies bits 16-23.
     * Bit 31 is the solid flag (1=solid, 0=air) for GPU raytracing.
     */
    public void setVoxelInPool(int slot, int lx, int ly, int lz, int type, int extra) {
        setVoxelInPool(slot, lx, ly, lz, type, extra, 0);
    }

    /**
     * Sets a voxel with both the low extra byte (bits 16-23, e.g. redstone power/conn)
     * and high flags (bits 24-30, e.g. kinetic spinning state). Callers that need the
     * GPU to see the change must route through ChunkManager.setVoxelWithFlags (marks
     * the chunk dirty); this low-level write only touches the CPU pool.
     */
    public void setVoxelInPool(int slot, int lx, int ly, int lz, int type, int extra, int flags) {
        int packed = (type & 0xFFFF) | ((extra & 0xFF) << 16) | ((flags & 0x7F) << 24);
        if (type > 0) packed |= 0x80000000; // Solid flag for GPU
        int bitIdx = lx | (ly << 4) | (lz << 8);
        int poolIdx = (slot << 12) | bitIdx;
        chunkPool[poolIdx] = packed;

        // Update Bitmask (kept for CPU-side queries; GPU uses bit 31)
        int wordIdx = (slot << 7) | (bitIdx >> 5);
        int bit = 1 << (bitIdx & 31);
        if (type > 0) {
            bitmaskPool[wordIdx] |= bit;
        } else {
            bitmaskPool[wordIdx] &= ~bit;
        }

        // Notify the GPU-upload layer so even low-level writes made without
        // going through ChunkManager (villager builders, dragon eggs, portal
        // fills, ...) mark their slot dirty. Without this, such blocks exist
        // for CPU targeting/saving but never reach the GPU — "ghost blocks".
        // The set dedupes, so hot generation loops only pay a hash lookup.
        java.util.function.IntConsumer listener = slotDirtyListener;
        if (listener != null) listener.accept(slot);
    }

    /** Listener invoked whenever a pool voxel changes; used by ChunkManager to dirty GPU uploads. */
    private volatile java.util.function.IntConsumer slotDirtyListener;
    public void setSlotDirtyListener(java.util.function.IntConsumer listener) {
        this.slotDirtyListener = listener;
    }

/**
 * Clears all voxels in a given chunk slot.
 */
public void clearChunkPoolSlot(int slot) {
    int voxelsPerChunk = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
    int startIdx = slot << 12;
    for (int i = 0; i < voxelsPerChunk; i++) {
        chunkPool[startIdx + i] = 0;
        lightPool[startIdx + i] = 0;
        occlusionPool[startIdx + i] = (short)0x3FFF; // sky-visible default, not pitch-black
    }

    int startBitWord = slot << 7;
    for (int i = 0; i < 128; i++) {
        bitmaskPool[startBitWord + i] = 0;
    }
}

// ══════════════════════════════════════════════════════════════════
//  Light accessors (sky light + RGB block light)
//  lightPool format per int (8 bits per channel):
//    bits 0-7   = sky light (0-255)
//    bits 8-15  = block light Red   (0-255)
//    bits 16-23 = block light Green (0-255)
//    bits 24-31 = block light Blue  (0-255)
// ══════════════════════════════════════════════════════════════════

private static final int LIGHT_SKY_SHIFT = 0;
private static final int LIGHT_BLOCK_R_SHIFT = 8;
private static final int LIGHT_BLOCK_G_SHIFT = 16;
private static final int LIGHT_BLOCK_B_SHIFT = 24;
private static final int LIGHT_SKY_MASK = 0xFF;
private static final int LIGHT_BLOCK_R_MASK = 0xFF00;
private static final int LIGHT_BLOCK_G_MASK = 0xFF0000;
private static final int LIGHT_BLOCK_B_MASK = (int) 0xFF000000L;
private static final int LIGHT_BLOCK_ALL_MASK = 0xFFFFFF00;

/**
 * Sets the sky light value (0-15) for a voxel in a given chunk slot at local coords.
 */    public void setSkyLight(int slot, int lx, int ly, int lz, int value) {
        int poolIdx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        int current = lightPool[poolIdx];
        lightPool[poolIdx] = (current & ~LIGHT_SKY_MASK) | ((value & 0xFF) << LIGHT_SKY_SHIFT);
    }

/**
 * Gets the sky light value (0-15) for a voxel in a given chunk slot at local coords.
 */    public int getSkyLight(int slot, int lx, int ly, int lz) {
        int poolIdx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        return (lightPool[poolIdx] >> LIGHT_SKY_SHIFT) & 0xFF;
    }

/**
 * Sets all three block light channel (R,G,B, 0-15 each) for a voxel.
 */    public void setBlockLightRGB(int slot, int lx, int ly, int lz, int r, int g, int b) {
        int poolIdx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        int current = lightPool[poolIdx];
        lightPool[poolIdx] = (current & ~LIGHT_BLOCK_ALL_MASK)
            | ((r & 0xFF) << LIGHT_BLOCK_R_SHIFT)
            | ((g & 0xFF) << LIGHT_BLOCK_G_SHIFT)
            | ((b & 0xFF) << LIGHT_BLOCK_B_SHIFT);
    }

/**
 * Sets block light to the same value in all three channels (white light).
 * Backward-compatible with old single-intensity callers.
 */
public void setBlockLight(int slot, int lx, int ly, int lz, int value) {
    setBlockLightRGB(slot, lx, ly, lz, value, value, value);
}

/**
 * Gets the block light intensity (max of R,G,B, 0-15).
 */    public int getBlockLight(int slot, int lx, int ly, int lz) {
        int poolIdx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        int r = (lightPool[poolIdx] >> LIGHT_BLOCK_R_SHIFT) & 0xFF;
        int g = (lightPool[poolIdx] >> LIGHT_BLOCK_G_SHIFT) & 0xFF;
        int b = (lightPool[poolIdx] >> LIGHT_BLOCK_B_SHIFT) & 0xFF;
        return Math.max(r, Math.max(g, b));
    }

/** Gets the block light Red channel (0-15). */    public int getBlockLightR(int slot, int lx, int ly, int lz) {
        int poolIdx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        return (lightPool[poolIdx] >> LIGHT_BLOCK_R_SHIFT) & 0xFF;
    }

/** Gets the block light Green channel (0-15). */    public int getBlockLightG(int slot, int lx, int ly, int lz) {
        int poolIdx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        return (lightPool[poolIdx] >> LIGHT_BLOCK_G_SHIFT) & 0xFF;
    }

/** Gets the block light Blue channel (0-15). */    public int getBlockLightB(int slot, int lx, int ly, int lz) {
        int poolIdx = (slot << 12) | (lx | (ly << 4) | (lz << 8));
        return (lightPool[poolIdx] >> LIGHT_BLOCK_B_SHIFT) & 0xFF;
    }

/**
 * Gets the packed lightmap from the light pool at world coordinates.
 * Returns packed RGBA-like int: sky(bit 20-23) | blockR(bit 16-19) | blockG(bit 12-15) | blockB(bit 8-11) | 0(lower bits)
 * Returns 0 if outside the buffer.
 */    public int getPackedLightmap(int x, int y, int z) {
        int rx = x - offsetX;
        int ry = y - offsetY;
        int rz = z - offsetZ;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= REGION_SIZE * CHUNK_SIZE || ry >= REGION_SIZE * CHUNK_SIZE || rz >= REGION_SIZE * CHUNK_SIZE) return 0;

        int cx = rx >> 4;
        int cy = ry >> 4;
        int cz = rz >> 4;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int slot = indirectionTable[tableIdx];
        if (slot == EMPTY) return 0;

        int poolIdx = (slot << 12) | ((rx & 15) | ((ry & 15) << 4) | ((rz & 15) << 8));
        int raw = lightPool[poolIdx];
        // Each channel is stored 0-255 (level × 17); shrink to 4-bit (0-15) so
        // the shifted fields never overflow and bleed into a neighbouring nibble.
        int sky = (raw & 0xFF) / 17;
        int r = ((raw >>> LIGHT_BLOCK_R_SHIFT) & 0xFF) / 17;
        int g = ((raw >>> LIGHT_BLOCK_G_SHIFT) & 0xFF) / 17;
        int b = ((raw >>> LIGHT_BLOCK_B_SHIFT) & 0xFF) / 17;
        return (sky << 20) | (r << 16) | (g << 12) | (b << 8);
    }

/**
 * Clears the light pool (sky+block) for a single chunk slot. Leaves other pools unchanged.
 */
public void clearLightPoolSlot(int slot) {
    int startIdx = slot << 12;
    int voxelsPerChunk = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
    for (int i = 0; i < voxelsPerChunk; i++) {
        lightPool[startIdx + i] = 0;
    }
}

    /**
     * Sets a voxel ID at world coordinates. Unallocated targets are retained as
     * deferred writes so structure edges are not lost.
     */
    public void setVoxel(int x, int y, int z, int type) {
        setVoxelWithData(x, y, z, type, 0);
    }

    /**
     * Sets a voxel ID with extra data at world coordinates.
     * The extra data is packed into bits 16-23 of the stored int,
     * useful for per-voxel metadata like redstone power level.
     * Unallocated targets are retained as deferred writes.
     */
    public void setVoxelWithData(int x, int y, int z, int type, int extra) {
        setVoxelWithFlags(x, y, z, type, extra, 0);
    }

    /**
     * Sets a voxel ID with extra data (bits 16-23) and high flags (bits 24-30)
     * at world coordinates. High flags carry per-voxel state such as the kinetic
     * spinning bit consumed by the raytracer shader.
     * Unallocated targets are retained as deferred writes.
     */
    public void setVoxelWithFlags(int x, int y, int z, int type, int extra, int flags) {
        int rx = x - offsetX;
        int ry = y - offsetY;
        int rz = z - offsetZ;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= REGION_SIZE * CHUNK_SIZE || ry >= REGION_SIZE * CHUNK_SIZE || rz >= REGION_SIZE * CHUNK_SIZE) {
            deferVoxelWrite(x, y, z, type, extra, flags);
            return;
        }

        int cx = rx >> 4;
        int cy = ry >> 4;
        int cz = rz >> 4;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int slot = indirectionTable[tableIdx];
        if (slot == EMPTY) {
            deferVoxelWrite(x, y, z, type, extra, flags);
            return;
        }

        setVoxelInPool(slot, rx & 15, ry & 15, rz & 15, type, extra, flags);
    }

    /**
     * Queues a voxel write for a structure whose target section is not loaded.
     * Air writes are retained too because structures use them to carve terrain.
     */
    private void deferVoxelWrite(int x, int y, int z, int type, int extra, int flags) {
        int packed = (type & 0xFFFF) | ((extra & 0xFF) << 16) | ((flags & 0x7F) << 24);
        queueDeferredVoxelWrite(x, y, z, packed);
    }

    /** Queues a persisted semi-generated write without applying it prematurely. */
    public void queueDeferredVoxelWrite(int x, int y, int z, int raw) {
        deferredVoxelWrites.put(new DeferredVoxelKey(x, y, z), raw);
    }

    /** Returns and removes all deferred writes for persistence. */
    public List<DeferredVoxelWrite> drainDeferredVoxelWrites() {
        List<DeferredVoxelWrite> result = new ArrayList<>();
        for (java.util.Map.Entry<DeferredVoxelKey, Integer> entry : deferredVoxelWrites.entrySet()) {
            if (deferredVoxelWrites.remove(entry.getKey(), entry.getValue())) {
                DeferredVoxelKey key = entry.getKey();
                result.add(new DeferredVoxelWrite(key.x, key.y, key.z, entry.getValue()));
            }
        }
        return result;
    }

    /**
     * Applies deferred writes for one now-registered section and returns the
     * writes that were consumed so their semi-generated records can be acked.
     */
    public List<DeferredVoxelWrite> applyDeferredVoxelWrites(int cx, int cy, int cz) {
        int slot = getChunkSlot(cx << 4, cy << 4, cz << 4);
        if (slot == EMPTY) return java.util.Collections.emptyList();
        List<DeferredVoxelWrite> applied = new ArrayList<>();
        for (java.util.Map.Entry<DeferredVoxelKey, Integer> entry : deferredVoxelWrites.entrySet()) {
            DeferredVoxelKey key = entry.getKey();
            if ((key.x >> 4) != cx || (key.y >> 4) != cy || (key.z >> 4) != cz) continue;
            Integer raw = entry.getValue();
            if (!deferredVoxelWrites.remove(key, raw)) continue;
            setVoxelInPool(slot, key.x & 15, key.y & 15, key.z & 15,
                    raw & 0xFFFF, (raw >>> 16) & 0xFF, (raw >>> 24) & 0x7F);
            applied.add(new DeferredVoxelWrite(key.x, key.y, key.z, raw));
        }
        return applied;
    }

    /** A deferred structure/feature write awaiting its target section. */
    public static final class DeferredVoxelWrite {
        public final int x, y, z, raw;
        public DeferredVoxelWrite(int x, int y, int z, int raw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.raw = raw;
        }
    }

    private static final class DeferredVoxelKey {
        final int x, y, z;
        DeferredVoxelKey(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof DeferredVoxelKey)) return false;
            DeferredVoxelKey key = (DeferredVoxelKey) other;
            return x == key.x && y == key.y && z == key.z;
        }
        @Override public int hashCode() {
            int result = 31 * x + y;
            return 31 * result + z;
        }
    }


    /**
     * Returns the full raw voxel value (including extra data in upper bits).
     * Use {@link #getVoxel(int, int, int)} to get just the block type.
     */
    public int getRawVoxel(int x, int y, int z) {
        int rx = x - offsetX;
        int ry = y - offsetY;
        int rz = z - offsetZ;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= REGION_SIZE * CHUNK_SIZE || ry >= REGION_SIZE * CHUNK_SIZE || rz >= REGION_SIZE * CHUNK_SIZE) return 0;

        int cx = rx >> 4;
        int cy = ry >> 4;
        int cz = rz >> 4;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int slot = indirectionTable[tableIdx];
        if (slot == EMPTY) return 0;

        int poolIdx = (slot << 12) | ((rx & 15) | ((ry & 15) << 4) | ((rz & 15) << 8));
        return chunkPool[poolIdx];
    }

    /**
     * Returns the pool slot for the chunk containing the given absolute block position,
     * or EMPTY if the chunk is not loaded.
     */
    public int getChunkSlot(int x, int y, int z) {
        int rx = x - offsetX;
        int ry = y - offsetY;
        int rz = z - offsetZ;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= REGION_SIZE * CHUNK_SIZE || ry >= REGION_SIZE * CHUNK_SIZE || rz >= REGION_SIZE * CHUNK_SIZE) return EMPTY;
        int cx = rx >> 4;
        int cy = ry >> 4;
        int cz = rz >> 4;
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        return indirectionTable[tableIdx];
    }
}
