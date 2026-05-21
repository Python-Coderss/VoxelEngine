package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightPropagationEngine;
import org.joml.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages ASYNC loading and unloading of chunks.
 * Supports directional priority: chunks in the player's look direction
 * are submitted for loading before chunks behind them.
 */
public class ChunkManager {
    private final World world;
    private final WorldGenerator generator;
    private final LightPropagationEngine lightEngine;
    private final int renderDistance;
    private final int chunkHeight = 16;

    private final Map<Long, Integer[]> loadedChunks = new ConcurrentHashMap<>();
    private final Deque<Integer> freeSlots = new ConcurrentLinkedDeque<>();
    private final Set<Integer> dirtySlots = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean tableDirty = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final Set<Long> pendingLoads = ConcurrentHashMap.newKeySet();

    private int lastPlayerCX = -1000, lastPlayerCZ = -1000;
    private float lastYaw = 0;

    // Buffer recentering: when the player gets within this many chunks of the buffer edge,
    // shift the buffer to re-center on the player.
    private static final int RECENTER_MARGIN_CHUNKS = 16;
    // Buffer is REGION_SIZE (128) chunks wide. Half is 64.
    // We want the player at chunk ~64 in the buffer, so the offset is playerCX - 64.
    private static final int BUFFER_HALF_CHUNKS = World.REGION_SIZE / 2;

    public ChunkManager(World world, WorldGenerator generator, LightPropagationEngine lightEngine, int renderDistance) {
        this.world = world;
        this.generator = generator;
        this.lightEngine = lightEngine;
        this.renderDistance = renderDistance;

        for (int i = 0; i < world.getPoolSizeForAlloc(); i++) {
            freeSlots.add(i);
        }
    }

    public void update(Vector3f playerPos, float yaw) {
        int pcx = (int) Math.floor(playerPos.x) >> 4;
        int pcz = (int) Math.floor(playerPos.z) >> 4;

        if (pcx != lastPlayerCX || pcz != lastPlayerCZ) {
            lastPlayerCX = pcx;
            lastPlayerCZ = pcz;
            lastYaw = yaw;
            
            // Queue management tasks
            executor.submit(() -> manageChunks(pcx, pcz, yaw));
        }
    }

