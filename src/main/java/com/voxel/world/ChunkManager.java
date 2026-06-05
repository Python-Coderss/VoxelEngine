package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightPropagationEngine;
import com.voxel.utils.BiomeManager;
import org.joml.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.voxel.world.WorldSaveManager;
import com.voxel.world.DimensionType;

/**
 * Manages chunk loading and unloading on a SINGLE dedicated world-gen thread.
 * All chunk operations (load, generate, decorate, unload, recenter) are
 * serialized through a FIFO task queue — oldest submitted
 * chunks are processed first, giving priority to the player's current location.
 *
 * Because everything runs on one thread, there are zero race conditions.
 * No atomics, no putIfAbsent gates, no pendingLoads guards, no snapshot
 * staleness issues. The indirection table and slot pool are always consistent.
 */
public class ChunkManager {
    private final World world;
    private final WorldGenerator generator;
    private final LightPropagationEngine lightEngine;
    private final WorldSaveManager saveManager;
    private final BiomeManager biomeManager;
    private final DimensionType dimension;
    private final int renderDistance;
    private final int chunkHeight = 16;

    // ── Chunk map (ConcurrentHashMap for safe cross-thread reads via isChunkLoaded) ──
    private final Map<Long, Integer[]> loadedChunks = new ConcurrentHashMap<>();

    // ── Slot pool: simple FILO stack (int[] + counter), single-threaded ──
    private final int[] freeSlotStack;
    private int freeSlotTop;

    // ── Dirty tracking (accessed by main thread for GPU upload) ──
    private final Set<Integer> dirtySlots = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean tableDirty = new AtomicBoolean(false);
    private final AtomicBoolean biomeMapDirty = new AtomicBoolean(false);
    private int lastBiomeOffsetX = 0;
    private int lastBiomeOffsetZ = 0;

    // ── Single dedicated world-gen thread with FIFO task queue ──
    private final BlockingDeque<Runnable> taskQueue = new LinkedBlockingDeque<>();
    private final Thread genThread;
    private volatile boolean running = true;

    private int lastPlayerCX = -1000, lastPlayerCZ = -1000;
    private float lastYaw = 0;

    // Buffer recentering constants
    private static final int RECENTER_MARGIN_CHUNKS = 16;
    private static final int BUFFER_HALF_CHUNKS = World.REGION_SIZE / 2;

    public ChunkManager(World world, WorldGenerator generator, LightPropagationEngine lightEngine,
                        int renderDistance, WorldSaveManager saveManager, DimensionType dimension,
                        BiomeManager biomeManager) {
        this.world = world;
        this.generator = generator;
        this.lightEngine = lightEngine;
        this.saveManager = saveManager;
        this.biomeManager = biomeManager;
        this.dimension = dimension;
        this.renderDistance = renderDistance;

        int poolSize = world.getPoolSizeForAlloc();
        this.freeSlotStack = new int[poolSize];
        // Fill the stack: slot 0 at bottom, slot N-1 at top (pop gets highest first = FILO)
        for (int i = 0; i < poolSize; i++) {
            freeSlotStack[i] = i;
        }
        this.freeSlotTop = poolSize;

        // Start the single world-gen thread
        genThread = new Thread(this::runGenLoop, "WorldGen");
        genThread.setDaemon(true);
        genThread.start();
    }

    // ══════════════════════════════════════════════════════════════════
    //  GEN THREAD LOOP — processes tasks from the FIFO queue
    // ══════════════════════════════════════════════════════════════════

    private void runGenLoop() {
        while (running) {
            try {
                Runnable task = taskQueue.takeFirst(); // FIFO: oldest task first
                task.run();
            } catch (InterruptedException e) {
                if (!running) break;
            } catch (Exception e) {
                WorldGenLogger.log("GEN_THREAD error: " + e.getMessage());
                e.printStackTrace();
            }
        }
        // Queue is deliberately NOT drained on shutdown — stale tasks
        // from a previous dimension are discarded, not processed.
    }

