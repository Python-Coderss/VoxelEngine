package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages fluid flow (water and lava) in the voxel world.
 *
 * - First computes the new fluid level from horizontal neighbors and fluid above.
 * - Then tries to flow downward (with mixing).
 * - Then tries horizontal flow using slope-distance pathfinding.
 * - Source blocks (level 0) also actively flow down and horizontally.
 * - Downward flow always creates a source block (level 0) for both water and lava.
 * - Infinite water sources: 2+ adjacent source blocks + solid below → new source.
 * - Lava + water mixing: source lava → obsidian (adjacent water), flowing lava → cobblestone.
 *   Lava flowing directly into water → stone.
 *
 * Level encoding:
 *   0    = source block (full, still)
 *   1-7  = flowing (1 = closest to source, 7 = farthest) — spread spawns thin layers,
 *           downward flow always creates source (level 0)
 *
 * Water: source block ID 15, flowing levels 1-7 use IDs 150-156
 * Lava:  block ID 21 (level in extra data bits 16-23)
 */
public class FluidManager {

    private final World world;
    private final ChunkManager chunkManager;
    private final BlockDataManager blockDataManager;

    // Block ID constants
    static final int WATER = 15;
    static final int LAVA = 21;
    static final int OBSIDIAN = 16;
    static final int COBBLESTONE = 71;
    static final int STONE = 2;

    // Water flowing block IDs: level 1→150 (15/16 height), level 2→151, ..., level 7→156
    static final int WATER_FLOWING_BASE = 150;
    static final int WATER_FLOWING_MAX = 156;

    // ── Thread-safe tick queue ──
    private final Deque<Long> tickQueue = new ArrayDeque<>();
    private final Set<Long> pendingTicks = new HashSet<>();
    private final Map<Long, Integer> tickCounters = new HashMap<>();

    private boolean isNether = false;

    /**
     * Creates a new FluidManager.
     * @param isNether whether this world is the Nether (lava flows faster/farther there)
     */
    public FluidManager(World world, ChunkManager chunkManager, BlockDataManager blockDataManager, boolean isNether) {
        this.world = world;
        this.chunkManager = chunkManager;
        this.blockDataManager = blockDataManager;
        this.isNether = isNether;
    }

    // ── Per-fluid config ──
    private int spreadIncrementFor(int fluidId) {
        if (fluidId == LAVA) {
            return isNether ? 1 : 2;
        }
        return 1;
    }

    private int tickRateFor(int fluidId) {
        if (fluidId == LAVA) {
            return isNether ? 10 : 30;
        }
        return 5;
    }

    private int slopeFindDistanceFor(int fluidId) {
        if (fluidId == LAVA) {
            return isNether ? 4 : 2;
        }
        return 4;
    }

    /** Sets the dimension context (only affects lava parameters). */
    public void setNether(boolean nether) {
        this.isNether = nether;
    }

    // ════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════

