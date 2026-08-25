package com.voxel.ai.brain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VillagerBrainTest {

    private static VillagerBrain.Action decide(boolean threat, float panic, float alert,
                                               boolean night, boolean swim,
                                               boolean build, boolean social) {
        return VillagerBrain.chooseAction(threat, panic, alert, night, swim, build, social);
    }

    @Test
    public void visibleThreatBeatsEverything() {
        assertEquals(VillagerBrain.Action.PANIC_FLEE,
                decide(true, 0f, 0f, true, false, true, true));
        assertEquals(VillagerBrain.Action.PANIC_FLEE,
                decide(true, 0f, 0f, false, false, false, false));
    }

    @Test
    public void panicFuelKeepsFleeingAfterThreatLost() {
        assertEquals(VillagerBrain.Action.PANIC_FLEE,
                decide(false, 2.5f, 0f, true, false, true, true));
    }

    @Test
    public void adoptedAlertLooksBeforeSheltering() {
        assertEquals(VillagerBrain.Action.ALERT_LOOK,
                decide(false, 0f, 1.5f, true, false, true, false));
    }

    @Test
    public void swimmingIsPanic() {
        assertEquals(VillagerBrain.Action.PANIC_FLEE,
                decide(false, 0f, 0f, false, true, false, false));
    }

    @Test
    public void nightBeatsWorkAndSocial() {
        assertEquals(VillagerBrain.Action.GO_INDOORS,
                decide(false, 0f, 0f, true, false, true, true));
    }

    @Test
    public void workBeatsSocializing() {
        assertEquals(VillagerBrain.Action.GO_BUILD,
                decide(false, 0f, 0f, false, false, true, true));
    }

    @Test
    public void socializesWhenFree() {
        assertEquals(VillagerBrain.Action.SOCIALIZE,
                decide(false, 0f, 0f, false, false, false, true));
    }

    @Test
    public void defaultsToWander() {
        assertEquals(VillagerBrain.Action.WANDER,
                decide(false, 0f, 0f, false, false, false, false));
    }

    @Test
    public void alertExpiresIntoNightRoutine() {
        assertEquals(VillagerBrain.Action.ALERT_LOOK,
                decide(false, 0f, 0.1f, true, false, false, false));
        assertEquals(VillagerBrain.Action.GO_INDOORS,
                decide(false, 0f, 0f, true, false, false, false));
    }
}