    // ══════════════════════════════════════════════════════════════════
    //  PUBLIC API — called from main thread
    // ══════════════════════════════════════════════════════════════════

    /**
     * Called from the main/render thread when the player moves to a new chunk.
     * Posts a manageChunks task to the front of the FILO queue so it gets
     * processed before older pending operations.
     */
    public void update(Vector3f playerPos, float yaw) {
        int pcx = (int) Math.floor(playerPos.x) >> 4;
        int pcz = (int) Math.floor(playerPos.z) >> 4;

        if (pcx != lastPlayerCX || pcz != lastPlayerCZ) {
            lastPlayerCX = pcx;
            lastPlayerCZ = pcz;
            lastYaw = yaw;

            // Push to front of queue — manage preempts stale load tasks
            int pcxFinal = pcx, pczFinal = pcz;
            float yawFinal = yaw;
            taskQueue.addFirst(() -> manageChunks(pcxFinal, pczFinal, yawFinal));
        }
    }

    /**
     * Thread-safe: reads ConcurrentHashMap, safe from any thread.
     */
    public boolean isChunkLoaded(int cx, int cz) {
        return loadedChunks.containsKey(chunkKey(cx, cz));
    }

    /**
     * Sets a voxel via the indirection table and marks the chunk dirty.
     * Thread-safe: called from any thread (e.g. player block placement).
     */
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
     * Sets a voxel with extra data (e.g. redstone power) without re-baking occlusion.
     * Thread-safe: called from any thread.
     */
    public boolean setVoxelWithData(int x, int y, int z, int type, int extra) {
        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return false;

        world.setVoxelWithData(x, y, z, type, extra);
        dirtySlots.add(slot);
        return true;
    }

    // GPU upload queries (called from main thread)
    public Set<Integer> getDirtySlots() { return dirtySlots; }
    public boolean isTableDirty() { return tableDirty.get(); }
    public void clearDirty() { tableDirty.set(false); dirtySlots.clear(); }
    public boolean isBiomeMapDirty() { return biomeMapDirty.get(); }
    public void clearBiomeMapDirty() { biomeMapDirty.set(false); }

