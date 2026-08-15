package com.voxel.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the two conditions that gate kinetic rotation sources:
 *
 * 1. {@link KineticManager#pack}/{@link KineticManager#unpackX} must round-trip
 *    negative world coordinates. The tutorial machine works lives at z=-160, and
 *    the pre-fix unpack returned a large positive value instead of the negative
 *    coordinate, so source detection read the wrong voxel and never spun.
 *
 * 2. {@link KineticManager#isWaterBlock} must recognise flowing water (150-156),
 *    not just the source block (15), so a water wheel dipped into a channel of
 *    flowing water is still treated as a rotation source.
 */
public class KineticSourceDetectionTest {

    @Test
    public void packUnpackRoundTripsNegativeCoordinates() {
        assertRoundTrip(-10, 5, -168);
        assertRoundTrip(0, -160, 22);
        assertRoundTrip(-1, -1, -1);
        assertRoundTrip(0, 0, 0);
        assertRoundTrip(123, 456, 789);
        assertRoundTrip(-1048576, 1048575, -524288); // 21-bit range extremes
    }

    @Test
    public void packUnpackSignExtendsTheTopBit() {
        // -1 packs to the all-ones 21-bit field (0x1FFFFF); unpack must sign-
        // extend it back to -1 rather than leaking 0x1FFFFF as a positive value.
        long key = KineticManager.pack(-1, 0, 0);
        assertEquals(-1, KineticManager.unpackX(key));
        assertEquals(0, KineticManager.unpackY(key));
        assertEquals(0, KineticManager.unpackZ(key));
    }

    @Test
    public void waterBlockRecognisesSourceAndFlowing() {
        assertTrue("source water", KineticManager.isWaterBlock(15));
        for (int id = 150; id <= 156; id++) {
            assertTrue("flowing water " + id, KineticManager.isWaterBlock(id));
        }
        assertFalse("air", KineticManager.isWaterBlock(0));
        assertFalse("stone", KineticManager.isWaterBlock(2));
        assertFalse("below flowing range", KineticManager.isWaterBlock(149));
        assertFalse("above flowing range", KineticManager.isWaterBlock(157));
    }

    private static void assertRoundTrip(int x, int y, int z) {
        long key = KineticManager.pack(x, y, z);
        assertEquals("x for " + x + "," + y + "," + z, x, KineticManager.unpackX(key));
        assertEquals("y for " + x + "," + y + "," + z, y, KineticManager.unpackY(key));
        assertEquals("z for " + x + "," + y + "," + z, z, KineticManager.unpackZ(key));
    }
}
