package com.voxel.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Vanilla critical rule: only a falling swing crits. */
public class CriticalHitTest {

    @Test
    public void onlyDownwardAirborneSwingsCrit() {
        assertTrue(CriticalHit.isCritical(false, -0.30));
        assertTrue(CriticalHit.isCritical(false, -0.051));
        assertFalse("grounded", CriticalHit.isCritical(true, -0.50));
        assertFalse("rising jump", CriticalHit.isCritical(false, 0.20));
        assertFalse("apex", CriticalHit.isCritical(false, 0.0));
    }

    @Test
    public void multipliesDamageByOnePointFive() {
        assertEquals(15f, CriticalHit.damage(10f, false, -0.2), 1e-6f);
        assertEquals(10f, CriticalHit.damage(10f, true, -0.2), 1e-6f);
        assertEquals(7.5f, CriticalHit.damage(5f, true), 1e-6f);
        assertEquals(5f, CriticalHit.damage(5f, false), 1e-6f);
    }
}
