package com.voxel.cinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CinematicScriptTest {

    @Test
    public void aLineAtZeroFiresOnTheFirstTick() {
        CinematicScript script = CinematicScript.of("Ancient Builder", "You stand on stone.");
        List<CinematicScript.Line> due = script.due(0f, 0.016f);
        assertEquals(1, due.size());
        assertEquals("You stand on stone.", due.get(0).text);
        assertEquals("Ancient Builder", due.get(0).speaker);
    }

    @Test
    public void linesFireExactlyOnceInTheirWindow() {
        CinematicScript script = new CinematicScript()
                .at(1.0f, "Scholar", "The charts agree.")
                .at(2.5f, "Sentry", "All quiet.");
        // Before its time: nothing.
        assertTrue(script.due(0f, 0.9f).isEmpty());
        // Crossing 1.0 fires the first line only.
        List<CinematicScript.Line> due = script.due(0.9f, 1.05f);
        assertEquals(1, due.size());
        assertEquals("Scholar", due.get(0).speaker);
        // The window that crossed 2.5 fires the second.
        due = script.due(2.4f, 2.6f);
        assertEquals(1, due.size());
        assertEquals("Sentry", due.get(0).speaker);
        // Re-asking the same window never double-fires.
        assertTrue(script.due(2.5f, 2.6f).isEmpty());
    }

    @Test
    public void multipleLinesCanShareATick() {
        CinematicScript script = new CinematicScript()
                .at(3.0f, "A", "first")
                .at(3.1f, "B", "second");
        assertEquals(2, script.due(2.99f, 3.2f).size());
    }

    @Test
    public void emptySpeakerDefaultsToNarrator() {
        CinematicScript script = new CinematicScript().at(0f, "", "The world remembers.");
        assertEquals("Narrator", script.due(-0.1f, 0f).get(0).speaker);
        assertFalse(script.isEmpty());
        assertEquals(1, script.size());
    }

    @Test
    public void emptyScriptFiresNothing() {
        CinematicScript script = new CinematicScript();
        assertTrue(script.isEmpty());
        assertTrue(script.due(0f, 100f).isEmpty());
    }
}
