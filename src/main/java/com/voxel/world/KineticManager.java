package com.voxel.world;

import com.voxel.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Create-style kinetic network.
 *
 * Water wheels generate rotation (when adjacent to water); shafts, cogs and the
 * wheel propagate it through 6-direction adjacency. A redstone-powered clutch
 * disengages (blocks propagation); a redstone-powered gearshift reverses the
 * direction of everything downstream. Every voxel of an active network gets a
 * kinetic flag written into bits 24-25 of its packed voxel data (bit 24 =
 * spinning, bit 25 = reversed), which the raytracer shader consumes to gate and
 * flip the animated cog/wheel textures.
 *
 * Thread-safety: the kinetic position set and last-flags map are concurrent;
 * the swap queue is drained on the GL thread (applySwaps) next to the redstone
 * lamp swaps. All world writes route through ChunkManager so dirty chunks get
 * re-uploaded to the GPU.
 *
 * Kinetic block IDs: shafts 291-293, cogwheel 294, large cogwheel 295, water
 * wheel 296, clutch 353/354, gearshift 355/356.
 */
public class KineticManager {
    public static final int BLOCK_SHAFT = 291;
    public static final int BLOCK_SHAFT_X = 292;
    public static final int BLOCK_SHAFT_Z = 293;
    public static final int BLOCK_COGWHEEL = 294;
    public static final int BLOCK_LARGE_COGWHEEL = 295;
    // Large cogwheel multiblock parts: 3x1x3 ghost blocks around the center (295).
    public static final int BLOCK_LARGE_COG_N = 422;   // z-1
    public static final int BLOCK_LARGE_COG_S = 423;   // z+1
    public static final int BLOCK_LARGE_COG_W = 424;   // x-1
    public static final int BLOCK_LARGE_COG_E = 425;   // x+1
    public static final int BLOCK_LARGE_COG_NW = 426;  // x-1,z-1
    public static final int BLOCK_LARGE_COG_NE = 427;  // x+1,z-1
    public static final int BLOCK_LARGE_COG_SW = 428;  // x-1,z+1
    public static final int BLOCK_LARGE_COG_SE = 429;  // x+1,z+1
    public static final int BLOCK_LARGE_COG_PART_MIN = BLOCK_LARGE_COG_N;
    public static final int BLOCK_LARGE_COG_PART_MAX = BLOCK_LARGE_COG_SE;
    // Water wheel multiblock parts: 3x3x1 footprint around the center (296).
    // The wheel is a vertical disc (axis Z), so parts sit in the XY plane.
    public static final int BLOCK_WATER_WHEEL_UP = 430;         // y+1
    public static final int BLOCK_WATER_WHEEL_DOWN = 431;       // y-1
    public static final int BLOCK_WATER_WHEEL_LEFT = 432;       // x-1
    public static final int BLOCK_WATER_WHEEL_RIGHT = 433;      // x+1
    public static final int BLOCK_WATER_WHEEL_UPLEFT = 434;     // x-1,y+1
    public static final int BLOCK_WATER_WHEEL_UPRIGHT = 435;    // x+1,y+1
    public static final int BLOCK_WATER_WHEEL_DOWNLEFT = 436;   // x-1,y-1
    public static final int BLOCK_WATER_WHEEL_DOWNRIGHT = 437;  // x+1,y-1
    public static final int BLOCK_WATER_WHEEL_PART_MIN = BLOCK_WATER_WHEEL_UP;
    public static final int BLOCK_WATER_WHEEL_PART_MAX = BLOCK_WATER_WHEEL_DOWNRIGHT;
    public static final int BLOCK_WATER_WHEEL = 296;
    public static final int BLOCK_CLUTCH = 353;
    public static final int BLOCK_CLUTCH_ON = 354;
    public static final int BLOCK_GEARSHIFT = 355;
    public static final int BLOCK_GEARSHIFT_ON = 356;
    // Steam engines: 396 idle, 397 active (heated by a lit blaze burner below).
    // Active engines are rotation sources, like spinning water wheels.
    public static final int BLOCK_STEAM_ENGINE = BlazeBurnerManager.BLOCK_STEAM_ENGINE;
    public static final int BLOCK_STEAM_ENGINE_ACTIVE = BlazeBurnerManager.BLOCK_STEAM_ENGINE_ACTIVE;
    // Create machines (404-413): hand crank and windmill bearing are rotation
    // sources (via CreateMachineManager); the rest propagate rotation and use it
    // to do work. Windmill sails (406) spin with the windmill; item vault (414)
    // and brass casing (415) are NOT kinetic.
    public static final int BLOCK_HAND_CRANK = com.voxel.game.CreateMachineManager.BLOCK_HAND_CRANK;
    public static final int BLOCK_WINDMILL_BEARING = com.voxel.game.CreateMachineManager.BLOCK_WINDMILL_BEARING;
    public static final int BLOCK_WINDMILL_SAIL = com.voxel.game.CreateMachineManager.BLOCK_WINDMILL_SAIL;
    public static final int BLOCK_MECHANICAL_PRESS = com.voxel.game.CreateMachineManager.BLOCK_MECHANICAL_PRESS;
    public static final int BLOCK_MILLSTONE = com.voxel.game.CreateMachineManager.BLOCK_MILLSTONE;
    public static final int BLOCK_CRUSHING_WHEEL = com.voxel.game.CreateMachineManager.BLOCK_CRUSHING_WHEEL;
    public static final int BLOCK_MECHANICAL_DRILL = com.voxel.game.CreateMachineManager.BLOCK_MECHANICAL_DRILL;
    public static final int BLOCK_MECHANICAL_SAW = com.voxel.game.CreateMachineManager.BLOCK_MECHANICAL_SAW;
    public static final int BLOCK_DEPLOYER = com.voxel.game.CreateMachineManager.BLOCK_DEPLOYER;
    public static final int BLOCK_BELT_CONVEYOR = com.voxel.game.CreateMachineManager.BLOCK_BELT_CONVEYOR;

