package com.voxel.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages crafting recipes and pattern matching for the 2x2 crafting grid.
 */
public class CraftingManager {
    private final List<CraftingRecipe> recipes = new ArrayList<>();
    
    public static class CraftingRecipe {
        public final String[][] pattern; // 2x2 grid of item IDs, null = empty
        public final String resultItemId;
        public final int resultCount;
        
        public CraftingRecipe(String[][] pattern, String resultItemId, int resultCount) {
            this.pattern = pattern;
            this.resultItemId = resultItemId;
            this.resultCount = resultCount;
        }
    }
    
    public CraftingManager() {
        registerDefaultRecipes();
    }
    
    private void registerDefaultRecipes() {
        // Wooden Pickaxe: 3 planks (using stone as placeholder) + 2 sticks
        addRecipe(new String[][]{
            {"stone", "stone"},
            {"stone", null}
        }, "wood_pickaxe", 1);
        
        // Wooden Axe: 3 planks + 2 sticks
        addRecipe(new String[][]{
            {"stone", "stone"},
            {"stone", null}
        }, "wood_axe", 1);
        
        // Wooden Shovel: 1 plank + 2 sticks
        addRecipe(new String[][]{
            {"stone", null},
            {null, null}
        }, "wood_shovel", 1);
        
        // Glass from sand: smelt sand -> glass (simplified: crafting instead of smelting)
        addRecipe(new String[][]{
            {"sand", "sand"},
            {"sand", "sand"}
        }, "glass", 4);
    }
    
    private void addRecipe(String[][] pattern, String resultItemId, int resultCount) {
        recipes.add(new CraftingRecipe(pattern, resultItemId, resultCount));
    }
    
    /**
     * Attempts to match a 2x2 crafting grid against registered recipes.
     * @param grid 2x2 array of item IDs (can be null for empty slots)
     * @return The matching recipe, or null if no match found
     */
    public CraftingRecipe matchRecipe(String[][] grid) {
        for (CraftingRecipe recipe : recipes) {
            if (matchesPattern(grid, recipe.pattern)) {
                return recipe;
            }
        }
        return null;
    }
    
    private boolean matchesPattern(String[][] grid, String[][] pattern) {
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                String gridItem = grid[r][c];
                String patternItem = pattern[r][c];
                
                if (patternItem == null) {
                    if (gridItem != null) return false;
                } else {
                    if (gridItem == null) return false;
                    if (!gridItem.equals(patternItem)) return false;
                }
            }
        }
        return true;
    }
    
    /**
     * Consumes items from the crafting grid based on a recipe.
     * @param grid The 2x2 crafting grid (will be modified)
     */
    public void consumeItems(String[][] grid) {
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                grid[r][c] = null;
            }
        }
    }
}
