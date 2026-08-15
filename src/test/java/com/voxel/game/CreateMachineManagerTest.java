package com.voxel.game;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import com.voxel.world.ChunkManager;
import com.voxel.world.DimensionType;
import com.voxel.world.WorldGenerator;
import com.voxel.world.WorldSaveManager;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link CreateMachineManager}: crank spin timing, windmill sail
 * validation, deployer loading/unloading, and the powered saw/drill behavior.
 * Machines are "powered" by writing the kinetic spin flag (bits 24-25) the same
 * way KineticManager does, so these tests need no redstone or GL.
 *
 * Every test shuts its ChunkManager down in a finally block — leaking the gen
 * thread on a failed assertion starves other suite tests' chunk generation.
 */
public class CreateMachineManagerTest {

    private static final int MX = 8, MY = 80, MZ = 8;

    private static final class Harness implements AutoCloseable {
        final GameContext ctx;
        final ChunkManager chunkManager;
        final DroppedItemManager droppedItems;
        final CreateMachineManager machines;

        Harness() throws Exception {
            ctx = new GameContext();
            World world = new World(128);
            BlockDataManager bdm = fullBlockData();
            WorldSaveManager saveManager = new WorldSaveManager(
                    System.getProperty("java.io.tmpdir") + "/voxel-machine-test-" + System.nanoTime());
            WorldGenerator gen = new WorldGenerator(2L, bdm) {
                @Override
                public int populateSection(int cx, int cy, int cz, World w, int slot) {
                    return 0; // all-air, instant
                }
            };
            chunkManager = new ChunkManager(world, gen, new LightEngine(world, bdm), 4, saveManager,
                    DimensionType.OVERWORLD, null, bdm);
            ctx.world = world;
            ctx.chunkManager = chunkManager;
            droppedItems = new DroppedItemManager(ctx);
            machines = new CreateMachineManager(ctx, world, chunkManager, droppedItems);
        }

        /**
         * Load the column around (MX, MY, MZ) and block until the section is
         * generated AND the world buffer has finished any initial recenter.
         * The recenter (triggered when the spawn sits near a buffer edge, as
         * y=80 always does) clears and regenerates sections, which would wipe
         * test fixtures placed before it completes.
         */
        void ready() throws InterruptedException {
            chunkManager.update(new Vector3f(MX + 0.5f, MY + 0.5f, MZ + 0.5f), 0f);
            waitSectionReady(0, 5, 0);
            // Wait for a one-time buffer recenter to fire (offset leaves 0,0,0),
            // or conclude it never will (bootstrap stayed on) within the timeout.
            long settleDeadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < settleDeadline) {
                if (ctx.world.getOffsetX() != 0 || ctx.world.getOffsetY() != 0 || ctx.world.getOffsetZ() != 0) {
                    break;
                }
                Thread.sleep(10);
            }
            // Post-recenter sections regenerate; wait for the player section again.
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

        /** Set a block and tell every manager it changed. */
        void place(int x, int y, int z, int block) {
            chunkManager.setVoxel(x, y, z, block);
            machines.onBlockChanged(x, y, z);
        }

        /** Set a block with facing data (bits 16-18) and tell every manager it changed. */
        void placeFacing(int x, int y, int z, int block, int facing) {
            chunkManager.setVoxelWithData(x, y, z, block, facing);
            machines.onBlockChanged(x, y, z);
        }

        /** Write the spin flag so the machine reads as powered (preserving facing). */
        void power(int x, int y, int z) {
            int existingExtra = (ctx.world.getRawVoxel(x, y, z) >> 16) & 0xFF;
            chunkManager.setVoxelWithFlags(x, y, z, ctx.world.getVoxel(x, y, z), existingExtra,
                    com.voxel.world.KineticManager.FLAG_SPINNING);
        }

        @Override
        public void close() {
            chunkManager.shutdown();
        }
    }

