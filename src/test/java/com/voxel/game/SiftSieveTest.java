package com.voxel.game;

import com.voxel.world.SiftTides;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Sift's sifting mechanic is pure loot-table + cooldown logic. These tests
 * pin the tide scaling (rich at high tide, poor at low), the two pools, and the
 * per-bed cooldown so the mechanic stays deterministic outside a GL context.
 */
public class SiftSieveTest {

    @Test
    public void poolsAreNonEmpty() {
        assertTrue(SiftSieve.commonCount() > 0);
        assertTrue(SiftSieve.rareCount() > 0);
    }

    @Test
    public void nullRandomAlwaysYieldsTheFirstCommon() {
        SiftSieve.Loot loot = SiftSieve.roll(0f, null);
        assertNotNull(loot);
        assertFalse(loot.rare);
        assertEquals(1, loot.count);
    }

    @Test
    public void highTideFindsRaresFarMoreOftenThanLowTide() {
        Random low = new Random(1L);
        Random high = new Random(1L);
        int lowRares = 0, highRares = 0;
        for (int i = 0; i < 20000; i++) {
            if (SiftSieve.roll(0f, low).rare) lowRares++;
            if (SiftSieve.roll(1f, high).rare) highRares++;
        }
        // 8% at low tide vs 55% at high tide — the gap is unmissable.
        assertTrue("high tide should be richer: low=" + lowRares + " high=" + highRares,
                highRares > lowRares * 3);
    }

    @Test
    public void everyRollComesFromAKnownPool() {
        Random rng = new Random(42L);
        for (int i = 0; i < 5000; i++) {
            SiftSieve.Loot loot = SiftSieve.roll(rng.nextFloat(), rng);
            assertNotNull(loot);
            assertTrue(loot.itemId != null && !loot.itemId.isEmpty());
            assertTrue(loot.count >= 1);
        }
    }

    @Test
    public void tideOverloadIsNullSafe() {
        assertNotNull(SiftSieve.roll((SiftTides) null, new Random(7L)));
        assertNotNull(SiftSieve.roll(new SiftTides(), new Random(7L)));
    }

    @Test
    public void perBedCooldownBlocksImmediateResifts() {
        SiftSieve.resetForTest();
        long now = 1_000_000L;
        assertFalse(SiftSieve.isOnCooldown(10, 72, 10, now));
        assertEquals(0f, SiftSieve.cooldownRemaining(10, 72, 10, now), 1e-4f);

        SiftSieve.markSifted(10, 72, 10, now);
        assertTrue(SiftSieve.isOnCooldown(10, 72, 10, now));
        assertTrue(SiftSieve.cooldownRemaining(10, 72, 10, now) > 0f);

        // A neighbouring bed is unaffected.
        assertFalse(SiftSieve.isOnCooldown(11, 72, 10, now));

        // Ready again once the cooldown elapses.
        assertFalse(SiftSieve.isOnCooldown(10, 72, 10, now + SiftSieve.COOLDOWN_MILLIS));
        assertEquals(0f, SiftSieve.cooldownRemaining(10, 72, 10, now + SiftSieve.COOLDOWN_MILLIS), 1e-4f);
    }
}
