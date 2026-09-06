package com.voxel.game;

/**
 * Vanilla redstone input blocks: lever, stone/wooden buttons, and
 * stone(heavy)/wooden(light) pressure plates.
 *
 * <p>The engine's block registry assigns the resource-pack IDs at startup
 * (the pack's lever/button/plate blockstates auto-register as scenery), so
 * these ids are not compile-time constants: {@link #configure} is called from
 * Main after content registration and stores the runtime ids in statics.
 * Before {@code configure} runs everything reads as "not a switch", which
 * keeps tests and early startup safe.
 *
 * <p>Each switch has two block states (off = the scenery id, on = a companion
 * id registered right after the base). The voxel extra byte holds the mount
 * face in bits 0-2; pressure plates store their output strength in bits 4-7.
 */
public final class RedstoneSwitches {

    public static final int MOUNT_DOWN = 0;
    public static final int MOUNT_UP = 1;
    public static final int MOUNT_NORTH = 2;
    public static final int MOUNT_SOUTH = 3;
    public static final int MOUNT_WEST = 4;
    public static final int MOUNT_EAST = 5;

    // Runtime-configured ids. Off = base (item-placeable), on = off + 1.
    private static volatile int leverOff, leverOn;
    private static volatile int stoneButtonOff, stoneButtonOn;
    private static volatile int woodButtonOff, woodButtonOn;
    private static volatile int stonePlateOff, stonePlateOn;
    private static volatile int woodPlateOff, woodPlateOn;

    private RedstoneSwitches() { }

    /**
     * Locks in the switch block ids discovered after content registration.
     * The "on" ids are registered by the caller into the block/shader/data
     * registries; this only records the numbers.
     */
    public static void configure(int leverOffId, int leverOnId,
                                 int stoneButtonOffId, int stoneButtonOnId,
                                 int woodButtonOffId, int woodButtonOnId,
                                 int stonePlateOffId, int stonePlateOnId,
                                 int woodPlateOffId, int woodPlateOnId) {
        if (leverOffId <= 0 || leverOnId <= 0 || stoneButtonOffId <= 0 || stoneButtonOnId <= 0
                || woodButtonOffId <= 0 || woodButtonOnId <= 0
                || stonePlateOffId <= 0 || stonePlateOnId <= 0
                || woodPlateOffId <= 0 || woodPlateOnId <= 0) {
            throw new IllegalArgumentException("switch ids must be positive");
        }
        leverOff = leverOffId; leverOn = leverOnId;
        stoneButtonOff = stoneButtonOffId; stoneButtonOn = stoneButtonOnId;
        woodButtonOff = woodButtonOffId; woodButtonOn = woodButtonOnId;
        stonePlateOff = stonePlateOffId; stonePlateOn = stonePlateOnId;
        woodPlateOff = woodPlateOffId; woodPlateOn = woodPlateOnId;
    }

    public static boolean isConfigured() {
        return leverOff > 0;
    }

    public static int leverId() { return leverOff; }
    public static int stoneButtonId() { return stoneButtonOff; }
    public static int woodButtonId() { return woodButtonOff; }
    public static int stonePlateId() { return stonePlateOff; }
    public static int woodPlateId() { return woodPlateOff; }

    public static boolean isLever(int block) {
        return block > 0 && (block == leverOff || block == leverOn);
    }

    public static boolean isStoneButton(int block) {
        return block > 0 && (block == stoneButtonOff || block == stoneButtonOn);
    }

    public static boolean isWoodButton(int block) {
        return block > 0 && (block == woodButtonOff || block == woodButtonOn);
    }

    public static boolean isButton(int block) {
        return isStoneButton(block) || isWoodButton(block);
    }

    public static boolean isStonePlate(int block) {
        return block > 0 && (block == stonePlateOff || block == stonePlateOn);
    }

    public static boolean isWoodPlate(int block) {
        return block > 0 && (block == woodPlateOff || block == woodPlateOn);
    }

    public static boolean isPressurePlate(int block) {
        return isStonePlate(block) || isWoodPlate(block);
    }

    /** Any switch block in either state. */
    public static boolean isSwitchBlock(int block) {
        return block > 0 && (isLever(block) || isButton(block) || isPressurePlate(block));
    }

    /** True when the given block id is the powered state of a switch. */
    public static boolean isOn(int block) {
        return block > 0 && (block == leverOn || block == stoneButtonOn || block == woodButtonOn
                || block == stonePlateOn || block == woodPlateOn);
    }

    /** Powered-state id for a switch block (either state). */
    public static int onId(int block) {
        if (block == leverOff || block == leverOn) return leverOn;
        if (block == stoneButtonOff || block == stoneButtonOn) return stoneButtonOn;
        if (block == woodButtonOff || block == woodButtonOn) return woodButtonOn;
        if (block == stonePlateOff || block == stonePlateOn) return stonePlateOn;
        if (block == woodPlateOff || block == woodPlateOn) return woodPlateOn;
        return block;
    }

    /** Unpowered-state id for a switch block (either state). */
    public static int offId(int block) {
        if (block == leverOff || block == leverOn) return leverOff;
        if (block == stoneButtonOff || block == stoneButtonOn) return stoneButtonOff;
        if (block == woodButtonOff || block == woodButtonOn) return woodButtonOff;
        if (block == stonePlateOff || block == stonePlateOn) return stonePlateOff;
        if (block == woodPlateOff || block == woodPlateOn) return woodPlateOff;
        return block;
    }

    /** Item id dropped when a switch of this state is mined. */
    public static String dropItem(int block) {
        if (isLever(block)) return "lever";
        if (isStoneButton(block)) return "stone_button";
        if (isWoodButton(block)) return "wooden_button";
        if (isStonePlate(block)) return "stone_pressure_plate";
        if (isWoodPlate(block)) return "wooden_pressure_plate";
        return null;
    }

    /**
     * Pressure-plate signal strength, mirroring vanilla: a wooden (light)
     * plate outputs one level per entity on it, while a stone (heavy) plate
     * sums weighted entity light (a player counts 10). Both cap at 15.
     */
    public static int plateSignal(boolean stone, int standingEntities) {
        int signal = standingEntities * (stone ? 10 : 1);
        return Math.max(0, Math.min(15, signal));
    }

    /** Button release delay in engine ticks (stone = short, wood = long). */
    public static int buttonHoldTicks(int block) {
        return isStoneButton(block) ? 10 : 15;
    }
}
