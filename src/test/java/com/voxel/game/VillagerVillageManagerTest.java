package com.voxel.game;

import com.voxel.World;
import org.joml.Vector3i;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Village spawning contract. Villagers are queued by the structure generator
 * and only materialised once the ground under the village exists, so they can
 * no longer spawn into columns that were never generated.
 */
public class VillagerVillageManagerTest {

    /** World stub: ground only inside a small plate around the origin. */
    private static final class PlateWorld extends World {
        private final int radius;

        PlateWorld(int radius) {
            super(8);
            this.radius = radius;
        }

        @Override
        public int getVoxel(int x, int y, int z) {
            if (y != 63) return 0;
            return Math.abs(x) <= radius && Math.abs(z) <= radius ? 1 : 0;
        }
    }

    private static final class EmptyWorld extends World {
        EmptyWorld() { super(8); }

        @Override
        public int getVoxel(int x, int y, int z) {
            return 0;
        }
    }

    @Test
    public void villagesOnlySpawnOnceTheGroundIsLoaded() {
        Vector3i center = new Vector3i(0, 64, 0);
        assertTrue("ground everywhere counts as loaded",
                VillagerVillageManager.groundLoaded(new PlateWorld(64), center));
        assertFalse("a small plate does not cover the sample ring",
                VillagerVillageManager.groundLoaded(new PlateWorld(4), center));
        assertFalse("an ungenerated area has no ground",
                VillagerVillageManager.groundLoaded(new EmptyWorld(), center));
        assertFalse("no world, no spawn", VillagerVillageManager.groundLoaded(null, center));
    }

    @Test
    public void queuedVillagesStayPendingUntilTheEngineCanSpawnThem() {
        VillagerVillageManager manager = new VillagerVillageManager();
        manager.queueVillageSpawn(new Vector3i(120, 64, 120), 48, 3L);
        manager.queueVillageSpawn(new Vector3i(-400, 64, 90), 48, 4L);
        assertEquals(2, manager.getPendingVillageCount());

        // No texture/entity managers (headless): the queue must survive.
        manager.tick(new PlateWorld(64), 120f, 120f, null, null);
        assertEquals(2, manager.getPendingVillageCount());
        assertTrue(manager.getAllVillages().isEmpty());
    }
}
