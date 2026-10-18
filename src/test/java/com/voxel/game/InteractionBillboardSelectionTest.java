package com.voxel.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Arrow-key navigation for the MCSM point-and-click billboards. The selection
 * step is a pure function so it can be checked without a GL context; the
 * wrapper in {@link InteractionBillboardSystem} only feeds it the visible flags.
 */
public class InteractionBillboardSelectionTest {

    private static boolean[] visible(boolean... flags) {
        return flags;
    }

    @Test
    public void forwardFromNothingSelectsTheFirstVisible() {
        assertEquals(0, InteractionBillboardSystem.nextSelectable(
                -1, 1, 3, visible(true, true, true)));
    }

    @Test
    public void backwardFromNothingSelectsTheLastVisible() {
        assertEquals(2, InteractionBillboardSystem.nextSelectable(
                -1, -1, 3, visible(true, true, true)));
    }

    @Test
    public void stepsAndWrapsAround() {
        boolean[] v = visible(true, true, true);
        assertEquals(1, InteractionBillboardSystem.nextSelectable(0, 1, 3, v));
        assertEquals(2, InteractionBillboardSystem.nextSelectable(1, 1, 3, v));
        assertEquals(0, InteractionBillboardSystem.nextSelectable(2, 1, 3, v));
        assertEquals(2, InteractionBillboardSystem.nextSelectable(0, -1, 3, v));
    }

    @Test
    public void skipsMarkersThatAreNotOnScreen() {
        // Off-screen markers (alpha 0) must never become the selection.
        boolean[] v = visible(false, true, false, true);
        assertEquals(1, InteractionBillboardSystem.nextSelectable(-1, 1, 4, v));
        assertEquals(3, InteractionBillboardSystem.nextSelectable(1, 1, 4, v));
        assertEquals(1, InteractionBillboardSystem.nextSelectable(3, 1, 4, v));
        assertEquals(3, InteractionBillboardSystem.nextSelectable(1, -1, 4, v));
    }

    @Test
    public void nothingToSelectReturnsMinusOne() {
        assertEquals(-1, InteractionBillboardSystem.nextSelectable(
                -1, 1, 0, new boolean[0]));
        assertEquals(-1, InteractionBillboardSystem.nextSelectable(
                -1, 1, 3, visible(false, false, false)));
    }

    @Test
    public void zeroDirectionDefaultsToForward() {
        assertEquals(0, InteractionBillboardSystem.nextSelectable(
                -1, 0, 2, visible(true, true)));
    }
}
