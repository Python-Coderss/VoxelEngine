package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightPropagationEngine;
import org.joml.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages ASYNC loading and unloading of chunks.
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

    public ChunkManager(World world, WorldGenerator generator, LightPropagationEngine lightEngine, int renderDistance) {
        this.world = world;
        this.generator = generator;
        this.lightEngine = lightEngine;
        this.renderDistance = renderDistance;

        for (int i = 0; i < World.POOL_SIZE; i++) {
            freeSlots.add(i);
        }
    }

    public void update(Vector3f playerPos) {
        int pcx = (int) Math.floor(playerPos.x) >> 4;
        int pcz = (int) Math.floor(playerPos.z) >> 4;

        if (pcx != lastPlayerCX || pcz != lastPlayerCZ) {
            lastPlayerCX = pcx;
            lastPlayerCZ = pcz;
            
            // Queue management tasks
            executor.submit(() -> manageChunks(pcx, pcz));
        }
    }

    private void manageChunks(int pcx, int pcz) {
        Set<Long> keep = new HashSet<>();

        // Identify chunks to load
        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (cx < 0 || cz < 0 || cx >= World.REGION_SIZE || cz >= World.REGION_SIZE) continue;

                long key = chunkKey(cx, cz);
                keep.add(key);

                if (!loadedChunks.containsKey(key) && !pendingLoads.contains(key)) {
                    pendingLoads.add(key);
                    executor.submit(() -> loadChunkAsync(cx, cz));
                }
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

    private void loadChunkAsync(int cx, int cz) {
        if (freeSlots.size() < chunkHeight) {
            pendingLoads.remove(chunkKey(cx, cz));
            return;
        }

        Integer[] slots = new Integer[chunkHeight];
        for (int cy = 0; cy < chunkHeight; cy++) {
            int slot = freeSlots.poll();
            slots[cy] = slot;
            generateStorageChunk(cx, cy, cz, slot);
            lightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
            world.setChunkSlot(cx, cy, cz, slot);
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

    public Set<Integer> getDirtySlots() { return dirtySlots; }
    public boolean isTableDirty() { return tableDirty.get(); }
    public void clearDirty() { tableDirty.set(false); dirtySlots.clear(); }
    public void shutdown() { executor.shutdown(); }
}
