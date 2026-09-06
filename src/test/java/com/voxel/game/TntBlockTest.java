package com.voxel.game;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import com.voxel.world.ChunkManager;
import com.voxel.world.DimensionType;
import com.voxel.world.WorldGenerator;
import com.voxel.world.WorldSaveManager;
import org.joml.Vector3f;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Headless coverage for {@link TntBlock}: igniting only real TNT, the fuse
 * holding until its time elapses, the core of the blast always destroying
 * blocks, bedrock surviving, and a primed block mined mid-fuse not exploding.
 * Uses the same all-air chunk-pool harness as the other world tests.
 */
public class TntBlockTest {

    private static final int MX = 8, MY = 80, MZ = 8;
    private static final int TNT = 601;
    private static final int BEDROCK = 7;

    @BeforeClass
    public static void configureTnt() {
        TntBlock.configure(TNT);
    }

    @AfterClass
    public static void unconfigureTnt() {
        TntBlock.resetForTest();
        TntBlock.configure(0);
    }

    private static final class Harness implements AutoCloseable {
        final World world;
        final ChunkManager chunkManager;

        Harness() throws Exception {
            world = new World(128);
            BlockDataManager bdm = new BlockDataManager() {
                @Override
                public boolean isFullBlock(int blockId) {
                    return blockId > 0;
                }
            };
            WorldSaveManager saveManager = new WorldSaveManager(
                    System.getProperty("java.io.tmpdir") + "/voxel-tnt-test-" + System.nanoTime());
            WorldGenerator gen = new WorldGenerator(2L, bdm) {
                @Override
                public int populateSection(int cx, int cy, int cz, World w, int slot) {
                    return 0; // all-air, instant
                }
            };
            chunkManager = new ChunkManager(world, gen, new LightEngine(world, bdm), 4, saveManager,
                    DimensionType.OVERWORLD, null, bdm);
        }

        void ready() throws InterruptedException {
            chunkManager.update(new Vector3f(MX + 0.5f, MY + 0.5f, MZ + 0.5f), 0f);
            waitSectionReady();
            // Wait for a one-time buffer recenter to fire (y=80 sits near the
            // buffer edge) or conclude it never will within the timeout.
            long settleDeadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < settleDeadline) {
                if (world.getOffsetX() != 0 || world.getOffsetY() != 0 || world.getOffsetZ() != 0) {
                    break;
                }
                Thread.sleep(10);
            }
            waitSectionReady();
        }

        private void waitSectionReady() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && !chunkManager.isPlayerSectionGenerated(0, 5, 0)) {
                Thread.sleep(10);
            }
            assertTrue("section never generated", chunkManager.isPlayerSectionGenerated(0, 5, 0));
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

    @Test
    public void fuseCountsDownThenDetonatesCoreOfBlast() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            TntBlock.configure(TNT);
            TntBlock.resetForTest();

            // TNT at the center, bedrock right beside it (must survive),
            // plain stone at the edge of the guaranteed-destroy core (must go).
            h.place(MX, MY, MZ, TNT);
            h.place(MX + 1, MY, MZ, BEDROCK);
            h.place(MX - 2, MY, MZ, 1); // dist 2 → inside the always-blast core

            // Igniting a non-TNT cell does nothing.
            assertFalse(TntBlock.ignite(h.world, MX + 1, MY, MZ));
            assertEquals(0, TntBlock.primedCount());
            assertTrue(TntBlock.ignite(h.world, MX, MY, MZ));
            assertEquals(1, TntBlock.primedCount());

            // Half the fuse: still primed, TNT block still there.
            TntBlock.tick(h.world, h.chunkManager, null, TntBlock.FUSE_SECONDS * 0.5f);
            assertEquals(1, TntBlock.primedCount());
            assertEquals(TNT, h.voxel(MX, MY, MZ));

            // The rest of the fuse detonates it.
            TntBlock.tick(h.world, h.chunkManager, null, TntBlock.FUSE_SECONDS);
            assertEquals(0, TntBlock.primedCount());
            assertEquals("TNT must vanish after detonation", 0, h.voxel(MX, MY, MZ));
            assertEquals("core stone inside the blast must be destroyed", 0, h.voxel(MX - 2, MY, MZ));
            assertEquals("bedrock must survive the blast", BEDROCK, h.voxel(MX + 1, MY, MZ));
        }
    }

    @Test
    public void miningPrimedTntMidFuseDefusesIt() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            TntBlock.configure(TNT);
            TntBlock.resetForTest();
            h.place(MX, MY, MZ, TNT);
            h.place(MX + 1, MY, MZ, 1); // neighbor that must survive a no-op defuse
            assertTrue(TntBlock.ignite(h.world, MX, MY, MZ));
            assertEquals(1, TntBlock.primedCount());

            // The block is mined away before the fuse ends.
            h.place(MX, MY, MZ, 0);
            TntBlock.tick(h.world, h.chunkManager, null, TntBlock.FUSE_SECONDS + 1.0f);
            assertEquals("a defused fuse must not linger", 0, TntBlock.primedCount());
            assertEquals("no blast: the neighbour survives", 1, h.voxel(MX + 1, MY, MZ));
        }
    }

    @Test
    public void unconfiguredTntIsInert() {
        TntBlock.configure(0);
        assertFalse(TntBlock.isTnt(TNT));
        assertFalse(TntBlock.isTnt(0));
        assertTrue(!TntBlock.ignite(null, 1, 2, 3));
        // Restore so sibling tests never depend on execution order.
        TntBlock.configure(TNT);
    }
}
