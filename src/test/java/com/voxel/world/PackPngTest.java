package com.voxel.world;

import com.voxel.game.GameContext;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Pins the cracked pack.png seed to the values the community recovered, and
 * checks that a fresh context actually starts from them.
 */
public class PackPngTest {

    @Test
    public void seedAndViewpointMatchTheRecoveredWorld() {
        assertEquals("Minecraft@Home recovered seed", 3257840388504953787L, PackPng.SEED);
        assertEquals("screenshot column x", 49, PackPng.VIEW_X);
        assertEquals("screenshot column z", 0, PackPng.VIEW_Z);
        assertEquals("icon hill x", 116, PackPng.HILL_X);
        assertEquals("icon hill z", -31, PackPng.HILL_Z);
        assertEquals("spawn uses the screenshot column", PackPng.VIEW_X, PackPng.SPAWN_X);
        assertEquals(PackPng.VIEW_Z, PackPng.SPAWN_Z);
    }

    @Test
    public void newWorldsDefaultToThePackPngSeed() {
        GameContext ctx = new GameContext();
        assertEquals(PackPng.SEED, ctx.worldSeed);
        assertEquals(PackPng.SEED, ctx.menuSeed);
        assertFalse("blank seed field is no longer random", ctx.randomSeed);
    }

    @Test
    public void perDimensionSeedDerivesFromPackPng() {
        DimensionManager manager = new DimensionManager(null, null, null);
        manager.setWorldSeed(PackPng.SEED);
        assertEquals(PackPng.SEED, manager.getWorldSeed());
        assertEquals(PackPng.SEED ^ (1L * 0x9E3779B97F4A7C15L),
                manager.seedFor(DimensionType.OVERWORLD));
        assertEquals(PackPng.SEED ^ (2L * 0x9E3779B97F4A7C15L),
                manager.seedFor(DimensionType.NETHER));
    }
}
