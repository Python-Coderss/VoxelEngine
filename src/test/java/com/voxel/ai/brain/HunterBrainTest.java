package com.voxel.ai.brain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Decision-matrix tests for the pack-hunter brain. */
public class HunterBrainTest {

    private static HunterBrain.Action decide(float health, float sinceSeen,
                                             float memory, float searchingFor,
                                             boolean hasLastKnown, boolean hasPackCall) {
        return HunterBrain.chooseAction(health, sinceSeen, memory, searchingFor,
                hasLastKnown, hasPackCall);
    }

    @Test
    public void lowHealthAlwaysRetreats() {
        assertEquals(HunterBrain.Action.RETREAT,
                decide(0.1f, 0f, 1f, 0f, true, true));
        // Even with fresh prey in sight.
        assertEquals(HunterBrain.Action.RETREAT,
                decide(0.2f, 0f, 1f, 0f, true, false));
    }

    @Test
    public void freshSightChases() {
        assertEquals(HunterBrain.Action.CHASE,
                decide(1f, 0f, 1f, 0f, true, false));
        assertEquals(HunterBrain.Action.CHASE,
                decide(1f, HunterBrain.MEMORY_SECONDS - 0.1f, 0.1f, 0f, true, false));
    }

    @Test
    public void expiredMemorySearchesLastKnownSpot() {
        assertEquals(HunterBrain.Action.SEARCH,
                decide(1f, HunterBrain.MEMORY_SECONDS + 1f, 0f, 1f, true, false));
        // A pack call alone also warrants searching.
        assertEquals(HunterBrain.Action.SEARCH,
                decide(1f, HunterBrain.MEMORY_SECONDS + 1f, 0f, 0f, false, true));
    }

    @Test
    public void longFruitlessSearchGivesUp() {
        assertEquals(HunterBrain.Action.LURK,
                decide(1f, HunterBrain.MEMORY_SECONDS + 1f, 0f,
                        HunterBrain.GIVE_UP_SECONDS + 0.5f, true, true));
    }

    @Test
    public void lurksWhenNothingIsKnown() {
        assertEquals(HunterBrain.Action.LURK,
                decide(1f, Float.MAX_VALUE, 0f, 0f, false, false));
    }

    @Test
    public void memoryWindowIsFinite() {
        assertTrue(HunterBrain.MEMORY_SECONDS > 0f);
        assertTrue(HunterBrain.GIVE_UP_SECONDS > 0f);
        assertTrue(HunterBrain.RETREAT_HEALTH_FRACTION > 0f
                && HunterBrain.RETREAT_HEALTH_FRACTION < 1f);
    }
}
