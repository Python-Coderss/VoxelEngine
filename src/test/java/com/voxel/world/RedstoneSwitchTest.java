package com.voxel.world;

import com.voxel.World;
import com.voxel.game.RedstoneSwitches;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Headless coverage for the redstone input switches (milestone: levers,
 * buttons, pressure plates): a lever must power an adjacent wire when flipped
 * and stop when flipped back, a button must pulse and auto-release after its
 * hold time, and a pressure plate must turn on under the player and off when
 * the player steps away. Uses a real all-air chunk pool, exactly like
 * {@code CreateMachineManagerTest}, and drives RedstoneManager through its
 * public tick/apply cycle.
 */
public class RedstoneSwitchTest {

    private static final int MX = 8, MY = 80, MZ = 8;
    // Off/on pairs for the five switches (test ids, configured at startup).
    private static final int LEVER = 711, LEVER_ON = 712;
    private static final int SBTN = 713, SBTN_ON = 714;
    private static final int WBTN = 715, WBTN_ON = 716;
    private static final int SPLATE = 717, SPLATE_ON = 718;
    private static final int WPLATE = 719, WPLATE_ON = 720;

    @BeforeClass
    public static void configureSwitches() {
        RedstoneSwitches.configure(LEVER, LEVER_ON, SBTN, SBTN_ON, WBTN, WBTN_ON,
                SPLATE, SPLATE_ON, WPLATE, WPLATE_ON);
    }

    private static final class Harness implements AutoCloseable {
        final World world;
        final ChunkManager chunkManager;
        final RedstoneManager rm;

        Harness() throws Exception {
            world = new World(128);
            BlockDataManager bdm = new BlockDataManager() {
                @Override
                public boolean isFullBlock(int blockId) {
                    return blockId > 0;
                }
            };
            WorldSaveManager saveManager = new WorldSaveManager(
                    System.getProperty("java.io.tmpdir") + "/voxel-rs-test-" + System.nanoTime());
            WorldGenerator gen = new WorldGenerator(2L, bdm) {
                @Override
                public int populateSection(int cx, int cy, int cz, World w, int slot) {
                    return 0; // all-air, instant
                }
            };
            chunkManager = new ChunkManager(world, gen, new LightEngine(world, bdm), 4, saveManager,
                    DimensionType.OVERWORLD, null, bdm);
            rm = new RedstoneManager(world, chunkManager);
        }

        void ready() throws InterruptedException {
            chunkManager.update(new Vector3f(MX + 0.5f, MY + 0.5f, MZ + 0.5f), 0f);
            waitSectionReady(0, 5, 0);
            long settleDeadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < settleDeadline) {
                if (world.getOffsetX() != 0 || world.getOffsetY() != 0 || world.getOffsetZ() != 0) {
                    break;
                }
                Thread.sleep(10);
            }
            waitSectionReady(0, 5, 0);
        }

