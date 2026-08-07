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
    // Y-load range: number of sections above/below player to keep loaded
    private final int yLoadRadius;

    // ── Chunk map (ConcurrentHashMap for safe cross-thread reads via isChunkLoaded) ──
    // Column sections: column key (cx,cz) → NavigableMap<cy, slot>
    // Each column can have any set of cy sections loaded, not a fixed count.
    private final Map<Long, NavigableMap<Integer, Integer>> loadedChunks = new ConcurrentHashMap<>();
    // Columns whose immediate 3-section spawn area has finished generation/loading.
    private final Set<Long> fullyGeneratedColumns = ConcurrentHashMap.newKeySet();
    // Procedurally generated Beta columns whose decoration was skipped during
    // bootstrap. Gen-thread confined; consumed after spawn is released.
    private final Set<Long> deferredBetaDecoration = new HashSet<>();
    private boolean deferredBetaDecorationQueued = false;
    private volatile boolean deferredBetaDecorationCancelled = false;
    private final AtomicBoolean spawnRetryQueued = new AtomicBoolean(false);
    // The first manage cycle only needs the immediate spawn cube. Do not queue
    // thousands of render-distance sections before the player can leave the
    // loading screen; the full stream is enabled after spawn resolution.
    private volatile boolean spawnBootstrap = true;



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
    private final Runnable deferredBetaDecorationTask = this::runDeferredBetaDecoration;
    private final Thread genThread;
    private volatile boolean running = true;

    private int lastPlayerCX = -1000, lastPlayerCZ = -1000, lastPlayerCY = -1000;
    private float lastYaw = 0;

    // ── Lighting ──
    private volatile boolean lightsNeedUpload = false;

    // Slots whose light-pool slice is currently being rebuilt by the light thread
    // (cleared to zeros on the CPU pool). The render thread must NOT upload these
    // slices — doing so pushes a transient all-zero light state to the GPU, which
    // renders as a BLACK frame on block break/place. The slot stays dirty until
    // the rebuild task finishes, then the final light is uploaded.
    private final Set<Integer> lightRebuildPending = ConcurrentHashMap.newKeySet();

    // ── Dedicated lighting thread: processes all BFS work off the gen thread ──
    private final BlockingDeque<Runnable> lightQueue = new LinkedBlockingDeque<>();
    private final Thread lightThread;
    private volatile boolean lightRunning = true;
    // Cancelled light task keys: gen thread signals this before unload; light thread checks before doing work
    private final Set<Long> cancelledLightTasks = ConcurrentHashMap.newKeySet();

    // ── Deferred lighting: chunks waiting for 5×5 grid to load before light runs ──
    private final Set<Long> pendingLighting = new HashSet<>();
    // Coalesces relights caused by sections arriving above/below an already loaded
    // section. The work itself always runs on the single Lighting thread.
    private final Set<Long> queuedColumnRelights = ConcurrentHashMap.newKeySet();
    // Set when another section arrives while a column relight is already queued;
    // the light worker schedules one follow-up pass after the current pass.
    private final Set<Long> relightAgain = ConcurrentHashMap.newKeySet();
    // Pool slots captured by an in-flight or queued lighting snapshot. The gen
    // thread will not release these slots until the Lighting worker finishes.
    private final ConcurrentHashMap<Integer, AtomicInteger> lightPinnedSlots = new ConcurrentHashMap<>();

    // ── Per-column section load tracking (gen thread only) ──
    // Track which sections of a column are currently being generated.
    // A column has an entry here while its first section is being generated;
    // it's removed when the column's load cycle is complete.
    private final Map<Long, Set<Integer>> columnSectionsLoaded = new HashMap<>();
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
        // Y load range: match the XZ render distance in section units
        this.yLoadRadius = renderDistance * 2;

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
    private void postLightTask(long chunkKey, NavigableMap<Integer, Integer> expectedSlots, Runnable work) {
        postLightTask(chunkKey, expectedSlots, work, () -> { });
    }

    /** Posts a task with a callback for the stale/cancelled case. */
    private void postLightTask(long chunkKey, NavigableMap<Integer, Integer> expectedSlots,
                               Runnable work, Runnable onSkipped) {
        lightQueue.addLast(() -> {
            boolean cancelled = cancelledLightTasks.remove(chunkKey);
            boolean valid = running && lightRunning && !cancelled
                    && (expectedSlots == null || loadedChunks.get(chunkKey) == expectedSlots);
            if (!valid) {
                onSkipped.run();
                return;
            }
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
        int pcy = (int) Math.floor(playerPos.y) >> 4;
        int pcz = (int) Math.floor(playerPos.z) >> 4;

        // Trigger on any chunk-coordinate change: X, Y, Z, or teleport
        if (pcx != lastPlayerCX || pcy != lastPlayerCY || pcz != lastPlayerCZ) {
            lastPlayerCX = pcx;
            lastPlayerCY = pcy;
            lastPlayerCZ = pcz;
            lastYaw = yaw;

            // Push to front of queue — manage preempts stale load tasks
            int pcxFinal = pcx, pcyFinal = pcy, pczFinal = pcz;
            float yawFinal = yaw;
            taskQueue.addFirst(() -> manageChunks(pcxFinal, pcyFinal, pczFinal, yawFinal));
        }
    }

    /**
     * Thread-safe: reads ConcurrentHashMap, safe from any thread.
     */
    public boolean isChunkLoaded(int cx, int cz) {
        return loadedChunks.containsKey(chunkKey(cx, cz));
    }

    /**
     * Returns true only after the 3x3 XZ spawn area has completed its immediate
     * section generation. A column entry alone is not sufficient because it is
     * published before its sections are filled.
     */
    public boolean areSpawnChunksGenerated(int centerCx, int centerCz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!fullyGeneratedColumns.contains(chunkKey(centerCx + dx, centerCz + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Requeues the immediate spawn-area generation when the initial pass could
     * not allocate every required section. The guard prevents a loading frame
     * from flooding the generation queue while the worker is still busy.
     */
    public void retrySpawnGeneration(Vector3f playerPos, float yaw) {
        int pcx = (int) Math.floor(playerPos.x) >> 4;
        int pcy = (int) Math.floor(playerPos.y) >> 4;
        int pcz = (int) Math.floor(playerPos.z) >> 4;
        if (!spawnRetryQueued.compareAndSet(false, true)) return;
        taskQueue.addFirst(() -> {
            try {
                manageChunks(pcx, pcy, pcz, yaw);
            } finally {
                spawnRetryQueued.set(false);
            }
        });
    }

    /**
     * Enables normal render-distance streaming after the immediate spawn area
     * has been resolved. The next update is forced to queue the full stream.
     */
    public void finishSpawnBootstrap() {
        spawnBootstrap = false;
        lastPlayerCX = -1000;
        lastPlayerCY = -1000;
        lastPlayerCZ = -1000;

        // Beta decoration performs neighbor-column copies and is much more
        // expensive than the immediate terrain needed to find a spawn. The
        // first post-bootstrap manage cycle consumes the deferred set after
        // the loading gate opens.
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

        // Notify fluid manager: block changes may affect fluid flow
        // (placing blocks blocks flow, breaking lets fluid spread)
        if (fluidManager != null) {
            fluidManager.notifyBlockChanged(x, y, z);
        }

        // Post lighting updates to the dedicated light thread unconditionally
        // (block changes always affect light: placing blocks creates shadows, breaking lets light through)
        final int colCx = x >> 4;
        final int colCz = z >> 4;
        final int fOldBlockId = oldBlockId;
        long colKey = chunkKey(colCx, colCz);
        postLightTask(colKey, () -> {
            try {
                Set<Integer> aff = mcLightEngine.onBlockChanged(x, y, z, fOldBlockId, lightRebuildPending);
                NavigableMap<Integer, Integer> colSlots = loadedChunks.get(colKey);
                if (colSlots != null) {
                    aff.addAll(mcLightEngine.generateSkyLight(colCx, colCz, colSlots));
                }
                dirtySlots.addAll(aff);
                lightsNeedUpload = true;
                tableDirty.set(true);
            } finally {
                // Rebuild complete: release the pending guard so the render thread
                // uploads the final converged light.
                lightRebuildPending.clear();
            }
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
            try {
                Set<Integer> aff = mcLightEngine.onBlockChanged(x, y, z, fOldBlockId2, lightRebuildPending);
                NavigableMap<Integer, Integer> colSlots = loadedChunks.get(colKey2);
                if (colSlots != null) {
                    aff.addAll(mcLightEngine.generateSkyLight(colCx2, colCz2, colSlots));
                }
                dirtySlots.addAll(aff);
                lightsNeedUpload = true;
                tableDirty.set(true);
            } finally {
                // Rebuild complete: release the pending guard so the render thread
                // uploads the final converged light.
                lightRebuildPending.clear();
            }
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

    /**
     * Generates the full biome noise map after the spawn bootstrap task. The
     * neutral fallback remains bound while this runs, so biome generation never
     * delays the first playable frame and still stays on the single world-gen
     * worker rather than introducing another generator thread.
     */
    public void queueBiomeMapGeneration() {
        taskQueue.addLast(() -> {
            if (!running || biomeManager == null || biomeManager.getBiomeProvider() == null) return;
            long t0 = System.currentTimeMillis();
            biomeManager.generateBiomeData(World.REGION_SIZE * World.CHUNK_SIZE);
            biomeMapDirty.set(true);
            System.out.println("[BOOT] biome map ready " + (System.currentTimeMillis() - t0) + " ms");
        });
    }

    public boolean needsLightUpload() { return lightsNeedUpload; }
    public void clearLightUpload() { lightsNeedUpload = false; }
    public boolean isLightRebuildPending(int slot) { return lightRebuildPending.contains(slot); }
    public Map<Long, NavigableMap<Integer, Integer>> getLoadedChunks() { return loadedChunks; }

    // volatile guard: true while any thread (gen or light) is modifying light pool
    private final AtomicInteger lightingActiveCount = new AtomicInteger(0);
    public boolean isLightingActive() { return lightingActiveCount.get() > 0; }

    public void shutdown() {
        // Publish cancellation before taking the lifecycle lock so an already
        // running deferred pass can stop between columns instead of making
        // shutdown wait for the entire expensive population pass.
        running = false;
        deferredBetaDecorationCancelled = true;
        synchronized (this) {
            deferredBetaDecorationQueued = false;
        }
        taskQueue.clear(); // Discard stale tasks from old dimension
        genThread.interrupt();
        try { genThread.join(5000); } catch (InterruptedException ignored) {}

        // Shut down light thread
        lightRunning = false;
        lightThread.interrupt();
        try { lightThread.join(5000); } catch (InterruptedException ignored) {}
    }

    private com.voxel.world.FluidManager fluidManager;

    /**
     * Sets the fluid manager for block change notifications.
     */
    public void setFluidManager(com.voxel.world.FluidManager fm) {
        this.fluidManager = fm;
    }

    // ══════════════════════════════════════════════════════════════════
    //  CHUNK MANAGEMENT — runs only on the gen thread
    // ══════════════════════════════════════════════════════════════════

    // Chunks within this radius of the player are never unloaded (persistent cache).
    private static final int KEEP_RADIUS = 32;

    private void manageChunks(int pcx, int pcy, int pcz, float yaw) {
        long t0 = System.currentTimeMillis();

        // ── 3×3×3 grid: ensure all 27 chunks around player are loaded synchronously ──
        ensure3x3x3Loaded(pcx, pcy, pcz);

        // Spawn readiness must not wait for the complete render-distance ring.
        // The initial 3×3×3 cube above is enough for surface detection and keeps
        // Beta's expensive decoration/noise work off the critical startup path.
        if (spawnBootstrap) {
            recenterIfNeeded(pcx, pcy, pcz);
            return;
        }

        // Deferred Beta decoration is consumed by runGenLoop after the queued
        // terrain stream drains, so it cannot block this first post-bootstrap
        // manage cycle.

        // Maximum distance: 2× forward, 1× sideways/back
        int maxDist = renderDistance * 2;

        // ── Build keep set: visible chunks + 32-radius persistence zone ──
        Set<Long> keep = new HashSet<>();
        List<int[]> chunksToLoad = new ArrayList<>();

        for (int dx = -maxDist; dx <= maxDist; dx++) {
            for (int dz = -maxDist; dz <= maxDist; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;

                int ring = Math.max(Math.abs(dx), Math.abs(dz));

                if (ring > renderDistance) continue;

                long key = chunkKey(cx, cz);
                keep.add(key);
                if (!loadedChunks.containsKey(key)) {
                    chunksToLoad.add(new int[]{cx, cz});
                } else {
                    // Column is in loadedChunks but may be PARTIALLY loaded
                    // (sections lost when queue was cleared mid-load by a new manageChunks call).
                    // Re-queue to load the missing sections.
                    Set<Integer> loaded = columnSectionsLoaded.get(key);
                    if (loaded != null && !loaded.isEmpty()) {
                        chunksToLoad.add(new int[]{cx, cz});
                    }
                }
            }
        }

        // Add all chunks within KEEP_RADIUS to the keep set (never unload).
        for (int dx = -KEEP_RADIUS; dx <= KEEP_RADIUS; dx++) {
            for (int dz = -KEEP_RADIUS; dz <= KEEP_RADIUS; dz++) {
                keep.add(chunkKey(pcx + dx, pcz + dz));
            }
        }

        // ── Unload chunks outside keep set BEFORE recenter ──
        // Must unload (save) first: recenterIfNeeded clears the indirection table,
        // and saveChunk reads voxels via world.getVoxel() which depends on it.
        int unloadedCount = 0;
        List<Long> toUnload = new ArrayList<>();
        for (Map.Entry<Long, NavigableMap<Integer, Integer>> entry : loadedChunks.entrySet()) {
            if (!keep.contains(entry.getKey())) {
                toUnload.add(entry.getKey());
            }
        }
        toUnload.sort(Long::compareTo);
        for (Long key : toUnload) {
            NavigableMap<Integer, Integer> slots = loadedChunks.get(key);
            if (slots == null || hasPinnedSlot(slots)) continue;
            slots = loadedChunks.remove(key);
            if (slots != null) {
                fullyGeneratedColumns.remove(key);
                unloadChunk(key, slots);
                tableDirty.set(true);
                unloadedCount++;
            }
        }

        recenterIfNeeded(pcx, pcy, pcz);

        // ── Compute current Y-range ──
        int yMin = pcy - yLoadRadius;
        int yMax = pcy + yLoadRadius;

        // ── Queue new columns in 2D XZ order (player 3×3 first) ──
        List<Integer> yOrder = orderedSections(yMin, yMax);
        if (!chunksToLoad.isEmpty()) {
            // Remove the deferred-population sentinel wherever it is in the
            // queue before pruning. Otherwise clear() could silently drop it
            // while leaving deferredBetaDecorationQueued=true forever.
            if (taskQueue.removeFirstOccurrence(deferredBetaDecorationTask)) {
                deferredBetaDecorationQueued = false;
            }

            // Keep the last 2 tasks so the gen thread never idles between swaps.
            Runnable keep1 = taskQueue.pollLast();
            Runnable keep2 = taskQueue.pollLast();
            taskQueue.clear();
            if (keep2 != null) taskQueue.addFirst(keep2);
            if (keep1 != null) taskQueue.addFirst(keep1);

            // Sort missing columns by player-centered 3×3 priority, then
            // distance and view angle. This keeps the immediate 3×3 grid ahead
            // of the rest of the render-distance stream.
            float lookX = (float) Math.cos(Math.toRadians(yaw));
            float lookZ = (float) Math.sin(Math.toRadians(yaw));
            chunksToLoad.sort((a, b) -> {
                int dxA = a[0] - pcx, dzA = a[1] - pcz;
                int dxB = b[0] - pcx, dzB = b[1] - pcz;
                boolean immediateA = isPlayer3x3(dxA, dzA);
                boolean immediateB = isPlayer3x3(dxB, dzB);
                if (immediateA != immediateB) return immediateA ? -1 : 1;
                int distA = Math.max(Math.abs(dxA), Math.abs(dzA));
                int distB = Math.max(Math.abs(dxB), Math.abs(dzB));
                if (distA != distB) return Integer.compare(distA, distB);
                float angleA = (float) Math.abs(Math.atan2(dzA, dxA) - Math.atan2(lookZ, lookX));
                float angleB = (float) Math.abs(Math.atan2(dzB, dxB) - Math.atan2(lookZ, lookX));
                if (angleA > Math.PI) angleA = (float) (2.0 * Math.PI - angleA);
                if (angleB > Math.PI) angleB = (float) (2.0 * Math.PI - angleB);
                return Float.compare(angleA, angleB);
            });

            // Queue: for each column (nearest first), queue all Y-range sections
            // high-to-low so the upper view becomes available first.
            // in Y-spiral order.
            for (int[] col : chunksToLoad) {
                int cx = col[0], cz = col[1];
                long key = chunkKey(cx, cz);
                NavigableMap<Integer, Integer> colSlots = loadedChunks.get(key);
                Set<Integer> loading = columnSectionsLoaded.get(key);
                for (int cy : yOrder) {
                    if ((colSlots != null && colSlots.containsKey(cy)) ||
                        (loading != null && loading.contains(cy))) continue;
                    final int fcy = cy;
                    taskQueue.addLast(() -> loadOneSection(cx, fcy, cz));
                }
            }

            // Queue a flush task: after all sections load, run pending lighting
            taskQueue.addLast(this::flushPendingLighting);
        }

        // ── Expand existing columns: queue missing Y sections when player moves vertically ──
        // Must run AFTER queue clear (above) so tasks are not lost.
        int expandedCount = 0;
        List<Map.Entry<Long, NavigableMap<Integer, Integer>>> expansionColumns =
                new ArrayList<>(loadedChunks.entrySet());
        expansionColumns.sort((a, b) -> compareColumnPriority(a.getKey(), b.getKey(), pcx, pcz));
        for (Map.Entry<Long, NavigableMap<Integer, Integer>> entry : expansionColumns) {
            NavigableMap<Integer, Integer> colSlots = entry.getValue();
            for (int cy : yOrder) {
                if (!colSlots.containsKey(cy)) {
                    long key = entry.getKey();
                    int cx = unpackX(key);
                    int cz = unpackZ(key);
                    Set<Integer> loading = columnSectionsLoaded.get(key);
                    if (loading != null && loading.contains(cy)) continue;
                    final int fcy = cy;
                    taskQueue.addLast(() -> loadOneSection(cx, fcy, cz));
                    expandedCount++;
                }
            }
        }

        if (!spawnBootstrap && !deferredBetaDecoration.isEmpty()
                && !deferredBetaDecorationQueued) {
            deferredBetaDecorationQueued = true;
            taskQueue.addLast(deferredBetaDecorationTask);
        }

        if (!chunksToLoad.isEmpty() || expandedCount > 0) {
            int totalSections = chunksToLoad.size() * (yMax - yMin + 1) + expandedCount;
            WorldGenLogger.log("MANAGE player(" + pcx + "," + pcy + "," + pcz + ") queueing "
                + chunksToLoad.size() + " columns + " + expandedCount + " Y-expansions, "
                + totalSections + " sections total, loaded=" + loadedChunks.size()
                + " (" + (System.currentTimeMillis() - t0) + "ms)");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  RECENTER — runs only on the gen thread, zero races
    // ══════════════════════════════════════════════════════════════════

    private void recenterIfNeeded(int pcx, int pcy, int pcz) {
        // The initial world buffer starts at (0,0,0), and the default spawn is
        // already safely inside its positive coordinate range. Re-centering it
        // immediately would clear the entire 128³ indirection table before the
        // loading screen can finish (a several-second operation on some JVMs).
        // Wait until the player actually approaches a buffer edge.
        if (canSkipInitialRecenter(spawnBootstrap, world.getOffsetX(), world.getOffsetY(),
                world.getOffsetZ(), pcx, pcy, pcz)) {
            return;
        }

        int bufMinX = world.getOffsetX() >> 4;
        int bufMinY = world.getOffsetY() >> 4;
        int bufMinZ = world.getOffsetZ() >> 4;
        int bufMaxX = bufMinX + World.REGION_SIZE;
        int bufMaxY = bufMinY + World.REGION_SIZE;
        int bufMaxZ = bufMinZ + World.REGION_SIZE;

        if (pcx >= bufMinX + RECENTER_MARGIN_CHUNKS && pcx < bufMaxX - RECENTER_MARGIN_CHUNKS &&
            pcy >= bufMinY + RECENTER_MARGIN_CHUNKS && pcy < bufMaxY - RECENTER_MARGIN_CHUNKS &&
            pcz >= bufMinZ + RECENTER_MARGIN_CHUNKS && pcz < bufMaxZ - RECENTER_MARGIN_CHUNKS) {
            return;
        }

        int newOffsetX = (pcx - BUFFER_HALF_CHUNKS) << 4;
        int newOffsetY = (pcy - BUFFER_HALF_CHUNKS) << 4;
        int newOffsetZ = (pcz - BUFFER_HALF_CHUNKS) << 4;

        // Capture old biome offsets before recentering
        int oldBiomeX = lastBiomeOffsetX;
        int oldBiomeZ = lastBiomeOffsetZ;

        WorldGenLogger.log("RECENTER oldOffset(" + world.getOffsetX() + "," + world.getOffsetY() + "," + world.getOffsetZ()
            + ") -> newOffset(" + newOffsetX + "," + newOffsetY + "," + newOffsetZ
            + ") loadedChunks=" + loadedChunks.size());

        // Slide the biome map on the gen thread alongside recenter
        if (biomeManager != null) {
            biomeManager.slideBiomeMap(oldBiomeX, oldBiomeZ, newOffsetX, newOffsetZ);
            lastBiomeOffsetX = newOffsetX;
            lastBiomeOffsetZ = newOffsetZ;
            biomeMapDirty.set(true);
        }

        // Clear the indirection table
        world.setOrigin(newOffsetX, newOffsetY, newOffsetZ);

        int kept = 0;

        // Re-register every chunk — no snapshot, no races, single thread.
        for (Map.Entry<Long, NavigableMap<Integer, Integer>> entry : loadedChunks.entrySet()) {
            long key = entry.getKey();
            int absCX = unpackX(key);
            int absCZ = unpackZ(key);
            int relCX = absCX - (newOffsetX >> 4);
            int relCZ = absCZ - (newOffsetZ >> 4);

            if (relCX >= 0 && relCX < World.REGION_SIZE && relCZ >= 0 && relCZ < World.REGION_SIZE) {
                NavigableMap<Integer, Integer> slots = entry.getValue();
                for (Map.Entry<Integer, Integer> se : slots.entrySet()) {
                    world.setChunkSlot(absCX, se.getKey(), absCZ, se.getValue());
                }
                kept++;
            }
        }

        tableDirty.set(true);
        WorldGenLogger.log("RECENTER done: kept=" + kept
            + " offset(" + newOffsetX + "," + newOffsetY + "," + newOffsetZ + ")");
        System.out.println("Recentered buffer: offset now (" + newOffsetX + ", " + newOffsetY + ", " + newOffsetZ + ")");
    }


    // ══════════════════════════════════════════════════════════════════
    //  CHUNK UNLOAD — runs only on the gen thread
    // ══════════════════════════════════════════════════════════════════

    private boolean hasPinnedSlot(NavigableMap<Integer, Integer> slots) {
        for (Integer slot : slots.values()) {
            if (isLightSlotPinned(slot)) return true;
        }
        return false;
    }

    private Set<Integer> pinLightSlots(NavigableMap<Integer, Integer> snapshot) {
        Set<Integer> pinned = new HashSet<>(snapshot.values());
        for (Integer slot : pinned) {
            lightPinnedSlots.computeIfAbsent(slot, ignored -> new AtomicInteger()).incrementAndGet();
        }
        return pinned;
    }

    private void unpinLightSlots(Set<Integer> pinned) {
        for (Integer slot : pinned) {
            AtomicInteger count = lightPinnedSlots.get(slot);
            if (count != null && count.decrementAndGet() <= 0) {
                lightPinnedSlots.remove(slot, count);
            }
        }
    }

    private boolean isLightSlotPinned(int slot) {
        AtomicInteger count = lightPinnedSlots.get(slot);
        return count != null && count.get() > 0;
    }

    private void unloadChunk(long key, NavigableMap<Integer, Integer> slots) {
        int cx = unpackX(key);
        int cz = unpackZ(key);
        WorldGenLogger.logChunk("UNLOAD", cx, -1, cz, "saving and freeing slots");

        // Cancel any pending light tasks for this chunk BEFORE freeing slots
        cancelledLightTasks.add(key);
        columnSectionsLoaded.remove(key);
        fullyGeneratedColumns.remove(key);
        deferredBetaDecoration.remove(key);
        queuedColumnRelights.remove(key);
        relightAgain.remove(key);

        if (saveManager != null) {
            saveManager.saveChunk(dimension, cx, cz, world);
        }
        int freed = 0;
        for (int cy : slots.keySet()) {
            int slot = slots.get(cy);
            world.clearChunkSlot(cx, cy, cz);
            world.clearLightPoolSlot(slot);
            world.clearDirSdfPoolSlot(slot);
            freeSlotStack[freeSlotTop++] = slot;
            freed++;
        }
        WorldGenLogger.logChunk("UNLOAD_DONE", cx, -1, cz,
            "freed " + freed + " slots");
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
    //  TERRAIN BOUNDS — SDF sky early-out support (gen thread only)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Updates the loaded terrain world-space bounds with a freshly loaded chunk column.
     *
     * X/Z bounds are kept TIGHT around the player's render-distance ring (instead of
     * the full KEEP_RADIUS buffer) so the side-plane SDF meaningfully culls out-of-frustum
     * rays. Y is the actual highest solid voxel in the chunk column (computed after
     * decoration so trees contribute). Volatile float[6] — gen thread only writes.
     *
     * @param cx absolute chunk x of loaded column
     * @param cz absolute chunk z of loaded column
     * @param columnMaxY highest solid voxel y in the column (after decoration)
     * @param pcx player's chunk x at load time (for visible-ring filtering)
     * @param pcz player's chunk z at load time
     */
    /** Used at chunk unload to zero the SDF pool slot so a future allocator
     *  reusing the same slot starts from a clean state. */
    public void clearSdfForSlot(int slot) {
        if (slot == World.EMPTY) return;
        world.clearDirSdfPoolSlot(slot);
    }



    /**
     * Computes 6 directional SDF distances (±X, ±Y, ±Z) for an empty-loaded
     * chunk section (caller must verify zero solids via bitmask scan).
     * Each direction's value says how many voxels the ray can travel along
     * that axis before hitting a non-empty neighbor chunk boundary.
     * (Entities handled analytically by traceAll; we don't bake them in to
     * avoid stale data after entity movement.)
     *
     * Encoded: byte = round(distance_in_voxels * 8), capped at 255.
     * Full chunk run (16 voxels, no obstacle) → byte = 128.
     *
     * Layout written to world.dirSdfPool:
     *   byte 0 = +X, byte 1 = -X, byte 2 = +Y, byte 3 = -Y,
     *   byte 4 = +Z, byte 5 = -Z. Bytes 6-7 unused (zero).
     *
     * Cost: 6 directions × 256 face cells × ≤16 voxel walks. Sub-ms per chunk.
     */
    private void computeChunkDirSDF(int slot, int absCx, int absCy, int absCz) {
        // For each of 6 directions: lookup the neighbor chunk's slot, then
        // check whether that neighbor has any solids via bitmask-pool OR.
        // "Free" neighbor = world's EMPTY sentinel OR a loaded-but-air chunk
        // (zero solids). Occupied neighbor = loaded chunk with at least one
        // solid → directional SDF = 8 (1 voxel). Otherwise = 128 (16 voxels).
        int[] neighborSlots = new int[6];
        neighborSlots[0] = world.getChunkSlot((absCx + 1) << 4, absCy << 4, absCz << 4);
        neighborSlots[1] = world.getChunkSlot((absCx - 1) << 4, absCy << 4, absCz << 4);
        neighborSlots[2] = world.getChunkSlot(absCx << 4, (absCy + 1) << 4, absCz << 4);
        neighborSlots[3] = world.getChunkSlot(absCx << 4, (absCy - 1) << 4, absCz << 4);
        neighborSlots[4] = world.getChunkSlot(absCx << 4, absCy << 4, (absCz + 1) << 4);
        neighborSlots[5] = world.getChunkSlot(absCx << 4, absCy << 4, (absCz - 1) << 4);

        int[] masks = world.getBitmaskPool();
        byte[] enc = new byte[6];
        for (int i = 0; i < 6; i++) {
            int nSlot = neighborSlots[i];
            boolean neighborFree;
            if (nSlot == World.EMPTY) {
                neighborFree = true;
            } else {
                // Check 128 bitmask-pool words; any bit set = chunk has solids.
                neighborFree = true;
                int bmBase = nSlot << 7;
                for (int w = 0; w < 128; w++) {
                    if (masks[bmBase + w] != 0) { neighborFree = false; break; }
                }
            }
            enc[i] = (byte) (neighborFree ? 128 : 8);
        }
        world.setDirSdfSlot(slot, enc[0], enc[1], enc[2], enc[3], enc[4], enc[5]);
    }



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
            NavigableMap<Integer, Integer> nslots = loadedChunks.get(nk);
            if (nslots == null) continue;
            if (is5x5Loaded(cx, cz)) {
                postLightTask(nk, nslots, () -> {
                    dirtySlots.addAll(mcLightEngine.generateSkyLight(cx, cz, nslots));
                    for (Map.Entry<Integer, Integer> se : nslots.entrySet()) {
                        dirtySlots.addAll(mcLightEngine.propagateBlockLight(cx, se.getKey(), cz, se.getValue()));
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
                    NavigableMap<Integer, Integer> nslots = loadedChunks.get(nk);
                    if (nslots != null) {
                        int finalNx = nx, finalNz = nz; // capture for lambda
                        postLightTask(nk, nslots, () -> {
                            dirtySlots.addAll(mcLightEngine.generateSkyLight(finalNx, finalNz, nslots));
                            for (Map.Entry<Integer, Integer> se : nslots.entrySet()) {
                                dirtySlots.addAll(mcLightEngine.propagateBlockLight(finalNx, se.getKey(), finalNz, se.getValue()));
                            }
                            lightsNeedUpload = true;
                            tableDirty.set(true);
                        });
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  PER-SECTION LOADING — one 16³ section at a time, spiral-ordered
    // ══════════════════════════════════════════════════════════════════

    /**
     * Sync-loads a 3×3×3 chunk grid (27 chunks) centered on (pcx, pcy, pcz).
     * Runs synchronously on the gen thread before any queued work, guaranteeing
     * the player's immediate surroundings are always present.
     *
     * For each of the 9 XZ columns:
     *   - If the column is not in loadedChunks, creates it and generates
     *     sections (pcy-1, pcy, pcy+1).
     *   - If the column exists but is missing any of these 3 Y sections,
     *     loads them inline (same logic as loadOneSection but synchronous).
     */
    private void ensure3x3x3Loaded(int pcx, int pcy, int pcz) {
        int sectionsLoaded = 0;
        int columnsCreated = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long colKey = chunkKey(cx, cz);

                NavigableMap<Integer, Integer> slots = loadedChunks.get(colKey);

                if (slots == null) {
                    // Column not loaded at all — sync create it
                    if (freeSlotTop < 2) evictFarthestColumn(cx, cz);
                    if (freeSlotTop < 1) continue;

                    slots = new TreeMap<>();
                    loadedChunks.put(colKey, slots);
                    cancelledLightTasks.remove(colKey);
                    columnsCreated++;

                    // Try disk load
                    boolean fromDisk = saveManager != null && saveManager.loadChunk(dimension, cx, cz, world);

                    int[] immediateSections = {pcy + 1, pcy, pcy - 1};
                    for (int cy : immediateSections) {
                        if (freeSlotTop < 1) break;
                        int slot = allocateSlot();
                        slots.put(cy, slot);
                        world.clearChunkPoolSlot(slot);
                        world.setChunkSlot(cx, cy, cz, slot);

                        if (!fromDisk) {
                            generateBaseTerrain(cx, cy, cz, slot);
                            decorateSectionIfAllowed(cx, cy, cz, slot);

                            int bmBase = slot << 7;
                            boolean anySolid = false;
                            for (int w = 0; w < 128; w++) {
                                if (world.getBitmaskPool()[bmBase + w] != 0) { anySolid = true; break; }
                            }
                            if (!anySolid) { computeChunkDirSDF(slot, cx, cy, cz); dirtySlots.add(slot); }
                        }
                        mcLightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
                        dirtySlots.add(slot);
                        sectionsLoaded++;
                    }

                tableDirty.set(true);
                scheduleFluidsInColumn(cx, cz, slots);
                scheduleColumnLighting(cx, cz, colKey, slots);
                if (slots.size() > 1) {
                    int highest = slots.lastKey();
                    trimLowerSectionsAfterHigherLoad(colKey, slots, highest);
                    scheduleNeighborSectionRelight(cx, highest, cz, colKey, slots);
                }

                // Do not publish readiness if slot pressure interrupted the
                    // immediate three-section spawn range.
                    boolean immediateRangeComplete = true;
                    for (int cy = pcy - 1; cy <= pcy + 1; cy++) {
                        if (!slots.containsKey(cy)) {
                            immediateRangeComplete = false;
                            break;
                        }
                    }
                    if (immediateRangeComplete) fullyGeneratedColumns.add(colKey);
                    else fullyGeneratedColumns.remove(colKey);
                } else {
                    // Column exists — load any missing Y sections in the 3×3 range,
                    // high sections first.
                    boolean addedAny = false;
                    int[] immediateSections = {pcy + 1, pcy, pcy - 1};
                    for (int cy : immediateSections) {
                        if (slots.containsKey(cy)) continue;
                        if (freeSlotTop < 1) { evictFarthestColumn(cx, cz); }
                        if (freeSlotTop < 1) break;

                        int slot = allocateSlot();
                        slots.put(cy, slot);
                        world.clearChunkPoolSlot(slot);
                        world.setChunkSlot(cx, cy, cz, slot);
                        generateBaseTerrain(cx, cy, cz, slot);
                        decorateSectionIfAllowed(cx, cy, cz, slot);

                        int bmBase = slot << 7;
                        boolean anySolid = false;
                        for (int w = 0; w < 128; w++) {
                            if (world.getBitmaskPool()[bmBase + w] != 0) { anySolid = true; break; }
                        }
                        if (!anySolid) { computeChunkDirSDF(slot, cx, cy, cz); dirtySlots.add(slot); }
                        mcLightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
                        dirtySlots.add(slot);
                        tableDirty.set(true);
                        sectionsLoaded++;
                        addedAny = true;
                    }
                    // Schedule lighting if we added new sections to this existing column
                    if (addedAny) {
                        scheduleColumnLighting(cx, cz, colKey, slots);
                        if (slots.size() > 1) {
                            int highest = slots.lastKey();
                            trimLowerSectionsAfterHigherLoad(colKey, slots, highest);
                            scheduleNeighborSectionRelight(cx, highest, cz, colKey, slots);
                        }
                    }
                    // Publish readiness only when all three immediate Y sections exist.
                    boolean immediateRangeComplete = true;
                    for (int cy = pcy - 1; cy <= pcy + 1; cy++) {
                        if (!slots.containsKey(cy)) {
                            immediateRangeComplete = false;
                            break;
                        }
                    }
                    if (immediateRangeComplete) fullyGeneratedColumns.add(colKey);
                    else fullyGeneratedColumns.remove(colKey);
                }
            }
        }

        if (columnsCreated > 0 || sectionsLoaded > 0) {
            WorldGenLogger.log("3x3x3 grid: created " + columnsCreated + " columns, loaded " + sectionsLoaded + " sections at (" + pcx + "," + pcy + "," + pcz + ")");
        }
    }

    /**
     * Evict the column farthest from (cx, cz) to free up slot space.
     */
    private void evictFarthestColumn(int cx, int cz) {
        List<Long> byDist = new ArrayList<>(loadedChunks.keySet());
        byDist.sort((a, b) -> {
            int da = Math.max(Math.abs(unpackX(a) - cx), Math.abs(unpackZ(a) - cz));
            int db = Math.max(Math.abs(unpackX(b) - cx), Math.abs(unpackZ(b) - cz));
            return Integer.compare(db, da); // farthest first
        });
        while (freeSlotTop < 2 && !byDist.isEmpty()) {
            Long victim = byDist.remove(0);
            NavigableMap<Integer, Integer> current = loadedChunks.get(victim);
            if (current == null || hasPinnedSlot(current)) continue;
            NavigableMap<Integer, Integer> vSlots = loadedChunks.remove(victim);
            if (vSlots != null) {
                unloadChunk(victim, vSlots);
                tableDirty.set(true);
            }
        }
    }

    /**
     * Emergency sync-load of the player's column: generates all Y-range sections
     * inline before any queued work. Called by the old EMERGENCY path
     * (now replaced by ensure3x3x3Loaded).
     */
    private void generateFullColumnSync(int cx, int cz) {
        long colKey = chunkKey(cx, cz);
        if (loadedChunks.containsKey(colKey)) return;
        if (freeSlotTop < 1) {
            // No room — unload the farthest chunks to make space
            WorldGenLogger.log("EMERGENCY: no free slots, forcing unload for player chunk");
            List<Long> byDist = new ArrayList<>(loadedChunks.keySet());
            byDist.sort((a, b) -> {
                int da = Math.max(Math.abs(unpackX(a) - cx), Math.abs(unpackZ(a) - cz));
                int db = Math.max(Math.abs(unpackX(b) - cx), Math.abs(unpackZ(b) - cz));
                return Integer.compare(db, da); // farthest first
            });
            while (freeSlotTop < 1 && !byDist.isEmpty()) {
                Long victim = byDist.remove(0);
                NavigableMap<Integer, Integer> vSlots = loadedChunks.remove(victim);
                if (vSlots != null) {
                    unloadChunk(victim, vSlots);
                    tableDirty.set(true);
                }
            }
        }

        generateOneColumn(cx, cz);
        tableDirty.set(true);
    }

    /**
     * Generate the player's column inline (sync, no queue).
     * Generates sections in the Y-range around the current player CY.
     * Used by the emergency path in manageChunks.
     */
    private void generateOneColumn(int cx, int cz) {
        long colKey = chunkKey(cx, cz);

        NavigableMap<Integer, Integer> slots = new TreeMap<>();
        loadedChunks.put(colKey, slots);
        cancelledLightTasks.remove(colKey);

        int yMin = lastPlayerCY - yLoadRadius;
        int yMax = lastPlayerCY + yLoadRadius;

        // Try disk load first
        boolean fromDisk = saveManager != null && saveManager.loadChunk(dimension, cx, cz, world);

        for (int cy : orderedSections(yMin, yMax)) {
            int slot = allocateSlot();
            slots.put(cy, slot);
            world.clearChunkPoolSlot(slot);

            if (fromDisk) {
                world.setChunkSlot(cx, cy, cz, slot);
                mcLightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
                dirtySlots.add(slot);
            } else {
                world.setChunkSlot(cx, cy, cz, slot);
                world.clearChunkPoolSlot(slot);
                generateBaseTerrain(cx, cy, cz, slot);
                decorateSectionIfAllowed(cx, cy, cz, slot);

                int bmBase = slot << 7;
                boolean anySolid = false;
                for (int w = 0; w < 128; w++) {
                    if (world.getBitmaskPool()[bmBase + w] != 0) { anySolid = true; break; }
                }
                if (!anySolid) { computeChunkDirSDF(slot, cx, cy, cz); dirtySlots.add(slot); }
                mcLightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
                dirtySlots.add(slot);
            }
        }

        tableDirty.set(true);
        scheduleFluidsInColumn(cx, cz, slots);
        scheduleColumnLighting(cx, cz, colKey, slots);
        if (slots.size() > 1) {
            int highest = slots.lastKey();
            trimLowerSectionsAfterHigherLoad(colKey, slots, highest);
            scheduleNeighborSectionRelight(cx, highest, cz, colKey, slots);
        }
    }

    /** Allocate a single slot from the free pool. */
    private int allocateSlot() {
        return freeSlotStack[--freeSlotTop];
    }

    /**
     * Load a single 16³ section at the given (cx, cy, cz).
     * The column's NavigableMap is created lazily on first access.
     * Each section allocates one pool slot independently.
     */
    private void loadOneSection(int cx, int cy, int cz) {
        long colKey = chunkKey(cx, cz);
        NavigableMap<Integer, Integer> slots = loadedChunks.get(colKey);

        if (slots == null) {
            // First section of this column: create the column, try disk load
            if (freeSlotTop < 1) return;
            slots = new TreeMap<>();
            loadedChunks.put(colKey, slots);
            cancelledLightTasks.remove(colKey);
            columnSectionsLoaded.put(colKey, new HashSet<>());

            // Try disk load — if successful, all Y-range sections are instantly done
            if (saveManager != null && saveManager.loadChunk(dimension, cx, cz, world)) {
                int yMin = lastPlayerCY - yLoadRadius;
                int yMax = lastPlayerCY + yLoadRadius;
                for (int scy : orderedSections(yMin, yMax)) {
                    if (freeSlotTop < 1 && scy > lastPlayerCY) {
                        trimLowerSectionsAfterHigherLoad(colKey, slots, scy);
                    }
                    if (freeSlotTop < 1) return;
                    int slot = allocateSlot();
                    slots.put(scy, slot);
                    world.setChunkSlot(cx, scy, cz, slot);
                    mcLightEngine.bakeChunkOcclusion(slot, cx, scy, cz);
                    dirtySlots.add(slot);
                }
                tableDirty.set(true);
                scheduleFluidsInColumn(cx, cz, slots);
                scheduleColumnLighting(cx, cz, colKey, slots);
                columnSectionsLoaded.remove(colKey);
                fullyGeneratedColumns.add(colKey);
                if (slots.size() > 1) {
                    int highest = slots.lastKey();
                    trimLowerSectionsAfterHigherLoad(colKey, slots, highest);
                    scheduleNeighborSectionRelight(cx, highest, cz, colKey, slots);
                }
                return;
            }

            // Start procedural-gen tracking
            // Mark cy as being generated (in the loading set), NOT yet loaded
        }

        // Allocate a slot for this specific section (not already loaded)
        Set<Integer> loading = columnSectionsLoaded.get(colKey);
        if (slots.containsKey(cy)) return; // already loaded
        if (loading != null && loading.contains(cy)) return; // being loaded by another task
        if (freeSlotTop < 1 && cy > lastPlayerCY) {
            // A higher section may reclaim stale lower sections in this same
            // column before we give up on the allocation.
            trimLowerSectionsAfterHigherLoad(colKey, slots, cy);
        }
        if (freeSlotTop < 1) return;
        int slot = allocateSlot();
        slots.put(cy, slot);
        if (loading != null) loading.add(cy);

        world.clearChunkPoolSlot(slot);
        world.setChunkSlot(cx, cy, cz, slot);
        generateBaseTerrain(cx, cy, cz, slot);
        decorateSectionIfAllowed(cx, cy, cz, slot);

        // Directional SDF for empty chunks
        int bmBase = slot << 7;
        boolean anySolid = false;
        for (int w = 0; w < 128; w++) {
            if (world.getBitmaskPool()[bmBase + w] != 0) { anySolid = true; break; }
        }
        if (!anySolid) { computeChunkDirSDF(slot, cx, cy, cz); dirtySlots.add(slot); }

        // Bake occlusion and mark dirty
        mcLightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
        dirtySlots.add(slot);
        tableDirty.set(true);

        // Schedule lighting once this column's full Y-load-range is present.
        // This runs off the async path (gen thread), so async-loaded chunks get
        // lighting without requiring another player update. (The previous code
        // checked loading.isEmpty() BEFORE removing cy — always false — so async
        // columns were never lit at all.)
        if (isColumnRangeLoaded(slots)) {
            scheduleColumnLighting(cx, cz, colKey, slots);
        }

        if (loading != null) {
            loading.remove(cy);
            if (loading.isEmpty()) {
                columnSectionsLoaded.remove(colKey);
            }
        }

        // A section arriving next to an existing section changes the vertical
        // sky-light path. Queue one complete-column rebuild on the dedicated
        // Lighting thread; duplicate arrivals coalesce by column key.
        if (slots.lowerKey(cy) != null || slots.higherKey(cy) != null) {
            // Trim first: the relight snapshot must never retain a section whose
            // slot has just been returned to the allocator.
            trimLowerSectionsAfterHigherLoad(colKey, slots, cy);
            scheduleNeighborSectionRelight(cx, cy, cz, colKey, slots);
        }
    }

    /** True when the column has every Y-section in the player's load range loaded. */
    private boolean isColumnRangeLoaded(NavigableMap<Integer, Integer> slots) {
        int yMin = lastPlayerCY - yLoadRadius;
        int yMax = lastPlayerCY + yLoadRadius;
        for (int cy = yMin; cy <= yMax; cy++) {
            if (!slots.containsKey(cy)) return false;
        }
        return true;
    }

    private void scheduleColumnLighting(int cx, int cz, long key, NavigableMap<Integer, Integer> slots) {
        // Light the full render distance so async-loaded columns are lit as soon
        // as they finish loading, regardless of whether the player has moved.
        if (Math.abs(cx - lastPlayerCX) <= renderDistance && Math.abs(cz - lastPlayerCZ) <= renderDistance) {
            // Snapshot on the generation thread before handing work to the
            // single lighting worker. The worker must never copy a mutable
            // TreeMap concurrently with section insertion.
            final NavigableMap<Integer, Integer> snapshot = new TreeMap<>(slots);
            final Set<Integer> pinned = pinLightSlots(snapshot);
            postLightTask(key, slots, () -> {
                try {
                    dirtySlots.addAll(mcLightEngine.generateSkyLight(cx, cz, snapshot));
                    for (Map.Entry<Integer, Integer> se : snapshot.entrySet()) {
                        dirtySlots.addAll(mcLightEngine.propagateBlockLight(cx, se.getKey(), cz, se.getValue()));
                    }
                    lightsNeedUpload = true;
                    tableDirty.set(true);
                    runPendingLightingIn5x5(cx, cz);
                } finally {
                    unpinLightSlots(pinned);
                }
            }, () -> unpinLightSlots(pinned));
        }
    }

    /**
     * Queues a coalesced relight after a section appears next to another section
     * in the same column. It is intentionally submitted to lightQueue only; the
     * generation thread never performs this rebuild.
     */
    private void scheduleNeighborSectionRelight(int cx, int cy, int cz, long key,
                                                 NavigableMap<Integer, Integer> slots) {
        if (!queuedColumnRelights.add(key)) {
            relightAgain.add(key);
            return;
        }
        final NavigableMap<Integer, Integer> snapshot = new TreeMap<>(slots);
        final Set<Integer> pinned = pinLightSlots(snapshot);
        postLightTask(key, slots, () -> {
            try {
                dirtySlots.addAll(mcLightEngine.generateSkyLight(cx, cz, snapshot));
                for (Map.Entry<Integer, Integer> entry : snapshot.entrySet()) {
                    dirtySlots.addAll(mcLightEngine.propagateBlockLight(cx, entry.getKey(), cz, entry.getValue()));
                }
                lightsNeedUpload = true;
                tableDirty.set(true);
            } finally {
                unpinLightSlots(pinned);
                queuedColumnRelights.remove(key);
                // A later section may have arrived while this pass was queued.
                // Requeue from the generation thread with a fresh snapshot.
                final boolean needsFollowUp = relightAgain.remove(key);
                if (running && loadedChunks.get(key) == slots) {
                    final int followCx = cx, followCy = cy, followCz = cz;
                    taskQueue.addLast(() -> {
                        // The current snapshot is no longer using these slots.
                        trimLowerSectionsAfterHigherLoad(key, slots, followCy);
                        if (!slots.containsKey(followCy)) {
                            // A higher request may have been deferred while the
                            // previous relight held its snapshot. Retry it now
                            // that lower slots can safely be reclaimed.
                            loadOneSection(followCx, followCy, followCz);
                        } else if (needsFollowUp) {
                            scheduleNeighborSectionRelight(followCx, followCy, followCz, key, slots);
                        }
                    });
                }
            }
        }, () -> {
            unpinLightSlots(pinned);
            queuedColumnRelights.remove(key);
            relightAgain.remove(key);
        });
    }

    /**
     * Removes stale lower sections only after a higher section has arrived and
     * only when they are below the active vertical window. The spawn cube is
     * explicitly protected so surface detection never loses its three sections.
     */
    private void trimLowerSectionsAfterHigherLoad(long key, NavigableMap<Integer, Integer> slots, int higherCy) {
        if (higherCy <= lastPlayerCY) return;
        // Never free a section represented by a queued relight snapshot. A
        // follow-up pass will see the current column after the first completes.
        // Do not free slots captured by the currently queued snapshot. The
        // follow-up pass retries this trim after that snapshot completes.
        if (queuedColumnRelights.contains(key)) return;
        int activeMinY = lastPlayerCY - yLoadRadius;
        boolean spawnColumn = isPlayer3x3(unpackX(key) - lastPlayerCX, unpackZ(key) - lastPlayerCZ);
        int protectedMin = lastPlayerCY - 1;
        int protectedMax = lastPlayerCY + 1;
        List<Integer> stale = new ArrayList<>();
        for (Integer sectionY : slots.keySet()) {
            boolean protectedSpawnSection = spawnBootstrap && spawnColumn
                    && sectionY >= protectedMin && sectionY <= protectedMax;
            Integer slot = slots.get(sectionY);
            if (slot != null && shouldEvictLowerSection(sectionY, higherCy, activeMinY, protectedSpawnSection)
                    && !isLightSlotPinned(slot)) {
                stale.add(sectionY);
            }
        }
        for (Integer sectionY : stale) {
            Integer slot = slots.remove(sectionY);
            if (slot == null || isLightSlotPinned(slot)) continue;
            world.clearChunkSlot(unpackX(key), sectionY, unpackZ(key));
            world.clearLightPoolSlot(slot);
            world.clearDirSdfPoolSlot(slot);
            lightRebuildPending.remove(slot);
            freeSlotStack[freeSlotTop++] = slot;
            dirtySlots.remove(slot);
            tableDirty.set(true);
        }
    }

    /** Compares columns by immediate 3×3 priority, then XZ distance. */
    private static int compareColumnPriority(long a, long b, int pcx, int pcz) {
        int adx = (int) (a >> 32) - pcx, adz = (int) a - pcz;
        int bdx = (int) (b >> 32) - pcx, bdz = (int) b - pcz;
        boolean aImmediate = isPlayer3x3(adx, adz);
        boolean bImmediate = isPlayer3x3(bdx, bdz);
        if (aImmediate != bImmediate) return aImmediate ? -1 : 1;
        int aDistance = Math.max(Math.abs(adx), Math.abs(adz));
        int bDistance = Math.max(Math.abs(bdx), Math.abs(bdz));
        if (aDistance != bDistance) return Integer.compare(aDistance, bDistance);
        return Long.compare(a, b);
    }

    /** True for one of the nine player-centered XZ columns. */
    static boolean isPlayer3x3(int dx, int dz) {
        return Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
    }

    /** Higher section coordinates sort before lower coordinates. */
    static int compareHigherSectionFirst(int a, int b) {
        return Integer.compare(b, a);
    }

    /** Pure eviction policy, exposed for focused regression tests. */
    static boolean shouldEvictLowerSection(int sectionY, int higherY, int activeMinY,
                                           boolean protectedSpawnSection) {
        return sectionY < higherY && sectionY < activeMinY && !protectedSpawnSection;
    }

    static List<Integer> orderedSections(int minY, int maxY) {
        List<Integer> result = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) result.add(y);
        result.sort(ChunkManager::compareHigherSectionFirst);
        return result;
    }

    /** Pure policy helper kept package-visible for the bootstrap regression test. */
    static boolean shouldDeferDecoration(boolean bootstrap, boolean betaGenerator) {
        return bootstrap && betaGenerator;
    }

    /**
     * Beta's population pass is deferred during the initial spawn bootstrap.
     * Other dimensions retain their existing decoration timing.
     */
    private void decorateSectionIfAllowed(int cx, int cy, int cz, int slot) {
        if (shouldDeferDecoration(spawnBootstrap, generator instanceof BetaWorldGenerator)) {
            deferredBetaDecoration.add(chunkKey(cx, cz));
            return;
        }
        generator.decorate(cx, cy, cz, slot, world);
    }

    /** Runs the deferred Beta population pass for the already loaded spawn columns. */
    private void runDeferredBetaDecoration() {
        synchronized (this) {
            deferredBetaDecorationQueued = false;
            if (!running || spawnBootstrap || deferredBetaDecorationCancelled) return;
            // Hold the lifecycle lock for the serialized pass. shutdown() then
            // waits for an already-started pass instead of racing into the
            // middle of a world mutation or starting it after cancellation.
            decorateBootstrapColumns();
        }
    }

    private void decorateBootstrapColumns() {
        if (!(generator instanceof BetaWorldGenerator)) return;

        int decorated = 0;
        for (Long key : new ArrayList<>(deferredBetaDecoration)) {
            if (!running || deferredBetaDecorationCancelled) break;
            NavigableMap<Integer, Integer> slots = loadedChunks.get(key);
            if (slots == null) {
                deferredBetaDecoration.remove(key);
                continue;
            }
            Integer slot = slots.get(4); // Beta's surface/decorating section.
            if (slot == null) continue;

            generator.decorate(unpackX(key), 4, unpackZ(key), slot, world);
            dirtySlots.addAll(slots.values());
            deferredBetaDecoration.remove(key);
            decorated++;
        }
        if (decorated > 0) {
            tableDirty.set(true);
            WorldGenLogger.log("BETA bootstrap decoration deferred complete: "
                    + decorated + " columns");
        }
    }

    private int generateBaseTerrain(int cx, int cy, int cz, int slot) {
        world.clearChunkPoolSlot(slot);
        int worldX = cx << 4;
        int worldY = cy << 4;
        int worldZ = cz << 4;

        // Prefer a generator's bulk section path. Beta already builds and
        // caches complete 16³ sections, so copying that cache avoids a second
        // 4,096-call per-voxel traversal during startup.
        int bulkCount = generator.populateSection(cx, cy, cz, world, slot);
        if (bulkCount >= 0) return bulkCount;

        int solidCount = 0;

        // Let direct 3D generators cheaply reject known-empty sections. This
        // replaces the old getHeight-based early-out without making section
        // generation depend on a column-height query.
        if (!generator.prepareSection(cx, cy, cz)) return 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // Use the generator's direct 3D query. Beta terrain is not
                // representable by a single column height, and querying one
                // here both costs an extra terrain pass and makes generation
                // depend on getHeight's cache/side effects.
                for (int ly = 0; ly < 16; ly++) {
                    int y = worldY + ly;
                    int type = generator.getBlockType(worldX + lx, y, worldZ + lz);
                    if (type != 0) {
                        world.setVoxelInPool(slot, lx, ly, lz, type);
                        solidCount++;
                    }
                }
            }
        }
        return solidCount;
    }

    /**
     * Scans a freshly loaded/generated chunk column for fluid blocks (water=15/150-164, lava=21)
     * and schedules them with the FluidManager so they begin flowing.
     * Called from loadChunk() after disk-load and after procedural generation.
     */
    private void scheduleFluidsInColumn(int cx, int cz, NavigableMap<Integer, Integer> slots) {
        if (fluidManager == null) return;
        int worldX = cx << 4;
        int worldZ = cz << 4;
        for (Map.Entry<Integer, Integer> se : slots.entrySet()) {
            int cy = se.getKey();
            int slot = se.getValue();
            if (slot == World.EMPTY) continue;
            int worldY = cy << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int raw = world.getRawVoxelInSlot(slot, lx, ly, lz);
                        int blockId = raw & 0xFFFF;
                        if (blockId == FluidManager.WATER
                            || (blockId >= FluidManager.WATER_FLOWING_BASE && blockId <= FluidManager.WATER_FLOWING_MAX)
                            || blockId == FluidManager.LAVA) {
                            fluidManager.notifyBlockChanged(worldX + lx, worldY + ly, worldZ + lz);
                        }
                    }
                }
            }
        }
    }

    /**
     * The bootstrap only loads the immediate 3x3x3 cube. Keep the initial
     * origin when that cube fits in the initial buffer; otherwise recenter as
     * usual so edge spawns are not silently dropped by setChunkSlot().
     */
    static boolean canSkipInitialRecenter(boolean bootstrap, int offsetX, int offsetY, int offsetZ,
                                          int pcx, int pcy, int pcz) {
        return bootstrap && offsetX == 0 && offsetY == 0 && offsetZ == 0
                && pcx >= 1 && pcx < World.REGION_SIZE - 1
                && pcy >= 1 && pcy < World.REGION_SIZE - 1
                && pcz >= 1 && pcz < World.REGION_SIZE - 1;
    }

    private long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    private int unpackX(long key) { return (int) (key >> 32); }
    private int unpackZ(long key) { return (int) key; }
}
