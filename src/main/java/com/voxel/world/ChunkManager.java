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

    // ── Terrain world-space bounds, written only by the gen thread.
    // Used by the compute shader as a cheap SDF for sky early-out:
    //   u_MaxY = top plane (camera above and ray.y>0 → pure sky)
    //   X/Z planes are restricted to the player's RENDER-DISTANCE ring so the
    //   side SDF is meaningful (the full loaded-chunk buffer would always
    //   swallow most rays).
    // Layout: [maxY, minY, maxX, minX, maxZ, minZ].
    private volatile float[] terrainBounds = new float[]{
        Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY
    };
    private final java.util.concurrent.atomic.AtomicBoolean boundsValid =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    /** True after the first terrain bounds update on the gen thread. */
    public boolean areBoundsValid() { return boundsValid.get(); }

    // ── Cheap sky-ray early-out: track the highest solid voxel Y across all
    //    loaded chunks. When the camera is ABOVE this value AND the ray points
    //    up, no amount of climbing will hit terrain → pure sky, skip DDA.
    //    Volatile int — gen thread writes, main thread reads per frame.
    //    Sentinel value -1 means "no chunks loaded yet" so the shader can gate
    //    the test behind u_BoundsValid without false-positive early-exits.
    private volatile int topSolidY = -1;
    /** Per-column max solid voxel Y map (chunk key → world Y). Gen thread only writes. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> chunkTopYByCol =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** @return Highest solid voxel Y across all loaded chunks, or -1 if none loaded. */
    public int getTopSolidY() { return topSolidY; }

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
            // Sort by cubic spiral: Chebyshev distance (max(|dx|,|dz|)) radiating
            // outward from player in concentric square shells.
            // Within each shell, spiral by angle from forward direction.
            chunksToLoad.sort((a, b) -> {
                int dxA = a[0] - pcx, dzA = a[1] - pcz;
                int dxB = b[0] - pcx, dzB = b[1] - pcz;

                int shellA = Math.max(Math.abs(dxA), Math.abs(dzA));
                int shellB = Math.max(Math.abs(dxB), Math.abs(dzB));

                if (shellA != shellB) return Integer.compare(shellA, shellB);

                // Same shell: spiral outward by angle from forward
                float angleA = (float) Math.abs(Math.atan2(dzA, dxA) - Math.atan2(lookZ, lookX));
                float angleB = (float) Math.abs(Math.atan2(dzB, dxB) - Math.atan2(lookZ, lookX));
                // Normalize to [0, 2π)
                if (angleA > Math.PI) angleA = (float)(2.0 * Math.PI - angleA);
                if (angleB > Math.PI) angleB = (float)(2.0 * Math.PI - angleB);
                return Float.compare(angleA, angleB);
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
            // Sky-ray early-out: register this chunk's top solid voxel Y.
            int colMax = computeColumnMaxY(slots);
            updateTopSolidY(key, colMax);
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
        // Pass 2.5: Chunk-level directional SDF for sphere-trace acceleration.
        // ONLY for chunks that have zero solid voxels (per user directive).
        // Chunks with solids use plain per-voxel DDA in the shader (their
        // dirSdfPool stays at 0, signaling "no SDF available").
        for (int cy = 0; cy < chunkHeight; cy++) {
            int slot = slots[cy];
            if (slot == World.EMPTY) continue;
            // Check if the chunk has any solids via the bitmask pool.
            int bmBase = slot << 7;
            boolean anySolid = false;
            for (int w = 0; w < 128; w++) {
                if (world.getBitmaskPool()[bmBase + w] != 0) { anySolid = true; break; }
            }
            if (!anySolid) {
                computeChunkDirSDF(slot, cx, cy, cz);
                dirtySlots.add(slot);
            }
        }
        WorldGenLogger.logChunk("GEN_PASS2_DECORATE", cx, -1, cz,
            "all 16 sections (" + (System.currentTimeMillis() - t2) + "ms)");
        // Sky-ray early-out: register this chunk's top solid voxel Y for u_TopSolidY.
        // Recomputed here (after SDF build) so tree-block foliage counts toward the max.
        int colMaxProc = computeColumnMaxY(slots);
        updateTopSolidY(key, colMaxProc);

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
            world.clearDirSdfPoolSlot(slots[cy]);
            freeSlotStack[freeSlotTop++] = slots[cy];
        }
        // Sky-ray early-out: deregister this chunk from the column-max map.
        // Only recompute global max when this chunk actually held it (cheap O(1)
        // guard; if the removed chunk's Y is below our current top, the max is
        // unchanged and we skip the O(N) scan over all loaded chunks).
        Integer removedY = chunkTopYByCol.remove(key);
        if (removedY != null && removedY >= topSolidY) {
            int newMax = -1;
            for (Integer y : chunkTopYByCol.values()) {
                if (y != null && y > newMax) newMax = y;
            }
            topSolidY = newMax;
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
     * Computes the highest solid voxel Y in a chunk column (across all 16 sections,
     * scanning top→bottom to exit early). Runs AFTER voxel data is finalized
     * (post-decoration or post-disk-load) and before the slot is sent to GPU.
     *
     * Cost: ~4096 cell checks in the WORST case (all-air column); typically far
     * less because we exit as soon as the highest solid is found. Sub-ms per chunk.
     *
     * Updates {@link #chunkTopYByCol} and {@link #topSolidY} atomically with the
     * gen-thread constraint (single writer for both, so plain volatile int suffices).
     *
     * @param slots the 16 chunk-section slot indexes for this column
     * @return the highest solid voxel world Y, or -1 if column is fully air
     */
    private int computeColumnMaxY(Integer[] slots) {
        int best = -1;
        // Scan top section down so we can break early on the first solid found.
        for (int cy = chunkHeight - 1; cy >= 0; cy--) {
            int slot = slots[cy];
            if (slot == World.EMPTY) continue;
            int secBaseY = cy << 4;
            // Within this section, scan ly down so we scan the highest solid first.
            for (int ly = 15; ly >= 0; ly--) {
                boolean solid = false;
                for (int lx = 0; lx < 16 && !solid; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if ((world.getRawVoxelInSlot(slot, lx, ly, lz) & 0x80000000) != 0) {
                            solid = true;
                            break;
                        }
                    }
                }
                if (solid) {
                    best = secBaseY + ly;
                    break; // Highest ly in topmost non-empty section = column max
                }
            }
            if (best >= 0) break; // Already found the column max, no need to scan lower
        }
        return best;
    }

    /** Record a chunk's column max-Y for the sky-ray early-out uniform.
     *  Also flips u_BoundsValid true on the first successful registration
     *  so the shader's gated skyPixel check actually runs. */
    private void updateTopSolidY(long chunkKey, int columnMaxY) {
        chunkTopYByCol.put(chunkKey, columnMaxY);
        if (columnMaxY > topSolidY) topSolidY = columnMaxY;
        boundsValid.set(true);
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

    private void updateTerrainBounds(int cx, int cz, int columnMaxY, int pcx, int pcz) {
        // Visible ring: only chunks within 2x renderDistance (stretched forward) participate
        // in the side SDF. Using the loading player's chunk as anchor.
        int vis = renderDistance * 2;
        int dx = cx - pcx, dz = cz - pcz;
        if (Math.abs(dx) > vis || Math.abs(dz) > vis) {
            // Out-of-visible chunk: don't loosen the box but DO update Y (cheap and useful)
            float[] cur = terrainBounds;
            terrainBounds = new float[]{cur[0], cur[1], cur[2], cur[3], cur[4], cur[5]};
            if (columnMaxY > cur[0]) {
                terrainBounds[0] = columnMaxY;
            }
            return;
        }
        float maxX = (cx << 4) + 16f;
        float minX = (cx << 4);
        float maxZ = (cz << 4) + 16f;
        float minZ = (cz << 4);
        float[] cur = terrainBounds;
        float[] next = new float[]{
            Math.max(cur[0], columnMaxY),
            Math.min(cur[1], 0f),
            Math.max(cur[2], maxX),
            Math.min(cur[3], minX),
            Math.max(cur[4], maxZ),
            Math.min(cur[5], minZ)
        };
        terrainBounds = next;
    }

    /** Convenience getters — single array reads, lock-free. Infinities are returned as 0f. */
    public float getMaxTerrainY() { float[] b = terrainBounds; return Float.isInfinite(b[0]) ? 0f : b[0]; }
    public float getMinTerrainY() { float[] b = terrainBounds; return Float.isInfinite(b[1]) ? 0f : b[1]; }
    public float getMaxTerrainX() { float[] b = terrainBounds; return Float.isInfinite(b[2]) ? 0f : b[2]; }
    public float getMinTerrainX() { float[] b = terrainBounds; return Float.isInfinite(b[3]) ? 0f : b[3]; }
    public float getMaxTerrainZ() { float[] b = terrainBounds; return Float.isInfinite(b[4]) ? 0f : b[4]; }
    public float getMinTerrainZ() { float[] b = terrainBounds; return Float.isInfinite(b[5]) ? 0f : b[5]; }

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