    @Test
    public void crankSpinsForConfiguredTicksThenStops() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.place(MX, MY, MZ, CreateMachineManager.BLOCK_HAND_CRANK);
            assertFalse(h.machines.isCrankSpinning(MX, MY, MZ));
            h.machines.spinCrank(MX, MY, MZ);
            assertTrue(h.machines.isCrankSpinning(MX, MY, MZ));
            // Wind down one full 5-second wind-up (plus margin).
            h.machines.tick(5.0f + 0.1f);
            assertFalse("crank should stop after its spin time elapses",
                    h.machines.isCrankSpinning(MX, MY, MZ));
        }
    }

    @Test
    public void windmillBearingNeedsTwoExposedSails() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.place(MX, MY, MZ, CreateMachineManager.BLOCK_WINDMILL_BEARING);
            // One sail, blocked beyond -> not spinning.
            h.place(MX, MY, MZ + 1, CreateMachineManager.BLOCK_WINDMILL_SAIL);
            h.place(MX, MY, MZ + 2, 2); // stone blocks the wind
            assertFalse(h.machines.isWindmillSpinning(MX, MY, MZ));
            // Second sail, also blocked beyond -> still not spinning (needs 2 exposed).
            h.place(MX + 1, MY, MZ, CreateMachineManager.BLOCK_WINDMILL_SAIL);
            h.place(MX + 2, MY, MZ, 2);
            assertFalse(h.machines.isWindmillSpinning(MX, MY, MZ));
            // Clear the first sail's blocker -> one exposed sail, still short of two.
            h.place(MX, MY, MZ + 2, 0);
            assertFalse(h.machines.isWindmillSpinning(MX, MY, MZ));
            // Clear the second blocker too -> both sails exposed -> spinning.
            h.place(MX + 2, MY, MZ, 0);
            assertTrue(h.machines.isWindmillSpinning(MX, MY, MZ));
            assertEquals(2, h.machines.windmillSailCount(MX, MY, MZ));
        }
    }

    @Test
    public void deployerLoadsRejectsDifferentItemAndUnloads() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.place(MX, MY, MZ, CreateMachineManager.BLOCK_DEPLOYER);
            assertTrue(h.machines.loadDeployer(MX, MY, MZ, "cobblestone"));
            assertTrue(h.machines.loadDeployer(MX, MY, MZ, "cobblestone"));
            assertFalse("deployer must reject a different item",
                    h.machines.loadDeployer(MX, MY, MZ, "oak_planks"));
            assertEquals("cobblestone x2", h.machines.deployerStatus(MX, MY, MZ));
            // Unloading via block break should consume the slot.
            h.machines.unloadDeployerToInventory(MX, MY, MZ, null);
            assertEquals("empty", h.machines.deployerStatus(MX, MY, MZ));
        }
    }

    @Test
    public void poweredSawCutsLogInFront() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            // Saw facing east (5); log directly in front.
            h.placeFacing(MX, MY, MZ, CreateMachineManager.BLOCK_MECHANICAL_SAW, 5);
            h.place(MX + 1, MY, MZ, 5); // oak_log
            h.power(MX, MY, MZ);
            h.machines.tick(5.0f); // way past the 45-tick cooldown
            assertEquals("saw must remove the log in front", 0, h.ctx.world.getVoxel(MX + 1, MY, MZ));
        }
    }

    @Test
    public void directionalFacingRoundTripsThroughVoxelData() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            // The raytracer reads facing from voxel extra-data bits 16-18 to rotate
            // directional machine models. Verify every facing value survives the
            // World<->voxel packing that CreateMachineManager.facingOf() consumes.
            for (int facing = 0; facing <= 5; facing++) {
                h.placeFacing(MX + facing, MY, MZ, CreateMachineManager.BLOCK_MECHANICAL_SAW, facing);
            }
            for (int facing = 0; facing <= 5; facing++) {
                int raw = h.ctx.world.getRawVoxel(MX + facing, MY, MZ);
                assertEquals("block type must survive", CreateMachineManager.BLOCK_MECHANICAL_SAW, raw & 0xFFFF);
                assertEquals("facing must live in bits 16-18", facing, (raw >> 16) & 0x7);
            }
        }
    }

    @Test
    public void unpoweredSawLeavesLogAlone() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.placeFacing(MX, MY, MZ, CreateMachineManager.BLOCK_MECHANICAL_SAW, 5);
            h.place(MX + 1, MY, MZ, 5);
            // No spin flag -> idle.
            h.machines.tick(5.0f);
            assertEquals("unpowered saw must not cut", 5, h.ctx.world.getVoxel(MX + 1, MY, MZ));
        }
    }

    @Test
    public void poweredDrillMinesStoneButNotMachines() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            // All fixtures live inside the player's generated section (y 80-95) so
            // placement never races with background chunk generation.
            // Drill A facing east (5): mines the stone in front of it.
            h.placeFacing(MX, MY, MZ, CreateMachineManager.BLOCK_MECHANICAL_DRILL, 5);
            h.place(MX + 1, MY, MZ, 2); // stone
            h.power(MX, MY, MZ);
            // Drill B facing down (0): the item vault directly below must survive.
            h.placeFacing(MX + 4, MY + 2, MZ, CreateMachineManager.BLOCK_MECHANICAL_DRILL, 0);
            h.place(MX + 4, MY + 1, MZ, CreateMachineManager.BLOCK_ITEM_VAULT);
            h.power(MX + 4, MY + 2, MZ);
            h.machines.tick(5.0f);
            assertEquals("drill must mine stone", 0, h.ctx.world.getVoxel(MX + 1, MY, MZ));
            assertEquals("drill must never remove machines", CreateMachineManager.BLOCK_ITEM_VAULT,
                    h.ctx.world.getVoxel(MX + 4, MY + 1, MZ));
        }
    }

    @Test
    public void machinePositionsUntrackedWhenBlockReplaced() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.placeFacing(MX, MY, MZ, CreateMachineManager.BLOCK_BELT_CONVEYOR, 3);
            // Replace the belt with a plain block.
            h.place(MX, MY, MZ, 2);
            h.power(MX, MY, MZ);
            h.machines.tick(0.05f);
            // Belt is gone; nothing should have moved and the position set is cleaned lazily.
        }
    }

    private static BlockDataManager fullBlockData() {
        return new BlockDataManager() {
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId > 0;
            }
        };
    }
}
