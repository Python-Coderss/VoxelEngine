package com.voxel.cinematic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure rules for MCSM decisions and quick-time events. */
public class CinematicChoiceTest {

    @Test
    public void optionsClampToFourAndKeepOrder() {
        CinematicChoice choice = new CinematicChoice("fork", "Narrator", "Pick.", 8f)
                .option("one").option("two").option("three").option("four").option("five");
        assertEquals(CinematicChoice.MAX_OPTIONS, choice.optionCount());
        assertEquals("one", choice.optionAt(0).label);
        assertEquals("four", choice.optionAt(3).label);
        // Out-of-range reads clamp rather than throw (input can be late).
        assertEquals("four", choice.optionAt(9).label);
    }

    @Test
    public void timerAndDefaultAreClamped() {
        CinematicChoice quick = new CinematicChoice("a", "Narrator", "", 0.1f)
                .defaultOption(99);
        assertEquals(CinematicChoice.MIN_SECONDS, quick.seconds(), 0f);
        assertTrue(quick.defaultIndex() <= CinematicChoice.MAX_OPTIONS - 1);

        CinematicChoice slow = new CinematicChoice("b", "Narrator", "", 900f);
        assertEquals(CinematicChoice.MAX_SECONDS, slow.seconds(), 0f);
    }

    @Test
    public void blankIdAndSpeakerFallBack() {
        CinematicChoice choice = new CinematicChoice("  ", null, "Prompt", 5f);
        assertEquals("choice", choice.id());
        assertEquals("Narrator", choice.speaker());
        assertEquals("Prompt", choice.prompt());
    }

    @Test
    public void responsesAreTrimmedAndOptional() {
        CinematicChoice choice = new CinematicChoice("c", "Builder", "?", 6f)
                .option("  Stay  ", "  Then stay.  ")
                .option("Go");
        assertEquals("Stay", choice.optionAt(0).label);
        assertEquals("Then stay.", choice.optionAt(0).response);
        assertEquals(null, choice.optionAt(1).response);
    }

    @Test
    public void storyForksFillTheFourCardRow() {
        // MCSM presents a full row; a fork with three options leaves a gap in
        // the layout (and one less way to answer).
        assertEquals(CinematicChoice.MAX_OPTIONS,
                StoryBeats.tutorialFork().optionCount());
        assertEquals(CinematicChoice.MAX_OPTIONS,
                StoryBeats.pointClickFork().optionCount());
        for (CinematicChoice choice : new CinematicChoice[]{
                StoryBeats.tutorialFork(), StoryBeats.pointClickFork()}) {
            java.util.Set<String> labels = new java.util.HashSet<String>();
            for (int i = 0; i < choice.optionCount(); i++) {
                assertTrue("each card needs its own line",
                        labels.add(choice.optionAt(i).label));
                assertTrue(choice.optionAt(i).label.length() > 0);
            }
        }
    }

    @Test
    public void quickTimeEventClampsAndExposesKeyHint() {
        QuickTimeEvent space = QuickTimeEvent.of("Duck!", QuickTimeEvent.Input.SPACE, 0.1f)
                .success("ok").fail("ouch");
        assertEquals(0.6f, space.seconds(), 0f);
        assertEquals("SPACE", space.keyHint());
        assertEquals("ok", space.successLine());
        assertEquals("ouch", space.failLine());

        QuickTimeEvent click = new QuickTimeEvent("Grab it", QuickTimeEvent.Input.CLICK, 99f);
        assertEquals(10f, click.seconds(), 0f);
        assertEquals("CLICK", click.keyHint());
        // A missing input defaults to the keyboard rather than throwing.
        assertEquals(QuickTimeEvent.Input.SPACE,
                new QuickTimeEvent("x", null, 2f).input());
    }
}
