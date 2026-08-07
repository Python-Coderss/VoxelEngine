package com.voxel.crafting;

import java.util.ArrayList;
import java.util.Arrays;
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
        public final String[][] pattern; // shaped grid of item IDs, null = empty
        public final List<String> ingredients; // unordered inputs for shapeless recipes
        public final boolean shapeless;
        public final String resultItemId;
        public final int resultCount;
        public final int gridSize; // 2 or 3
        
        public CraftingRecipe(String[][] pattern, String resultItemId, int resultCount, int gridSize) {
            this.pattern = pattern;
            this.ingredients = null;
            this.shapeless = false;
            this.resultItemId = resultItemId;
            this.resultCount = resultCount;
            this.gridSize = gridSize;
        }

        public CraftingRecipe(List<String> ingredients, String resultItemId, int resultCount, int gridSize) {
            this.pattern = null;
            this.ingredients = new ArrayList<>(ingredients);
            this.shapeless = true;
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
        
        // Wood planks from logs are shapeless: the log can occupy any slot.
        addShapeless2x2("oak_planks", 4, "oak_log");

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
        // Every registered wood plank variant can make a crafting table.
        addRecipe2x2(new String[][]{
            {"spruce_planks", "spruce_planks"},
            {"spruce_planks", "spruce_planks"}
        }, "crafting_table", 1);
        addRecipe2x2(new String[][]{
            {"birch_planks", "birch_planks"},
            {"birch_planks", "birch_planks"}
        }, "crafting_table", 1);
        addRecipe2x2(new String[][]{
            {"jungle_planks", "jungle_planks"},
            {"jungle_planks", "jungle_planks"}
        }, "crafting_table", 1);
        addRecipe2x2(new String[][]{
            {"acacia_planks", "acacia_planks"},
            {"acacia_planks", "acacia_planks"}
        }, "crafting_table", 1);
        addRecipe2x2(new String[][]{
            {"dark_oak_planks", "dark_oak_planks"},
            {"dark_oak_planks", "dark_oak_planks"}
        }, "crafting_table", 1);
        addRecipe2x2(new String[][]{
            {"skyroot_planks", "skyroot_planks"},
            {"skyroot_planks", "skyroot_planks"}
        }, "crafting_table", 1);
        
        // Wooden tools: every plank variant makes the shared wood-tier tools.
        registerWoodToolRecipes("oak_planks");
        registerWoodToolRecipes("spruce_planks");
        registerWoodToolRecipes("birch_planks");
        registerWoodToolRecipes("jungle_planks");
        registerWoodToolRecipes("acacia_planks");
        registerWoodToolRecipes("dark_oak_planks");
        registerWoodToolRecipes("skyroot_planks");

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

        // Redstone ore/block -> redstone dust are order-independent conversions.
        addShapeless2x2("redstone_wire", 4, "redstone_ore");
        addShapeless2x2("redstone_wire", 9, "redstone_block");

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

        // Flint and steel: the two ingredients can be placed in any slots/order.
        addShapeless2x2("flint_and_steel", 1, "flint", "iron_ingot");

        // Bucket: 3 iron ingots in V shape
        addRecipe3x3(new String[][]{
            {"iron_ingot", null, "iron_ingot"},
            {null, "iron_ingot", null},
            {null, null, null}
        }, "bucket", 1);

        // Iron Ingot from Iron Ore
        addRecipe2x2(new String[][]{
            {"iron_ore", "iron_ore"},
            {"iron_ore", "iron_ore"}
        }, "iron_ingot", 1);

        // Flint from gravel is a one-ingredient shapeless conversion.
        addShapeless2x2("flint", 1, "gravel");

        // Furnace: 8 cobblestone in a ring
        addRecipe3x3(new String[][]{
            {"cobblestone", "cobblestone", "cobblestone"},
            {"cobblestone", null, "cobblestone"},
            {"cobblestone", "cobblestone", "cobblestone"}
        }, "furnace_off", 1);

        // Chest: 8 planks in a ring
        addRecipe3x3(new String[][]{
            {"oak_planks", "oak_planks", "oak_planks"},
            {"oak_planks", null, "oak_planks"},
            {"oak_planks", "oak_planks", "oak_planks"}
        }, "chest", 1);

        // ===== Redstone extras =====

        // Redstone torch: dust and stick can be placed in any slots/order.
        addShapeless2x2("redstone_torch", 1, "redstone_wire", "stick");

        // Redstone Block: 9 dust
        addRecipe3x3(new String[][]{
            {"redstone_wire", "redstone_wire", "redstone_wire"},
            {"redstone_wire", "redstone_wire", "redstone_wire"},
            {"redstone_wire", "redstone_wire", "redstone_wire"}
        }, "redstone_block", 1);

        // Redstone Lamp: glowstone core, dust cross
        addRecipe3x3(new String[][]{
            {null, "redstone_wire", null},
            {"redstone_wire", "glowstone", "redstone_wire"},
            {null, "redstone_wire", null}
        }, "redstone_lamp", 1);

        // ===== Create-inspired recipes =====

        // Andesite from diorite + cobblestone is shapeless.
        addShapeless2x2("andesite", 2, "diorite", "cobblestone");

        // Andesite Casing: planks frame + andesite
        addRecipe3x3(new String[][]{
            {"oak_planks", "andesite", "oak_planks"},
            {"andesite", "oak_planks", "andesite"},
            {"oak_planks", "andesite", "oak_planks"}
        }, "andesite_casing", 4);

        // Encased Fan: iron frame + casing + redstone
        addRecipe3x3(new String[][]{
            {"oak_planks", "iron_ingot", "oak_planks"},
            {"iron_ingot", "andesite_casing", "iron_ingot"},
            {"oak_planks", "redstone_wire", "oak_planks"}
        }, "encased_fan", 1);

        // ===== Additional wood variants =====

        // All registered log types can be turned into matching planks shapelessly.
        addShapeless2x2("birch_planks", 4, "birch_log");
        addShapeless2x2("spruce_planks", 4, "spruce_log");
        addShapeless2x2("jungle_planks", 4, "jungle_log");
        addShapeless2x2("acacia_planks", 4, "acacia_log");
        addShapeless2x2("dark_oak_planks", 4, "dark_oak_log");

        // Any registered plank type makes four sticks in the same vertical pattern.
        addRecipe2x2(new String[][]{{"spruce_planks", null}, {"spruce_planks", null}}, "stick", 4);
        addRecipe2x2(new String[][]{{"birch_planks", null}, {"birch_planks", null}}, "stick", 4);
        addRecipe2x2(new String[][]{{"jungle_planks", null}, {"jungle_planks", null}}, "stick", 4);
        addRecipe2x2(new String[][]{{"acacia_planks", null}, {"acacia_planks", null}}, "stick", 4);
        addRecipe2x2(new String[][]{{"dark_oak_planks", null}, {"dark_oak_planks", null}}, "stick", 4);
        addRecipe2x2(new String[][]{{"skyroot_planks", null}, {"skyroot_planks", null}}, "stick", 4);

        // ===== Additional compacting and building recipes =====

        // Sand-family building blocks: four sand make four sandstone.
        addRecipe2x2(new String[][]{
            {"sand", "sand"},
            {"sand", "sand"}
        }, "sandstone", 4);
        addRecipe2x2(new String[][]{
            {"sandstone", "sandstone"},
            {"sandstone", "sandstone"}
        }, "smooth_sandstone", 4);

        // Four ice blocks make packed ice.
        addRecipe2x2(new String[][]{
            {"ice", "ice"},
            {"ice", "ice"}
        }, "packed_ice", 1);

        // Nine clay blocks make one hardened clay block.
        addRecipe3x3(new String[][]{
            {"clay", "clay", "clay"},
            {"clay", "clay", "clay"},
            {"clay", "clay", "clay"}
        }, "hardened_clay", 1);

        // Aether masonry and natural materials.
        addRecipe2x2(new String[][]{
            {"holystone", "holystone"},
            {"holystone", "holystone"}
        }, "holystone_bricks", 4);
        addRecipe2x2(new String[][]{
            {"brown_mushroom", "brown_mushroom"},
            {"brown_mushroom", "brown_mushroom"}
        }, "brown_mushroom_block", 1);
        addRecipe2x2(new String[][]{
            {"red_mushroom", "red_mushroom"},
            {"red_mushroom", "red_mushroom"}
        }, "red_mushroom_block", 1);

        // ===== Additional metal block conversions =====

        // Compacting/decompacting are shapeless; only the ingredient counts matter.
        addShapeless3x3("iron_block", 1,
            "iron_ingot", "iron_ingot", "iron_ingot",
            "iron_ingot", "iron_ingot", "iron_ingot",
            "iron_ingot", "iron_ingot", "iron_ingot");
        addShapeless2x2("iron_ingot", 9, "iron_block");
    }
    
    private void registerWoodToolRecipes(String plankItemId) {
        // Pickaxe: three planks across the top and two sticks down the center.
        addRecipe3x3(new String[][]{
            {plankItemId, plankItemId, plankItemId},
            {null, "stick", null},
            {null, "stick", null}
        }, "wood_pickaxe", 1);
        // Axe: two planks across the top, one below on the left, and a handle.
        addRecipe3x3(new String[][]{
            {plankItemId, plankItemId, null},
            {plankItemId, "stick", null},
            {null, "stick", null}
        }, "wood_axe", 1);
        // Shovel: one plank over a two-stick handle.
        addRecipe3x3(new String[][]{
            {plankItemId, null, null},
            {"stick", null, null},
            {"stick", null, null}
        }, "wood_shovel", 1);
    }

    private void addRecipe2x2(String[][] pattern, String resultItemId, int resultCount) {
        recipes2x2.add(new CraftingRecipe(pattern, resultItemId, resultCount, 2));
    }

    private void addShapeless2x2(String resultItemId, int resultCount, String... ingredients) {
        recipes2x2.add(new CraftingRecipe(Arrays.asList(ingredients), resultItemId, resultCount, 2));
    }
    
    private void addRecipe3x3(String[][] pattern, String resultItemId, int resultCount) {
        recipes3x3.add(new CraftingRecipe(pattern, resultItemId, resultCount, 3));
    }

    private void addShapeless3x3(String resultItemId, int resultCount, String... ingredients) {
        recipes3x3.add(new CraftingRecipe(Arrays.asList(ingredients), resultItemId, resultCount, 3));
    }

    /**
     * Attempts to match a 2x2 crafting grid against 2x2 recipes.
     */
    public CraftingRecipe matchRecipe(String[][] grid) {
        for (CraftingRecipe recipe : recipes2x2) {
            if (matchesRecipe(grid, recipe, 2)) {
                return recipe;
            }
        }
        return null;
    }
    
    /**
     * Attempts to match a 3x3 crafting grid against 3x3 recipes. Shapeless 2x2
     * recipes are also valid in the larger table because they have no position
     * requirements.
     */
    public CraftingRecipe matchRecipe3x3(String[][] grid) {
        for (CraftingRecipe recipe : recipes3x3) {
            if (matchesRecipe(grid, recipe, 3)) {
                return recipe;
            }
        }
        for (CraftingRecipe recipe : recipes2x2) {
            if (recipe.shapeless && matchesRecipe(grid, recipe, 3)) {
                return recipe;
            }
        }
        return null;
    }
    
    private boolean matchesRecipe(String[][] grid, CraftingRecipe recipe, int size) {
        return recipe.shapeless
            ? matchesShapeless(grid, recipe.ingredients, size)
            : matchesPattern(grid, recipe.pattern, size);
    }

    private boolean matchesShapeless(String[][] grid, List<String> ingredients, int size) {
        List<String> remaining = new ArrayList<>(ingredients);
        int itemCount = 0;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                String gridItem = (r < grid.length && c < grid[r].length) ? grid[r][c] : null;
                if (gridItem == null) continue;
                itemCount++;
                if (!remaining.remove(gridItem)) return false;
            }
        }
        return itemCount == ingredients.size() && remaining.isEmpty();
    }

    /**
     * Matches a shaped recipe in any cardinal rotation. Reflections are not
     * accepted: rotating a recipe is allowed, mirroring it is a different shape.
     */
    private boolean matchesPattern(String[][] grid, String[][] pattern, int size) {
        for (int rotation = 0; rotation < 4; rotation++) {
            if (matchesPatternRotation(grid, pattern, size, rotation)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPatternRotation(String[][] grid, String[][] pattern, int size, int rotation) {
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                String gridItem = (r < grid.length && c < grid[r].length) ? grid[r][c] : null;
                int sourceRow;
                int sourceCol;
                switch (rotation) {
                    case 0:
                        sourceRow = r;
                        sourceCol = c;
                        break;
                    case 1: // 90 degrees clockwise
                        sourceRow = size - 1 - c;
                        sourceCol = r;
                        break;
                    case 2: // 180 degrees
                        sourceRow = size - 1 - r;
                        sourceCol = size - 1 - c;
                        break;
                    case 3: // 270 degrees clockwise
                        sourceRow = c;
                        sourceCol = size - 1 - r;
                        break;
                    default:
                        return false;
                }
                String patternItem = pattern[sourceRow][sourceCol];

                if (patternItem == null) {
                    if (gridItem != null) return false;
                } else {
                    if (gridItem == null || !gridItem.equals(patternItem)) return false;
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
