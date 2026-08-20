package com.voxel.entity;

import com.voxel.utils.TextureManager;
import org.joml.Vector3f;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests EnderDragonEntity heal/damage mechanics. We bypass the entity's
 * 3D model loading (which requires a GL texture manager) by subclassing
 * with a no-op loadModel override, then exercising the health
 * side-channel directly.
 */
public class EnderDragonEntityTest {

    /** Subclass that skips the GL-bound loadModel() path. */
    private static EnderDragonEntity newBareDragon() {
        return new EnderDragonEntity(1, new Vector3f(0, 0, 0), null, null) {
            @Override
            public void loadModel(String path, TextureManager textureManager) {
                // Skip GL model loading — we only test health mechanics here.
            }
        };
    }

    @Test
    public void dragonSurvivesUpToNineteenPunches() {
        EnderDragonEntity dragon = newBareDragon();
        for (int i = 0; i < 19; i++) dragon.onPunch();
        assertFalse("19 punches should not kill", dragon.isDead());
        assertEquals(19, dragon.getCumulativeHits());
    }

    @Test
    public void dragonDiesAfterTwentyPunches() {
        EnderDragonEntity dragon = newBareDragon();
        for (int i = 0; i < 20; i++) dragon.onPunch();
        assertTrue("20 punches should kill", dragon.isDead());
    }

    @Test
    public void healClampsCumulativeHitsAtZero() {
        EnderDragonEntity dragon = newBareDragon();
        dragon.onPunch();
        assertEquals(1, dragon.getCumulativeHits());
        // Heal 10.0 — internally subtracts ceil(10.0 * 100) = 1000 ticks.
        // Counter must clamp at 0, not go negative.
        dragon.heal(10.0f);
        assertEquals("hits clamp at 0", 0, dragon.getCumulativeHits());
    }

    @Test
    public void healReducesHitCounterByFivePerFiveHundredths() {
        EnderDragonEntity dragon = newBareDragon();
        // Five punches = 5 hits.
        for (int i = 0; i < 5; i++) dragon.onPunch();
        assertEquals(5, dragon.getCumulativeHits());
        // heal(0.05) subtracts 5 ticks worth (5f * 100 -> ceil -> 5).
        dragon.heal(0.05f);
        assertEquals(0, dragon.getCumulativeHits());
    }
}