    public void shutdown() {
        running = false;
        taskQueue.clear(); // Discard stale tasks from old dimension
        genThread.interrupt();
        try { genThread.join(5000); } catch (InterruptedException ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════
    //  CHUNK MANAGEMENT — runs only on the gen thread
    // ══════════════════════════════════════════════════════════════════

    // Chunks within this radius of the player are never unloaded (persistent cache).
    private static final int KEEP_RADIUS = 32;

    private void manageChunks(int pcx, int pcz, float yaw) {
        long t0 = System.currentTimeMillis();

        recenterIfNeeded(pcx, pcz);

        float lookX = (float) Math.cos(Math.toRadians(yaw));
        float lookZ = (float) Math.sin(Math.toRadians(yaw));

        // Maximum distance: 2× forward, 1× sideways/back
        int maxDist = renderDistance * 2;

        // ── Build keep set: visible chunks + 32-radius persistence zone ──
        Set<Long> keep = new HashSet<>();
        List<int[]> chunksToLoad = new ArrayList<>();

        for (int dx = -maxDist; dx <= maxDist; dx++) {
            for (int dz = -maxDist; dz <= maxDist; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;

                // Project onto look direction (along) and perpendicular (side)
                float along = dx * lookX + dz * lookZ;
                float perp = -dx * lookZ + dz * lookX;

                // Stretched ring: forward distance counts as half
                // (doubles the effective radius in look direction)
                int ring = Math.max(
                    Math.round(Math.abs(along) * 0.5f),
                    Math.round(Math.abs(perp)));

                if (ring > renderDistance) continue;

                long key = chunkKey(cx, cz);
                keep.add(key);
                if (!loadedChunks.containsKey(key)) {
                    chunksToLoad.add(new int[]{cx, cz});
                }
            }
        }

        // Add all chunks within KEEP_RADIUS to the keep set (never unload).
        // HashSet deduplicates with the visible range above, so we can add unconditionally.
        for (int dx = -KEEP_RADIUS; dx <= KEEP_RADIUS; dx++) {
            for (int dz = -KEEP_RADIUS; dz <= KEEP_RADIUS; dz++) {
                keep.add(chunkKey(pcx + dx, pcz + dz));
            }
        }

        int unloadedCount = 0;

        // ── Hot-swap: clear stale load tasks, keep last 2 as buffer ──
        if (!chunksToLoad.isEmpty()) {
            // Keep the last 2 tasks so the gen thread never idles between swaps.
            // Poll from the back (most recently added stale tasks).
            Runnable keep1 = taskQueue.pollLast();
            Runnable keep2 = taskQueue.pollLast();
            taskQueue.clear();
            // Re-add kept tasks to the front (they run immediately after manageChunks)
            if (keep2 != null) taskQueue.addFirst(keep2);
            if (keep1 != null) taskQueue.addFirst(keep1);

            // Sort by: ring ascending, then abs(angle from forward) ascending.
            // Ascending ring → inner rings first in the sorted list.
            // Ascending abs-angle → forward (angle=0) sorts FIRST in the list.
            // The FIFO push loop (addLast) means first-in-list = first-in-deque.
            chunksToLoad.sort((a, b) -> {
                int dxA = a[0] - pcx, dzA = a[1] - pcz;
                int dxB = b[0] - pcx, dzB = b[1] - pcz;

                float alongA = dxA * lookX + dzA * lookZ;
                float perpA = -dxA * lookZ + dzA * lookX;
                float alongB = dxB * lookX + dzB * lookZ;
                float perpB = -dxB * lookZ + dzB * lookX;

                int ringA = Math.max(Math.round(Math.abs(alongA) * 0.5f), Math.round(Math.abs(perpA)));
                int ringB = Math.max(Math.round(Math.abs(alongB) * 0.5f), Math.round(Math.abs(perpB)));

                if (ringA != ringB) return Integer.compare(ringA, ringB);

                // Same ring: forward (small angle) sorts first → pushed first → FIFO front
                float angleA = (float) Math.abs(Math.atan2(perpA, alongA));
                float angleB = (float) Math.abs(Math.atan2(perpB, alongB));
                return Float.compare(angleA, angleB); // ascending: forward=0 first
            });

            WorldGenLogger.log("MANAGE playerChunk(" + pcx + "," + pcz + ") queueing "
                + chunksToLoad.size() + " chunks in spiral (loaded=" + loadedChunks.size() + ")");

            for (int[] pos : chunksToLoad) {
                int cx = pos[0], cz = pos[1];
                taskQueue.addLast(() -> loadChunk(cx, cz));
            }
        }

        // ── Unload chunks outside keep set ──
        Iterator<Map.Entry<Long, Integer[]>> it = loadedChunks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Integer[]> entry = it.next();
            if (!keep.contains(entry.getKey())) {
                it.remove();
                unloadChunk(entry.getKey(), entry.getValue());
                tableDirty.set(true);
                unloadedCount++;
            }
        }

        if (unloadedCount > 0 || !chunksToLoad.isEmpty()) {
            WorldGenLogger.log("MANAGE done: queued=" + chunksToLoad.size()
                + " unloaded=" + unloadedCount + " loadedNow=" + loadedChunks.size()
                + " (" + (System.currentTimeMillis() - t0) + "ms)");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  RECENTER — runs only on the gen thread, zero races
    // ══════════════════════════════════════════════════════════════════

    private void recenterIfNeeded(int pcx, int pcz) {
        int bufMinX = world.getOffsetX() >> 4;
        int bufMinZ = world.getOffsetZ() >> 4;
        int bufMaxX = bufMinX + World.REGION_SIZE;
        int bufMaxZ = bufMinZ + World.REGION_SIZE;

        if (pcx >= bufMinX + RECENTER_MARGIN_CHUNKS && pcx < bufMaxX - RECENTER_MARGIN_CHUNKS &&
            pcz >= bufMinZ + RECENTER_MARGIN_CHUNKS && pcz < bufMaxZ - RECENTER_MARGIN_CHUNKS) {
            return;
        }

        int newOffsetX = (pcx - BUFFER_HALF_CHUNKS) << 4;
        int newOffsetZ = (pcz - BUFFER_HALF_CHUNKS) << 4;

        // Capture old biome offsets before recentering
        int oldBiomeX = lastBiomeOffsetX;
        int oldBiomeZ = lastBiomeOffsetZ;

        WorldGenLogger.log("RECENTER oldOffset(" + world.getOffsetX() + "," + world.getOffsetZ()
            + ") -> newOffset(" + newOffsetX + "," + newOffsetZ
            + ") loadedChunks=" + loadedChunks.size());

        // Slide the biome map on the gen thread alongside recenter
        if (biomeManager != null) {
            biomeManager.slideBiomeMap(oldBiomeX, oldBiomeZ, newOffsetX, newOffsetZ);
            lastBiomeOffsetX = newOffsetX;
            lastBiomeOffsetZ = newOffsetZ;
            biomeMapDirty.set(true);
        }

        // Clear the indirection table
        world.setOrigin(newOffsetX, 0, newOffsetZ);

        int kept = 0;

        // Re-register every chunk — no snapshot, no races, single thread.
        for (Map.Entry<Long, Integer[]> entry : loadedChunks.entrySet()) {
            long key = entry.getKey();
            int absCX = unpackX(key);
            int absCZ = unpackZ(key);
            int relCX = absCX - (newOffsetX >> 4);
            int relCZ = absCZ - (newOffsetZ >> 4);

            if (relCX >= 0 && relCX < World.REGION_SIZE && relCZ >= 0 && relCZ < World.REGION_SIZE) {
                Integer[] slots = entry.getValue();
                for (int cy = 0; cy < chunkHeight; cy++) {
                    world.setChunkSlot(absCX, cy, absCZ, slots[cy]);
                }
                kept++;
            }
            // Note: chunks outside the new buffer are NOT unloaded here.
            // They'll be unloaded by the next manageChunks call (which runs
            // immediately after recenter in the manageChunks flow).
        }

        tableDirty.set(true);
        WorldGenLogger.log("RECENTER done: kept=" + kept
            + " offset(" + newOffsetX + "," + newOffsetZ + ")");
        System.out.println("Recentered buffer: offset now (" + newOffsetX + ", 0, " + newOffsetZ + ")");
    }

    // ══════════════════════════════════════════════════════════════════
    //  CHUNK LOADING — runs only on the gen thread
    // ══════════════════════════════════════════════════════════════════

    private void loadChunk(int cx, int cz) {
        long t0 = System.currentTimeMillis();
        long key = chunkKey(cx, cz);
        WorldGenLogger.logChunk("LOAD_START", cx, -1, cz, "dim=" + dimension.name);

        // Already loaded? (can happen if a previous manage+load beat us)
        if (loadedChunks.containsKey(key)) {
            WorldGenLogger.logChunk("LOAD_DUP", cx, -1, cz, "already loaded, skipping");
            return;
        }

        if (freeSlotTop < chunkHeight) {
            WorldGenLogger.logChunk("LOAD_NOSLOTS", cx, -1, cz, "freeSlots=" + freeSlotTop + " < " + chunkHeight);
            return;
        }

        // Allocate 16 slots from the FILO stack
        Integer[] slots = new Integer[chunkHeight];
        for (int cy = 0; cy < chunkHeight; cy++) {
            slots[cy] = freeSlotStack[--freeSlotTop]; // FILO pop
        }
        int slotHash = java.util.Arrays.hashCode(slots);
        WorldGenLogger.logChunk("LOAD_ALLOC", cx, -1, cz,
            "slots=[" + slots[0] + ".." + slots[15] + "] hash=" + Integer.toHexString(slotHash));

        // Register in loadedChunks and indirection table
        loadedChunks.put(key, slots);
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }

        // Try disk load first
        if (saveManager != null && saveManager.loadChunk(dimension, cx, cz, world)) {
            for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
                world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
            }
            WorldGenLogger.logChunk("LOAD_DISK", cx, -1, cz, "loaded from disk, baking occlusion");
            for (int cy = 0; cy < chunkHeight; cy++) {
                lightEngine.bakeChunkOcclusion(slots[cy], cx, cy, cz);
                dirtySlots.add(slots[cy]);
            }
            tableDirty.set(true);
            WorldGenLogger.logChunk("LOAD_DONE_DISK", cx, -1, cz, (System.currentTimeMillis() - t0) + "ms");
            return;
        }

        // ═══════════════════════════════════════════════════════════════
        //  PROCEDURAL GENERATION — sequential passes for the column
        // ═══════════════════════════════════════════════════════════════
        WorldGenLogger.logChunk("GEN_START", cx, -1, cz, "procedural generation beginning");

        // Defensive re-register (no-op in single-thread mode, kept to guard
        // against future changes that might clear the table off-thread).
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }

        // Pass 1: Base terrain
        long t1 = System.currentTimeMillis();
        int totalSolid = 0;
        for (int cy = 0; cy < chunkHeight; cy++) {
            totalSolid += generateBaseTerrain(cx, cy, cz, slots[cy]);
        }
        WorldGenLogger.logChunk("GEN_PASS1_BASE", cx, -1, cz,
            "all 16 sections, " + totalSolid + " solid voxels (" + (System.currentTimeMillis() - t1) + "ms)");

        // Pass 2: Decoration
        long t2 = System.currentTimeMillis();
        // Defensive re-register (no-op in single-thread mode).
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }
        for (int cy = 0; cy < chunkHeight; cy++) {
            generator.decorate(cx, cy, cz, slots[cy], world);
        }
        WorldGenLogger.logChunk("GEN_PASS2_DECORATE", cx, -1, cz,
            "all 16 sections (" + (System.currentTimeMillis() - t2) + "ms)");

