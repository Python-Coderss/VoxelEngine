package com.voxel.ai.brain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AncientBuilderBrainTest {

    @Test
    public void threatSendsNonSentriesFleeing() {
        assertEquals(AncientBuilderBrain.Action.FLEE,
                AncientBuilderBrain.chooseAction(true, false, false, false, true, false));
    }

    @Test
    public void sentriesHoldTheirGroundOnTheWall() {
        // A sentry does not flee a nearby threat; it stands, greets or works.
        assertEquals(AncientBuilderBrain.Action.PATROL,
                AncientBuilderBrain.chooseAction(true, false, false, false, true, true));
    }

    @Test
    public void anAttackerIsWarnedBeforeAnythingElse() {
        assertEquals(AncientBuilderBrain.Action.WARN,
                AncientBuilderBrain.chooseAction(false, true, true, true, true, true));
    }

    @Test
    public void aVisitorIsGreeted() {
        assertEquals(AncientBuilderBrain.Action.GREET,
                AncientBuilderBrain.chooseAction(false, false, true, false, true, true));
    }

    @Test
    public void workComesBeforeWalkingTheRounds() {
        assertEquals(AncientBuilderBrain.Action.WORK,
                AncientBuilderBrain.chooseAction(false, false, false, true, true, true));
    }

    @Test
    public void aHomeSiteMeansPatrolOtherwiseIdle() {
        assertEquals(AncientBuilderBrain.Action.PATROL,
                AncientBuilderBrain.chooseAction(false, false, false, false, true, true));
        assertEquals(AncientBuilderBrain.Action.IDLE,
                AncientBuilderBrain.chooseAction(false, false, false, false, false, true));
    }
}
