package com.voxel.crafting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class CraftingManagerButtonTest {
    @Test
    public void matchingDoesNotMutateTheIngredientGrid() {
        CraftingManager manager = new CraftingManager();
        String[][] grid = new String[][] {
            {"oak_planks", "oak_planks", "oak_planks"},
            {null, "stick", null},
            {null, "stick", null}
        };

        CraftingManager.CraftingRecipe preview = manager.matchRecipe3x3(grid);

        assertNotNull(preview);
        assertEquals("wood_pickaxe", preview.resultItemId);
        assertEquals("oak_planks", grid[0][0]);
        assertEquals("stick", grid[1][1]);
    }

    @Test
    public void consumeClearsInputsOnlyWhenExplicitlyRequested() {
        CraftingManager manager = new CraftingManager();
        String[][] grid = new String[][] {
            {"oak_planks", "oak_planks", "oak_planks"},
            {null, "stick", null},
            {null, "stick", null}
        };

        assertNotNull(manager.matchRecipe3x3(grid));
        assertEquals("oak_planks", grid[0][0]);

        manager.consumeItems3x3(grid);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                assertNull(grid[row][col]);
            }
        }
    }
}
