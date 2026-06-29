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
        
        // Wood Planks from Oak Log
        addRecipe2x2(new String[][]{
            {"oak_log", null},
            {null, null}
        }, "oak_planks", 4);

        // Stick from planks
        addRecipe2x2(new String[][]{
            {"oak_planks", null},
            {"oak_planks", null}
        }, "stick", 4);

        // Crafting Table from planks
        addRecipe2x2(new String[][]{
            {"oak_planks", "oak_planks"},
            {"oak_planks", "oak_planks"}
        }, "crafting_table", 1);
        
        // Wooden tools (pickaxe=full top row, axe=top-left L shape, shovel=center column)
        addRecipe3x3(new String[][]{
            {"oak_planks", "oak_planks", "oak_planks"},
            {null, "stick", null},
            {null, "stick", null}
        }, "wood_pickaxe", 1);
        addRecipe3x3(new String[][]{
            {"oak_planks", "oak_planks", null},
            {"oak_planks", "stick", null},
            {null, "stick", null}
        }, "wood_axe", 1);
        addRecipe3x3(new String[][]{
            {"oak_planks", null, null},
            {"stick", null, null},
            {"stick", null, null}
        }, "wood_shovel", 1);

        // Stone tools
        addRecipe3x3(new String[][]{
            {"cobblestone", "cobblestone", "cobblestone"},
            {null, "stick", null},
            {null, "stick", null}
        }, "stone_pickaxe", 1);
        addRecipe3x3(new String[][]{
            {"cobblestone", "cobblestone", null},
            {"cobblestone", "stick", null},
            {null, "stick", null}
        }, "stone_axe", 1);
        addRecipe3x3(new String[][]{
            {"cobblestone", null, null},
            {"stick", null, null},
            {"stick", null, null}
        }, "stone_shovel", 1);

        // Iron tools
        addRecipe3x3(new String[][]{
            {"iron_block", "iron_block", "iron_block"},
            {null, "stick", null},
            {null, "stick", null}
        }, "iron_pickaxe", 1);
        addRecipe3x3(new String[][]{
            {"iron_block", "iron_block", null},
            {"iron_block", "stick", null},
            {null, "stick", null}
        }, "iron_axe", 1);
        addRecipe3x3(new String[][]{
            {"iron_block", null, null},
            {"stick", null, null},
            {"stick", null, null}
        }, "iron_shovel", 1);

        // Diamond tools
        addRecipe3x3(new String[][]{
            {"diamond_block", "diamond_block", "diamond_block"},
            {null, "stick", null},
            {null, "stick", null}
        }, "diamond_pickaxe", 1);
        addRecipe3x3(new String[][]{
            {"diamond_block", "diamond_block", null},
            {"diamond_block", "stick", null},
            {null, "stick", null}
        }, "diamond_axe", 1);
        addRecipe3x3(new String[][]{
            {"diamond_block", null, null},
            {"stick", null, null},
            {"stick", null, null}
        }, "diamond_shovel", 1);

        // Glass from sand
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

        // Brick block from clay (simplified: 4 clay → brick)
        addRecipe2x2(new String[][]{
            {"clay", "clay"},
            {"clay", "clay"}
        }, "brick", 4);

        // Slabs: 3 blocks in a row → 6 slabs
        addRecipe3x3(new String[][]{
            {"oak_planks", "oak_planks", "oak_planks"},
            {null, null, null},
            {null, null, null}
        }, "oak_slab", 6);
        addRecipe3x3(new String[][]{
            {"cobblestone", "cobblestone", "cobblestone"},
            {null, null, null},
            {null, null, null}
        }, "cobblestone_slab", 6);
        addRecipe3x3(new String[][]{
            {"stone_brick", "stone_brick", "stone_brick"},
            {null, null, null},
            {null, null, null}
        }, "stone_brick_slab", 6);
        addRecipe3x3(new String[][]{
            {"brick", "brick", "brick"},
            {null, null, null},
            {null, null, null}
        }, "brick_slab", 6);
        addRecipe3x3(new String[][]{
            {"sandstone", "sandstone", "sandstone"},
            {null, null, null},
            {null, null, null}
        }, "sandstone_slab", 6);

        // Torch: stick + coal
        addRecipe3x3(new String[][]{
            {"coal_ore", null, null},
            {"stick", null, null},
            {null, null, null}
        }, "torch", 4);

        // ===== 3x3 Recipes (crafting table only) =====
        
        // Piston
        addRecipe3x3(new String[][]{
            {"oak_planks", "oak_planks", "oak_planks"},
            {"cobblestone", "iron_block", "cobblestone"},
            {"cobblestone", "redstone_wire", "cobblestone"}
        }, "piston", 1);

        // Sticky Piston
        addRecipe3x3(new String[][]{
            {null, null, null},
            {null, "aerogel", null},
            {null, "piston", null}
        }, "sticky_piston", 1);

        // Stone Brick from cobblestone (2x2)
        addRecipe2x2(new String[][]{
            {"cobblestone", "cobblestone"},
            {"cobblestone", "cobblestone"}
        }, "stone_brick", 4);

        // Bookshelf: 3 planks + 3 books (simplified: oak_log = book)
        addRecipe3x3(new String[][]{
            {"oak_planks", "oak_planks", "oak_planks"},
            {"oak_log", "oak_log", "oak_log"},
            {"oak_planks", "oak_planks", "oak_planks"}
        }, "bookshelf", 1);

        // Iron Block from iron (compact: 9 iron = iron block)
        addRecipe3x3(new String[][]{
            {"iron_ore", "iron_ore", "iron_ore"},
            {"iron_ore", "iron_ore", "iron_ore"},
            {"iron_ore", "iron_ore", "iron_ore"}
        }, "iron_block", 1);

        // Gold Block from gold ore
        addRecipe3x3(new String[][]{
            {"gold_ore", "gold_ore", "gold_ore"},
            {"gold_ore", "gold_ore", "gold_ore"},
            {"gold_ore", "gold_ore", "gold_ore"}
        }, "gold_block", 1);

        // Diamond Block from diamond ore
        addRecipe3x3(new String[][]{
            {"diamond_ore", "diamond_ore", "diamond_ore"},
            {"diamond_ore", "diamond_ore", "diamond_ore"},
            {"diamond_ore", "diamond_ore", "diamond_ore"}
        }, "diamond_block", 1);

        // Lapis Block from lapis ore
        addRecipe3x3(new String[][]{
            {"lapis_ore", "lapis_ore", "lapis_ore"},
            {"lapis_ore", "lapis_ore", "lapis_ore"},
            {"lapis_ore", "lapis_ore", "lapis_ore"}
        }, "lapis_block", 1);

        // Emerald Block
        addRecipe3x3(new String[][]{
            {"emerald_ore", "emerald_ore", "emerald_ore"},
            {"emerald_ore", "emerald_ore", "emerald_ore"},
            {"emerald_ore", "emerald_ore", "emerald_ore"}
        }, "emerald_block", 1);

        // Stairs (6 blocks → 4 stairs, standard Minecraft ratio)
        addRecipe3x3(new String[][]{
            {"oak_planks", null, null},
            {"oak_planks", "oak_planks", null},
            {"oak_planks", "oak_planks", "oak_planks"}
        }, "oak_stairs", 4);
        addRecipe3x3(new String[][]{
            {"cobblestone", null, null},
            {"cobblestone", "cobblestone", null},
            {"cobblestone", "cobblestone", "cobblestone"}
        }, "cobblestone_stairs", 4);
        addRecipe3x3(new String[][]{
            {"stone_brick", null, null},
            {"stone_brick", "stone_brick", null},
            {"stone_brick", "stone_brick", "stone_brick"}
        }, "stone_brick_stairs", 4);
        addRecipe3x3(new String[][]{
            {"brick", null, null},
            {"brick", "brick", null},
            {"brick", "brick", "brick"}
        }, "brick_stairs", 4);
        addRecipe3x3(new String[][]{
            {"sandstone", null, null},
            {"sandstone", "sandstone", null},
            {"sandstone", "sandstone", "sandstone"}
        }, "sandstone_stairs", 4);
        addRecipe3x3(new String[][]{
            {"nether_brick", null, null},
            {"nether_brick", "nether_brick", null},
            {"nether_brick", "nether_brick", "nether_brick"}
        }, "nether_brick_stairs", 4);

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
