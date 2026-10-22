package com.voxel.cinematic;

import com.voxel.world.DimensionType;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every dimension must have a voice: a title, two arrival lines, a return
 * line and ambient observations. A new realm that forgets one of these would
 * arrive silently, which is exactly what this guards against.
 */
public class DimensionDialogueTest {

    @Test
    public void everyDimensionHasArrivalDialogue() {
        for (DimensionType dimension : DimensionType.values()) {
            assertTrue("title for " + dimension,
                    DimensionDialogue.title(dimension).trim().length() > 0);
            assertTrue("subtitle for " + dimension,
                    DimensionDialogue.subtitle(dimension).trim().length() > 0);
            assertTrue("return line for " + dimension,
                    DimensionDialogue.returnLine(dimension).trim().length() > 0);

            CinematicScript script = DimensionDialogue.arrivalLines(dimension);
            assertEquals("two arrival lines for " + dimension, 2, script.size());
            for (CinematicScript.Line line : script.lines()) {
                assertEquals("Narrator", line.speaker);
                assertTrue("line has text for " + dimension, line.text.trim().length() > 0);
            }
            assertTrue("first line is not instant", script.lines().get(0).at > 0f);
            assertTrue("lines do not overlap",
                    script.lines().get(1).at > script.lines().get(0).at);
        }
    }

    @Test
    public void everyDimensionHasVariedAmbientLines() {
        for (DimensionType dimension : DimensionType.values()) {
            String[] lines = DimensionDialogue.ambientLines(dimension);
            assertTrue("ambient lines for " + dimension, lines.length >= 3);
            Set<String> unique = new HashSet<String>();
            for (String line : lines) {
                assertNotNull(line);
                assertTrue("ambient line has text for " + dimension,
                        line.trim().length() > 0);
                assertTrue("ambient lines are not duplicates in " + dimension,
                        unique.add(line));
            }
            String picked = DimensionDialogue.ambientLine(dimension, new Random(7));
            assertNotNull("an ambient line is pickable in " + dimension, picked);
            assertTrue("the pick belongs to the dimension's pool",
                    unique.contains(picked));
        }
    }

    @Test
    public void theRealmsReadDifferently() {
        Set<String> titles = new HashSet<String>();
        for (DimensionType dimension : DimensionType.values()) {
            assertTrue("titles are distinct: " + dimension,
                    titles.add(DimensionDialogue.title(dimension)));
        }
        assertTrue("the game has more than the Overworld",
                DimensionType.values().length >= 7);
    }
}
