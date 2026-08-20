package com.voxel.entity;

import com.voxel.utils.TextureManager;
import org.joml.Vector3f;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests WitherEntity charge cycle + damage + dead-flag. We avoid GL by
 * extending with a no-op loadModel().
 */
public class WitherEntityTest {

    private static WitherEntity newBareWither() {
        return new WitherEntity(1, new Vector3f(0, 64, 0), null, null) {
            @Override
            public void loadModel(String path, TextureManager textureManager) {
                // Skip GL model load — we only exercise health mechanics here.
            }
        };
    }

    @Test
    public void startsInChargingState() {
        WitherEntity w = newBareWither();
        assertTrue(w.isCharging());
        assertFalse(w.isDead());
    }

    @Test
    public void punchesDuringChargingAreIgnored() {
        WitherEntity w = newBareWither();
        for (int i = 0; i < 50; i++) w.onPunch();
        assertFalse("punches during charge should not kill the Wither", w.isDead());
        assertTrue(w.isCharging());
    }

    @Test
    public void ticksPastChargeWindowExitsCharging() {
        WitherEntity w = newBareWither();
        // Drive 12 seconds of ticks — well past the 11s charge window.
        for (int i = 0; i < 120; i++) w.update(0.1f);
        assertFalse("Wither should leave charging after 11s", w.isCharging());
        assertFalse(w.isDead());
    }

    @Test
    public void fifteenPunchesAfterChargeKillTheWither() {
        WitherEntity w = newBareWither();
        // Force exit of charging by ticking past 11s.
        for (int i = 0; i < 120; i++) w.update(0.1f);
        // 300 HP / 20 damage per hit = 15 hits to kill.
        for (int i = 0; i < 14; i++) w.onPunch();
        assertFalse("14 punches should not kill", w.isDead());
        w.onPunch();
        assertTrue("15th punch should kill", w.isDead());
    }
}