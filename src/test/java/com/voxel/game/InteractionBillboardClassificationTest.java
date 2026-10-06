package com.voxel.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The point-and-click billboards must classify exactly the blocks that have a
 * real use handler — a billboard that points at a dead block is worse than no
 * billboard at all. These checks pin the classification to the handlers in
 * {@link BlockInteraction#useBlockAt}.
 */
public class InteractionBillboardClassificationTest {

    private static final int[] INTERACTABLE = {
            115, 116, 117, 118, 274, 19, 128, 106, 127,
            404, 405, 414
    };

    @Test
    public void knownInteractablesAreClassified() {
        for (int id : INTERACTABLE) {
            assertTrue("block " + id + " should be interactable",
                    InteractionBillboardSystem.isInteractableBlock(id));
        }
    }

    @Test
    public void ordinaryBlocksAreNotInteractable() {
        int[] plain = {0, 1, 2, 3, 13, 71, 131, 136, 274 + 1};
        for (int id : plain) {
            assertFalse("block " + id + " should not be interactable",
                    InteractionBillboardSystem.isInteractableBlock(id));
        }
    }

    @Test
    public void everyInteractableHasANameAndAVerb() {
        for (int id : INTERACTABLE) {
            String name = InteractionBillboardSystem.blockName(id);
            String action = InteractionBillboardSystem.blockAction(id);
            assertFalse("name for " + id, name.isEmpty());
            assertFalse("action for " + id, action.isEmpty());
        }
    }

    @Test
    public void representativeNamesAndVerbs() {
        assertEquals("Crafting Table", InteractionBillboardSystem.blockName(115));
        assertEquals("Craft", InteractionBillboardSystem.blockAction(115));
        assertEquals("Furnace", InteractionBillboardSystem.blockName(116));
        assertEquals("Smelt", InteractionBillboardSystem.blockAction(117));
        assertEquals("Chest", InteractionBillboardSystem.blockName(118));
        assertEquals("Open", InteractionBillboardSystem.blockAction(118));
        assertEquals("Villager TV", InteractionBillboardSystem.blockName(274));
        assertEquals("Watch", InteractionBillboardSystem.blockAction(274));
        assertEquals("Nether Portal", InteractionBillboardSystem.blockName(128));
        assertEquals("Enter", InteractionBillboardSystem.blockAction(19));
        assertEquals("Hand Crank", InteractionBillboardSystem.blockName(404));
        assertEquals("Crank", InteractionBillboardSystem.blockAction(404));
        assertEquals("Windmill Bearing", InteractionBillboardSystem.blockName(405));
        assertEquals("Inspect", InteractionBillboardSystem.blockAction(405));
        assertEquals("Item Vault", InteractionBillboardSystem.blockName(414));
        assertEquals("Open", InteractionBillboardSystem.blockAction(414));
    }

    @Test
    public void configuredLeversAndButtonsAreClassified() {
        // Runtime ids are assigned by Main after content registration; mimic that.
        RedstoneSwitches.configure(711, 712, 713, 714, 715, 716, 717, 718, 719, 720);
        assertTrue(InteractionBillboardSystem.isInteractableBlock(711)); // lever off
        assertTrue(InteractionBillboardSystem.isInteractableBlock(712)); // lever on
        assertTrue(InteractionBillboardSystem.isInteractableBlock(713)); // stone button
        assertEquals("Lever", InteractionBillboardSystem.blockName(711));
        assertEquals("Flip", InteractionBillboardSystem.blockAction(711));
        assertEquals("Button", InteractionBillboardSystem.blockName(713));
        assertEquals("Press", InteractionBillboardSystem.blockAction(713));
        // Pressure plates are stepped on, not clicked — never a billboard.
        assertFalse(InteractionBillboardSystem.isInteractableBlock(717));
    }
}
