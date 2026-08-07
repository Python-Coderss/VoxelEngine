package com.voxel.crafting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ShapelessCraftingTest {
    private final CraftingManager manager = new CraftingManager();

    @Test
    public void matchesTwoByTwoIngredientsInAnyOrderAndPosition() {
        CraftingManager.CraftingRecipe recipe = manager.matchRecipe(new String[][] {
            {null, "iron_ingot"},
            {"flint", null}
        });

        assertNotNull(recipe);
        assertEquals("flint_and_steel", recipe.resultItemId);
        assertEquals(1, recipe.resultCount);
        assertEquals(true, recipe.shapeless);
    }

    @Test
    public void shapelessRecipesRequireExactlyTheDeclaredIngredients() {
        assertNull(manager.matchRecipe(new String[][] {
            {"flint", "iron_ingot"},
            {"stick", null}
        }));
        assertNull(manager.matchRecipe(new String[][] {
            {"flint", null},
            {null, null}
        }));
    }

    @Test
    public void duplicateShapelessIngredientsAreCounted() {
        CraftingManager.CraftingRecipe recipe = manager.matchRecipe3x3(new String[][] {
            {"iron_ingot", "iron_ingot", "iron_ingot"},
            {"iron_ingot", "iron_ingot", "iron_ingot"},
            {"iron_ingot", "iron_ingot", "iron_ingot"}
        });

        assertNotNull(recipe);
        assertEquals("iron_block", recipe.resultItemId);
        assertEquals(1, recipe.resultCount);
    }

    @Test
    public void twoByTwoShapelessRecipesAlsoWorkOnThreeByThreeTables() {
        CraftingManager.CraftingRecipe recipe = manager.matchRecipe3x3(new String[][] {
            {null, null, null},
            {null, "flint", null},
            {null, null, "iron_ingot"}
        });

        assertNotNull(recipe);
        assertEquals("flint_and_steel", recipe.resultItemId);
    }

    @Test
    public void shapedRecipesRemainPositional() {
        CraftingManager.CraftingRecipe recipe = manager.matchRecipe3x3(new String[][] {
            {"oak_planks", "oak_planks", "oak_planks"},
            {null, "stick", null},
            {null, "stick", null}
        });

        assertNotNull(recipe);
        assertEquals("wood_pickaxe", recipe.resultItemId);
        assertEquals(false, recipe.shapeless);
    }
}
