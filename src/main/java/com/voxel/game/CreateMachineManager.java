package com.voxel.game;

import com.voxel.World;
import com.voxel.game.ItemDefinitions.ItemDefinition;
import com.voxel.world.ChunkManager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create-style machines beyond the base kinetic network:
 *
 *  - Hand crank (404): right-click winds it; it powers the adjacent kinetic
 *    network for {@link #CRANK_SPIN_TICKS} ticks.
 *  - Windmill bearing (405): generates rotation when at least two sails (406)
 *    are attached to its sides and those sails have open air beyond them.
 *  - Belt conveyor (413): when powered, moves dropped items resting on top of
 *    it along its facing direction.
 *  - Millstone (408): powered grinds the item resting on top (cobblestone ->
 *    gravel -> flint, andesite -> sand -> clay, netherrack -> soul sand).
 *  - Mechanical press (407): powered compacts 4 sand -> sandstone, 2 planks ->
 *    slab, and alloys copper + zinc -> 2 brass ingots (items below or on top).
 *  - Crushing wheel (409): powered doubles ores from the item thrown in front
 *    of its face.
 *  - Mechanical drill (410): powered mines the block in front of its face.
 *  - Mechanical saw (411): powered converts the log in front into 4 planks.
 *  - Deployer (412): loaded with a block item (right-click); when powered it
 *    periodically places that block in front of its face.
 *
 * Directional machines (belt/crusher/drill/saw/deployer) encode their facing
 * in voxel extra-data bits 16-18, the same scheme the encased fan uses.
 * "Powered" is read from the kinetic flags KineticManager writes into bits
 * 24-25, so this manager never needs to know about network propagation.
 *
 * Thread-safety: all state maps are concurrent because onBlockChanged can run
 * on the GL thread while tick() runs on the logic thread.
 */
public class CreateMachineManager {

    // ---- Block IDs (404-415) ----
    public static final int BLOCK_HAND_CRANK = 404;
    public static final int BLOCK_WINDMILL_BEARING = 405;
    public static final int BLOCK_WINDMILL_SAIL = 406;
    public static final int BLOCK_MECHANICAL_PRESS = 407;
    public static final int BLOCK_MILLSTONE = 408;
    public static final int BLOCK_CRUSHING_WHEEL = 409;
    public static final int BLOCK_MECHANICAL_DRILL = 410;
    public static final int BLOCK_MECHANICAL_SAW = 411;
    public static final int BLOCK_DEPLOYER = 412;
    public static final int BLOCK_BELT_CONVEYOR = 413;
    public static final int BLOCK_ITEM_VAULT = 414;
    public static final int BLOCK_BRASS_CASING = 415;

    /** One crank wind-up: 5 seconds of rotation. */
    public static final int CRANK_SPIN_TICKS = 100;
    /** Belt speed in voxels/sec. */
    public static final float BELT_SPEED = 1.6f;

    /** {input, output} pairs, ground one at a time. */
    private static final String[][] MILLSTONE_RECIPES = {
        {"cobblestone", "gravel"},
        {"gravel", "flint"},
        {"andesite", "sand"},
        {"sand", "clay"},
        {"netherrack", "soul_sand"},
    };
    /** {input, count, output, outputCount}. */
    private static final Object[][] PRESS_RECIPES = {
        {"sand", 4, "sandstone", 1},
        {"oak_planks", 2, "oak_slab", 1},
    };
    private static final String[][] CRUSHING_RECIPES = {
        {"iron_ore", "iron_ingot", "2"},
        {"gold_ore", "gold_ingot", "2"},
        {"copper_ore", "copper_ingot", "2"},
        {"zinc_ore", "zinc_ingot", "2"},
        {"quartz_ore", "nether_quartz", "2"},
        {"redstone_ore", "redstone_wire", "4"},
    };

    // Direction order matches pistons/fans: down, up, north, south, west, east
    private static final int[][] DIR_OFFSETS = {
        { 0, -1,  0},
        { 0,  1,  0},
        { 0,  0, -1},
        { 0,  0,  1},
        {-1,  0,  0},
        { 1,  0,  0},
    };
    private static final String[] DIR_NAMES = {"down", "up", "north", "south", "west", "east"};

    /** Per-tick cooldowns for each machine type. */
    private static final int COOLDOWN_MILLSTONE = 40;
    private static final int COOLDOWN_PRESS = 30;
    private static final int COOLDOWN_CRUSHER = 35;
    private static final int COOLDOWN_DRILL = 50;
    private static final int COOLDOWN_SAW = 45;
    private static final int COOLDOWN_DEPLOYER = 45;
    private static final int RETRY_TICKS = 3;

    /** Contents of a deployer. */
    public static final class DeployerSlot {
        public String itemId;
        public int count;
    }

    private final GameContext ctx;
    private final World world;
    private final ChunkManager chunkManager;
    private final DroppedItemManager droppedItemManager;

    private final Set<Long> machinePositions = ConcurrentHashMap.newKeySet();
    /** Remaining crank spin in ticks. */
    private final Map<Long, Integer> crankSpin = new ConcurrentHashMap<>();
    private final Map<Long, DeployerSlot> deployerContents = new ConcurrentHashMap<>();
    private final Map<Long, Integer> workTicks = new ConcurrentHashMap<>();
    private final Set<Long> scannedColumns = ConcurrentHashMap.newKeySet();
    private int rescanCooldown = 0;

    public CreateMachineManager(GameContext ctx, World world, ChunkManager chunkManager,
                                DroppedItemManager droppedItemManager) {
        this.ctx = ctx;
        this.world = world;
        this.chunkManager = chunkManager;
        this.droppedItemManager = droppedItemManager;
    }

    public static boolean isMachineBlock(int block) {
        return block >= BLOCK_HAND_CRANK && block <= BLOCK_BRASS_CASING;
    }

    /** Machines that consume power and do work each tick (excludes sails/vault/brass). */
    private static boolean isProcessingMachine(int block) {
        return block >= BLOCK_MECHANICAL_PRESS && block <= BLOCK_BELT_CONVEYOR;
    }

    /** Called after any block place/break to track machine positions. */
    public void onBlockChanged(int x, int y, int z) {
        if (world == null) return;
        int block = world.getVoxel(x, y, z);
        long key = pack(x, y, z);
        if (isProcessingMachine(block)) {
            machinePositions.add(key);
        } else {
            machinePositions.remove(key);
            workTicks.remove(key);
        }
        if (block == BLOCK_HAND_CRANK) {
            crankSpin.putIfAbsent(key, 0);
        } else {
            crankSpin.remove(key);
        }
        if (block == BLOCK_DEPLOYER) {
            deployerContents.putIfAbsent(key, new DeployerSlot());
        } else {
            deployerContents.remove(key);
        }
    }

    // ---- Crank ----

    public void spinCrank(int x, int y, int z) {
        crankSpin.put(pack(x, y, z), CRANK_SPIN_TICKS);
    }

    public boolean isCrankSpinning(int x, int y, int z) {
        return crankSpin.getOrDefault(pack(x, y, z), 0) > 0;
    }

    // ---- Windmill ----

    /** A bearing spins when >= 2 sails are attached with open air beyond them. */
    public boolean isWindmillSpinning(int x, int y, int z) {
        int exposed = 0;
        for (int i = 2; i <= 5; i++) { // N, S, W, E
            int[] off = DIR_OFFSETS[i];
            int sx = x + off[0], sy = y + off[1], sz = z + off[2];
            if (world.getVoxel(sx, sy, sz) != BLOCK_WINDMILL_SAIL) continue;
            if (world.getVoxel(sx + off[0], sy, sz + off[2]) == 0) exposed++;
        }
        return exposed >= 2;
    }

    public int windmillSailCount(int x, int y, int z) {
        int n = 0;
        for (int i = 2; i <= 5; i++) {
            int[] off = DIR_OFFSETS[i];
            if (world.getVoxel(x + off[0], y + off[1], z + off[2]) == BLOCK_WINDMILL_SAIL) n++;
        }
        return n;
    }

    // ---- Deployer ----

    public boolean loadDeployer(int x, int y, int z, String itemId) {
        long key = pack(x, y, z);
        DeployerSlot slot = deployerContents.computeIfAbsent(key, k -> new DeployerSlot());
        if (slot.itemId != null && !slot.itemId.equals(itemId)) return false;
        if (slot.count >= 64) return false;
        slot.itemId = itemId;
        slot.count++;
        return true;
    }

    public String deployerStatus(int x, int y, int z) {
        DeployerSlot slot = deployerContents.get(pack(x, y, z));
        if (slot == null || slot.itemId == null || slot.count <= 0) return "empty";
        return slot.itemId.replace('_', ' ') + " x" + slot.count;
    }

    /** Returns the deployer's item to the player (called when the block breaks). */
    public void unloadDeployerToInventory(int x, int y, int z, PlayerInventory inv) {
        long key = pack(x, y, z);
        DeployerSlot slot = deployerContents.get(key);
        if (slot != null && slot.itemId != null && slot.count > 0 && inv != null) {
            inv.addItem(slot.itemId, slot.count);
        }
        deployerContents.remove(key);
    }

    // ---- Power state ----

    /** True when the kinetic network currently powers this block (flags bits 24-25). */
    public boolean isMachinePowered(int x, int y, int z) {
        return ((world.getRawVoxel(x, y, z) >> 24) & 3) != 0;
    }

    // ---- Tick ----

    /** Called from the logic thread every tick, BEFORE KineticManager.tick(). */
    public void tick(float dt) {
        if (world == null || droppedItemManager == null) return;
        rescanIncremental();
        int ticks = Math.max(1, Math.round(dt * 20.0f));

        // 1. Crank spin timers wind down.
        for (Map.Entry<Long, Integer> e : crankSpin.entrySet()) {
            int v = e.getValue() - ticks;
            if (v <= 0) v = 0;
            e.setValue(v);
        }

        // 2. Processing machines.
        for (long key : machinePositions) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            int block = world.getVoxel(x, y, z);
            if (block <= 0 || !isProcessingMachine(block)) {
                machinePositions.remove(key);
                continue;
            }
            if (block == BLOCK_BELT_CONVEYOR) {
                tickBelt(x, y, z, dt);
                continue;
            }
            if (!isMachinePowered(x, y, z)) {
                workTicks.remove(key);
                continue;
            }
            int cd = workTicks.getOrDefault(key, 0) - ticks;
            if (cd > 0) {
                workTicks.put(key, cd);
                continue;
            }
            boolean worked = false;
            switch (block) {
                case BLOCK_MILLSTONE: worked = tickMillstone(x, y, z); break;
                case BLOCK_MECHANICAL_PRESS: worked = tickPress(x, y, z); break;
                case BLOCK_CRUSHING_WHEEL: worked = tickCrushingWheel(x, y, z); break;
                case BLOCK_MECHANICAL_DRILL: worked = tickDrill(x, y, z); break;
                case BLOCK_MECHANICAL_SAW: worked = tickSaw(x, y, z); break;
                case BLOCK_DEPLOYER: worked = tickDeployer(x, y, z); break;
                default: break;
            }
            workTicks.put(key, worked ? cooldownFor(block) : RETRY_TICKS);
        }
    }

    /**
     * Incrementally discovers machines in newly loaded chunk columns (covers
     * dimension switches and world reloads where this manager starts empty).
     * Mirrors KineticManager.rescanIncremental.
     */
    private void rescanIncremental() {
        if (--rescanCooldown > 0) return;
        rescanCooldown = 5;
        if (chunkManager == null || chunkManager.getLoadedChunks() == null) return;
        for (long colKey : chunkManager.getLoadedChunks().keySet()) {
            if (!scannedColumns.add(colKey)) continue;
            int cx = (int) (colKey >> 32);
            int cz = (int) colKey;
            int wx = cx * 16, wz = cz * 16;
            for (int x = wx; x < wx + 16; x++) {
                for (int z = wz; z < wz + 16; z++) {
                    for (int y = 0; y < 256; y++) {
                        int b = world.getVoxel(x, y, z);
                        if (b <= 0) continue;
                        long key = pack(x, y, z);
                        if (isProcessingMachine(b)) machinePositions.add(key);
                        if (b == BLOCK_HAND_CRANK) crankSpin.putIfAbsent(key, 0);
                        if (b == BLOCK_DEPLOYER) deployerContents.putIfAbsent(key, new DeployerSlot());
                    }
                }
            }
        }
    }

    // ---- Persistence (deployer contents) ----

    /** Saves all deployer contents to a file (binary, mirrors ChestManager). */
    public void saveToFile(java.io.File file) throws java.io.IOException {
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(file)))) {
            out.writeInt(deployerContents.size());
            for (Map.Entry<Long, DeployerSlot> e : deployerContents.entrySet()) {
                out.writeLong(e.getKey());
                DeployerSlot slot = e.getValue();
                if (slot != null && slot.itemId != null && slot.count > 0) {
                    out.writeBoolean(true);
                    out.writeUTF(slot.itemId);
                    out.writeInt(slot.count);
                } else {
                    out.writeBoolean(false);
                }
            }
        }
    }

    /** Loads deployer contents from a file. */
    public void loadFromFile(java.io.File file) throws java.io.IOException {
        deployerContents.clear();
        if (!file.exists()) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                DeployerSlot slot = new DeployerSlot();
                if (in.readBoolean()) {
                    slot.itemId = in.readUTF();
                    slot.count = in.readInt();
                }
                deployerContents.put(key, slot);
            }
        }
    }

    private static int cooldownFor(int block) {
        switch (block) {
            case BLOCK_MILLSTONE: return COOLDOWN_MILLSTONE;
            case BLOCK_MECHANICAL_PRESS: return COOLDOWN_PRESS;
            case BLOCK_CRUSHING_WHEEL: return COOLDOWN_CRUSHER;
            case BLOCK_MECHANICAL_DRILL: return COOLDOWN_DRILL;
            case BLOCK_MECHANICAL_SAW: return COOLDOWN_SAW;
            case BLOCK_DEPLOYER: return COOLDOWN_DEPLOYER;
            default: return RETRY_TICKS;
        }
    }

    private void tickBelt(int x, int y, int z, float dt) {
        if (!isMachinePowered(x, y, z)) return;
        int dir = facingOf(x, y, z);
        if (dir < 2 || dir > 5) return; // belts only run horizontally
        int[] off = DIR_OFFSETS[dir];
        droppedItemManager.moveOnBelt(x, y, z, off[0], off[2], BELT_SPEED * dt);
    }

    private boolean tickMillstone(int x, int y, int z) {
        for (String[] recipe : MILLSTONE_RECIPES) {
            if (droppedItemManager.consumeFromCell(x, y + 1, z, recipe[0], 1)) {
                droppedItemManager.spawn(recipe[1], 1, x, y + 1, z);
                return true;
            }
        }
        return false;
    }

    private boolean tickPress(int x, int y, int z) {
        // Alloy: copper + zinc -> 2 brass (items below or on top of the press).
        for (int cy : new int[]{y - 1, y + 1}) {
            if (droppedItemManager.hasItemInCell(x, cy, z, "copper_ingot")
                    && droppedItemManager.hasItemInCell(x, cy, z, "zinc_ingot")) {
                droppedItemManager.consumeFromCell(x, cy, z, "copper_ingot", 1);
                droppedItemManager.consumeFromCell(x, cy, z, "zinc_ingot", 1);
                droppedItemManager.spawn("brass_ingot", 2, x, cy, z);
                return true;
            }
        }
        for (Object[] recipe : PRESS_RECIPES) {
            String input = (String) recipe[0];
            int need = (Integer) recipe[1];
            String output = (String) recipe[2];
            int outCount = (Integer) recipe[3];
            for (int cy : new int[]{y - 1, y + 1}) {
                if (droppedItemManager.consumeFromCell(x, cy, z, input, need)) {
                    droppedItemManager.spawn(output, outCount, x, cy, z);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tickCrushingWheel(int x, int y, int z) {
        int dir = facingOf(x, y, z);
        if (dir > 5) dir = 1;
        int[] off = DIR_OFFSETS[dir];
        int cx = x + off[0], cy = y + off[1], cz = z + off[2];
        for (String[] recipe : CRUSHING_RECIPES) {
            if (droppedItemManager.consumeFromCell(cx, cy, cz, recipe[0], 1)) {
                int outCount;
                try {
                    outCount = Integer.parseInt(recipe[2]);
                } catch (NumberFormatException nfe) {
                    outCount = 2;
                }
                droppedItemManager.spawn(recipe[1], outCount, cx, cy, cz);
                return true;
            }
        }
        return false;
    }

    private boolean tickDrill(int x, int y, int z) {
        int dir = facingOf(x, y, z);
        if (dir > 5) dir = 1;
        int[] off = DIR_OFFSETS[dir];
        int bx = x + off[0], by = y + off[1], bz = z + off[2];
        int block = world.getVoxel(bx, by, bz);
        if (!isDrillBreakable(block)) return false;
        if (!chunkManager.setVoxel(bx, by, bz, 0)) return false;
        afterWorldChange(bx, by, bz);
        String drop = BlockInteraction.dropItemForBlock(block);
        if (drop == null && ctx.itemDefinitions != null) {
            drop = ctx.itemDefinitions.getBlockItemByBlockId().get(block);
        }
        if (drop != null) droppedItemManager.spawn(drop, 1, bx, by, bz);
        return true;
    }

    private boolean tickSaw(int x, int y, int z) {
        int dir = facingOf(x, y, z);
        if (dir > 5) dir = 1;
        int[] off = DIR_OFFSETS[dir];
        int bx = x + off[0], by = y + off[1], bz = z + off[2];
        int block = world.getVoxel(bx, by, bz);
        if (!isLogBlock(block)) return false;
        if (!chunkManager.setVoxel(bx, by, bz, 0)) return false;
        afterWorldChange(bx, by, bz);
        droppedItemManager.spawn("oak_planks", 4, bx, by, bz);
        return true;
    }

    private boolean tickDeployer(int x, int y, int z) {
        DeployerSlot slot = deployerContents.get(pack(x, y, z));
        if (slot == null || slot.itemId == null || slot.count <= 0) return false;
        if (ctx.itemDefinitions == null) return false;
        ItemDefinition def = ctx.itemDefinitions.getDefinition(slot.itemId);
        if (def == null || def.kind != ItemDefinitions.ItemKind.BLOCK || def.blockId <= 0) return false;
        int dir = facingOf(x, y, z);
        if (dir > 5) dir = 1;
        int[] off = DIR_OFFSETS[dir];
        int px = x + off[0], py = y + off[1], pz = z + off[2];
        if (world.getVoxel(px, py, pz) != 0) return false;
        if (!chunkManager.setVoxel(px, py, pz, def.blockId)) return false;
        afterWorldChange(px, py, pz);
        slot.count--;
        if (slot.count <= 0) {
            slot.itemId = null;
            slot.count = 0;
        }
        if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
        return true;
    }

    private void afterWorldChange(int x, int y, int z) {
        if (ctx.redstoneManager != null) {
            ctx.redstoneManager.onBlockChanged(x, y, z);
            ctx.redstoneManager.notifyNeighbors(x, y, z);
        }
        if (ctx.kineticManager != null) ctx.kineticManager.onBlockChanged(x, y, z);
        if (ctx.encasedFanSystem != null) ctx.encasedFanSystem.onBlockChanged(x, y, z);
        if (ctx.fluidManager != null) ctx.fluidManager.notifyBlockChanged(x, y, z);
        if (ctx.machineManager != null) ctx.machineManager.onBlockChanged(x, y, z);
        if (ctx.droppedItemManager != null) ctx.droppedItemManager.onBlockDestroyed(x, y, z);
    }

    private static boolean isLogBlock(int block) {
        return block == 5 || block == 46 || block == 47 || block == 49 || block == 51 || block == 52;
    }

    /** Blocks the drill must never remove. */
    private static boolean isDrillBreakable(int block) {
        if (block <= 0) return false;
        if (block == 15 || block == 21 || (block >= 150 && block <= 156)) return false; // liquids
        if (block == 7) return false; // bedrock
        if (block == 258 || block == 274 || block == 275) return false; // spawner, TV, command block
        if (com.voxel.game.CommandBlockManager.isCommandBlock(block)) return false;
        if (block >= 394 && block <= 421) return false; // burners, engines, tanks, machines, items
        if (block == 263 || block == 115 || block == 116 || block == 117 || block == 118) return false;
        if (block == 391 || block == 392 || block == 393) return false; // rails, minecart
        if (block == 33 || block == 259) return false; // piston heads
        return true;
    }

    private int facingOf(int x, int y, int z) {
        int dir = (world.getRawVoxel(x, y, z) >> 16) & 0x7;
        if (dir > 5) return 1;
        return dir;
    }

    public static String directionName(int dir) {
        if (dir >= 0 && dir < DIR_NAMES.length) return DIR_NAMES[dir];
        return "?";
    }

    // ---- Position packing (matches KineticManager) ----

    private static long pack(int x, int y, int z) {
        long ux = x & 0x1FFFFFL;
        long uy = y & 0x1FFFFFL;
        long uz = z & 0x1FFFFFL;
        return (ux << 42) | (uy << 21) | uz;
    }

    private static int unpackX(long key) { return (int) ((key >> 42) & 0x1FFFFFL); }
    private static int unpackY(long key) { return (int) ((key >> 21) & 0x1FFFFFL); }
    private static int unpackZ(long key) { return (int) (key & 0x1FFFFFL); }
}