        private void waitSectionReady(int cx, int cy, int cz) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && !chunkManager.isPlayerSectionGenerated(cx, cy, cz)) {
                Thread.sleep(10);
            }
            assertTrue("section (" + cx + "," + cy + "," + cz + ") never generated",
                    chunkManager.isPlayerSectionGenerated(cx, cy, cz));
        }

        void place(int x, int y, int z, int block) {
            assertTrue("place failed for block " + block, chunkManager.setVoxel(x, y, z, block));
            rm.onBlockChanged(x, y, z);
            rm.notifyNeighbors(x, y, z);
        }

        void placeSwitch(int x, int y, int z, int block, int face) {
            assertTrue("place failed for block " + block, chunkManager.setVoxelWithData(x, y, z, block, face));
            rm.onBlockChanged(x, y, z);
            rm.notifyNeighbors(x, y, z);
        }

        int voxel(int x, int y, int z) {
            return world.getVoxel(x, y, z);
        }

        /** Run the logic/apply cycle a few times so queued swaps land. */
        void settle(int rounds) {
            for (int i = 0; i < rounds; i++) {
                rm.tickLamps();
                rm.applyLampChanges();
            }
        }

        @Override
        public void close() {
            chunkManager.shutdown();
        }
    }

    @Test
    public void leverPowersWireWhenFlippedOnAndOff() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.rm.setPlayerPosition(8.5f, 80.5f, 50f); // far from the ore-trigger radius
            h.place(MX, MY, MZ, RedstoneManager.BLOCK_REDSTONE_WIRE);
            h.place(MX + 2, MY, MZ, 1);               // stone mount east of the lever
            h.placeSwitch(MX + 1, MY, MZ, LEVER, 5);  // lever between wire and stone, face east
            h.settle(2);
            assertEquals("lever starts off, wire unpowered", 0, h.rm.getPowerLevel(MX, MY, MZ));

            // Player pulls the lever (GL-thread state change, like BlockInteraction).
            assertTrue(h.chunkManager.setVoxelWithData(MX + 1, MY, MZ, LEVER_ON, 5));
            h.rm.onSwitchToggled(MX + 1, MY, MZ, LEVER_ON);
            h.settle(2);
            assertEquals("lever on must power the adjacent wire (15 - 1)",
                    14, h.rm.getPowerLevel(MX, MY, MZ));

            // Flip it back off.
            assertTrue(h.chunkManager.setVoxelWithData(MX + 1, MY, MZ, LEVER, 5));
            h.rm.onSwitchToggled(MX + 1, MY, MZ, LEVER);
            h.settle(2);
            assertEquals("lever off must depower the wire", 0, h.rm.getPowerLevel(MX, MY, MZ));
        }
    }

    @Test
    public void stoneButtonPulsesThenAutoReleases() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.rm.setPlayerPosition(8.5f, 80.5f, 50f);
            h.place(MX, MY, MZ, RedstoneManager.BLOCK_REDSTONE_WIRE);
            h.place(MX + 2, MY, MZ, 1);
            h.placeSwitch(MX + 1, MY, MZ, SBTN, 5);
            h.settle(2);
            assertEquals(0, h.rm.getPowerLevel(MX, MY, MZ));

            // Press it.
            assertTrue(h.chunkManager.setVoxelWithData(MX + 1, MY, MZ, SBTN_ON, 5));
            h.rm.onSwitchToggled(MX + 1, MY, MZ, SBTN_ON);
            h.settle(2);
            assertEquals("pressed button must power the wire", 14, h.rm.getPowerLevel(MX, MY, MZ));

            // Wait past the stone button's hold time; it must pop back out on its own.
            h.settle(20);
            assertEquals("button must auto-release to its unpowered block",
                    SBTN, h.voxel(MX + 1, MY, MZ));
            assertEquals("released button must depower the wire", 0, h.rm.getPowerLevel(MX, MY, MZ));
        }
    }

    @Test
    public void woodenButtonHoldsLongerThanStone() throws Exception {
        assertEquals(10, RedstoneSwitches.buttonHoldTicks(SBTN));
        assertEquals(15, RedstoneSwitches.buttonHoldTicks(WBTN));
    }

    @Test
    public void stonePlatePowersUnderPlayerAndReleasesWhenTheyLeave() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.place(MX, MY, MZ, RedstoneManager.BLOCK_REDSTONE_WIRE);
            h.place(MX + 1, MY - 1, MZ, 1);            // support block under the plate
            h.placeSwitch(MX + 1, MY, MZ, SPLATE, 0);  // plate on the support, wire adjacent west
            h.rm.setPlayerPosition(8.5f, 30f, 8.5f);   // player far away
            h.settle(2);
            assertEquals("nobody on the plate: unpowered", 0, h.rm.getPowerLevel(MX, MY, MZ));
            assertEquals(SPLATE, h.voxel(MX + 1, MY, MZ));

            // Player stands on the plate.
            h.rm.setPlayerPosition(MX + 1.5f, MY + 0.3f, MZ + 0.5f);
            h.settle(4);
            assertEquals("stone plate should be pressed down", SPLATE_ON, h.voxel(MX + 1, MY, MZ));
            // Weighted signal: a lone player counts 10 on a stone plate → wire = 9.
            assertEquals(9, h.rm.getPowerLevel(MX, MY, MZ));

            // Step off: plate must release.
            h.rm.setPlayerPosition(8.5f, 30f, 8.5f);
            h.settle(4);
            assertEquals(SPLATE, h.voxel(MX + 1, MY, MZ));
            assertEquals(0, h.rm.getPowerLevel(MX, MY, MZ));
        }
    }

    @Test
    public void plateSignalMathMatchesVanillaWeights() {
        // Wooden (light) plate: one level per entity.
        assertEquals(1, RedstoneSwitches.plateSignal(false, 1));
        assertEquals(5, RedstoneSwitches.plateSignal(false, 5));
        assertEquals(15, RedstoneSwitches.plateSignal(false, 99));
        // Stone (heavy) plate: player weight 10, capped at 15.
        assertEquals(10, RedstoneSwitches.plateSignal(true, 1));
        assertEquals(15, RedstoneSwitches.plateSignal(true, 2));
        assertEquals(0, RedstoneSwitches.plateSignal(true, 0));
        // Drop names round-trip per material.
        assertEquals("lever", RedstoneSwitches.dropItem(LEVER));
        assertEquals("lever", RedstoneSwitches.dropItem(LEVER_ON));
        assertEquals("stone_button", RedstoneSwitches.dropItem(SBTN_ON));
        assertEquals("wooden_button", RedstoneSwitches.dropItem(WBTN));
        assertEquals("stone_pressure_plate", RedstoneSwitches.dropItem(SPLATE_ON));
        assertEquals("wooden_pressure_plate", RedstoneSwitches.dropItem(WPLATE_ON));
        assertTrue(RedstoneSwitches.dropItem(1) == null);
        // Unconfigured air never reads as a switch or as "on".
        assertTrue(!RedstoneSwitches.isSwitchBlock(0));
        assertTrue(!RedstoneSwitches.isOn(0));
    }
}
