package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the single-section readiness gate {@link ChunkManager#isPlayerSectionGenerated}.
 * It must report false while the player's own section is missing or still
 * mid-generation, and true once it has fully generated. This is the gate the
 * center-ray look-ahead uses to keep its preloads second-priority behind the
 * player's own section — it is NOT a player freeze gate anymore (gameplay
 * never waits on it).
 *
 * The mid-generation case is the critical one: a chunk slot is published BEFORE
 * its voxels are written, so a "loaded" check alone passes while the section is
 * still empty air — which is how the player used to fall through the world.
 */
public class ChunkManagerGridReadyTest {

    /** Player at (8, 88, 8) sits in chunk (0, 5, 0). */
    private static final int PCX = 0, PCY = 5, PCZ = 0;

    @Test
    public void reportsFalseWhileSectionIsMidGeneration() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorldGenerator blockingGen = new WorldGenerator(1L, fullBlockData()) {
            @Override
            public int populateSection(int cx, int cy, int cz, World world, int slot) {
                started.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return 0; // all-air section, but "generated" once unblocked
            }
        };

        ChunkManager cm = newChunkManager(blockingGen);
        try {
            cm.update(new Vector3f(8f, 88f, 8f), 0f);

            assertTrue("gen thread never started generating a section",
                    started.await(15, TimeUnit.SECONDS));
            // The player's section is blocked mid-populateSection: its slot is
            // already allocated, so the section MUST NOT be reported ready.
            assertFalse("section reported ready while still generating",
                    cm.isPlayerSectionGenerated(PCX, PCY, PCZ));
        } finally {
            release.countDown();
            cm.shutdown();
        }
    }

    @Test
    public void reportsFalseBeforeAnyChunksExist() {
        ChunkManager cm = newChunkManager(fastEmptyGenerator());
        try {
            assertFalse(cm.isPlayerSectionGenerated(PCX, PCY, PCZ));
        } finally {
            cm.shutdown();
        }
    }

    @Test
    public void playerSectionBecomesReadyAfterUpdateQueuesManage() throws Exception {
        ChunkManager cm = newChunkManager(fastEmptyGenerator());
        try {
            cm.update(new Vector3f(8f, 88f, 8f), 0f);
            waitForSectionReady(cm, PCX, PCY, PCZ);
            assertTrue(cm.isPlayerSectionGenerated(PCX, PCY, PCZ));
        } finally {
            cm.shutdown();
        }
    }

    @Test
    public void playerSectionReadyForAdjacentLoadedColumn() throws Exception {
        ChunkManager cm = newChunkManager(fastEmptyGenerator());
        try {
            cm.update(new Vector3f(8f, 88f, 8f), 0f);
            waitForSectionReady(cm, PCX, PCY, PCZ);
            // An adjacent column is streamed without ever freezing the player;
            // its own section becomes ready independently of the player's.
            cm.update(new Vector3f(24f, 88f, 8f), 0f);
            waitForSectionReady(cm, 1, PCY, 0);
            assertTrue(cm.isPlayerSectionGenerated(1, PCY, 0));
        } finally {
            cm.shutdown();
        }
    }

    private static void waitForSectionReady(ChunkManager cm, int cx, int cy, int cz) throws InterruptedException {
        // Generous deadline: under full-suite load the gen thread shares CPU with
        // the other ChunkManager-backed tests, so the section can take a while.
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && !cm.isPlayerSectionGenerated(cx, cy, cz)) {
            Thread.sleep(10);
        }
        assertTrue("section (" + cx + "," + cy + "," + cz + ") never became fully generated",
                cm.isPlayerSectionGenerated(cx, cy, cz));
    }

    private static WorldGenerator fastEmptyGenerator() {
        return new WorldGenerator(2L, fullBlockData()) {
            @Override
            public int populateSection(int cx, int cy, int cz, World world, int slot) {
                return 0; // bulk path: all-air section, instant
            }
        };
    }

    private static ChunkManager newChunkManager(WorldGenerator gen) {
        World world = new World(128); // enough pool slots for the 27-section grid
        BlockDataManager bdm = fullBlockData();
        WorldSaveManager saveManager = new WorldSaveManager(
                System.getProperty("java.io.tmpdir") + "/voxel-grid-test-" + System.nanoTime());
        return new ChunkManager(world, gen, new LightEngine(world, bdm), 4, saveManager,
                DimensionType.OVERWORLD, null, bdm);
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