    // Voxel flag bits (bits 24-25 of the packed int)
    public static final int FLAG_SPINNING = 1;
    public static final int FLAG_REVERSE = 2;

    private static final int BLOCK_WATER = 15;

    private static final int[][] DIRS = {
        {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    public static boolean isKinetic(int block) {
        return (block >= BLOCK_SHAFT && block <= BLOCK_WATER_WHEEL)
            || isLargeCogPart(block)   // multiblock parts join the gear's network
            || isWaterWheelPart(block) // water-wheel parts join the network
            || block == BLOCK_CLUTCH || block == BLOCK_CLUTCH_ON
            || block == BLOCK_GEARSHIFT || block == BLOCK_GEARSHIFT_ON
            || block == BLOCK_STEAM_ENGINE || block == BLOCK_STEAM_ENGINE_ACTIVE
            || block == BLOCK_HAND_CRANK || block == BLOCK_WINDMILL_BEARING
            || block == BLOCK_WINDMILL_SAIL   // sails spin with the windmill
            || (block >= BLOCK_MECHANICAL_PRESS && block <= BLOCK_BELT_CONVEYOR);
    }

    public static boolean isClutch(int block) {
        return block == BLOCK_CLUTCH || block == BLOCK_CLUTCH_ON;
    }

    public static boolean isGearshift(int block) {
        return block == BLOCK_GEARSHIFT || block == BLOCK_GEARSHIFT_ON;
    }

    /** True for the water wheel center (296) or any of its 8 multiblock parts. */
    public static boolean isWaterWheel(int block) {
        return block == BLOCK_WATER_WHEEL || isWaterWheelPart(block);
    }

    /** True for the 8 ghost parts of a water wheel (430-437), not the center. */
    public static boolean isWaterWheelPart(int block) {
        return block >= BLOCK_WATER_WHEEL_PART_MIN && block <= BLOCK_WATER_WHEEL_PART_MAX;
    }

    /** Horizontal/vertical offset {dx, dy} of a water-wheel part from its center, or null. */
    public static int[] waterWheelPartOffset(int block) {
        switch (block) {
            case BLOCK_WATER_WHEEL_UP:        return new int[]{0, 1};
            case BLOCK_WATER_WHEEL_DOWN:      return new int[]{0, -1};
            case BLOCK_WATER_WHEEL_LEFT:      return new int[]{-1, 0};
            case BLOCK_WATER_WHEEL_RIGHT:     return new int[]{1, 0};
            case BLOCK_WATER_WHEEL_UPLEFT:    return new int[]{-1, 1};
            case BLOCK_WATER_WHEEL_UPRIGHT:   return new int[]{1, 1};
            case BLOCK_WATER_WHEEL_DOWNLEFT:  return new int[]{-1, -1};
            case BLOCK_WATER_WHEEL_DOWNRIGHT: return new int[]{1, -1};
            default: return null;
        }
    }

    /** True for the large cogwheel center (295) or any of its 8 multiblock parts. */
    public static boolean isLargeCog(int block) {
        return block == BLOCK_LARGE_COGWHEEL || isLargeCogPart(block);
    }

    /** True for the 8 ghost parts of a large cogwheel (422-429), not the center. */
    public static boolean isLargeCogPart(int block) {
        return block >= BLOCK_LARGE_COG_PART_MIN && block <= BLOCK_LARGE_COG_PART_MAX;
    }

    /** Horizontal offset {dx, dz} of a large-cog part from its center, or null. */
    public static int[] largeCogPartOffset(int block) {
        switch (block) {
            case BLOCK_LARGE_COG_N:  return new int[]{0, -1};
            case BLOCK_LARGE_COG_S:  return new int[]{0, 1};
            case BLOCK_LARGE_COG_W:  return new int[]{-1, 0};
            case BLOCK_LARGE_COG_E:  return new int[]{1, 0};
            case BLOCK_LARGE_COG_NW: return new int[]{-1, -1};
            case BLOCK_LARGE_COG_NE: return new int[]{1, -1};
            case BLOCK_LARGE_COG_SW: return new int[]{-1, 1};
            case BLOCK_LARGE_COG_SE: return new int[]{1, 1};
            default: return null;
        }
    }

    /** True while at least one large cogwheel (295) is loaded. */
    public boolean hasLargeCog() {
        return !largeCogPositions.isEmpty();
    }

    /**
     * Gear prism parameters for a kinetic block, mirroring the raytracer's
     * getGear(). axis: 0=X, 1=Y, 2=Z; radius and halfThickness are in block units
     * (1 block = 1.0). The cross-section is a regular convex `sides`-gon; gear
     * teeth/spokes come from the block texture, not geometry. Returns null for
     * non-gear blocks. Keep in sync with raytracer.comp's getGear().
     */
    public static GearDescriptor gearDescriptor(int block) {
        final float P = 1.0f / 16.0f;
        switch (block) {
            case 291: return new GearDescriptor(1, 2*P, 16, 8*P);
            case 292: return new GearDescriptor(0, 2*P, 16, 8*P);
            case 293: return new GearDescriptor(2, 2*P, 16, 8*P);
            case 294: return new GearDescriptor(1, 8*P, 16, 3*P); // cogwheel fills the block
            case 295: return new GearDescriptor(1, 24*P, 16, 3*P);
            case 296: return new GearDescriptor(2, 24*P, 16, 4*P); // water wheel is now a 3x3 multiblock
            default: break;
        }
        if (block >= BLOCK_LARGE_COG_PART_MIN && block <= BLOCK_LARGE_COG_PART_MAX) {
            return new GearDescriptor(1, 24*P, 16, 3*P);
        }
        if (block >= BLOCK_WATER_WHEEL_PART_MIN && block <= BLOCK_WATER_WHEEL_PART_MAX) {
            return new GearDescriptor(2, 24*P, 16, 4*P);
        }
        return null;
    }

    /**
     * Java mirror of the raytracer's convex N-gon slab test (intersectGear's
     * 2D cross-section) so the entry/exit bounds can be unit-tested without a
     * GLSL validator. Treats the gear as a regular `sides`-gon of the given
     * radius centred at (cx,cz); the ray is (ox,oz)+t*(dx,dz). Returns the
     * entry distance, or -1 on a miss. Keep in sync with raytracer.comp's
     * intersectGear().
     */
    public static float intersectGearEntry(float ox, float oz, float dx, float dz,
                                           float cx, float cz, float radius, int sides) {
        float tEnter = -1e30f, tExit = 1e30f;
        for (int i = 0; i < sides; i++) {
            double a0 = i * 2.0 * Math.PI / sides;
            double a1 = (i + 1) * 2.0 * Math.PI / sides;
            double p0x = cx + Math.cos(a0) * radius, p0z = cz + Math.sin(a0) * radius;
            double p1x = cx + Math.cos(a1) * radius, p1z = cz + Math.sin(a1) * radius;
            double ex = p1x - p0x, ez = p1z - p0z;
            double nx = ez, nz = -ex; // (edge.y, -edge.x)
            double len = Math.sqrt(nx * nx + nz * nz);
            nx /= len; nz /= len;
            double mx = (p0x + p1x) * 0.5 - cx, mz = (p0z + p1z) * 0.5 - cz;
            if (nx * mx + nz * mz < 0.0) { nx = -nx; nz = -nz; } // point outward
            double planeDist = nx * p0x + nz * p0z;
            double denom = nx * dx + nz * dz;
            double rhs = planeDist - (nx * ox + nz * oz);
            if (Math.abs(denom) < 1e-7) {
                if (rhs < 0.0) return -1f;
                continue;
            }
            double tE = rhs / denom;
            if (denom > 0.0) {
                if (tE < tExit) tExit = (float) tE; // leaving this half-plane -> exit
            } else {
                if (tE > tEnter) tEnter = (float) tE; // entering -> entry bound
            }
        }
        if (tEnter > tExit) return -1f;
        return tEnter;
    }

    /** Immutable gear shape descriptor (mirror of the shader's getGear()). */
    public static final class GearDescriptor {
        public final int axis;
        public final float radius;
        public final int sides;
        public final float halfThickness;
        public GearDescriptor(int axis, float radius, int sides, float halfThickness) {
            this.axis = axis;
            this.radius = radius;
            this.sides = sides;
            this.halfThickness = halfThickness;
        }
    }

    private final World world;
    private final ChunkManager chunkManager;
    private final RedstoneManager redstoneManager;
    /** Provides crank/windmill source state (may be null). */
    private com.voxel.game.CreateMachineManager machineManager;

    public void setMachineManager(com.voxel.game.CreateMachineManager machineManager) {
        this.machineManager = machineManager;
    }

    private final Set<Long> kineticPositions = ConcurrentHashMap.newKeySet();
    /** Large cogwheels (295) in the world — gates the shader's 3x1x3 gear scan. */
    private final Set<Long> largeCogPositions = ConcurrentHashMap.newKeySet();
    // Concurrent: onBlockChanged runs on the GL thread while tick()/writeFlags run on
    // the logic thread (same GL/logic split as RedstoneManager's component maps).
    private final Map<Long, Integer> lastFlags = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<int[]> swapQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> scannedColumns = ConcurrentHashMap.newKeySet();
    private int rescanCooldown = 0;

    private static long pack(int x, int y, int z) {
        long ux = x & 0x1FFFFFL;
        long uy = y & 0x1FFFFFL;
        long uz = z & 0x1FFFFFL;
        return (ux << 42) | (uy << 21) | uz;
    }

    private static int unpackX(long key) { return (int) ((key >> 42) & 0x1FFFFFL); }
    private static int unpackY(long key) { return (int) ((key >> 21) & 0x1FFFFFL); }
    private static int unpackZ(long key) { return (int) (key & 0x1FFFFFL); }

    public KineticManager(World world, ChunkManager chunkManager, RedstoneManager redstoneManager) {
        this.world = world;
        this.chunkManager = chunkManager;
        this.redstoneManager = redstoneManager;
    }

    /** Called after any block change (placement/break) to track kinetic positions. */
    public void onBlockChanged(int x, int y, int z) {
        long key = pack(x, y, z);
        int block = world.getVoxel(x, y, z);
        if (isKinetic(block)) {
            kineticPositions.add(key);
        } else {
            kineticPositions.remove(key);
            lastFlags.remove(key);
        }
        if (block == BLOCK_LARGE_COGWHEEL) {
            largeCogPositions.add(key);
        } else {
            largeCogPositions.remove(key);
        }
    }

    /**
     * Incrementally discovers kinetic blocks in newly loaded chunk columns
     * (covers dimension switches and world reloads, where this manager starts
     * with an empty position set). Each column is scanned once; ~65k lookups.
     */
    private void rescanIncremental() {
        if (--rescanCooldown > 0) return;
        rescanCooldown = 5; // every 5 logic ticks (~80ms)
        if (chunkManager.getLoadedChunks() == null) return;
        for (long colKey : chunkManager.getLoadedChunks().keySet()) {
            if (!scannedColumns.add(colKey)) continue;
            int cx = (int) (colKey >> 32);
            int cz = (int) colKey;
            int wx = cx * 16, wz = cz * 16;
            for (int x = wx; x < wx + 16; x++) {
                for (int z = wz; z < wz + 16; z++) {
                    for (int y = 0; y < 256; y++) {
                        int block = world.getVoxel(x, y, z);
                        if (isKinetic(block)) {
                            kineticPositions.add(pack(x, y, z));
                        }
                        if (block == BLOCK_LARGE_COGWHEEL) {
                            largeCogPositions.add(pack(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * Called from the logic thread every tick, AFTER redstoneManager.tickLamps()
     * so clutch/gearshift power states reflect the latest network.
     */
    public void tick() {
        rescanIncremental();

        // 1. Refresh clutch/gearshift powered states. The swaps are applied on the GL
        // thread (applySwaps), so a just-powered clutch gates its network one tick
        // later — the same swap pattern the redstone lamps use.
        // (block-ID swaps applied on GL thread)
        for (long key : kineticPositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);
            if (block <= 0) continue;
            if (isClutch(block)) {
                int want = redstoneManager.hasPoweredNeighbor(x, y, z) ? BLOCK_CLUTCH_ON : BLOCK_CLUTCH;
                if (block != want) swapQueue.add(new int[]{x, y, z, want});
            } else if (isGearshift(block)) {
                int want = redstoneManager.hasPoweredNeighbor(x, y, z) ? BLOCK_GEARSHIFT_ON : BLOCK_GEARSHIFT;
                if (block != want) swapQueue.add(new int[]{x, y, z, want});
            }
        }

        // 2. Clear stale flags, then BFS every network that contains a spinning water wheel.
        for (long key : kineticPositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            if (world.getVoxel(x, y, z) > 0) writeFlags(x, y, z, 0);
        }
        Set<Long> visited = new HashSet<>();
        for (long key : kineticPositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);
            boolean isSource = (isWaterWheel(block) && hasAdjacentWater(x, y, z))
                || block == BLOCK_STEAM_ENGINE_ACTIVE
                || (block == BLOCK_HAND_CRANK && machineManager != null && machineManager.isCrankSpinning(x, y, z))
                || (block == BLOCK_WINDMILL_BEARING && machineManager != null && machineManager.isWindmillSpinning(x, y, z));
            if (isSource) {
                bfsNetwork(x, y, z, visited);
            }
        }
    }

    private void bfsNetwork(int sx, int sy, int sz, Set<Long> visited) {
        long startKey = pack(sx, sy, sz);
        if (visited.contains(startKey)) return;
        visited.add(startKey);
        Deque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{sx, sy, sz, 0});
        while (!queue.isEmpty()) {
            long[] cur = queue.poll();
            int x = (int) cur[0], y = (int) cur[1], z = (int) cur[2];
            boolean reverse = cur[3] != 0;
            writeFlags(x, y, z, reverse ? (FLAG_SPINNING | FLAG_REVERSE) : FLAG_SPINNING);
            for (int[] off : DIRS) {
                int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                long nkey = pack(nx, ny, nz);
                if (visited.contains(nkey)) continue;
                int nb = world.getVoxel(nx, ny, nz);
                if (!isKinetic(nb) || nb <= 0) continue;
                if (nb == BLOCK_CLUTCH_ON) continue; // powered clutch disengages
                boolean nrev = reverse;
                if (nb == BLOCK_GEARSHIFT_ON) nrev = !reverse;
                visited.add(nkey);
                queue.add(new long[]{nx, ny, nz, nrev ? 1 : 0});
            }
        }
    }

    private void writeFlags(int x, int y, int z, int flags) {
        long key = pack(x, y, z);
        Integer last = lastFlags.get(key);
        if (last != null && last.intValue() == flags) return;
        lastFlags.put(key, flags);
        int block = world.getVoxel(x, y, z);
        if (block > 0) {
            // Preserve extra data (bits 16-23) — directional machines like the
            // encased fan, belt, drill, saw, deployer and crusher store their
            // facing there, and a spinning network must not wipe it.
            int existingExtra = (world.getRawVoxel(x, y, z) >> 16) & 0xFF;
            chunkManager.setVoxelWithFlags(x, y, z, block, existingExtra, flags);
        }
    }

    private boolean hasAdjacentWater(int x, int y, int z) {
        int[][] offs = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, -1, 0}};
        for (int[] off : offs) {
            if (world.getVoxel(x + off[0], y + off[1], z + off[2]) == BLOCK_WATER) return true;
        }
        return false;
    }

    /** Must be called from the GL thread next to redstoneManager.applyLampChanges(). */
    public void applySwaps() {
        int[] change;
        while ((change = swapQueue.poll()) != null) {
            chunkManager.setVoxel(change[0], change[1], change[2], change[3]);
        }
    }

}
