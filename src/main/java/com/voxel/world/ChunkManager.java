package com.voxel.world;

import com.voxel.GameLogger;
import com.voxel.World;
import com.voxel.lighting.LightPropagationEngine;
import com.voxel.lighting.LightSource;
import com.voxel.lighting.LightType;
import com.voxel.utils.BiomeManager;
import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;
import org.joml.Vector3i;
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

    // ── Lighting: per-source BFS tracking (LinkedHashSet for deterministic iteration) ──
    private final Map<Long, Set<Long>> chunkLightSources = new HashMap<>();
    private volatile boolean lightsNeedUpload = false;

    // ── Deferred lighting: chunks waiting for 5×5 grid to load before BFS runs ──
    private final Set<Long> pendingLighting = new HashSet<>();
    private static final int LIGHT_GRID_RADIUS = 5; // 11×11 player zone: which chunks to light
    private static final int BFS_WAIT_RADIUS = 2;   // 5×5 BFS wait zone: 24 neighbors must be loaded before BFS runs

    // Buffer recentering constants
    private static final int RECENTER_MARGIN_CHUNKS = 16;
    private static final int BUFFER_HALF_CHUNKS = World.REGION_SIZE / 2;

    public ChunkManager(World world, WorldGenerator generator, LightPropagationEngine lightEngine,
                        int renderDistance, WorldSaveManager saveManager, DimensionType dimension,
                        BiomeManager biomeManager, BlockDataManager blockDataManager) {
        this.world = world;
        this.generator = generator;
        this.lightEngine = lightEngine;
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

        int oldBlock = world.getVoxel(x, y, z);
        world.setVoxel(x, y, z, type);

        int cx = x >> 4;
        int cy = y >> 4;
        int cz = z >> 4;

        boolean isEmissiveNew = blockDataManager.isEmissive(type);
        boolean isEmissiveOld = blockDataManager.isEmissive(oldBlock);

        // Always mark slot dirty synchronously so GPU sees voxel data change immediately.
        // Lighting BFS is deferred to gen thread (async), but occlusion is baked sync.
        lightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
        dirtySlots.add(slot);

        // Post per-source add/remove tasks to the gen thread
        if (isEmissiveOld) {
            int oldId = oldBlock;
            taskQueue.addLast(() -> removeLightSource(x, y, z, oldId));
        }
        if (isEmissiveNew) {
            int newId = type;
            taskQueue.addLast(() -> addLightSourceFromBlock(x, y, z, newId));
        }
        return true;
    }

    /**
     * Sets a voxel with extra data (e.g. redstone power) without re-baking occlusion.
     * Thread-safe: called from any thread.
     */
    public boolean setVoxelWithData(int x, int y, int z, int type, int extra) {
        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return false;

        int oldBlock = world.getVoxel(x, y, z);
        world.setVoxelWithData(x, y, z, type, extra);

        boolean isEmissiveNew = blockDataManager.isEmissive(type);
        boolean isEmissiveOld = blockDataManager.isEmissive(oldBlock);

        // Always mark dirty synchronously so GPU sees voxel data change immediately.
        dirtySlots.add(slot);

        if (isEmissiveOld) {
            int oldId = oldBlock;
            taskQueue.addLast(() -> removeLightSource(x, y, z, oldId));
        }
        if (isEmissiveNew) {
            int newId = type;
            taskQueue.addLast(() -> addLightSourceFromBlock(x, y, z, newId));
        }
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

    // volatile guard: true while gen thread is modifying light pool
    private volatile boolean lightingActive = false;
    public boolean isLightingActive() { return lightingActive; }

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
        for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
            world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
        }

        // Try disk load first
        if (saveManager != null && saveManager.loadChunk(dimension, cx, cz, world)) {
            for (int sectionY = 0; sectionY < chunkHeight; sectionY++) {
                world.setChunkSlot(cx, sectionY, cz, slots[sectionY]);
            }
            WorldGenLogger.logChunk("LOAD_DISK", cx, -1, cz, "loaded from disk, clearing stale light pool and baking occlusion");
            // Clear stale light pool data from recycled slots before additive BFS.
            // Without this, values from a previous run's chunks cause updateVoxelLight
            // to skip propagation (already 255, or wrong color).
            int[] lp = world.getLightPool();
            for (int cy = 0; cy < chunkHeight; cy++) {
                int s = slots[cy];
                int base = s << 12;
                for (int i = 0; i < 4096; i++) lp[base + i] = 0;
                lightEngine.bakeChunkOcclusion(s, cx, cy, cz);
                dirtySlots.add(s);
            }
            tableDirty.set(true);
            // Lighting only within 11×11 grid around player; outside chunks stay dark.
            // Defer ALL BFS until 5×5 grid around this chunk is fully loaded so light can
            // propagate across chunk boundaries without cutting off at unloaded neighbors.
            if (Math.abs(cx - lastPlayerCX) <= LIGHT_GRID_RADIUS && Math.abs(cz - lastPlayerCZ) <= LIGHT_GRID_RADIUS) {
                if (is5x5Loaded(cx, cz)) {
                    repropagateNeighborLights(cx, cz);
                    if (hasEmissiveSource(cx, cz, slots)) {
                        scanAndAddLightSources(cx, cz, slots);
                    }
                    runPendingLightingIn5x5(cx, cz);
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
        // Lighting only within 11×11 grid around player; outside chunks stay dark.
        // Defer ALL BFS until 5×5 grid around this chunk is fully loaded.
        if (Math.abs(cx - lastPlayerCX) <= LIGHT_GRID_RADIUS && Math.abs(cz - lastPlayerCZ) <= LIGHT_GRID_RADIUS) {
            if (is5x5Loaded(cx, cz)) {
                repropagateNeighborLights(cx, cz);
                if (hasEmissiveSource(cx, cz, slots)) {
                    scanAndAddLightSources(cx, cz, slots);
                }
                runPendingLightingIn5x5(cx, cz);
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
        WorldGenLogger.logChunk("UNLOAD", cx, -1, cz, "saving, removing lights, and freeing slots");
        if (saveManager != null) {
            saveManager.saveChunk(dimension, cx, cz, world);
        }
        // Subtract all light sources in this chunk before clearing data
        removeChunkLightSources(cx, cz);
        int[] lp = world.getLightPool();
        for (int cy = 0; cy < chunkHeight; cy++) {
            world.clearChunkSlot(cx, cy, cz);
            // Zero light pool for the freed slot so it starts clean when recycled
            int base = slots[cy] << 12;
            for (int i = 0; i < 4096; i++) lp[base + i] = 0;
            freeSlotStack[freeSlotTop++] = slots[cy];
        }
        int slotHash = java.util.Arrays.hashCode(slots);
        WorldGenLogger.logChunk("UNLOAD_DONE", cx, -1, cz,
            "freed " + chunkHeight + " slots hash=" + Integer.toHexString(slotHash));
    }

    // ══════════════════════════════════════════════════════════════════
    //  LIGHTING: per-source add/remove, rebuild
    // ══════════════════════════════════════════════════════════════════

    /**
     * Rebuilds all lighting from scratch, processing sources in distance-from-player spiral.
     * Posts to front of gen thread queue; world gen pauses until complete.
     */
    public void rebuildAllLighting(Vector3f playerPos) {
        float px = playerPos.x, pz = playerPos.z;
        taskQueue.addFirst(() -> {
            lightsNeedUpload = false;  // block uploads during rebuild
            long t0 = System.currentTimeMillis();
            int[] lp = world.getLightPool();
            int[] it = world.getIndirectionTable();

            // Clear light pool for loaded slots
            for (Integer[] slots : loadedChunks.values()) {
                for (int cy = 0; cy < chunkHeight; cy++) {
                    int base = slots[cy] << 12;
                    for (int i = 0; i < 4096; i++) lp[base + i] = 0;
                }
            }
            chunkLightSources.clear();

            // Collect chunks sorted by distance from player (spiral)
            Map<Long, Integer> distMap = new HashMap<>();
            for (Long ck : loadedChunks.keySet()) {
                int cx = unpackX(ck), cz = unpackZ(ck);
                float dx = (cx << 4) + 8 - px, dz = (cz << 4) + 8 - pz;
                distMap.put(ck, (int)(dx * dx + dz * dz));
            }
            List<Long> sorted = new ArrayList<>(loadedChunks.keySet());
            sorted.sort((a, b) -> {
                int cmp = Integer.compare(distMap.get(a), distMap.get(b));
                if (cmp != 0) return cmp;
                return Long.compare(a, b);  // tiebreaker: deterministic chunk-key order
            });

            // Quick first pass: count total emissive sources
            int grandTotal = 0;
            for (Long ck : sorted) {
                Integer[] slots = loadedChunks.get(ck);
                if (slots == null) continue;
                int cx = unpackX(ck), cz = unpackZ(ck);
                int[] cp = world.getChunkPool();
                for (int cy = 0; cy < chunkHeight; cy++) {
                    int slot = slots[cy];
                    if (slot == World.EMPTY) continue;
                    int wy = cy << 4;
                    for (int ly = 0; ly < 16; ly++)
                        for (int lz = 0; lz < 16; lz++)
                            for (int lx = 0; lx < 16; lx++) {
                                int bid = cp[(slot << 12) | (lx | (ly << 4) | (lz << 8))] & 0xFFFF;
                                if (blockDataManager.isEmissive(bid)) grandTotal++;
                            }
                }
            }

            int total = 0, skipped = 0, lastPct = -1;
            for (Long ck : sorted) {
                Integer[] slots = loadedChunks.get(ck);
                if (slots == null) continue;
                int cx = unpackX(ck), cz = unpackZ(ck);
                int[] cp = world.getChunkPool();
                for (int cy = 0; cy < chunkHeight; cy++) {
                    int slot = slots[cy];
                    if (slot == World.EMPTY) continue;
                    int wy = cy << 4;
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                int bid = cp[(slot << 12) | (lx | (ly << 4) | (lz << 8))] & 0xFFFF;
                                if (!blockDataManager.isEmissive(bid)) continue;
                                int wx = (cx << 4) + lx, wy2 = wy + ly, wz = (cz << 4) + lz;
                                LightSource src = createLightSource(wx, wy2, wz, bid);
                                lightingActive = true;
                                Set<Integer> aff = lightEngine.runSingleSourceBFS(src, lp, it, true);
                                lightingActive = false;
                                if (!aff.isEmpty()) {
                                    total++;
                                    dirtySlots.addAll(aff);
                                    lightsNeedUpload = true;
                                    chunkLightSources.computeIfAbsent(ck, k -> new LinkedHashSet<>()).add(posKey(wx, wy2, wz));
                                } else skipped++;
                                lastPct = printProgressBar(total + skipped, grandTotal, lastPct, "LIGHT rebuild");
                            }
                        }
                    }
                }
            }
            System.out.println();
            tableDirty.set(true);
            lightsNeedUpload = true;
            long elapsed = System.currentTimeMillis() - t0;
            GameLogger.log("LIGHT rebuild done: " + total + " sources" + (skipped > 0 ? " (" + skipped + " skipped)" : "") + ", " + elapsed + "ms, lightsNeedUpload=true, dirtySlots=" + dirtySlots.size());
        });
    }

    // ── Called from gen thread only ──

    private void addLightSourceFromBlock(int x, int y, int z, int blockId) {
        // Bake occlusion first — the new emissive block may be solid and should occlude sky.
        // This runs on the gen thread so both occlusion and light are updated atomically
        // before the slot is marked dirty.
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        int slot = world.getChunkSlot(x, y, z);
        if (slot != World.EMPTY) {
            lightEngine.bakeChunkOcclusion(slot, cx, cy, cz);
        }

        LightSource src = createLightSource(x, y, z, blockId);
        long ck = chunkKey(x >> 4, z >> 4);
        lightingActive = true;
        Set<Integer> affected = lightEngine.runSingleSourceBFS(src, world.getLightPool(), world.getIndirectionTable(), true);
        lightingActive = false;
        if (!affected.isEmpty()) {
            dirtySlots.addAll(affected);
            tableDirty.set(true);
            lightsNeedUpload = true;
            chunkLightSources.computeIfAbsent(ck, k -> new LinkedHashSet<>()).add(posKey(x, y, z));
            GameLogger.log("LIGHT add source block=" + blockId + " at (" + x + "," + y + "," + z + ") affected=" + affected.size() + " slots dirtySlots=" + dirtySlots.size());
        } else {
            GameLogger.log("LIGHT add source block=" + blockId + " at (" + x + "," + y + "," + z + ") BFS EMPTY (surrounded or out of range)");
        }
    }

    private void removeLightSource(int x, int y, int z, int oldBlockId) {
        LightSource src = createLightSource(x, y, z, oldBlockId);
        long ck = chunkKey(x >> 4, z >> 4);
        lightingActive = true;
        Set<Integer> affected = lightEngine.runSingleSourceBFS(src, world.getLightPool(), world.getIndirectionTable(), false);
        lightingActive = false;
        if (!affected.isEmpty()) {
            dirtySlots.addAll(affected);
            tableDirty.set(true);
            lightsNeedUpload = true;  // GPU must re-upload after subtract
        }
        Set<Long> set = chunkLightSources.get(ck);
        if (set != null) set.remove(posKey(x, y, z));
    }

    private void scanAndAddLightSources(int cx, int cz, Integer[] slots) {
        int[] chunkPool = world.getChunkPool();
        int[] indirection = world.getIndirectionTable();
        int[] lightPool = world.getLightPool();
        long ck = chunkKey(cx, cz);

        // Pre-count emissive sources for progress bar
        int grandTotal = 0;
        for (int cy = 0; cy < chunkHeight; cy++) {
            int slot = slots[cy];
            if (slot == World.EMPTY) continue;
            for (int ly = 0; ly < 16; ly++)
                for (int lz = 0; lz < 16; lz++)
                    for (int lx = 0; lx < 16; lx++) {
                        int bid = chunkPool[(slot << 12) | (lx | (ly << 4) | (lz << 8))] & 0xFFFF;
                        if (blockDataManager.isEmissive(bid)) grandTotal++;
                    }
        }
        if (grandTotal == 0) {
            GameLogger.log("LIGHT scanAndAdd chunk(" + cx + "," + cz + ") 0 emissive sources — skipping");
            return;
        }

        int found = 0, processed = 0, lastPct = -1;
        String prefix = "LIGHT chunk(" + cx + "," + cz + ")";
        for (int cy = 0; cy < chunkHeight; cy++) {
            int slot = slots[cy];
            if (slot == World.EMPTY) continue;
            int worldY = cy << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int blockId = chunkPool[(slot << 12) | (lx | (ly << 4) | (lz << 8))] & 0xFFFF;
                        if (!blockDataManager.isEmissive(blockId)) continue;
                        int wx = (cx << 4) + lx, wy = worldY + ly, wz = (cz << 4) + lz;
                        LightSource src = createLightSource(wx, wy, wz, blockId);
                        lightingActive = true;
                        Set<Integer> affected = lightEngine.runSingleSourceBFS(src, lightPool, indirection, true);
                        lightingActive = false;
                        processed++;
                        if (!affected.isEmpty()) {
                            dirtySlots.addAll(affected);
                            lightsNeedUpload = true;
                            chunkLightSources.computeIfAbsent(ck, k -> new LinkedHashSet<>()).add(posKey(wx, wy, wz));
                            found++;
                        }
                        lastPct = printProgressBar(processed, grandTotal, lastPct, prefix);
                    }
                }
            }
        }
        System.out.println();
        GameLogger.log("LIGHT scanAndAdd chunk(" + cx + "," + cz + ") found " + found + " emissive, dirtySlots=" + dirtySlots.size());
    }

    /**
     * After a chunk loads, re-propagate light from adjacent chunks' emissive sources
     * into the new chunk. Subtract-then-re-add pattern ensures no double-counting
     * in the neighbor's own voxels.
     */
    private void repropagateNeighborLights(int cx, int cz) {
        long[] neighbors = {
            chunkKey(cx - 1, cz), chunkKey(cx + 1, cz),
            chunkKey(cx, cz - 1), chunkKey(cx, cz + 1)
        };
        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        // Pre-count total neighbor sources for progress bar
        int grandTotal = 0;
        for (long nk : neighbors) {
            Set<Long> sources = chunkLightSources.get(nk);
            if (sources != null) grandTotal += sources.size();
        }
        if (grandTotal == 0) {
            GameLogger.log("LIGHT repropagate chunk(" + cx + "," + cz + ") 0 neighbor sources — skipping");
            return;
        }

        int totalPropagated = 0, processed = 0, lastPct = -1;
        String prefix = "LIGHT re-prop chunk(" + cx + "," + cz + ")";
        for (long nk : neighbors) {
            Set<Long> sources = chunkLightSources.get(nk);
            if (sources == null || sources.isEmpty()) continue;
            for (long pk : sources) {
                int x = unpackPosX(pk), y = unpackPosY(pk), z = unpackPosZ(pk);
                int bid = world.getVoxel(x, y, z);
                if (bid > 0 && blockDataManager.isEmissive(bid)) {
                    LightSource src = createLightSource(x, y, z, bid);
                    lightingActive = true;
                    lightEngine.runSingleSourceBFS(src, lp, it, false);
                    Set<Integer> affected = lightEngine.runSingleSourceBFS(src, lp, it, true);
                    lightingActive = false;
                    processed++;
                    if (!affected.isEmpty()) {
                        dirtySlots.addAll(affected);
                        lightsNeedUpload = true;
                        totalPropagated++;
                    }
                    lastPct = printProgressBar(processed, grandTotal, lastPct, prefix);
                }
            }
        }
        System.out.println();
        if (totalPropagated > 0) {
            GameLogger.log("LIGHT repropagate chunk(" + cx + "," + cz + ") re-propagated " + totalPropagated + " neighbor sources, dirtySlots=" + dirtySlots.size());
        }
    }

    private void removeChunkLightSources(int cx, int cz) {
        long ck = chunkKey(cx, cz);
        Set<Long> set = chunkLightSources.remove(ck);
        if (set == null || set.isEmpty()) {
            GameLogger.log("LIGHT remove chunk(" + cx + "," + cz + ") 0 sources — skipping");
            return;
        }
        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        int grandTotal = set.size();
        int processed = 0, lastPct = -1;
        String prefix = "LIGHT remove chunk(" + cx + "," + cz + ")";
        for (long pk : set) {
            int x = unpackPosX(pk), y = unpackPosY(pk), z = unpackPosZ(pk);
            int bid = world.getVoxel(x, y, z);
            if (bid > 0 && blockDataManager.isEmissive(bid)) {
                LightSource src = createLightSource(x, y, z, bid);
                lightingActive = true;
                Set<Integer> affected = lightEngine.runSingleSourceBFS(src, lp, it, false);
                lightingActive = false;
                processed++;
                if (!affected.isEmpty()) {
                    dirtySlots.addAll(affected);
                    tableDirty.set(true);
                    lightsNeedUpload = true;  // GPU must re-upload after subtract
                }
                lastPct = printProgressBar(processed, grandTotal, lastPct, prefix);
            }
        }
        System.out.println();
    }

    private LightSource createLightSource(int x, int y, int z, int blockId) {
        int emissive = blockDataManager.getEmissive(blockId);
        java.awt.Color albedo = blockDataManager.getAlbedo(blockId);
        return new LightSource(
            new Vector3i(x, y, z),
            new Vector3f(albedo.getRed() / 255f, albedo.getGreen() / 255f, albedo.getBlue() / 255f),
            emissive,
            15f,
            LightType.BLOCK
        );
    }

    // ── Position packing for light source tracking ──
    private static long posKey(int x, int y, int z) {
        return ((long)(x & 0x7FF) << 22) | ((long)(y & 0x7FF) << 11) | (long)(z & 0x7FF);
    }
    private static int unpackPosX(long p) { return (int)((p >>> 22) & 0x7FF); }
    private static int unpackPosY(long p) { return (int)((p >>> 11) & 0x7FF); }
    private static int unpackPosZ(long p) { return (int)(p & 0x7FF); }

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

    // ── Quick check: does this chunk have any emissive blocks? ──
    private boolean hasEmissiveSource(int cx, int cz, Integer[] slots) {
        int[] cp = world.getChunkPool();
        for (int cy = 0; cy < chunkHeight; cy++) {
            int slot = slots[cy];
            if (slot == World.EMPTY) continue;
            for (int ly = 0; ly < 16; ly++)
                for (int lz = 0; lz < 16; lz++)
                    for (int lx = 0; lx < 16; lx++) {
                        int bid = cp[(slot << 12) | (lx | (ly << 4) | (lz << 8))] & 0xFFFF;
                        if (blockDataManager.isEmissive(bid)) return true;
                    }
        }
        return false;
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
     * Flush pending lighting: runs repropagateNeighborLights for every pending chunk
     * within the 11×11 grid around the player, and scanAndAddLightSources only for
     * chunks that have emissive sources. Chunks outside the 11×11 stay pending.
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
                continue;  // stays in pendingLighting for later
            }
            if (!pendingLighting.remove(nk)) continue; // already processed via cascade
            Integer[] nslots = loadedChunks.get(nk);
            if (nslots == null) continue;
            // Defer ALL BFS until 5×5 grid around the chunk is fully loaded.
            if (is5x5Loaded(cx, cz)) {
                repropagateNeighborLights(cx, cz);
                if (hasEmissiveSource(cx, cz, nslots)) {
                    scanAndAddLightSources(cx, cz, nslots);
                }
                runPendingLightingIn5x5(cx, cz);
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
                        scanAndAddLightSources(nx, nz, nslots);
                        repropagateNeighborLights(nx, nz);
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
