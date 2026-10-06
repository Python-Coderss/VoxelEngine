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
            115, 116, 117, 118, 274, 19, 128, 106, 127
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
    }
}
