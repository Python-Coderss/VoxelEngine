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
 * Tests the player-grid freeze gate: {@link ChunkManager#arePlayerChunksGenerated}
 * must report false while any of the 27 sections of the player's 3×3×3 grid is
 * missing or still mid-generation, and true once everything has generated.
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
            // The first section is blocked mid-populateSection: its slot is
            // already allocated, so the grid MUST NOT be reported ready.
            assertFalse("grid reported ready while a section is still generating",
                    cm.arePlayerChunksGenerated(PCX, PCY, PCZ));
        } finally {
            release.countDown();
            cm.shutdown();
        }
    }

    @Test
    public void reportsFalseBeforeAnyChunksExist() {
        ChunkManager cm = newChunkManager(fastEmptyGenerator());
        try {
            assertFalse(cm.arePlayerChunksGenerated(PCX, PCY, PCZ));
        } finally {
            cm.shutdown();
        }
    }

    @Test
    public void playerGridBecomesReadyAfterUpdateQueuesManage() throws Exception {
        ChunkManager cm = newChunkManager(fastEmptyGenerator());
        try {
            cm.update(new Vector3f(8f, 88f, 8f), 0f);
            waitForGridReady(cm);
            assertTrue(cm.arePlayerChunksGenerated(PCX, PCY, PCZ));
        } finally {
            cm.shutdown();
        }
    }

    @Test
    public void retryGridGenerationDrivesGridToReady() throws Exception {
        ChunkManager cm = newChunkManager(fastEmptyGenerator());
        try {
            // No update() call: retryGridGeneration must fire manageChunks on its own.
            cm.retryGridGeneration(new Vector3f(8f, 88f, 8f), 0f);
            waitForGridReady(cm);
            assertTrue(cm.arePlayerChunksGenerated(PCX, PCY, PCZ));
        } finally {
            cm.shutdown();
        }
    }

    private static void waitForGridReady(ChunkManager cm) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !cm.arePlayerChunksGenerated(PCX, PCY, PCZ)) {
            Thread.sleep(10);
        }
        assertTrue("3×3×3 player grid never became fully generated",
                cm.arePlayerChunksGenerated(PCX, PCY, PCZ));
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
