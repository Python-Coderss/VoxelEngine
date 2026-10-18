package com.voxel.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TutorialProgressTest {

    @Test
    public void stepsCompleteOnceInAnyOrder() {
        TutorialProgress p = new TutorialProgress();
        assertEquals(0, p.currentIndex());
        assertTrue(p.complete(TutorialProgress.CRAFT));
        assertFalse("a step must only complete once", p.complete(TutorialProgress.CRAFT));
        assertEquals("completed count counts actions, not order", 1, p.completedCount());

        for (int i = 0; i < TutorialProgress.STEP_COUNT; i++) {
            p.completeStep(i);
        }
        assertTrue(p.isComplete());
        assertEquals(-1, p.currentIndex());
        assertTrue(p.headline().startsWith("TOUR COMPLETE"));
    }

    @Test
    public void completionEventsAreConsumedOneAtATime() {
        TutorialProgress p = new TutorialProgress();
        p.complete(TutorialProgress.MINE);
        p.complete(TutorialProgress.SMELT);
        assertEquals(0, p.consumeJustCompleted()); // first completion stays queued
        assertEquals("second completion is consumed next", 2, p.consumeJustCompleted());
        assertEquals(-1, p.consumeJustCompleted());
    }

    @Test
    public void actionNamesAndCopyAreWellFormed() {
        TutorialProgress p = new TutorialProgress();
        for (int i = 0; i < TutorialProgress.STEP_COUNT; i++) {
            assertTrue(TutorialProgress.isAction(TutorialProgress.actionFor(i)));
            assertFalse(TutorialProgress.title(i).isEmpty());
            assertFalse(TutorialProgress.hint(i).isEmpty());
        }
        assertFalse(TutorialProgress.isAction("nonsense"));
        assertEquals("STEP 1/6 · " + TutorialProgress.title(0), p.headline());
    }

    @Test
    public void resetRestartsTheTour() {
        TutorialProgress p = new TutorialProgress();
        p.completeStep(0);
        p.completeStep(1);
        p.reset();
        assertEquals(0, p.completedCount());
        assertEquals(0, p.currentIndex());
    }
}