    /**
     * Notifies that a block at (x,y,z) may need fluid processing.
     * Schedules the block itself plus all 6 neighbors if they contain fluid.
     * Thread-safe.
     */
    public void notifyBlockChanged(int x, int y, int z) {
        checkForMixing(x, y, z);

        int blockId = world.getVoxel(x, y, z);
        if (isFluid(blockId)) {
            scheduleTickIfFlowable(x, y, z, blockId);
        }
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            checkForMixing(nx, ny, nz);
            int nb = world.getVoxel(nx, ny, nz);
            if (isFluid(nb)) {
                scheduleTickIfFlowable(nx, ny, nz, nb);
            }
        }
    }

    /**
     * Bulk-scan entry used when a chunk column is loaded or generated. Unlike
     * {@link #notifyBlockChanged}, it does not fan out to all six neighbors:
     * every block in the column is visited anyway, so re-checking each one as a
     * neighbor of another is wasted work. Only flowable fluid blocks are
     * scheduled; lava still gets its water-mixing check.
     */
    public void scheduleFluidOnChunkLoad(int x, int y, int z, int blockId) {
        if (isLavaBlock(blockId)) {
            checkForMixing(x, y, z);
        }
        if (isFluid(blockId) && needsFlow(x, y, z, blockId)) {
            scheduleTick(x, y, z, blockId);
        }
    }

    /**
     * Whether this fluid block can actually do flow work right now. Fully
     * submerged ocean/lake water sources (fluid below, no open neighbor) are
     * stable and return false — scheduling them is what floods the tick queue
     * when ocean chunks load and kills the TPS. Only source blocks with a flow
     * target, and all flowing blocks, need ticking.
     */
    private boolean needsFlow(int x, int y, int z, int blockId) {
        int level = getLevel(x, y, z);
        if (level > 0) return true; // already flowing: keep updating until it settles

        // Source block (level 0): only flows down, or sideways when on solid ground.
        int below = world.getVoxel(x, y - 1, z);
        if (canFlowInto(below, blockId)) return true;
        if (!isBlocked(below)) return false;

        int[][] horizontals = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
        for (int[] d : horizontals) {
            if (canFlowInto(world.getVoxel(x + d[0], y, z + d[2]), blockId)) return true;
        }
        return false;
    }

    /** Schedules a fluid block for ticking only if it can actually flow. */
    private void scheduleTickIfFlowable(int x, int y, int z, int blockId) {
        if (needsFlow(x, y, z, blockId)) {
            scheduleTick(x, y, z, blockId);
        }
    }

    /** Called when a fluid source block is placed by the player. */
    public void onFluidPlaced(int x, int y, int z, int fluidId) {
        checkForMixing(x, y, z);
        scheduleTick(x, y, z, fluidId);
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            int nb = world.getVoxel(nx, ny, nz);
            if (isFluid(nb)) {
                scheduleTick(nx, ny, nz, nb);
            }
        }
    }

    /** Processes pending fluid ticks. Call once per logic frame. */
    public void tick(int maxPerTick) {
        tick(maxPerTick, Float.NaN, Float.NaN, Float.NaN);
    }

    /**
     * Processes pending fluid ticks with proximity prioritisation.
     * When player coordinates are provided, entries closest to the player
     * are processed first; the remainder are deferred. Uses half the budget
     * for near prioritisation and half for round-robin fairness.
     */
    public void tick(int maxPerTick, float px, float py, float pz) {
        int processed = 0;
        boolean havePlayer = !Float.isNaN(px);
        int nearBudget = havePlayer ? maxPerTick / 2 : maxPerTick;

        // ── Drain all pending entries into a temporary list ──
        List<Long> batch = new ArrayList<>();
        synchronized (this) {
            while (!tickQueue.isEmpty()) {
                long packed = tickQueue.pollFirst();
                pendingTicks.remove(packed);
                batch.add(packed);
            }
        }
        if (batch.isEmpty()) return;

        // ── Sort by distance to player (closest first) ──
        if (havePlayer) {
            final float pfx = px, pfy = py, pfz = pz;
            Collections.sort(batch, new Comparator<Long>() {
                public int compare(Long a, Long b) {
                    float da = distSq(unpackX(a), unpackY(a), unpackZ(a), pfx, pfy, pfz);
                    float db = distSq(unpackX(b), unpackY(b), unpackZ(b), pfx, pfy, pfz);
                    return Float.compare(da, db);
                }
            });
        }

        // ── Process closest entries up to the near budget ──
        int idx = 0;
        while (idx < batch.size() && processed < nearBudget) {
            long packed = batch.get(idx++);
            if (processOne(packed)) processed++;
        }

        // ── Round-robin: one from the back of the remaining list to avoid starvation ──
        int farIdx = batch.size() - 1;
        while (idx <= farIdx && processed < maxPerTick) {
            long packed = batch.get(farIdx--);
            if (processOne(packed)) processed++;
        }

        // ── Re-queue any entries we didn't get to ──
        synchronized (this) {
            while (idx <= farIdx) {
                long packed = batch.get(idx++);
                if (pendingTicks.add(packed)) {
                    tickQueue.addLast(packed);
                }
            }
        }
    }

    /** Shared single-entry processor (called from tick). Returns true if work was done. */
    private boolean processOne(long packed) {
        int x = unpackX(packed);
        int y = unpackY(packed);
        int z = unpackZ(packed);

        int blockId = world.getVoxel(x, y, z);
        if (!isFluid(blockId)) {
            synchronized (this) { tickCounters.remove(packed); }
            return true;
        }

        Integer counter;
        synchronized (this) { counter = tickCounters.get(packed); }
        if (counter != null && counter > 0) {
            synchronized (this) {
                tickCounters.put(packed, counter - 1);
                if (!pendingTicks.contains(packed)) {
                    tickQueue.addLast(packed);
                    pendingTicks.add(packed);
                }
            }
            return true;
        }

        synchronized (this) { tickCounters.remove(packed); }
        updateFluidBlock(x, y, z, blockId);
        return true;
    }

    private static float distSq(int x, int y, int z, float px, float py, float pz) {
        float dx = x - px, dy = y - py, dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public int pendingCount() { return tickQueue.size(); }

    // ════════════════════════════════════════════════════════════════
    //  FLUID UPDATE
    // ════════════════════════════════════════════════════════════════

    private int adjacentSourceBlocks;

    private void updateFluidBlock(int x, int y, int z, int fluidId) {
        int level = getLevel(x, y, z);
        int j = spreadIncrementFor(fluidId);

        // ── PART 1: Compute new level (flowing blocks only) ──
        if (level > 0) {
            int minAdjacent = -100;
            this.adjacentSourceBlocks = 0;

            int[][] horizontals = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
            for (int[] d : horizontals) {
                minAdjacent = checkAdjacentBlock(
                    x + d[0], y + d[1], z + d[2], fluidId, minAdjacent);
            }

            int newLevel = minAdjacent + j;

            // Flowing blocks exist at levels 1-7.  Beyond that, evaporate.
            if (newLevel > 7 || minAdjacent < 0) {
                newLevel = -1;
            }

            // Check for fluid above: source above replenishes this block
            int aboveLevel = getLevel(x, y + 1, z);
            if (aboveLevel >= 0 && isSameFluidRaw(world.getVoxel(x, y + 1, z), fluidId)) {
                newLevel = 0;
            }

            // Infinite water source
            if (this.adjacentSourceBlocks >= 2 && isWaterBlock(fluidId)) {
                int belowBlock = world.getVoxel(x, y - 1, z);
                if (isSolidBlock(belowBlock)) {
                    newLevel = 0;
                } else if (isSameFluidRaw(belowBlock, fluidId) && getLevel(x, y - 1, z) == 0) {
                    newLevel = 0;
                }
            }

            if (newLevel == level) {
                // Stabilize: level didn't change, stop ticking.
            } else {
                level = newLevel;
                if (newLevel < 0) {
                    chunkManager.setVoxel(x, y, z, 0);
                    notifyNeighborsForFlow(x, y, z);
                    int above = world.getVoxel(x, y + 1, z);
                    if (isFluid(above)) scheduleTick(x, y + 1, z, above);
                    return;
                } else {
                    setFluidBlock(x, y, z, fluidId, newLevel);
                    scheduleTick(x, y, z, fluidId);
                    notifyNeighborsForFlow(x, y, z);
                }
            }
        }

        // ── PART 2: Flow downward — always creates a source block ──
        int belowBlock = world.getVoxel(x, y - 1, z);

        if (canFlowInto(belowBlock, fluidId)) {
            // Lava flowing into water → stone
            if (fluidId == LAVA && isWaterBlock(belowBlock)) {
                chunkManager.setVoxel(x, y - 1, z, STONE);
                return;
            }
            tryFlowInto(x, y - 1, z, fluidId, 0);
        }
        else if (level >= 0 && isBlocked(belowBlock)) {
            // Horizontal flow — only when on solid ground
            int k1 = level + j;
            if (k1 > 7) {
                return;
            }

            int[][] flowDirs = getPossibleFlowDirections(x, y, z, fluidId);
            for (int[] d : flowDirs) {
                tryFlowInto(x + d[0], y + d[1], z + d[2], fluidId, k1);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════

    private int checkAdjacentBlock(int x, int y, int z, int fluidId, int currentMin) {
        int blockId = world.getVoxel(x, y, z);
        int depth = getLevel(x, y, z);

        if (depth < 0 || !isSameFluidRaw(blockId, fluidId)) {
            return currentMin;
        }

        if (depth == 0) {
            this.adjacentSourceBlocks++;
        }

        return currentMin >= 0 && depth >= currentMin ? currentMin : depth;
    }

    /**
     * Checks whether fluid can flow into a position.
     * Water cannot flow into lava (mixing is handled separately).
     */
    private boolean canFlowInto(int blockId, int fluidId) {
        if (blockId == 0) return true;
        if (isSameFluidRaw(blockId, fluidId)) return false;
        // A cell occupied by ANY fluid is impassable: water must not displace lava,
        // and a non-water fluid (e.g. a wrongly-tagged lily) must not displace water.
        // Lava-into-water is handled by checkForMixing / the STONE write in updateFluidBlock.
        if (isFluid(blockId)) return false;
        return !isBlocked(blockId);
    }

    private boolean isBlocked(int blockId) {
        if (blockId == 0) return false;
        if (isFluid(blockId)) return false;
        return isSolidBlock(blockId);
    }

    private void tryFlowInto(int x, int y, int z, int fluidId, int level) {
        int existing = world.getVoxel(x, y, z);

        if (!canFlowInto(existing, fluidId)) return;

        setFluidBlock(x, y, z, fluidId, level);
        scheduleTick(x, y, z, fluidId);
    }

    private int[][] getPossibleFlowDirections(int x, int y, int z, int fluidId) {
        int bestDist = 1000;
        List<int[]> bestDirs = new ArrayList<>();

        int[][] horizontals = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
        for (int[] d : horizontals) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            int neighborBlock = world.getVoxel(nx, ny, nz);

            if (!isBlocked(neighborBlock) &&
                (!isSameFluidRaw(neighborBlock, fluidId) || getLevel(nx, ny, nz) > 0)) {

                int dist;
                int belowNeighbor = world.getVoxel(nx, ny - 1, nz);

                if (isBlocked(belowNeighbor)) {
                    int oppX = -d[0], oppZ = -d[2];
                    dist = getSlopeDistance(nx, ny, nz, fluidId, 1, oppX, oppZ,
                        slopeFindDistanceFor(fluidId));
                } else {
                    dist = 0;
                }

                if (dist < bestDist) {
                    bestDirs.clear();
                    bestDist = dist;
                }
                if (dist <= bestDist) {
                    bestDirs.add(d);
                }
            }
        }

        return bestDirs.toArray(new int[0][]);
    }

    private int getSlopeDistance(int x, int y, int z, int fluidId,
                                  int distance, int avoidDX, int avoidDZ, int maxDist) {
        int best = 1000;

        int[][] horizontals = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
        for (int[] d : horizontals) {
            if (d[0] == avoidDX && d[2] == avoidDZ) continue;

            int nx = x + d[0], ny = y, nz = z + d[2];
            int neighborBlock = world.getVoxel(nx, ny, nz);

            if (!isBlocked(neighborBlock) &&
                (!isSameFluidRaw(neighborBlock, fluidId) || getLevel(nx, ny, nz) > 0)) {

                int belowBlock = world.getVoxel(nx, ny - 1, nz);

                if (!isBlocked(belowBlock)) {
                    return distance;
                }

                if (distance < maxDist) {
                    int oppX = -d[0], oppZ = -d[2];
                    int sub = getSlopeDistance(nx, ny, nz, fluidId,
                        distance + 1, oppX, oppZ, maxDist);
                    if (sub < best) best = sub;
                }
            }
        }

        return best;
    }

    // ════════════════════════════════════════════════════════════════
    //  LAVA-WATER MIXING
    // ════════════════════════════════════════════════════════════════

    private boolean checkForMixing(int x, int y, int z) {
        int blockId = world.getVoxel(x, y, z);
        if (!isLavaBlock(blockId)) return false;

        int level = getLevel(x, y, z);
        boolean waterAdjacent = false;

        int[][] dirs = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (isWaterBlock(world.getVoxel(nx, ny, nz))) {
                waterAdjacent = true;
                break;
            }
        }

        if (waterAdjacent) {
            if (level == 0) {
                chunkManager.setVoxel(x, y, z, OBSIDIAN);
            } else if (level <= 4) {
                chunkManager.setVoxel(x, y, z, COBBLESTONE);
            }
            notifyNeighborsForFlow(x, y, z);
            return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  BLOCK ID HELPERS
    // ════════════════════════════════════════════════════════════════

    private int getLevel(int x, int y, int z) {
        int raw = world.getRawVoxel(x, y, z);
        return getLevelFromRaw(raw);
    }

    private int getLevelFromRaw(int raw) {
        int blockId = raw & 0xFFFF;
        if (!isFluid(blockId)) return -1;
        if (isWaterBlock(blockId)) {
            if (blockId == WATER) return 0;
            return blockId - WATER_FLOWING_BASE + 1; // 150→1 ... 156→7
        }
        return (raw >> 16) & 0xFF; // lava: level from extra data
    }

    private boolean isFluid(int blockId) {
        return blockDataManager.isLiquid(blockId);
    }

    private boolean isWaterBlock(int blockId) {
        return blockId == WATER || (blockId >= WATER_FLOWING_BASE && blockId <= WATER_FLOWING_MAX);
    }

    private boolean isLavaBlock(int blockId) {
        return blockId == LAVA;
    }

    private boolean isSameFluidRaw(int blockId, int fluidId) {
        if (isWaterBlock(fluidId)) return isWaterBlock(blockId);
        if (isLavaBlock(fluidId)) return isLavaBlock(blockId);
        return false;
    }

    private boolean isSolidBlock(int blockId) {
        return blockId > 0 && !isFluid(blockId) && blockDataManager.isFullBlock(blockId);
    }

    private void setFluidBlock(int x, int y, int z, int fluidType, int level) {
        if (isWaterBlock(fluidType)) {
            chunkManager.setVoxel(x, y, z, waterBlockIdFromLevel(level));
        } else {
            chunkManager.setVoxelWithData(x, y, z, fluidType, level);
        }
    }

    private int waterBlockIdFromLevel(int level) {
        if (level <= 0 || level > 7) return WATER;
        return WATER_FLOWING_BASE + level - 1;
    }

    // ════════════════════════════════════════════════════════════════
    //  SCHEDULING
    // ════════════════════════════════════════════════════════════════

    private void scheduleTick(int x, int y, int z, int fluidId) {
        long packed = packPos(x, y, z);
        synchronized (this) {
            if (!pendingTicks.contains(packed)) {
                tickQueue.addLast(packed);
                pendingTicks.add(packed);
                tickCounters.put(packed, tickRateFor(fluidId));
            }
        }
    }

    private void notifyNeighborsForFlow(int x, int y, int z) {
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            int nb = world.getVoxel(nx, ny, nz);
            if (isFluid(nb)) {
                scheduleTick(nx, ny, nz, nb);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  POSITION PACKING
    // ════════════════════════════════════════════════════════════════

    private static final int POS_BITS = 20;
    private static final int POS_MASK = (1 << POS_BITS) - 1;
    private static final int POS_OFFSET = 1 << (POS_BITS - 1);

    private static long packPos(int x, int y, int z) {
        long px = ((long)(x + POS_OFFSET) & POS_MASK);
        long py = ((long)(y + POS_OFFSET) & POS_MASK);
        long pz = ((long)(z + POS_OFFSET) & POS_MASK);
        return (px) | (py << POS_BITS) | (pz << (POS_BITS * 2));
    }

    private static int unpackX(long packed) { return (int)((packed & POS_MASK) - POS_OFFSET); }
    private static int unpackY(long packed) { return (int)(((packed >> POS_BITS) & POS_MASK) - POS_OFFSET); }
    private static int unpackZ(long packed) { return (int)(((packed >> (POS_BITS * 2)) & POS_MASK) - POS_OFFSET); }
}
