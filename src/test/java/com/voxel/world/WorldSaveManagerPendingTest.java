package com.voxel.world;

import com.voxel.World;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Regression coverage for structure writes that cross an unloaded chunk edge. */
public class WorldSaveManagerPendingTest {
    @Test
    public void deferredCrossChunkWriteSurvivesUntilTargetLoads() {
        File saveDir = new File(System.getProperty("java.io.tmpdir"),
                "voxel-pending-structure-test-" + System.nanoTime());
        WorldSaveManager save = new WorldSaveManager(saveDir.getPath());
        try {
            World sourceWorld = new World(8);
            sourceWorld.setChunkSlot(0, 0, 0, 0);
            sourceWorld.setVoxel(16, 4, 0, 72); // target chunk (1, 0), not loaded yet

            save.savePendingVoxels(DimensionType.OVERWORLD,
                    sourceWorld.drainDeferredVoxelWrites());

            World targetWorld = new World(8);
            targetWorld.setChunkSlot(1, 0, 0, 0);
            assertFalse(save.loadChunk(DimensionType.OVERWORLD, 1, 0, targetWorld));

            java.util.List<World.DeferredVoxelWrite> applied =
                    targetWorld.applyDeferredVoxelWrites(1, 0, 0);
            assertEquals(1, applied.size());
            assertEquals(72, targetWorld.getVoxel(16, 4, 0));

            save.acknowledgePendingVoxels(DimensionType.OVERWORLD, applied);
            assertFalse(new File(saveDir, "overworld/chunks/0_0/1_0.pending").exists());
        } finally {
            deleteRecursively(saveDir);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
