package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.Direction;
import com.voxel.GameLogger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.HashSet;

/**
 * Minecraft-style dual-channel lighting engine.
 *
 * Sky light (EnumSkyBlock.SKY):
 *   Propagates top-down from the world ceiling. Each column is computed independently:
 *   starting from the highest block, sky light = 15 and diminishes by block opacity
 *   as it descends.
 *
 * Block light (EnumSkyBlock.BLOCK):
 *   Flood-fills outward from emissive block sources (torches, glowstone, etc.).
 *   Each step away from the source reduces light by 1. Opaque blocks block propagation.
 *   Light values range from 0 to 15.
 *
 * Light is stored in the existing World.lightPool using a packed format:
 *   bits 0-3 = sky light (0-15)
 *   bits 4-7 = block light (0-15)
 */
public class LightEngine {

    private final World world;
    private final BlockDataManager blockDataManager;

    /** Maximum light value (both sky and block). */
    public static final int MAX_LIGHT = 15;

    /** Height limit for sky light computation. */
    public static final int WORLD_HEIGHT = 256;

    public LightEngine(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
    }

    // ══════════════════════════════════════════════════════════════════
    //  SKY LIGHT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Generates sky light for an entire chunk column (all 16 sections, y = 0..255).
     * Computes top-down per (x,z) column: starts at the highest block, sets 15,
     * then decreases by block opacity as it descends.
     *
     * @param cx    Absolute chunk X coordinate
     * @param cz    Absolute chunk Z coordinate
     * @param slots The 16 pool slots for this chunk column [y=0..15]
     * @return Set of dirty slot indices
     */
    public Set<Integer> generateSkyLight(int cx, int cz, Integer[] slots) {
        Set<Integer> dirtySlots = new HashSet<>();

        int worldBaseX = cx << 4;
        int worldBaseZ = cz << 4;
        int bufMaxY = world.getOffsetY() + 2048;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = worldBaseX + lx;
                int wz = worldBaseZ + lz;

                // Find top non-transparent block in this column (within buffer bounds)
                int topY = findTopBlockY(wx, wz);

                // Propagate sky light downward from topY
                int skyLight = MAX_LIGHT;
                for (int y = Math.min(topY, bufMaxY - 1); y >= 0; y--) {
                    int slot = getSlotForWorldPos(wx, y, wz);
                    if (slot == World.EMPTY) {
                        // No chunk loaded at this y — can't set sky light
                        // If we're above the loaded area, skip; if inside, continue
                        continue;
                    }

                    int blockId = world.getVoxel(wx, y, wz);
                    int ly = y & 15;

                    int currentSky = world.getSkyLight(slot, lx, ly, lz);
                    if (currentSky != skyLight) {
                        world.setSkyLight(slot, lx, ly, lz, skyLight);
                        dirtySlots.add(slot);
                    }

                    if (skyLight <= 0) break;

                    // Diminish based on opacity: full blocks absorb remaining light
                    int opacity = getBlockOpacity(blockId);
                    skyLight = Math.max(0, skyLight - opacity);
                }
            }
        }
        return dirtySlots;
    }

    /**
     * Finds the Y coordinate of the topmost non-transparent block in the column.
     * Scans downward from the world height limit.
     */
    private int findTopBlockY(int wx, int wz) {
        int bufMaxY = world.getOffsetY() + 2048;
        for (int y = bufMaxY - 1; y >= 0; y--) {
            int blockId = world.getVoxel(wx, y, wz);
            if (blockId > 0 && blockDataManager.isFullBlock(blockId)) {
                return y + 1; // sky light starts above the top block
            }
        }
        // No blocks found — sky reaches the bottom with full brightness
        return bufMaxY - 1;
    }

    // ══════════════════════════════════════════════════════════════════
    //  BLOCK LIGHT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Propagaes block light from all emissive sources in a chunk section.
     * Flood-fill BFS: light diminishes by 1+opacity per step, full blocks block propagation.
     *
     * @param cx   Absolute chunk X coordinate
     * @param cy   Absolute chunk section Y coordinate
     * @param cz   Absolute chunk Z coordinate
     * @param slot Pool slot for this section
     * @return Set of dirty slot indices
     */
    public Set<Integer> propagateBlockLight(int cx, int cy, int cz, int slot) {
        Set<Integer> dirtySlots = new HashSet<>();
        int worldBaseX = cx << 4;
        int worldBaseY = cy << 4;
        int worldBaseZ = cz << 4;

        Deque<LightNode> queue = new ArrayDeque<>();

        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int wx = worldBaseX + lx;
                    int wy = worldBaseY + ly;
                    int wz = worldBaseZ + lz;
                    int blockId = world.getVoxel(wx, wy, wz);

                    int emissive = blockDataManager.getEmissive(blockId);
                    if (emissive > 0) {
                        int lightLevel = Math.min(emissive, MAX_LIGHT);
                        world.setBlockLight(slot, lx, ly, lz, lightLevel);
                        dirtySlots.add(slot);
                        queue.add(new LightNode(wx, wy, wz, lightLevel));
                    }
                }
            }
        }

        // Flood-fill BFS
        while (!queue.isEmpty()) {
            LightNode node = queue.poll();
            for (Direction dir : Direction.values()) {
                int nx = node.x + dir.x;
                int ny = node.y + dir.y;
                int nz = node.z + dir.z;

                if (!isInBuffer(nx, ny, nz)) continue;

                int neighborSlot = getSlotForWorldPos(nx, ny, nz);
                if (neighborSlot == World.EMPTY) continue;

                int blockId = world.getVoxel(nx, ny, nz);
                int opacity = getBlockOpacity(blockId);
                int nextLevel = node.distance - Math.max(1, opacity);
                if (nextLevel <= 0) continue;

                int nlx = nx & 15;
                int nly = ny & 15;
                int nlz = nz & 15;

                int current = world.getBlockLight(neighborSlot, nlx, nly, nlz);
                if (current < nextLevel) {
                    world.setBlockLight(neighborSlot, nlx, nly, nlz, nextLevel);
                    dirtySlots.add(neighborSlot);
                    if (nextLevel > 1) {
                        queue.add(new LightNode(nx, ny, nz, nextLevel));
                    }
                }
            }
        }

        return dirtySlots;
    }

    /**
     * Runs block light BFS from a single source position (for block placement/removal).
     * Uses incremental darken+flood instead of brute-force clear+rebuild for real-time performance.
     *
     * @param x World X coordinate of changed block
     * @param y World Y coordinate of changed block
     * @param z World Z coordinate of changed block
     * @return Set of dirty slot indices
     */
    public Set<Integer> checkBlockLight(int x, int y, int z) {
        Set<Integer> dirtySlots = new HashSet<>();
        int slot = getSlotForWorldPos(x, y, z);
        if (slot == World.EMPTY) return dirtySlots;

        int lx = x & 15, ly = y & 15, lz = z & 15;
        int currentLight = world.getBlockLight(slot, lx, ly, lz);
        int blockId = world.getVoxel(x, y, z);
        int emissive = blockDataManager.getEmissive(blockId);

        Deque<LightNode> removeQueue = new ArrayDeque<>();
        Deque<LightNode> addQueue = new ArrayDeque<>();

        // Capture initial state
        if (emissive > 0) {
            int lightLevel = Math.min(emissive, MAX_LIGHT);
            world.setBlockLight(slot, lx, ly, lz, lightLevel);
            dirtySlots.add(slot);
            addQueue.add(new LightNode(x, y, z, lightLevel));
        } else {
            world.setBlockLight(slot, lx, ly, lz, 0);
            dirtySlots.add(slot);
            if (currentLight > 0) {
                removeQueue.add(new LightNode(x, y, z, currentLight));
            }
        }

        // 1. Darken previous light boundaries (un-propagate)
        while (!removeQueue.isEmpty()) {
            LightNode node = removeQueue.poll();
            int lightLevel = node.distance;

            for (Direction dir : Direction.values()) {
                int nx = node.x + dir.x, ny = node.y + dir.y, nz = node.z + dir.z;
                if (!isInBuffer(nx, ny, nz)) continue;

                int nSlot = getSlotForWorldPos(nx, ny, nz);
                if (nSlot == World.EMPTY) continue;

                int nlx = nx & 15, nly = ny & 15, nlz = nz & 15;
                int nLight = world.getBlockLight(nSlot, nlx, nly, nlz);

                if (nLight != 0 && nLight < lightLevel) {
                    world.setBlockLight(nSlot, nlx, nly, nlz, 0);
                    dirtySlots.add(nSlot);
                    removeQueue.add(new LightNode(nx, ny, nz, nLight));
                } else if (nLight >= lightLevel) {
                    addQueue.add(new LightNode(nx, ny, nz, nLight));
                }
            }
        }

        // 2. Flood fill returning light + emissive (re-propagate)
        while (!addQueue.isEmpty()) {
            LightNode node = addQueue.poll();
            for (Direction dir : Direction.values()) {
                int nx = node.x + dir.x, ny = node.y + dir.y, nz = node.z + dir.z;
                if (!isInBuffer(nx, ny, nz)) continue;

                int nSlot = getSlotForWorldPos(nx, ny, nz);
                if (nSlot == World.EMPTY) continue;

                int nBlockId = world.getVoxel(nx, ny, nz);
                int opacity = getBlockOpacity(nBlockId);
                int expectedLight = node.distance - Math.max(1, opacity);

                if (expectedLight <= 0) continue;

                int nlx = nx & 15, nly = ny & 15, nlz = nz & 15;
                int current = world.getBlockLight(nSlot, nlx, nly, nlz);

                if (current < expectedLight) {
                    world.setBlockLight(nSlot, nlx, nly, nlz, expectedLight);
                    dirtySlots.add(nSlot);
                    addQueue.add(new LightNode(nx, ny, nz, expectedLight));
                }
            }
        }
        return dirtySlots;
    }

    // ══════════════════════════════════════════════════════════════════
    //  FULL REBUILD (all chunks)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Rebuilds all sky light and block light from scratch for the given loaded chunks.
     *
     * @param loadedChunks Map of chunkKey -> slots
     * @return Total number of dirty slots
     */
    public Set<Integer> rebuildAllLighting(java.util.Map<Long, Integer[]> loadedChunks) {
        Set<Integer> allDirty = new HashSet<>();

        // Phase 1: Clear all light in loaded chunks
        for (Integer[] slots : loadedChunks.values()) {
            for (int cy = 0; cy < 16; cy++) {
                world.clearLightPoolSlot(slots[cy]);
            }
        }

        // Phase 2: Sky light for all columns
        GameLogger.log("LIGHT Sky light generation...");
        int colDone = 0;
        for (java.util.Map.Entry<Long, Integer[]> entry : loadedChunks.entrySet()) {
            long key = entry.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Set<Integer> dirty = generateSkyLight(cx, cz, entry.getValue());
            allDirty.addAll(dirty);
            colDone++;
            if (colDone % 50 == 0) {
                System.out.print("\r  Sky light: " + colDone + "/" + loadedChunks.size() + " columns");
            }
        }
        System.out.println("\r  Sky light: " + colDone + "/" + loadedChunks.size() + " columns done");

        // Phase 3: Block light for all sections
        GameLogger.log("LIGHT Block light propagation...");
        int secDone = 0;
        int totalSecs = loadedChunks.size() * 16;
        for (java.util.Map.Entry<Long, Integer[]> entry : loadedChunks.entrySet()) {
            long key = entry.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Integer[] slots = entry.getValue();
            for (int cy = 0; cy < 16; cy++) {
                Set<Integer> dirty = propagateBlockLight(cx, cy, cz, slots[cy]);
                allDirty.addAll(dirty);
            }
            secDone += 16;
            if (secDone % 800 == 0) {
                System.out.print("\r  Block light: " + secDone + "/" + totalSecs + " sections");
            }
        }
        System.out.println("\r  Block light: " + secDone + "/" + totalSecs + " sections done");

        return allDirty;
    }

    // ══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns the light opacity of a block in units of 0-15.
     * Full blocks = 16 (completely opaque to light).
     * Uses BlockDataManager.getOpacity for partial opacity (water=3, leaves=1, etc.).
     */
    private int getBlockOpacity(int blockId) {
        if (blockId <= 0) return 0; // air
        return blockDataManager.getOpacity(blockId);
    }

    private int getSlotForWorldPos(int x, int y, int z) {
        return world.getChunkSlot(x, y, z);
    }

    private boolean isInBuffer(int x, int y, int z) {
        int rx = x - world.getOffsetX();
        int ry = y - world.getOffsetY();
        int rz = z - world.getOffsetZ();
        return rx >= 0 && ry >= 0 && rz >= 0
            && rx < World.REGION_SIZE * World.CHUNK_SIZE
            && ry < World.REGION_SIZE * World.CHUNK_SIZE
            && rz < World.REGION_SIZE * World.CHUNK_SIZE;
    }

    /** Simple BFS node for block light propagation. */
    private static class LightNode {
        final int x, y, z;
        final int distance; // light level at this node

        LightNode(int x, int y, int z, int distance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
        }
    }
}
