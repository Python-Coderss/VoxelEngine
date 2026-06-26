package com.voxel.world;

import com.voxel.GameLogger;
import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BiomeManager;
import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.voxel.world.WorldSaveManager;
import com.voxel.world.DimensionType;
import java.util.concurrent.ConcurrentHashMap;

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
    private final LightEngine mcLightEngine;
    private final BlockDataManager blockDataManager;
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

    // ── Lighting ──
    private volatile boolean lightsNeedUpload = false;

    // ── Dedicated lighting thread: processes all BFS work off the gen thread ──
    private final BlockingDeque<Runnable> lightQueue = new LinkedBlockingDeque<>();
    private final Thread lightThread;
    private volatile boolean lightRunning = true;
    // Cancelled light task keys: gen thread signals this before unload; light thread checks before doing work
    private final Set<Long> cancelledLightTasks = ConcurrentHashMap.newKeySet();

    // ── Deferred lighting: chunks waiting for 5×5 grid to load before light runs ──
    private final Set<Long> pendingLighting = new HashSet<>();
    private static final int LIGHT_GRID_RADIUS = 5; // 11×11 player zone: which chunks to light
    private static final int BFS_WAIT_RADIUS = 2;   // 5×5 BFS wait zone: 24 neighbors must be loaded before BFS runs

    // Buffer recentering constants
    private static final int RECENTER_MARGIN_CHUNKS = 16;
    private static final int BUFFER_HALF_CHUNKS = World.REGION_SIZE / 2;

    public ChunkManager(World world, WorldGenerator generator,
                        LightEngine mcLightEngine,
                        int renderDistance, WorldSaveManager saveManager, DimensionType dimension,
                        BiomeManager biomeManager, BlockDataManager blockDataManager) {
        this.world = world;
        this.generator = generator;
        this.mcLightEngine = mcLightEngine;
        this.saveManager = saveManager;
        this.biomeManager = biomeManager;
        this.blockDataManager = blockDataManager;
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

        // Start the dedicated lighting thread (offloads BFS from gen thread)
        lightThread = new Thread(this::runLightLoop, "Lighting");
        lightThread.setDaemon(true);
        lightThread.start();
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
    //  LIGHT THREAD LOOP — processes BFS work off the gen thread
    // ══════════════════════════════════════════════════════════════════

    private void runLightLoop() {
        while (lightRunning) {
            try {
                Runnable task = lightQueue.take();
                task.run();
            } catch (InterruptedException e) {
                if (!lightRunning) break;
            } catch (Exception e) {
                WorldGenLogger.log("LIGHT_THREAD error: " + e.getMessage());
                e.printStackTrace();
            }
        }
        // Drain remaining tasks on shutdown (unlike gen thread) so light pool is consistent
        Runnable task;
        while ((task = lightQueue.poll()) != null) {
            try { task.run(); } catch (Exception ignored) {}
        }
    }

    /**
     * Posts a lighting task to the dedicated light thread.
     * The task checks cancellation and verifies slots haven't changed (prevents stale-slot
     * corruption when a chunk is unloaded and immediately reloaded).
     * @param chunkKey The chunk key for cancellation tracking
     * @param expectedSlots The slot array the task should operate on, or null if dynamic
     * @param work The lighting work to perform
     */
    private void postLightTask(long chunkKey, Integer[] expectedSlots, Runnable work) {
        lightQueue.addLast(() -> {
            if (cancelledLightTasks.remove(chunkKey)) return; // cancelled by unload
            // Verify slots haven't changed (chunk wasn't unloaded and reloaded)
            if (expectedSlots != null && loadedChunks.get(chunkKey) != expectedSlots) return;
            lightingActiveCount.incrementAndGet();
            try {
                work.run();
            } finally {
                lightingActiveCount.decrementAndGet();
            }
        });
    }

    /** Convenience overload for tasks that don't capture slots (dynamic lookup). */
    private void postLightTask(long chunkKey, Runnable work) {
        postLightTask(chunkKey, null, work);
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

        int oldBlockId = world.getVoxel(x, y, z);
        world.setVoxel(x, y, z, type);

        int cx = x >> 4;
        int cy = y >> 4;
        int cz = z >> 4;

        // Always mark slot dirty synchronously so GPU sees voxel data change immediately.
        // Lighting BFS is deferred to light thread (async), but occlusion is baked sync.
        mcLightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
        dirtySlots.add(slot);

        // ── Immediate light pool seed on block break ──
        // When a block is broken, the shader samples the lightmap at the now-empty position
        // for adjacent faces (using hp + n * 0.5). If we don't update the light pool here,
        // those faces will render black because the light pool still contains the stale
        // values from when the solid block was there (sky=0, block=0).
        // We seed from the brightest neighbor as a rough approximation for the 1-frame gap
        // before the async BFS light task completes.
        if (type == 0) {
            int lx = x & 15, ly = y & 15, lz = z & 15;
            int maxSky = 0, maxBlock = 0;
            int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                int ns = world.getChunkSlot(nx, ny, nz);
                if (ns != World.EMPTY) {
                    int nlx = nx & 15, nly = ny & 15, nlz = nz & 15;
                    maxSky = Math.max(maxSky, world.getSkyLight(ns, nlx, nly, nlz));
                    maxBlock = Math.max(maxBlock, world.getBlockLight(ns, nlx, nly, nlz));
                }
            }
            world.setSkyLight(slot, lx, ly, lz, maxSky);
            world.setBlockLight(slot, lx, ly, lz, maxBlock);
            // Signal GPU upload immediately so the seeded values don't sit stale on CPU
            lightsNeedUpload = true;
            tableDirty.set(true);
        }

        // Post lighting updates to the dedicated light thread unconditionally
        // (block changes always affect light: placing blocks creates shadows, breaking lets light through)
        final int colCx = x >> 4;
        final int colCz = z >> 4;
        final int fOldBlockId = oldBlockId;
        long colKey = chunkKey(colCx, colCz);
        postLightTask(colKey, () -> {
            Set<Integer> aff = mcLightEngine.onBlockChanged(x, y, z, fOldBlockId);
            Integer[] colSlots = loadedChunks.get(colKey);
            if (colSlots != null) {
                aff.addAll(mcLightEngine.generateSkyLight(colCx, colCz, colSlots));
            }
            dirtySlots.addAll(aff);
            lightsNeedUpload = true;
            tableDirty.set(true);
        });
        return true;
    }

    /**
     * Sets a voxel with extra data (e.g. redstone power) without re-baking occlusion.
     * Thread-safe: called from any thread.
     */
    public boolean setVoxelWithData(int x, int y, int z, int type, int extra) {
        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return false;

        int oldBlockId = world.getVoxel(x, y, z);
        world.setVoxelWithData(x, y, z, type, extra);

        // Always mark dirty synchronously so GPU sees voxel data change immediately.
        dirtySlots.add(slot);

        // Post lighting updates to the dedicated light thread unconditionally
        final int colCx2 = x >> 4;
        final int colCz2 = z >> 4;
        final int fOldBlockId2 = oldBlockId;
        long colKey2 = chunkKey(colCx2, colCz2);
        postLightTask(colKey2, () -> {
            Set<Integer> aff = mcLightEngine.onBlockChanged(x, y, z, fOldBlockId2);
            Integer[] colSlots = loadedChunks.get(colKey2);
            if (colSlots != null) {
                aff.addAll(mcLightEngine.generateSkyLight(colCx2, colCz2, colSlots));
            }
            dirtySlots.addAll(aff);
            lightsNeedUpload = true;
            tableDirty.set(true);
        });
        return true;
    }

    // GPU upload queries (called from main thread)
    public Set<Integer> getDirtySlots() { return dirtySlots; }
    public boolean isTableDirty() { return tableDirty.get(); }
    public void clearDirty() { tableDirty.set(false); dirtySlots.clear(); }
    public void clearTableDirtyOnly() { tableDirty.set(false); }
    public boolean isBiomeMapDirty() { return biomeMapDirty.get(); }
    public void clearBiomeMapDirty() { biomeMapDirty.set(false); }
    public void markBiomeMapDirty() { biomeMapDirty.set(true); }
    public boolean needsLightUpload() { return lightsNeedUpload; }
    public void clearLightUpload() { lightsNeedUpload = false; }
    public Map<Long, Integer[]> getLoadedChunks() { return loadedChunks; }

    // volatile guard: true while any thread (gen or light) is modifying light pool
    private final AtomicInteger lightingActiveCount = new AtomicInteger(0);
    public boolean isLightingActive() { return lightingActiveCount.get() > 0; }

    public void shutdown() {
        running = false;
        taskQueue.clear(); // Discard stale tasks from old dimension
        genThread.interrupt();
        try { genThread.join(5000); } catch (InterruptedException ignored) {}

        // Shut down light thread
        lightRunning = false;
        lightThread.interrupt();
        try { lightThread.join(5000); } catch (InterruptedException ignored) {}
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

            // Split: 11×11 grid first (LIGHT_GRID_RADIUS=5), then outer.
            // The 11×11 must load first so lighting BFS can propagate across chunk borders.
            List<int[]> inner = new ArrayList<>();
            List<int[]> outer = new ArrayList<>();
            for (int[] pos : chunksToLoad) {
                int dx = pos[0] - pcx, dz = pos[1] - pcz;
                if (Math.abs(dx) <= LIGHT_GRID_RADIUS && Math.abs(dz) <= LIGHT_GRID_RADIUS) {
                    inner.add(pos);
                } else {
                    outer.add(pos);
                }
            }

            WorldGenLogger.log("MANAGE playerChunk(" + pcx + "," + pcz + ") queueing "
                + inner.size() + " inner + " + outer.size() + " outer (loaded=" + loadedChunks.size() + ")");

            // Load inner 11×11 grid first, then outer
            for (int[] pos : inner) {
                int cx = pos[0], cz = pos[1];
                taskQueue.addLast(() -> loadChunk(cx, cz));
            }
            for (int[] pos : outer) {
                int cx = pos[0], cz = pos[1];
                taskQueue.addLast(() -> loadChunk(cx, cz));
            }

            // Queue a flush task: after all chunks load, run pending lighting unconditionally.
            // The 5×5 cascade cannot complete on its own because edge chunks' 5×5 grids
            // extend into newly loaded outer territory which itself gets deferred — infinite chain.
            taskQueue.addLast(this::flushPendingLighting);

            // Lighting is handled per-source on each loadChunk call
        }

        // ── Unload chunks outside keep set (deterministic order) ──
        List<Long> toUnload = new ArrayList<>();
        for (Map.Entry<Long, Integer[]> entry : loadedChunks.entrySet()) {
            if (!keep.contains(entry.getKey())) {
                toUnload.add(entry.getKey());
            }
        }
        toUnload.sort(Long::compareTo);  // deterministic unload order
        for (Long key : toUnload) {
            Integer[] slots = loadedChunks.remove(key);
            if (slots != null) {
                unloadChunk(key, slots);
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
        // Clear any stale cancellation from a previous unload of this chunk position
        cancelledLightTasks.remove(key);
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }

        // Try disk load first
        if (saveManager != null && saveManager.loadChunk(dimension, cx, cz, world)) {
            for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
                world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
            }
            WorldGenLogger.logChunk("LOAD_DISK", cx, -1, cz, "loaded from disk, clearing stale light pool and baking occlusion");
            // Clear stale light pool data from recycled slots
            for (int cy = 0; cy < chunkHeight; cy++) {
                int s = slots[cy];
                world.clearLightPoolSlot(s);
                mcLightEngine.bakeChunkOcclusion(s, cx, cy, cz);
                dirtySlots.add(s);
            }
            tableDirty.set(true);
            // Minecraft-style lighting: posted to dedicated light thread
            if (Math.abs(cx - lastPlayerCX) <= LIGHT_GRID_RADIUS && Math.abs(cz - lastPlayerCZ) <= LIGHT_GRID_RADIUS) {
                if (is5x5Loaded(cx, cz)) {
                    postLightTask(key, slots, () -> {
                        dirtySlots.addAll(mcLightEngine.generateSkyLight(cx, cz, slots));
                        for (int cy = 0; cy < chunkHeight; cy++) {
                            dirtySlots.addAll(mcLightEngine.propagateBlockLight(cx, cy, cz, slots[cy]));
                        }
                        lightsNeedUpload = true;
                        tableDirty.set(true);
                        runPendingLightingIn5x5(cx, cz);
                    });
                } else {
                    pendingLighting.add(chunkKey(cx, cz));
                    GameLogger.log("LIGHT deferred chunk(" + cx + "," + cz + ") waiting for 5×5 grid");
                }
            }
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
            mcLightEngine.bakeChunkOcclusion(slots[cy], cx, cy, cz);
            dirtySlots.add(slots[cy]);
        }
        WorldGenLogger.logChunk("GEN_PASS3_BAKE", cx, -1, cz,
            "occlusion baked (" + (System.currentTimeMillis() - t3) + "ms)");

        // Pass 4: Commit + Minecraft-style lighting
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }
        tableDirty.set(true);
        // Lighting only within 11×11 grid around player; posted to dedicated light thread
        if (Math.abs(cx - lastPlayerCX) <= LIGHT_GRID_RADIUS && Math.abs(cz - lastPlayerCZ) <= LIGHT_GRID_RADIUS) {
            if (is5x5Loaded(cx, cz)) {
                postLightTask(key, slots, () -> {
                    dirtySlots.addAll(mcLightEngine.generateSkyLight(cx, cz, slots));
                    for (int cy = 0; cy < chunkHeight; cy++) {
                        dirtySlots.addAll(mcLightEngine.propagateBlockLight(cx, cy, cz, slots[cy]));
                    }
                    lightsNeedUpload = true;
                    tableDirty.set(true);
                    runPendingLightingIn5x5(cx, cz);
                });
            } else {
                pendingLighting.add(chunkKey(cx, cz));
                GameLogger.log("LIGHT deferred chunk(" + cx + "," + cz + ") waiting for 5×5 grid");
            }
        }
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

        // Cancel any pending light tasks for this chunk BEFORE freeing slots
        cancelledLightTasks.add(key);

        if (saveManager != null) {
            saveManager.saveChunk(dimension, cx, cz, world);
        }
        for (int cy = 0; cy < chunkHeight; cy++) {
            world.clearChunkSlot(cx, cy, cz);
            world.clearLightPoolSlot(slots[cy]);
            freeSlotStack[freeSlotTop++] = slots[cy];
        }
        int slotHash = java.util.Arrays.hashCode(slots);
        WorldGenLogger.logChunk("UNLOAD_DONE", cx, -1, cz,
            "freed " + chunkHeight + " slots hash=" + Integer.toHexString(slotHash));
    }

    // ══════════════════════════════════════════════════════════════════
    //  LIGHTING: Minecraft-style rebuild
    // ══════════════════════════════════════════════════════════════════

    /**
     * Rebuilds all lighting from scratch using Minecraft-style dual-channel approach.
     * Posts to front of gen thread queue; world gen pauses until complete.
     */
    public void rebuildAllLighting(Vector3f playerPos) {
        taskQueue.addFirst(() -> {
            lightsNeedUpload = false;
            lightingActiveCount.incrementAndGet();
            long t0 = System.currentTimeMillis();

            Set<Integer> dirty = mcLightEngine.rebuildAllLighting(loadedChunks);
            dirtySlots.addAll(dirty);
            tableDirty.set(true);
            lightsNeedUpload = true;
            lightingActiveCount.decrementAndGet();

            long elapsed = System.currentTimeMillis() - t0;
            GameLogger.log("LIGHT rebuild done: " + dirty.size() + " dirty slots, " + elapsed + "ms");
        });
    }

    /**
     * Prints block light and sky light values around the given position for debugging.
     * Called from the game thread (F3+L hotkey).
     */
    public void dumpBlockLight(Vector3f pos) {
        int px = (int) Math.floor(pos.x);
        int py = (int) Math.floor(pos.y);
        int pz = (int) Math.floor(pos.z);
        int range = 4;
        System.out.println("=== Block Light Debug at (" + px + ", " + py + ", " + pz + ") ===");
        for (int y = py + range; y >= py - range; y--) {
            StringBuilder line = new StringBuilder(String.format("y=%3d: ", y));
            for (int x = px - range; x <= px + range; x++) {
                int blockId = world.getVoxel(x, y, pz);
                int sky = 0, block = 0;
                int slot = world.getChunkSlot(x, y, pz);
                if (slot != World.EMPTY) {
                    int lx = x & 15, ly = y & 15, lz = pz & 15;
                    sky = world.getSkyLight(slot, lx, ly, lz);
                    block = world.getBlockLight(slot, lx, ly, lz);
                }
                if (blockId > 0) {
                    line.append(String.format("[%s]S%dB%d ", blockDataManager.getName(blockId).substring(0, Math.min(4, blockDataManager.getName(blockId).length())), sky, block));
                } else if (sky > 0 || block > 0) {
                    line.append(String.format("[air]S%dB%d ", sky, block));
                } else {
                    line.append("...... ");
                }
            }
            System.out.println(line.toString());
        }
    }

    /** @return the BlockDataManager for light debug queries */
    public BlockDataManager getBlockDataManager() { return blockDataManager; }

    // ══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Shared progress bar: prints a 50-char bar to console with carriage return.
     * Only prints when the percentage changes.
     */
    private static int printProgressBar(int current, int total, int lastPct, String prefix) {
        if (total <= 0) return lastPct;
        int pct = current * 100 / total;
        if (pct == lastPct) return lastPct;
        int barLen = pct / 2;
        StringBuilder bar = new StringBuilder("\r  [");
        for (int j = 0; j < 50; j++) bar.append(j < barLen ? '=' : j == barLen ? '>' : ' ');
        bar.append("] ").append(pct).append("% ").append(prefix).append(' ').append(current).append('/').append(total);
        System.out.print(bar.toString());
        return pct;
    }

    // ── Lighting grid check: 5×5 (center + 24 neighbors) fully loaded? ──
    private boolean is5x5Loaded(int cx, int cz) {
        for (int dx = -BFS_WAIT_RADIUS; dx <= BFS_WAIT_RADIUS; dx++) {
            for (int dz = -BFS_WAIT_RADIUS; dz <= BFS_WAIT_RADIUS; dz++) {
                if (!loadedChunks.containsKey(chunkKey(cx + dx, cz + dz))) return false;
            }
        }
        return true;
    }

    /**
     * Flush pending lighting using the Minecraft-style LightEngine.
     * Called at the end of a manageChunks cycle.
     */
    private void flushPendingLighting() {
        if (pendingLighting.isEmpty()) return;
        int totalPending = pendingLighting.size();

        // Count how many are within the 11×11 grid
        int inRange = 0;
        List<Long> snapshot = new ArrayList<>(pendingLighting);
        for (Long nk : snapshot) {
            int cx = unpackX(nk), cz = unpackZ(nk);
            if (Math.abs(cx - lastPlayerCX) <= LIGHT_GRID_RADIUS && Math.abs(cz - lastPlayerCZ) <= LIGHT_GRID_RADIUS) {
                inRange++;
            }
        }

        GameLogger.log("LIGHT flush " + inRange + "/" + totalPending + " chunks within 11×11 of player(" + lastPlayerCX + "," + lastPlayerCZ + ")");
        if (inRange == 0) return;
        System.out.println("Flushing " + inRange + " pending lighting chunks (" + (totalPending - inRange) + " outside 11×11)...");

        for (Long nk : snapshot) {
            int cx = unpackX(nk), cz = unpackZ(nk);
            // Only flush chunks within the 11×11 grid around the player
            if (Math.abs(cx - lastPlayerCX) > LIGHT_GRID_RADIUS || Math.abs(cz - lastPlayerCZ) > LIGHT_GRID_RADIUS) {
                continue;
            }
            if (!pendingLighting.remove(nk)) continue;
            Integer[] nslots = loadedChunks.get(nk);
            if (nslots == null) continue;
            if (is5x5Loaded(cx, cz)) {
                postLightTask(nk, nslots, () -> {
                    dirtySlots.addAll(mcLightEngine.generateSkyLight(cx, cz, nslots));
                    for (int cy = 0; cy < chunkHeight; cy++) {
                        dirtySlots.addAll(mcLightEngine.propagateBlockLight(cx, cy, cz, nslots[cy]));
                    }
                    lightsNeedUpload = true;
                    tableDirty.set(true);
                    runPendingLightingIn5x5(cx, cz);
                });
            } else {
                pendingLighting.add(chunkKey(cx, cz));
                GameLogger.log("LIGHT defer (flush) chunk(" + cx + "," + cz + ") 5×5 not loaded");
            }
        }
        GameLogger.log("LIGHT flush complete — remaining pending: " + pendingLighting.size());
    }

    // ── Run lighting for any pending chunks whose 5×5 grid is now loaded ──
    private void runPendingLightingIn5x5(int cx, int cz) {
        for (int dx = -BFS_WAIT_RADIUS; dx <= BFS_WAIT_RADIUS; dx++) {
            for (int dz = -BFS_WAIT_RADIUS; dz <= BFS_WAIT_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = cx + dx, nz = cz + dz;
                Long nk = chunkKey(nx, nz);
                if (pendingLighting.contains(nk) && is5x5Loaded(nx, nz)) {
                    pendingLighting.remove(nk);
                    Integer[] nslots = loadedChunks.get(nk);
                    if (nslots != null) {
                        int finalNx = nx, finalNz = nz; // capture for lambda
                        postLightTask(nk, nslots, () -> {
                            dirtySlots.addAll(mcLightEngine.generateSkyLight(finalNx, finalNz, nslots));
                            for (int cy = 0; cy < chunkHeight; cy++) {
                                dirtySlots.addAll(mcLightEngine.propagateBlockLight(finalNx, cy, finalNz, nslots[cy]));
                            }
                            lightsNeedUpload = true;
                            tableDirty.set(true);
                        });
                    }
                }
            }
        }
    }

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
