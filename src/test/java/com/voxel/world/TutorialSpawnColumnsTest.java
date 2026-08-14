package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the Tutorial World spawn. The spawn is at block (0, 69, 6),
 * so the sync 3×3×3 spawn grid includes the negative-coordinate chunk columns
 * (-1,-1), (-1,0), (-1,1), (0,-1) and (1,-1). The initial voxel buffer starts at
 * (0,0,0), so ChunkManager must recenter the buffer BEFORE loading the spawn
 * 3×3 — otherwise those five columns are claimed-but-empty (their section
 * writes are silently dropped outside the buffer) and the player falls straight
 * through them.
 *
 * The test boots a real ChunkManager against a temp copy of the bundled
 * tutorial template and asserts real terrain (stone_brick plaza, fountain
 * water) is present in both the positive and the negative spawn columns.
 */
public class TutorialSpawnColumnsTest {

    private ChunkManager cm;
    private World world;
    private File tempSave;

    @Before
    public void setUp() throws Exception {
        // Copy the bundled template to a temp dir so the test never writes back
        // into the git-tracked resources.
        tempSave = new File(System.getProperty("java.io.tmpdir") + "/voxel-tutorial-test-" + System.nanoTime());
        copyRecursively(new File("src/main/resources/tutorial_world"), tempSave);

        world = new World(2048);
        BlockDataManager bdm = fullBlockData();
        TutorialWorldGenerator gen = new TutorialWorldGenerator(1234567L, bdm);
        WorldSaveManager save = new WorldSaveManager(tempSave.getPath());
        cm = new ChunkManager(world, gen, new LightEngine(world, bdm), 2, save,
                DimensionType.OVERWORLD, null, bdm);
    }

    @After
    public void tearDown() {
        if (cm != null) cm.shutdown();
        if (tempSave != null) deleteRecursively(tempSave);
    }

    @Test
    public void spawnNegativeColumnsLoadRealTerrain() throws Exception {
        // Spawn: block (0.5, 69, 6.5) -> chunk (0, 4, 0).
        cm.update(new Vector3f(0.5f, 69f, 6.5f), 0f);

        // Wait on the full 3×3 readiness gate (the same one the game uses for
        // spawn resolution) — isPlayerSectionGenerated alone can report true
        // between slot registration and the synchronous disk-load write.
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && !cm.areSpawnChunksGenerated(0, 0)) {
            Thread.sleep(10);
        }
        assertTrue("spawn 3×3 never loaded", cm.areSpawnChunksGenerated(0, 0));

        // Positive-coordinate column (never affected by the bug).
        assertEquals("positive column plaza floor", 131, world.getVoxel(3, 68, 0));

        // Negative-coordinate columns: these used to read as air because the
        // 3×3 loaded before the buffer recentered to cover them.
        assertEquals("chunk(-1,0) plaza floor", 131, world.getVoxel(-4, 68, 5));
        assertEquals("chunk(-1,0) fountain water", 15, world.getVoxel(-1, 68, 1));
        assertEquals("chunk(0,-1) plaza floor", 131, world.getVoxel(4, 68, -4));
        assertEquals("chunk(-1,-1) base terrain", 2, world.getVoxel(-4, 64, -4));
        assertEquals("chunk(-1,1) plaza floor", 131, world.getVoxel(-4, 68, 10));
        assertEquals("chunk(1,-1) plaza floor", 131, world.getVoxel(10, 68, -4));
    }

    private static void copyRecursively(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children == null) return;
            for (File c : children) copyRecursively(c, new File(dst, c.getName()));
        } else {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
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
