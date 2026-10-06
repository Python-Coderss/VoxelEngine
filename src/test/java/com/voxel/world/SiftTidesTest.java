package com.voxel.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SiftTidesTest {

    @Test
    public void cycleRunsLowToHighAndBack() {
        SiftTides tides = new SiftTides(240f);
        assertEquals(0f, tides.strength(), 1e-3f);
        assertEquals(SiftTides.Phase.LOW, tides.phase());
        assertFalse(tides.hazardous());

        tides.tick(120f); // half a cycle -> high tide
        assertEquals(1f, tides.strength(), 1e-3f);
        assertEquals(SiftTides.Phase.HIGH, tides.phase());
        assertTrue(tides.hazardous());

        tides.tick(120f); // full cycle -> back to low
        assertEquals(0f, tides.strength(), 1e-3f);
        assertEquals(SiftTides.Phase.LOW, tides.phase());
    }

    @Test
    public void risingAndEbbingAreReported() {
        SiftTides tides = new SiftTides(240f);
        tides.tick(60f); // a quarter in, climbing
        assertEquals(SiftTides.Phase.RISING, tides.phase());
        tides.tick(120f); // three quarters in, receding
        assertEquals(SiftTides.Phase.EBBING, tides.phase());
    }

    @Test
    public void siftingIsRicherAtHighTide() {
        SiftTides tides = new SiftTides(240f);
        float lowYield = tides.siftYieldMultiplier();
        tides.tick(120f);
        float highYield = tides.siftYieldMultiplier();
        assertEquals(1f, lowYield, 1e-3f);
        assertEquals(3f, highYield, 1e-3f);
        assertTrue(highYield > lowYield);
    }

    @Test
    public void clockWrapsWithoutOverflow() {
        SiftTides tides = new SiftTides(10f);
        for (int i = 0; i < 1000; i++) tides.tick(0.5f);
        assertTrue(tides.time() >= 0f && tides.time() < 10f);
        assertTrue(tides.strength() >= 0f && tides.strength() <= 1f);
    }

    @Test
    public void describeIsNeverEmpty() {
        SiftTides tides = new SiftTides(240f);
        for (int i = 0; i < 5; i++) {
            assertFalse(tides.describe().isEmpty());
            tides.tick(30f);
        }
    }
}