    private void manageChunks(int pcx, int pcz, float yaw) {
        // Check if the buffer needs to slide to keep the player centered
        recenterIfNeeded(pcx, pcz);

        Set<Long> keep = new HashSet<>();
        List<int[]> chunksToLoad = new ArrayList<>();

        // Collect all chunks within render distance
        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;

                long key = chunkKey(cx, cz);
                keep.add(key);

                if (!loadedChunks.containsKey(key) && !pendingLoads.contains(key)) {
                    chunksToLoad.add(new int[]{cx, cz});
                }
            }
        }

        // Sort by directional priority: chunks in the player's look direction load first
        if (!chunksToLoad.isEmpty()) {
            float lookX = (float) Math.cos(Math.toRadians(yaw));
            float lookZ = (float) Math.sin(Math.toRadians(yaw));

            chunksToLoad.sort((a, b) -> {
                float dotA = (a[0] - pcx) * lookX + (a[1] - pcz) * lookZ;
                float dotB = (b[0] - pcx) * lookX + (b[1] - pcz) * lookZ;
                // Higher dot product = more aligned with look direction
                return Float.compare(dotB, dotA);
            });

            // Submit loads in priority order
            for (int[] pos : chunksToLoad) {
                int cx = pos[0], cz = pos[1];
                long key = chunkKey(cx, cz);
                pendingLoads.add(key);
                executor.submit(() -> loadChunkAsync(cx, cz));
            }
        }

        // Unload chunks (Synchronized on loadedChunks for safe iteration)
        loadedChunks.entrySet().removeIf(entry -> {
            if (!keep.contains(entry.getKey())) {
                unloadChunk(entry.getKey(), entry.getValue());
                tableDirty.set(true);
                return true;
            }
            return false;
        });
    }

    /**
     * Checks if the player is near the edge of the 2048^3 buffer and recenters if needed.
     * This slides the world window so the player is always in the center region.
     */
    private void recenterIfNeeded(int pcx, int pcz) {
        int bufMinX = world.getOffsetX() >> 4;
        int bufMinZ = world.getOffsetZ() >> 4;
        int bufMaxX = bufMinX + World.REGION_SIZE;
        int bufMaxZ = bufMinZ + World.REGION_SIZE;

        // Check if player is well within the buffer margins
        if (pcx >= bufMinX + RECENTER_MARGIN_CHUNKS && pcx < bufMaxX - RECENTER_MARGIN_CHUNKS &&
            pcz >= bufMinZ + RECENTER_MARGIN_CHUNKS && pcz < bufMaxZ - RECENTER_MARGIN_CHUNKS) {
            return; // Still well within buffer
        }

        // Compute new offset to put the player at the center of the buffer
        int newOffsetX = (pcx - BUFFER_HALF_CHUNKS) << 4;
        int newOffsetZ = (pcz - BUFFER_HALF_CHUNKS) << 4;

        // Clear the indirection table and set new origin
        world.setOrigin(newOffsetX, 0, newOffsetZ);

        // Re-register ALL currently loaded chunks that fall within the new buffer.
        // We iterate the LIVE loadedChunks map (as a snapshot) to also catch chunks
        // that were being loaded concurrently by executor tasks during the recenter.
        Map<Long, Integer[]> currentChunks = new HashMap<>(loadedChunks);
        for (Map.Entry<Long, Integer[]> entry : currentChunks.entrySet()) {
            int absCX = unpackX(entry.getKey());
            int absCZ = unpackZ(entry.getKey());

            int relCX = absCX - (newOffsetX >> 4);
            int relCZ = absCZ - (newOffsetZ >> 4);

            if (relCX >= 0 && relCX < World.REGION_SIZE && relCZ >= 0 && relCZ < World.REGION_SIZE) {
                Integer[] slots = entry.getValue();
                for (int cy = 0; cy < chunkHeight; cy++) {
                    world.setChunkSlot(absCX, cy, absCZ, slots[cy]);
                }
            } else {
                // Chunk falls outside new buffer — unload it
                unloadChunk(entry.getKey(), entry.getValue());
                loadedChunks.remove(entry.getKey());
            }
        }

        tableDirty.set(true);
        System.out.println("Recentered buffer: offset now (" + newOffsetX + ", 0, " + newOffsetZ + ")");
    }

    private void loadChunkAsync(int cx, int cz) {
        if (freeSlots.size() < chunkHeight) {
            pendingLoads.remove(chunkKey(cx, cz));
            return;
        }

        Integer[] slots = new Integer[chunkHeight];
        for (int cy = 0; cy < chunkHeight; cy++) {
            int slot = freeSlots.poll();
            slots[cy] = slot;
            // Set the chunk slot BEFORE generation so world.setVoxel()
            // (called during decoration) can find the correct pool slot.
            world.setChunkSlot(cx, cy, cz, slot);
            generateStorageChunk(cx, cy, cz, slot);
            lightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
            dirtySlots.add(slot);
        }
        loadedChunks.put(chunkKey(cx, cz), slots);
        pendingLoads.remove(chunkKey(cx, cz));
        tableDirty.set(true);
    }

    private void generateStorageChunk(int cx, int cy, int cz, int slot) {
        world.clearChunkPoolSlot(slot);
        int worldX = cx << 4;
        int worldY = cy << 4;
        int worldZ = cz << 4;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int height = generator.getHeight(worldX + lx, worldZ + lz);
                if (height < worldY && worldY > 64) continue; 
                for (int ly = 0; ly < 16; ly++) {
                    int y = worldY + ly;
                    int type = generator.getBlockType(worldX + lx, y, worldZ + lz, height);
                    if (type != 0) world.setVoxelInPool(slot, lx, ly, lz, type);
                }
            }
        }

        // Decorate chunk with features (trees, etc.)
        generator.decorate(cx, cy, cz, slot, world);
    }

    private void unloadChunk(long key, Integer[] slots) {
        int cx = unpackX(key);
        int cz = unpackZ(key);
        for (int cy = 0; cy < chunkHeight; cy++) {
            world.clearChunkSlot(cx, cy, cz);
            freeSlots.add(slots[cy]);
        }
    }

    private long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    private int unpackX(long key) { return (int) (key >> 32); }
    private int unpackZ(long key) { return (int) key; }

    public boolean isChunkLoaded(int cx, int cz) {
        return loadedChunks.containsKey(chunkKey(cx, cz));
    }

    public boolean setVoxel(int x, int y, int z, int type) {
        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return false;

        world.setVoxel(x, y, z, type);

        int cx = x >> 4;
        int cy = y >> 4;
        int cz = z >> 4;
        lightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
        dirtySlots.add(slot);
        return true;
    }

    /**
     * Sets a voxel with extra data packed into the upper bits and marks the
     * chunk as dirty so the GPU gets updated. The block type occupies the
     * lower 16 bits; extra data occupies bits 16-23.
     * This is used for per-voxel metadata like redstone power level.
     * Unlike {@link #setVoxel}, this does NOT re-bake occlusion, since the
     * block type is unchanged.
     */
    public boolean setVoxelWithData(int x, int y, int z, int type, int extra) {
        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return false;

        world.setVoxelWithData(x, y, z, type, extra);
        dirtySlots.add(slot);
        return true;
    }

    public Set<Integer> getDirtySlots() { return dirtySlots; }
    public boolean isTableDirty() { return tableDirty.get(); }
    public void clearDirty() { tableDirty.set(false); dirtySlots.clear(); }
    public void shutdown() { executor.shutdown(); }
}
