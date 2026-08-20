package com.voxel.world;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * Tests the StrongholdLocator singleton: the canonical source of truth for
 * "where do thrown Eyes of Ender fly to". We exercise the
 * seed-fallback-vs-setCenter race plus the debug override path.
 */
public class StrongholdLocatorTest {

    @Before
    public void reset() {
        StrongholdLocator.reset();
    }

    @Test
    public void initialStateIsUnspawned() {
        assertFalse(StrongholdLocator.isSpawned());
        // The fallback positions default to 0,0 even before setStrongholdChunk.
        assertEquals(0, StrongholdLocator.getCenterX());
        assertEquals(0, StrongholdLocator.getCenterZ());
        // hasStrongholdChunk requires setStrongholdChunk to have fired.
        assertFalse(StrongholdLocator.hasStrongholdChunk());
    }

    @Test
    public void seedFallbackBeforeSetCenterMarksUnspawned() {
        StrongholdLocator.seedFallback(64, -32);
        // seedFallback does NOT flip the spawned flag, because it represents
        // a placeholder until worldgen has actually placed the structure.
        assertFalse(StrongholdLocator.isSpawned());
        assertEquals(64, StrongholdLocator.getCenterX());
        assertEquals(-32, StrongholdLocator.getCenterZ());
    }

    @Test
    public void setCenterMarksSpawnedAndOverridesSeed() {
        StrongholdLocator.seedFallback(64, -32);
        StrongholdLocator.setCenter(128, 256);
        assertTrue(StrongholdLocator.isSpawned());
        assertEquals(128, StrongholdLocator.getCenterX());
        assertEquals(256, StrongholdLocator.getCenterZ());
    }

    @Test
    public void setStrongholdChunkEnablesHook() {
        assertFalse(StrongholdLocator.hasStrongholdChunk());
        StrongholdLocator.setStrongholdChunk(8, -4, 32);
        assertTrue(StrongholdLocator.hasStrongholdChunk());
        assertEquals(8, StrongholdLocator.getStrongholdChunkX());
        assertEquals(-4, StrongholdLocator.getStrongholdChunkZ());
        assertEquals(32, StrongholdLocator.getStrongholdBaseY());
    }

    @Test
    public void debugSetCenterResistsWorldgenOverride() {
        StrongholdLocator.debugSetCenter(1000, 1000);
        assertTrue(StrongholdLocator.isSpawned());
        assertEquals(1000, StrongholdLocator.getCenterX());
        // Worldgen's setCenter is a no-op while debug override is active.
        StrongholdLocator.setCenter(20, 20);
        assertEquals(1000, StrongholdLocator.getCenterX());
        // clearOverride() lets worldgen drive the locator again.
        StrongholdLocator.clearOverride();
        StrongholdLocator.setCenter(20, 20);
        assertEquals(20, StrongholdLocator.getCenterX());
    }

    @Test
    public void resetReturnsToInitialState() {
        StrongholdLocator.setCenter(64, 64);
        StrongholdLocator.setStrongholdChunk(4, 4, 16);
        StrongholdLocator.reset();
        assertFalse(StrongholdLocator.isSpawned());
        assertFalse(StrongholdLocator.hasStrongholdChunk());
        assertEquals(0, StrongholdLocator.getCenterX());
    }
}