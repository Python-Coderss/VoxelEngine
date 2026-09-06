package com.voxel.game;

import com.voxel.Player;
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
 * Headless coverage for {@link FarmBlocks}: the block-id helpers and the
 * periodic hydration/growth scan, driven against a real (all-air) chunk pool
 * exactly like {@code CreateMachineManagerTest} does. Every test shuts its
 * ChunkManager down in a finally block so the gen thread cannot leak.
 */
public class SurvivalFarmingTest {

    private static final int MX = 8, MY = 80, MZ = 8;

    private static final class Harness implements AutoCloseable {
        final GameContext ctx;
        final ChunkManager chunkManager;
        final World world;

        Harness() throws Exception {
            ctx = new GameContext();
            world = new World(128);
            BlockDataManager bdm = new BlockDataManager() {
                @Override
                public boolean isFullBlock(int blockId) {
                    return blockId > 0;
                }
            };
            WorldSaveManager saveManager = new WorldSaveManager(
                    System.getProperty("java.io.tmpdir") + "/voxel-farm-test-" + System.nanoTime());
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
            ctx.player = new Player(MX + 0.5f, MY + 0.5f, MZ + 0.5f);
        }

        /** Load the fixture column and wait out any one-time buffer recenter. */
        void ready() throws InterruptedException {
            chunkManager.update(new Vector3f(MX + 0.5f, MY + 0.5f, MZ + 0.5f), 0f);
            waitSectionReady(0, 5, 0);
            long settleDeadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < settleDeadline) {
                if (ctx.world.getOffsetX() != 0 || ctx.world.getOffsetY() != 0 || ctx.world.getOffsetZ() != 0) {
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
        }

        int voxel(int x, int y, int z) {
            return world.getVoxel(x, y, z);
        }

        @Override
        public void close() {
            chunkManager.shutdown();
        }
    }

    private static Player player(Harness h) {
        return h.ctx.player;
    }

    @Test
    public void blockIdHelpersDistinguishFarmlandAndWheatStages() {
        assertTrue(FarmBlocks.isFarmland(FarmBlocks.BLOCK_FARMLAND_DRY));
        assertTrue(FarmBlocks.isFarmland(FarmBlocks.BLOCK_FARMLAND_WET));
        assertFalse(FarmBlocks.isFarmland(13)); // dirt
        assertTrue(FarmBlocks.isWheat(FarmBlocks.BLOCK_WHEAT_0));
        assertTrue(FarmBlocks.isWheat(FarmBlocks.BLOCK_WHEAT_7));
        assertFalse(FarmBlocks.isWheat(FarmBlocks.BLOCK_FARMLAND_WET));
        assertEquals(0, FarmBlocks.wheatStage(FarmBlocks.BLOCK_WHEAT_0));
        assertEquals(7, FarmBlocks.wheatStage(FarmBlocks.BLOCK_WHEAT_7));
        assertEquals(-1, FarmBlocks.wheatStage(1)); // stone
    }

    @Test
    public void farmlandDriesWithoutWaterAndRehydratesWithWater() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            // Wet farmland with no water within radius 4 must dry out on the scan.
            h.place(MX, MY, MZ, FarmBlocks.BLOCK_FARMLAND_WET);
            FarmBlocks.tickSurvival(h.world, h.chunkManager, player(h));
            assertEquals("wet farmland with no water should dry out",
                    FarmBlocks.BLOCK_FARMLAND_DRY, h.voxel(MX, MY, MZ));

            // Bring water within 4 blocks (same level) — next pass rehydrates it.
            h.place(MX + 4, MY, MZ, 15); // water source
            FarmBlocks.tickSurvival(h.world, h.chunkManager, player(h));
            assertEquals("farmland within hydration radius should turn wet",
                    FarmBlocks.BLOCK_FARMLAND_WET, h.voxel(MX, MY, MZ));

            // And removing the water dries it again on a later pass.
            h.place(MX + 4, MY, MZ, 0);
            FarmBlocks.tickSurvival(h.world, h.chunkManager, player(h));
            assertEquals(FarmBlocks.BLOCK_FARMLAND_DRY, h.voxel(MX, MY, MZ));
        }
    }

    @Test
    public void cropsOnlyGrowOnFarmlandAndNeverPastRipe() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            // Ripe wheat on wet farmland stays ripe forever.
            h.place(MX, MY, MZ, FarmBlocks.BLOCK_FARMLAND_WET);
            h.place(MX, MY + 1, MZ, FarmBlocks.BLOCK_WHEAT_7);
            // Seedling on stone (no farmland below) must never grow.
            h.place(MX + 2, MY, MZ, 1); // stone
            h.place(MX + 2, MY + 1, MZ, FarmBlocks.BLOCK_WHEAT_0);

            for (int i = 0; i < 30; i++) {
                FarmBlocks.tickSurvival(h.world, h.chunkManager, player(h));
                assertEquals("ripe wheat must never exceed stage 7",
                        FarmBlocks.BLOCK_WHEAT_7, h.voxel(MX, MY + 1, MZ));
                assertEquals("a crop without farmland below must not grow",
                        FarmBlocks.BLOCK_WHEAT_0, h.voxel(MX + 2, MY + 1, MZ));
            }

            // A seedling on wet farmland grows monotonically and stays in range.
            h.place(MX + 1, MY, MZ, FarmBlocks.BLOCK_FARMLAND_WET);
            h.place(MX + 1, MY + 1, MZ, FarmBlocks.BLOCK_WHEAT_0);
            int prevStage = -1;
            for (int i = 0; i < 30; i++) {
                FarmBlocks.tickSurvival(h.world, h.chunkManager, player(h));
                int stage = FarmBlocks.wheatStage(h.voxel(MX + 1, MY + 1, MZ));
                assertTrue("wheat stage must stay within 0..7, was " + stage,
                        stage >= 0 && stage <= 7);
                assertTrue("wheat must never shrink, was " + prevStage + " then " + stage,
                        stage >= prevStage);
                prevStage = stage;
            }
        }
    }

    @Test
    public void tickScanIsHarmlessOnOrdinaryBlocks() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            h.place(MX, MY, MZ, 1); // stone
            h.place(MX, MY + 1, MZ, 13); // dirt above stone
            for (int i = 0; i < 5; i++) {
                int changed = FarmBlocks.tickSurvival(h.world, h.chunkManager, player(h));
                assertTrue("change count must never go negative", changed >= 0);
            }
            assertEquals(1, h.voxel(MX, MY, MZ));
            assertEquals(13, h.voxel(MX, MY + 1, MZ));
        }
    }
}
