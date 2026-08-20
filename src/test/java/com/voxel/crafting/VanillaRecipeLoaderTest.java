package com.voxel.crafting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class VanillaRecipeLoaderTest {
    @Test
    public void importsVanillaRecipesAndMatchesRectangularPattern() {
        CraftingManager manager = new CraftingManager();
        Set<String> knownItems = new HashSet<>(Arrays.asList(
                "oak_planks", "boat", "acacia_planks", "acacia_log", "stick", "torch", "coal"));

        int loaded = VanillaRecipeLoader.load(
                manager, "src/main/resources/assets/minecraft/recipes", knownItems);

        assertTrue("vanilla recipe resources should import", loaded > 0);
        CraftingManager.CraftingRecipe boat = manager.matchRecipe3x3(new String[][] {
                {"oak_planks", null, "oak_planks"},
                {"oak_planks", "oak_planks", "oak_planks"},
                {null, null, null}
        });
        assertNotNull(boat);
        assertEquals("boat", boat.resultItemId);
        assertEquals(1, boat.resultCount);
    }
}
