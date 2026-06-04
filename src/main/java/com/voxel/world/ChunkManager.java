package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightPropagationEngine;
import org.joml.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.voxel.world.WorldSaveManager;
import com.voxel.world.DimensionType;

/**
 * Manages ASYNC loading and unloading of chunks.
 * Supports directional priority: chunks in the player's look direction
 * are submitted for loading before chunks behind them.
 */
public class ChunkManager {
    private final World world;
    private final WorldGenerator generator;
    private final LightPropagationEngine lightEngine;
    private final WorldSaveManager saveManager;
    private final DimensionType dimension;
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

    public ChunkManager(World world, WorldGenerator generator, LightPropagationEngine lightEngine, int renderDistance, WorldSaveManager saveManager, DimensionType dimension) {
        this.world = world;
        this.generator = generator;
        this.lightEngine = lightEngine;
        this.saveManager = saveManager;
        this.dimension = dimension;
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
        long t0 = System.currentTimeMillis();
        
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

                if (!loadedChunks.containsKey(key) && pendingLoads.add(key)) {
                    chunksToLoad.add(new int[]{cx, cz});
                }
            }
        }

        int[] unloadedCount = {0};

        // Sort by directional priority: chunks in the player's look direction load first
        if (!chunksToLoad.isEmpty()) {
            float lookX = (float) Math.cos(Math.toRadians(yaw));
            float lookZ = (float) Math.sin(Math.toRadians(yaw));

            chunksToLoad.sort((a, b) -> {
                float dotA = (a[0] - pcx) * lookX + (a[1] - pcz) * lookZ;
                float dotB = (b[0] - pcx) * lookX + (b[1] - pcz) * lookZ;
                return Float.compare(dotB, dotA);
            });

            WorldGenLogger.log("MANAGE playerChunk(" + pcx + "," + pcz + ") queueing " + chunksToLoad.size() + " chunks (loaded=" + loadedChunks.size() + ")");

            // pendingLoads.add(key) was already done atomically above
            for (int[] pos : chunksToLoad) {
                int cx = pos[0], cz = pos[1];
                executor.submit(() -> loadChunkAsync(cx, cz));
            }
        }

        // Unload chunks — use remove(key, slots) as atomic gate so only
        // one thread can unload a given chunk (prevents duplicate slot frees).
        // Also skip chunks still being generated (in pendingLoads) to avoid
        // freeing slots that the generator thread is still writing to.
        for (Iterator<Map.Entry<Long, Integer[]>> it = loadedChunks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Integer[]> entry = it.next();
            if (!keep.contains(entry.getKey()) && !pendingLoads.contains(entry.getKey())) {
                // Only unload if WE successfully removed this exact slot array.
                // If another thread already replaced or removed it, skip.
                if (loadedChunks.remove(entry.getKey(), entry.getValue())) {
                    unloadChunk(entry.getKey(), entry.getValue());
                    tableDirty.set(true);
                    unloadedCount[0]++;
                }
            }
        }

        if (unloadedCount[0] > 0 || !chunksToLoad.isEmpty()) {
            WorldGenLogger.log("MANAGE done: queued=" + chunksToLoad.size() + " unloaded=" + unloadedCount[0] + " loadedNow=" + loadedChunks.size() + " (" + (System.currentTimeMillis() - t0) + "ms)");
        }
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

        WorldGenLogger.log("RECENTER oldOffset(" + world.getOffsetX() + "," + world.getOffsetZ() + ") -> newOffset(" + newOffsetX + "," + newOffsetZ + ") loadedChunks=" + loadedChunks.size());

        // Clear the indirection table and set new origin
        world.setOrigin(newOffsetX, 0, newOffsetZ);

        int kept = 0, dropped = 0;

        // Re-register ALL currently loaded chunks that fall within the new buffer.
        // IMPORTANT: iterate the live map, NOT a stale snapshot. Between taking a
        // snapshot and re-registering, chunks can be unloaded and their slots recycled.
        // Re-registering stale (recycled) slots at old coordinates is the root cause of
        // "misplaced columns" — two chunks fight over the same indirection table entries.
        for (Map.Entry<Long, Integer[]> entry : loadedChunks.entrySet()) {
            int absCX = unpackX(entry.getKey());
            int absCZ = unpackZ(entry.getKey());

            int relCX = absCX - (newOffsetX >> 4);
            int relCZ = absCZ - (newOffsetZ >> 4);

            if (relCX >= 0 && relCX < World.REGION_SIZE && relCZ >= 0 && relCZ < World.REGION_SIZE) {
                Integer[] slots = entry.getValue();
                // Atomically verify the chunk still owns these slots.
                // If the chunk was reloaded between iterator advancement and now,
                // replace(key,slots,slots) returns false and we skip — prevents
                // registering recycled slots at the wrong coordinates.
                if (!loadedChunks.replace(entry.getKey(), slots, slots)) {
                    WorldGenLogger.log("RECENTER stale skip: chunk(" + absCX + "," + absCZ + ") was reloaded, not re-registering");
                    continue;
                }
                for (int cy = 0; cy < chunkHeight; cy++) {
                    world.setChunkSlot(absCX, cy, absCZ, slots[cy]);
                }
                kept++;
            } else if (!pendingLoads.contains(entry.getKey())) {
                // Only unload if WE successfully removed this exact slot array.
                if (loadedChunks.remove(entry.getKey(), entry.getValue())) {
                    unloadChunk(entry.getKey(), entry.getValue());
                    dropped++;
                }
            }
        }

        tableDirty.set(true);
        WorldGenLogger.log("RECENTER done: kept=" + kept + " dropped=" + dropped + " offset(" + newOffsetX + "," + newOffsetZ + ")");
        System.out.println("Recentered buffer: offset now (" + newOffsetX + ", 0, " + newOffsetZ + ")");
    }

    private void loadChunkAsync(int cx, int cz) {
        long t0 = System.currentTimeMillis();
        long key = chunkKey(cx, cz);
        WorldGenLogger.logChunk("LOAD_START", cx, -1, cz, "dim=" + dimension.name);

        // Guard: if this chunk was already loaded by another thread (duplicate
        // submission race), bail out immediately without touching the indirection table.
        if (loadedChunks.containsKey(key)) {
            WorldGenLogger.logChunk("LOAD_DUP", cx, -1, cz, "already loaded, skipping");
            pendingLoads.remove(key);
            return;
        }

        if (freeSlots.size() < chunkHeight) {
            WorldGenLogger.logChunk("LOAD_NOSLOTS", cx, -1, cz, "freeSlots=" + freeSlots.size() + " < " + chunkHeight);
            pendingLoads.remove(key);
            return;
        }

        // Allocate chunk slots WITHOUT registering in the indirection table.
        Integer[] slots = new Integer[chunkHeight];
        for (int cy = 0; cy < chunkHeight; cy++) {
            Integer slot = freeSlots.poll();
            if (slot == null) {
                for (int j = 0; j < cy; j++) {
                    freeSlots.add(slots[j]);
                }
                WorldGenLogger.logChunk("LOAD_SLOTRACE", cx, -1, cz, "null poll at cy=" + cy);
                pendingLoads.remove(key);
                return;
            }
            slots[cy] = slot;
        }
        int slotHash = java.util.Arrays.hashCode(slots);
        WorldGenLogger.logChunk("LOAD_ALLOC", cx, -1, cz, "slots=[" + slots[0] + ".." + slots[15] + "] hash=" + Integer.toHexString(slotHash));

        // Atomically register this chunk in loadedChunks.
        if (((ConcurrentHashMap<Long, Integer[]>) loadedChunks).putIfAbsent(key, slots) != null) {
            for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
                freeSlots.add(slots[sectionY]);
            }
            WorldGenLogger.logChunk("LOAD_LOSER", cx, -1, cz, "lost putIfAbsent race, returning slots");
            pendingLoads.remove(key);
            return;
        }

        // Register slots in the indirection table before loading/generating.
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }

        // Try loading from disk first.
        // loadChunk calls world.setVoxel() which goes through the indirection table.
        // If recenter cleared the table during the load, voxels are silently dropped.
        // Re-register after loading to fix any entries that were lost.
        if (saveManager != null && saveManager.loadChunk(dimension, cx, cz, world)) {
            for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
                world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
            }
            WorldGenLogger.logChunk("LOAD_DISK", cx, -1, cz, "loaded from disk, baking occlusion");
            for (int cy = 0; cy < chunkHeight; cy++) {
                lightEngine.bakeChunkOcclusion(slots[cy], cx, cy, cz);
                dirtySlots.add(slots[cy]);
            }
            pendingLoads.remove(key);
            tableDirty.set(true);
            WorldGenLogger.logChunk("LOAD_DONE_DISK", cx, -1, cz, (System.currentTimeMillis() - t0) + "ms");
            return;
        }

        // =====================================================================
        // PROCEDURAL GENERATION — sequential passes for the entire column.
        // =====================================================================
        WorldGenLogger.logChunk("GEN_START", cx, -1, cz, "procedural generation beginning");

        // Re-register all column slots before generation.
        // The table may have been cleared by a concurrent recenter during the
        // failed disk-load attempt above. This ensures every section is mapped.
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }

        // ---- Pass 1: Base terrain for all sections ----
        long t1 = System.currentTimeMillis();
        int totalSolid = 0;
        for (int cy = 0; cy < chunkHeight; cy++) {
            int solid = generateBaseTerrain(cx, cy, cz, slots[cy]);
            totalSolid += solid;
        }
        WorldGenLogger.logChunk("GEN_PASS1_BASE", cx, -1, cz, "all 16 sections, " + totalSolid + " solid voxels (" + (System.currentTimeMillis() - t1) + "ms)");

        // ---- Pass 2: Decoration for all sections ----
        long t2 = System.currentTimeMillis();
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }
        for (int cy = 0; cy < chunkHeight; cy++) {
            generator.decorate(cx, cy, cz, slots[cy], world);
        }
        WorldGenLogger.logChunk("GEN_PASS2_DECORATE", cx, -1, cz, "all 16 sections (" + (System.currentTimeMillis() - t2) + "ms)");

        // ---- Pass 3: Bake occlusion ----
        long t3 = System.currentTimeMillis();
        for (int cy = 0; cy < chunkHeight; cy++) {
            lightEngine.bakeChunkOcclusion(slots[cy], cx, cy, cz);
            dirtySlots.add(slots[cy]);
        }
        WorldGenLogger.logChunk("GEN_PASS3_BAKE", cx, -1, cz, "occlusion baked (" + (System.currentTimeMillis() - t3) + "ms)");

        // ---- Pass 4: Commit ----
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }
        pendingLoads.remove(key);
        tableDirty.set(true);
        WorldGenLogger.logChunk("GEN_DONE", cx, -1, cz, "committed, total=" + totalSolid + " solid, " + (System.currentTimeMillis() - t0) + "ms");
    }

    /**
     * Generates base terrain (voxels only, no decoration) for one chunk section.
     * Returns the count of solid (non-air) voxels placed.
     */
    private int generateBaseTerrain(int cx, int cy, int cz, int slot) {
        world.clearChunkPoolSlot(slot);
        int worldX = cx << 4;
        int worldY = cy << 4;
        int worldZ = cz << 4;
        int solidCount = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int height = generator.getHeight(worldX + lx, worldZ + lz);
                if (height < worldY && worldY > 64) continue;
                for (int ly = 0; ly < 16; ly++) {
                    int y = worldY + ly;
                    int type = generator.getBlockType(worldX + lx, y, worldZ + lz, height);
                    if (type != 0) {
                        world.setVoxelInPool(slot, lx, ly, lz, type);
                        solidCount++;
                    }
                }
            }
        }
        return solidCount;
    }

    private void unloadChunk(long key, Integer[] slots) {
        int cx = unpackX(key);
        int cz = unpackZ(key);
        WorldGenLogger.logChunk("UNLOAD", cx, -1, cz, "saving and freeing slots");
        if (saveManager != null) {
            saveManager.saveChunk(dimension, cx, cz, world);
        }
        for (int cy = 0; cy < chunkHeight; cy++) {
            world.clearChunkSlot(cx, cy, cz);
            freeSlots.add(slots[cy]);
        }
        int slotHash = java.util.Arrays.hashCode(slots);
        WorldGenLogger.logChunk("UNLOAD_DONE", cx, -1, cz, "freed " + chunkHeight + " slots hash=" + Integer.toHexString(slotHash));
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
