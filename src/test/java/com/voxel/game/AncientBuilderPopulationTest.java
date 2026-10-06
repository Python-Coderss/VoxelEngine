package com.voxel.game;

import com.voxel.World;
import com.voxel.entity.AncientBuilderEntity;
import com.voxel.world.AncientBuilderSites;
import org.joml.Vector3i;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ancient-builder spawning contract: sites queue a population and the builders
 * only appear once the ground around the site exists. The queue survives a tick
 * with no texture/entity managers (headless), exactly like villages.
 */
public class AncientBuilderPopulationTest {

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

    @Before
    public void reset() {
        AncientBuilderPopulation.resetForTest();
    }

    @After
    public void tearDown() {
        AncientBuilderPopulation.resetForTest();
    }

    @Test
    public void buildersOnlySpawnOnceTheGroundIsLoaded() {
        Vector3i center = new Vector3i(0, 64, 0);
        assertTrue(AncientBuilderPopulation.groundLoaded(new PlateWorld(64), center));
        assertFalse(AncientBuilderPopulation.groundLoaded(new PlateWorld(4), center));
        assertFalse(AncientBuilderPopulation.groundLoaded(new EmptyWorld(), center));
        assertFalse(AncientBuilderPopulation.groundLoaded(null, center));
        assertFalse(AncientBuilderPopulation.groundLoaded(new PlateWorld(64), null));
    }

    @Test
    public void queuedSitesStayPendingUntilTheEngineCanSpawnThem() {
        AncientBuilderPopulation.queue(AncientBuilderSites.WATCH_SPIRE,
                new Vector3i(264, 64, -64), 7L);
        AncientBuilderPopulation.queue(AncientBuilderSites.GRAND_ARCHIVE,
                new Vector3i(-900, 64, -900), 8L);
        assertEquals(2, AncientBuilderPopulation.getPendingCount());

        // No entity manager (headless): the queue must survive.
        AncientBuilderPopulation.tick(new PlateWorld(64), 264f, -64f, null, null);
        assertEquals(2, AncientBuilderPopulation.getPendingCount());
    }

    @Test
    public void rolesFollowTheSite() {
        assertEquals(AncientBuilderEntity.Role.SENTINEL,
                AncientBuilderPopulation.roleFor("watch_spire", 0));
        assertEquals(AncientBuilderEntity.Role.SCHOLAR,
                AncientBuilderPopulation.roleFor("grand_archive", 0));
        assertEquals(AncientBuilderEntity.Role.ENGINEER,
                AncientBuilderPopulation.roleFor("engine_works", 0));
        assertEquals(AncientBuilderEntity.Role.ARCHITECT,
                AncientBuilderPopulation.roleFor("monument_garden", 0));
        // Unknown site falls back to architects.
        assertEquals(AncientBuilderEntity.Role.ARCHITECT,
                AncientBuilderPopulation.roleFor("mystery", 0));
    }

    @Test
    public void roleMixIsStableAcrossIndexes() {
        AncientBuilderEntity.Role[] mix =
                AncientBuilderPopulation.rolesFor("grand_archive");
        assertTrue(mix.length >= 1);
        for (int i = 0; i < 6; i++) {
            assertEquals(mix[i % mix.length],
                    AncientBuilderPopulation.roleFor("grand_archive", i));
        }
    }
}
