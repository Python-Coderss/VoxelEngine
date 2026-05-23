package com.voxel.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages crafting recipes and pattern matching for both 2x2 (inventory) and 3x3 (crafting table) grids.
 */
public class CraftingManager {
    private final List<CraftingRecipe> recipes2x2 = new ArrayList<>();
    private final List<CraftingRecipe> recipes3x3 = new ArrayList<>();
    
    public static class CraftingRecipe {
        public final String[][] pattern; // grid of item IDs, null = empty
        public final String resultItemId;
        public final int resultCount;
        public final int gridSize; // 2 or 3
        
        public CraftingRecipe(String[][] pattern, String resultItemId, int resultCount, int gridSize) {
            this.pattern = pattern;
            this.resultItemId = resultItemId;
            this.resultCount = resultCount;
            this.gridSize = gridSize;
        }
    }
    
    public CraftingManager() {
        registerDefaultRecipes();
    }
    
    private void registerDefaultRecipes() {
        // ===== 2x2 Recipes (inventory crafting) =====
        
        // Wooden Pickaxe: 3 planks (using stone as placeholder) + 2 sticks
        addRecipe2x2(new String[][]{
            {"stone", "stone"},
            {"stone", null}
        }, "wood_pickaxe", 1);
        
        // Wooden Axe: 3 planks + 2 sticks
        addRecipe2x2(new String[][]{
            {"stone", "stone"},
            {"stone", null}
        }, "wood_axe", 1);
        
        // Wooden Shovel: 1 plank + 2 sticks
        addRecipe2x2(new String[][]{
            {"stone", null},
            {null, null}
        }, "wood_shovel", 1);
        
        // Glass from sand: smelt sand -> glass (simplified)
        addRecipe2x2(new String[][]{
            {"sand", "sand"},
            {"sand", "sand"}
        }, "glass", 4);

        // Redstone ore -> Redstone dust
        addRecipe2x2(new String[][]{
            {"redstone_ore", null},
            {null, null}
        }, "redstone_wire", 4);

        // Redstone block -> Redstone dust
        addRecipe2x2(new String[][]{
            {"redstone_block", null},
            {null, null}
        }, "redstone_wire", 9);

        // ===== 3x3 Recipes (crafting table only) =====
        
        // Crafting Table: 4 planks
        addRecipe3x3(new String[][]{
            {"skyroot_planks", "skyroot_planks", null},
            {"skyroot_planks", "skyroot_planks", null},
            {null, null, null}
        }, "crafting_table", 1);

        // Stone Pickaxe: 3 cobble + 2 sticks
        addRecipe3x3(new String[][]{
            {"stone", "stone", "stone"},
            {null, "stick", null},
            {null, "stick", null}
        }, "stone_pickaxe", 1);

        // Iron Pickaxe: 3 iron + 2 sticks
        addRecipe3x3(new String[][]{
            {"redstone_block", "redstone_block", "redstone_block"},
            {null, "stick", null},
            {null, "stick", null}
        }, "iron_pickaxe", 1);

        // Piston: 3 planks + 4 cobble + 1 iron + 1 redstone
        addRecipe3x3(new String[][]{
            {"skyroot_planks", "skyroot_planks", "skyroot_planks"},
            {"stone", "redstone_block", "stone"},
            {"stone", "redstone_wire", "stone"}
        }, "piston", 1);

        // Sticky Piston: slime + piston
        addRecipe3x3(new String[][]{
            {null, null, null},
            {null, "aerogel", null},
            {null, "piston", null}
        }, "sticky_piston", 1);

        // Skyroot planks from skyroot log
        addRecipe3x3(new String[][]{
            {"skyroot_log", null, null},
            {null, null, null},
            {null, null, null}
        }, "skyroot_planks", 4);

        // Stone Brick from holystone
        addRecipe3x3(new String[][]{
            {"holystone", "holystone", null},
            {"holystone", "holystone", null},
            {null, null, null}
        }, "holystone_bricks", 4);
    }
    
    private void addRecipe2x2(String[][] pattern, String resultItemId, int resultCount) {
        recipes2x2.add(new CraftingRecipe(pattern, resultItemId, resultCount, 2));
    }
    
    private void addRecipe3x3(String[][] pattern, String resultItemId, int resultCount) {
        recipes3x3.add(new CraftingRecipe(pattern, resultItemId, resultCount, 3));
    }
    
    /**
     * Attempts to match a 2x2 crafting grid against 2x2 recipes.
     */
    public CraftingRecipe matchRecipe(String[][] grid) {
        for (CraftingRecipe recipe : recipes2x2) {
            if (matchesPattern(grid, recipe.pattern, 2)) {
                return recipe;
            }
        }
        return null;
    }
    
    /**
     * Attempts to match a 3x3 crafting grid against 3x3 recipes.
     */
    public CraftingRecipe matchRecipe3x3(String[][] grid) {
        for (CraftingRecipe recipe : recipes3x3) {
            if (matchesPattern(grid, recipe.pattern, 3)) {
                return recipe;
            }
        }
        return null;
    }
    
    private boolean matchesPattern(String[][] grid, String[][] pattern, int size) {
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                String gridItem = (r < grid.length && c < grid[r].length) ? grid[r][c] : null;
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
     * Consumes items from a 2x2 crafting grid.
     */
    public void consumeItems(String[][] grid) {
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                grid[r][c] = null;
            }
        }
    }
    
    /**
     * Consumes items from a 3x3 crafting grid.
     */
    public void consumeItems3x3(String[][] grid) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                grid[r][c] = null;
            }
        }
    }
}