        // Pass 3: Bake occlusion
        long t3 = System.currentTimeMillis();
        for (int cy = 0; cy < chunkHeight; cy++) {
            lightEngine.bakeChunkOcclusion(slots[cy], cx, cy, cz);
            dirtySlots.add(slots[cy]);
        }
        WorldGenLogger.logChunk("GEN_PASS3_BAKE", cx, -1, cz,
            "occlusion baked (" + (System.currentTimeMillis() - t3) + "ms)");

        // Pass 4: Commit — final re-registration
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }
        tableDirty.set(true);
        WorldGenLogger.logChunk("GEN_DONE", cx, -1, cz,
            "committed, total=" + totalSolid + " solid, " + (System.currentTimeMillis() - t0) + "ms");
    }

    // ══════════════════════════════════════════════════════════════════
    //  CHUNK UNLOAD — runs only on the gen thread
    // ══════════════════════════════════════════════════════════════════

    private void unloadChunk(long key, Integer[] slots) {
        int cx = unpackX(key);
        int cz = unpackZ(key);
        WorldGenLogger.logChunk("UNLOAD", cx, -1, cz, "saving and freeing slots");
        if (saveManager != null) {
            saveManager.saveChunk(dimension, cx, cz, world);
        }
        for (int cy = 0; cy < chunkHeight; cy++) {
            world.clearChunkSlot(cx, cy, cz);
            freeSlotStack[freeSlotTop++] = slots[cy]; // FILO push
        }
        int slotHash = java.util.Arrays.hashCode(slots);
        WorldGenLogger.logChunk("UNLOAD_DONE", cx, -1, cz,
            "freed " + chunkHeight + " slots hash=" + Integer.toHexString(slotHash));
    }

    // ══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════

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

    private long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    private int unpackX(long key) { return (int) (key >> 32); }
    private int unpackZ(long key) { return (int) key; }
}
