package com.voxel.world;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests MobSpawnerLogic track/untrack lifecycle. The spawn tick itself
 * requires a live EntityManager, which is heavy; we exercise the public
 * registration surface here and trust the spawn emission is covered by
 * manual smoke testing.
 */
public class MobSpawnerLogicTest {

    @Test
    public void trackRegistersSpawner() {
        MobSpawnerLogic.resetForTests();
        MobSpawnerLogic.track(10, 32, 20);
        assertEquals("one tracked spawner",
                1, MobSpawnerLogic.getKnownSpawners().size());
    }

    @Test
    public void trackIsIdempotent() {
        MobSpawnerLogic.resetForTests();
        MobSpawnerLogic.track(10, 32, 20);
        MobSpawnerLogic.track(10, 32, 20);
        MobSpawnerLogic.track(10, 32, 20);
        assertEquals("duplicate tracks coalesce",
                1, MobSpawnerLogic.getKnownSpawners().size());
    }

    @Test
    public void resetForTestsClearsAllState() {
        MobSpawnerLogic.resetForTests();
        MobSpawnerLogic.track(1, 2, 3);
        MobSpawnerLogic.track(4, 5, 6);
        assertEquals(2, MobSpawnerLogic.getKnownSpawners().size());
        MobSpawnerLogic.resetForTests();
        assertEquals("reset wipes the registry",
                0, MobSpawnerLogic.getKnownSpawners().size());
    }
}