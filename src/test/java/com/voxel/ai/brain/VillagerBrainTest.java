package com.voxel.ai.brain;

import org.joml.Vector3f;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    // ── panic flee geometry ────────────────────────────────────────────

    @Test
    public void fleePointIsEightBlocksAwayAndOppositeThreat() {
        Vector3f pos = new Vector3f(0f, 64f, 0f);
        Vector3f threat = new Vector3f(5f, 64f, 0f); // threat sits at +x
        Vector3f point = VillagerBrain.pickFleePoint(pos, threat, new Random(42));
        assertEquals(8f, pos.distance(point), 0.01f);
        Vector3f heading = new Vector3f(point).sub(pos).normalize();
        assertTrue("flee heading must lead away from the threat", heading.x < -0.5f);
    }

    @Test
    public void fleePointJittersBetweenSeeds() {
        Vector3f pos = new Vector3f(0f, 64f, 0f);
        Vector3f threat = new Vector3f(0f, 64f, 3f); // threat at +z
        // These two seeds draw far-apart first uniforms (0.585 vs 0.949), so
        // the bearing jitter differs by tens of degrees between villagers.
        Vector3f a = VillagerBrain.pickFleePoint(pos, threat, new Random(1661));
        Vector3f b = VillagerBrain.pickFleePoint(pos, threat, new Random(2438));
        Vector3f da = new Vector3f(a).sub(pos).normalize();
        Vector3f db = new Vector3f(b).sub(pos).normalize();
        double bearingDiff = Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, da.dot(db)))));
        assertTrue("different villagers should take different bearings (got "
                        + bearingDiff + " deg)", bearingDiff > 10.0);
        assertTrue(a.z < 0f && b.z < 0f); // both still lead away from +z threat
    }

    @Test
    public void fleePointWithThreatAtOwnPositionIsStable() {
        Vector3f pos = new Vector3f(1f, 64f, 1f);
        Vector3f point = VillagerBrain.pickFleePoint(pos, pos, new Random(7));
        assertEquals(8f, pos.distance(point), 0.01f);
        assertTrue(Float.isFinite(point.x) && Float.isFinite(point.z));
    }
}
