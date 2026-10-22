package com.voxel.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wording of the point-and-click reticle prompt. The player reads this line
 * where they are looking, so it has to name the target and — when there is a
 * choice — show the key hints that pick a verb.
 */
public class InteractionPromptLineTest {

    @Test
    public void singleActionReadsAsAClick() {
        String line = InteractionBillboardSystem.promptLine(
                "Crafting Table", new String[]{"Craft"});
        assertTrue(line, line.startsWith("CLICK"));
        assertTrue(line, line.contains("Craft"));
        assertTrue(line, line.contains("Crafting Table"));
    }

    @Test
    public void multipleActionsShowKeyHintsInOrder() {
        String line = InteractionBillboardSystem.promptLine(
                "Mason", new String[]{"Talk", "Trade"});
        assertTrue(line, line.contains("[1] Talk"));
        assertTrue(line, line.contains("[2] Trade"));
        assertTrue("the target is still named", line.startsWith("Mason"));
        assertTrue("no click hint when the key picks the verb",
                line.indexOf("[1]") < line.indexOf("[2]"));
    }

    @Test
    public void aTargetWithoutAVerbIsJustItsName() {
        assertEquals("Chest", InteractionBillboardSystem.promptLine("Chest", new String[0]));
        assertEquals("Chest", InteractionBillboardSystem.promptLine("Chest", null));
        assertEquals("Chest", InteractionBillboardSystem.promptLine("Chest", new String[]{""}));
    }

    @Test
    public void missingNameDoesNotLeaveDanglingSeparators() {
        String line = InteractionBillboardSystem.promptLine(null, new String[]{"Open"});
        assertFalse(line, line.endsWith("\u00b7   "));
        assertTrue(line, line.contains("Open"));
    }

    @Test
    public void probeLayoutMarkersCarryNamesAndVerbs() {
        // The classification helpers the prompt relies on stay in sync with
        // the display text the HUD prints.
        assertEquals("Crafting Table", InteractionBillboardSystem.blockName(115));
        assertEquals("Craft", InteractionBillboardSystem.blockAction(115));
        assertEquals("Chest", InteractionBillboardSystem.blockName(118));
        assertEquals("Open", InteractionBillboardSystem.blockAction(118));
    }
}
