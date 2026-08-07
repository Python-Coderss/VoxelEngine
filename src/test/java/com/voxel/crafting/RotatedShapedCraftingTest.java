package com.voxel.crafting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RotatedShapedCraftingTest {
    private final CraftingManager manager = new CraftingManager();

    @Test
    public void rotatesTwoByTwoStickRecipe() {
        CraftingManager.CraftingRecipe recipe = manager.matchRecipe(new String[][] {
            {"oak_planks", "oak_planks"},
            {null, null}
        });

        assertNotNull(recipe);
        assertEquals("stick", recipe.resultItemId);
        assertEquals(4, recipe.resultCount);
        assertEquals(false, recipe.shapeless);
    }

    @Test
    public void rotatesThreeByThreeShovelRecipe() {
        CraftingManager.CraftingRecipe recipe = manager.matchRecipe3x3(new String[][] {
            {"stick", "stick", "oak_planks"},
            {null, null, null},
            {null, null, null}
        });

        assertNotNull(recipe);
        assertEquals("wood_shovel", recipe.resultItemId);
    }

    @Test
    public void doesNotAddMirroredShapes() {
        // This is the horizontal mirror of the original oak axe pattern, not
        // one of its cardinal rotations.
        CraftingManager.CraftingRecipe recipe = manager.matchRecipe3x3(new String[][] {
            {null, "oak_planks", "oak_planks"},
            {null, "stick", "oak_planks"},
            {null, "stick", null}
        });

        assertNull(recipe);
    }
}
