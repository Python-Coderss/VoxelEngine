package com.voxel.ai.brain;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChaosTest {

    @Before
    public void resetChaos() {
        Chaos.reset();
    }

    @Test
    public void startsCalmAndRisesOnEvents() {
        assertEquals(0f, Chaos.level(), 1e-6);
        Chaos.raise(0.25f);
        assertEquals(0.25f, Chaos.level(), 1e-6);
        Chaos.raise(0.10f);
        assertEquals(0.35f, Chaos.level(), 1e-6);
        assertEquals(0.35f, Chaos.peak(), 1e-6);
    }

    @Test
    public void clampsToOne() {
        Chaos.raise(3f);
        assertEquals(1f, Chaos.level(), 1e-6);
    }

    @Test
    public void decaysSlowlyOverTime() {
        Chaos.raise(0.5f);
        Chaos.tick(10f);
        assertTrue("chaos should decay but stay positive", Chaos.level() > 0f);
        assertTrue("chaos should decay", Chaos.level() < 0.5f);
    }

    @Test
    public void decaysToZeroEventually() {
        Chaos.raise(0.2f);
        for (int i = 0; i < 30; i++) {
            Chaos.tick(10f);
        }
        assertEquals(0f, Chaos.level(), 1e-6);
    }

    @Test
    public void negativeRaiseIgnoredAndResetWorks() {
        Chaos.raise(-5f);
        assertEquals(0f, Chaos.level(), 1e-6);
        Chaos.raise(0.4f);
        Chaos.reset();
        assertEquals(0f, Chaos.level(), 1e-6);
        assertEquals(0f, Chaos.peak(), 1e-6);
    }
}
