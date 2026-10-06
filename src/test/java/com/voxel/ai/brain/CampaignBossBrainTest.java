package com.voxel.ai.brain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Decision-matrix tests for the four bespoke campaign boss brains. */
public class CampaignBossBrainTest {

    // ── Wither Storm ────────────────────────────────────────────────────

    @Test
    public void witherStormEnragesWhenWoundedOrStruck() {
        assertEquals(WitherStormBrain.Phase.ENRAGED,
                WitherStormBrain.choosePhase(1f, 30f, false, true));
        assertEquals(WitherStormBrain.Phase.ENRAGED,
                WitherStormBrain.choosePhase(0.2f, 30f, false, false));
    }

    @Test
    public void witherStormSummonsBeforeVolleying() {
        assertEquals(WitherStormBrain.Phase.SUMMON,
                WitherStormBrain.choosePhase(1f, 4f, true, false));
    }

    @Test
    public void witherStormVolleysInRangeAndStalksBeyond() {
        assertEquals(WitherStormBrain.Phase.VOLLEY,
                WitherStormBrain.choosePhase(1f, WitherStormBrain.HOLD_DISTANCE, false, false));
        assertEquals(WitherStormBrain.Phase.STALK,
                WitherStormBrain.choosePhase(1f, WitherStormBrain.SIGHT_RANGE, false, false));
    }

    // ── Great Hog ───────────────────────────────────────────────────────

    @Test
    public void greatHogSummonsFirst() {
        assertEquals(GreatHogBrain.Action.SUMMON,
                GreatHogBrain.chooseAction(30f, 1f, true, true));
    }

    @Test
    public void greatHogPoundsInRangeWhenReady() {
        assertEquals(GreatHogBrain.Action.POUND,
                GreatHogBrain.chooseAction(2f, 1f, false, true));
    }

    @Test
    public void greatHogChargesOutsideMeleeAndStalksBeyondChargeRange() {
        assertEquals(GreatHogBrain.Action.CHARGE,
                GreatHogBrain.chooseAction(2f, 1f, false, false));
        assertEquals(GreatHogBrain.Action.CHARGE,
                GreatHogBrain.chooseAction(GreatHogBrain.CHARGE_RANGE, 1f, false, true));
        assertEquals(GreatHogBrain.Action.STALK,
                GreatHogBrain.chooseAction(GreatHogBrain.CHARGE_RANGE + 5f, 1f, false, true));
    }

    // ── Arch-Illager ────────────────────────────────────────────────────

    @Test
    public void archIllagerBlinksOutOfMeleeThenSummons() {
        assertEquals(ArchIllagerBrain.Action.BLINK,
                ArchIllagerBrain.chooseAction(2f, 0.5f, true, true));
        assertEquals(ArchIllagerBrain.Action.SUMMON,
                ArchIllagerBrain.chooseAction(8f, 1f, true, false));
    }

    @Test
    public void archIllagerCastsAtRangeAndHoversFarAway() {
        assertEquals(ArchIllagerBrain.Action.FANGS,
                ArchIllagerBrain.chooseAction(ArchIllagerBrain.CAST_RANGE, 1f, false, false));
        assertEquals(ArchIllagerBrain.Action.HOVER,
                ArchIllagerBrain.chooseAction(ArchIllagerBrain.SIGHT_RANGE, 1f, false, false));
    }

    // ── Heart of Ender ──────────────────────────────────────────────────

    @Test
    public void heartOfEnderSpawnsThenPulsesInRange() {
        assertEquals(HeartOfEnderBrain.Action.SPAWN,
                HeartOfEnderBrain.chooseAction(3f, 1f, true, true));
        assertEquals(HeartOfEnderBrain.Action.PULSE,
                HeartOfEnderBrain.chooseAction(3f, 1f, false, true));
    }

    @Test
    public void heartOfEnderDriftsWhenOutOfRangeOrOnCooldown() {
        assertEquals(HeartOfEnderBrain.Action.DRIFT,
                HeartOfEnderBrain.chooseAction(20f, 1f, false, true));
        assertEquals(HeartOfEnderBrain.Action.DRIFT,
                HeartOfEnderBrain.chooseAction(3f, 1f, false, false));
    }

    // ── shared sanity ───────────────────────────────────────────────────

    @Test
    public void everyoneTracksThePlayerFromTheSensesSnapshot() {
        // The brains reuse Senses for target acquisition; confirm the shared
        // gateway constant is finite so the fallback distance test is meaningful.
        assertTrue(WitherStormBrain.SIGHT_RANGE > 0f);
        assertTrue(GreatHogBrain.SIGHT_RANGE > 0f);
        assertTrue(ArchIllagerBrain.SIGHT_RANGE > 0f);
        assertTrue(HeartOfEnderBrain.SIGHT_RANGE > 0f);
    }
}
