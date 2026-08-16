package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import com.voxel.world.ChunkManager;
import com.voxel.world.DimensionType;
import com.voxel.world.FluidManager;
import com.voxel.world.WorldGenerator;
import com.voxel.world.WorldSaveManager;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Regression tests for the tutorial-world lake oscillation bug: after a lake
 * chunk loads, the fluid sim must leave the lake alone. Historically,
 * "waterlily" (41) tripped the name-based liquid heuristic (it contains
 * "water"), and {@code canFlowInto} let a fluid displace another fluid — so a
 * lily at y+1 flowed DOWN through the water at y, replaced it, spread, then
 * evaporated to air, and water flowed back in — an eternal
 * water↔lily↔air↔flowing cycle that re-triggered the full sky/block light
 * rebuild every ~100 ms.
 *
 * These tests model both the fixed reality (lily is NOT liquid) and the
 * defense-in-depth case (even a wrongly-tagged liquid must not displace water).
 */
public class FluidLilyStabilityTest {

    private static final int X = 8, Z = 8;
    private static final int WATER_Y = 68;   // lake surface
    private static final int BED_Y = 67;     // sand lakebed
    private static final int LILY_Y = 69;    // lily above the surface

    private static final class Harness implements AutoCloseable {
        final World world;
        final ChunkManager chunkManager;
        final FluidManager fluids;

        Harness(final boolean lilyIsLiquid) throws Exception {
            world = new World(128);
            BlockDataManager bdm = fullBlockData(lilyIsLiquid);
            WorldSaveManager saveManager = new WorldSaveManager(
                    System.getProperty("java.io.tmpdir") + "/voxel-fluid-lily-test-" + System.nanoTime());
            WorldGenerator gen = new WorldGenerator(2L, bdm) {
                @Override
                public int populateSection(int cx, int cy, int cz, World w, int slot) {
                    return 0; // all-air, instant
                }
            };
            chunkManager = new ChunkManager(world, gen, new LightEngine(world, bdm), 4, saveManager,
                    DimensionType.OVERWORLD, null, bdm);
            fluids = new FluidManager(world, chunkManager, bdm, false);
            chunkManager.setFluidManager(fluids);
        }

        /** Load the column around the lake and wait for the section + recenter settle. */
        void ready() throws InterruptedException {
            chunkManager.update(new Vector3f(X + 0.5f, WATER_Y + 0.5f, Z + 0.5f), 0f);
            waitSectionReady(0, 4, 0);
            long settleDeadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < settleDeadline) {
                if (world.getOffsetX() != 0 || world.getOffsetY() != 0 || world.getOffsetZ() != 0) {
                    break;
                }
                Thread.sleep(10);
            }
            waitSectionReady(0, 4, 0);
        }

        private void waitSectionReady(int cx, int cy, int cz) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && !chunkManager.isPlayerSectionGenerated(cx, cy, cz)) {
                Thread.sleep(10);
            }
            assertEquals("section (" + cx + "," + cy + "," + cz + ") never generated",
                    true, chunkManager.isPlayerSectionGenerated(cx, cy, cz));
        }

        /** Set a block (air → type; must not be a no-op for the fixture cells). */
        void place(int x, int y, int z, int block) {
            if (!chunkManager.setVoxel(x, y, z, block)) {
                throw new IllegalStateException("setVoxel refused at (" + x + "," + y + "," + z + ")");
            }
        }

        @Override
        public void close() {
            chunkManager.shutdown();
        }
    }

    /** Builds a 1-cell pond: solid ring at the surface, sand bed, water, lily on top. */
    private static void buildPond(Harness h) {
        // Solid ring at lake level so water cannot flow out horizontally.
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            h.place(X + d[0], WATER_Y, Z + d[1], 14); // sand ring
        }
        h.place(X, BED_Y, Z, 14);   // sand lakebed
        h.place(X, WATER_Y, Z, 15); // water source
        h.place(X, LILY_Y, Z, 41);  // waterlily above the water
    }

    @Test
    public void lakeWithLilyIsStable() throws Exception {
        // Post-fix reality: lily (41) is NOT liquid (Main.java resets the
        // name-heuristic LIQUID effect), so the fluid sim must ignore it and
        // the lake must not churn across many ticks.
        try (Harness h = new Harness(false)) {
            h.ready();
            buildPond(h);
            // Mimic scheduleFluidsInColumn: notify the water block on load.
            h.fluids.notifyBlockChanged(X, WATER_Y, Z);
            for (int i = 0; i < 300; i++) {
                h.fluids.tick(64);
            }
            assertEquals("lake water must stay water", 15, h.world.getVoxel(X, WATER_Y, Z));
            assertEquals("lakebed must stay sand", 14, h.world.getVoxel(X, BED_Y, Z));
            assertEquals("lily must stay a lily", 41, h.world.getVoxel(X, LILY_Y, Z));
            assertEquals("air above must stay air", 0, h.world.getVoxel(X, LILY_Y + 1, Z));
        }
    }

    @Test
    public void misTaggedLiquidCannotDisplaceWater() throws Exception {
        // Defense-in-depth (canFlowInto): even if a block were wrongly tagged
        // liquid (the old heuristic flagged "waterlily"), a fluid must never
        // flow into a cell already occupied by another fluid. This is the exact
        // mechanism that turned lakes into eternal water↔lily↔air cycles.
        try (Harness h = new Harness(true)) {
            h.ready();
            buildPond(h);
            h.fluids.notifyBlockChanged(X, WATER_Y, Z);
            for (int i = 0; i < 300; i++) {
                h.fluids.tick(64);
            }
            assertEquals("water must not be displaced", 15, h.world.getVoxel(X, WATER_Y, Z));
            assertEquals("lily must not be displaced", 41, h.world.getVoxel(X, LILY_Y, Z));
        }
    }

    private static BlockDataManager fullBlockData(final boolean lilyIsLiquid) {
        return new BlockDataManager() {
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId > 0;
            }

            @Override
            public boolean isLiquid(int blockId) {
                if (blockId == 15 || (blockId >= 150 && blockId <= 156) || blockId == 21) return true;
                if (lilyIsLiquid && blockId == 41) return true;
                return false;
            }
        };
    }
}
