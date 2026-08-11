package com.voxel.crafting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CraftingManagerAdditionalRecipesTest {
    private final CraftingManager manager = new CraftingManager();

    @Test
    public void supportsNonOakLogsAndAetherPlanks() {
        CraftingManager.CraftingRecipe birch = manager.matchRecipe(new String[][] {
            {"birch_log", null},
            {null, null}
        });
        assertNotNull(birch);
        assertEquals("birch_planks", birch.resultItemId);
        assertEquals(4, birch.resultCount);

        CraftingManager.CraftingRecipe skyroot = manager.matchRecipe(new String[][] {
            {"skyroot_planks", null},
            {"skyroot_planks", null}
        });
        assertNotNull(skyroot);
        assertEquals("stick", skyroot.resultItemId);
    }

    @Test
    public void supportsCraftingTablesFromWoodVariants() {
        String[] plankTypes = {
            "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
            "acacia_planks", "dark_oak_planks", "skyroot_planks"
        };

        for (String plankType : plankTypes) {
            CraftingManager.CraftingRecipe table = manager.matchRecipe(new String[][] {
                {plankType, plankType},
                {plankType, plankType}
            });
            assertNotNull(plankType, table);
            assertEquals(plankType, "crafting_table", table.resultItemId);
            assertEquals(plankType, 1, table.resultCount);
        }
    }

    @Test
    public void supportsSticksAndWoodenToolsFromEveryPlankVariant() {
        String[] plankTypes = {
            "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
            "acacia_planks", "dark_oak_planks", "skyroot_planks"
        };

        for (String plankType : plankTypes) {
            CraftingManager.CraftingRecipe sticks = manager.matchRecipe(new String[][] {
                {plankType, null},
                {plankType, null}
            });
            assertNotNull(plankType, sticks);
            assertEquals(plankType, "stick", sticks.resultItemId);
            assertEquals(plankType, 4, sticks.resultCount);

            CraftingManager.CraftingRecipe pickaxe = manager.matchRecipe3x3(new String[][] {
                {plankType, plankType, plankType},
                {null, "stick", null},
                {null, "stick", null}
            });
            assertNotNull(plankType, pickaxe);
            assertEquals(plankType, "wood_pickaxe", pickaxe.resultItemId);

            CraftingManager.CraftingRecipe axe = manager.matchRecipe3x3(new String[][] {
                {plankType, plankType, null},
                {plankType, "stick", null},
                {null, "stick", null}
            });
            assertNotNull(plankType, axe);
            assertEquals(plankType, "wood_axe", axe.resultItemId);

            CraftingManager.CraftingRecipe shovel = manager.matchRecipe3x3(new String[][] {
                {plankType, null, null},
                {"stick", null, null},
                {"stick", null, null}
            });
            assertNotNull(plankType, shovel);
            assertEquals(plankType, "wood_shovel", shovel.resultItemId);
        }
    }

    @Test
    public void shaped2x2RecipesCraftOnThe3x3Table() {
        // Sticks (2 planks in a column) are a 2x2 SHAPED recipe. They must also
        // match on the 3x3 crafting table, in any placement and rotation —
        // previously only shapeless 2x2 recipes were tried there.
        String[] plankTypes = {
            "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
            "acacia_planks", "dark_oak_planks", "skyroot_planks"
        };
        for (String plankType : plankTypes) {
            // Vertical column in the top-left 2x2 region of the table
            CraftingManager.CraftingRecipe sticks = manager.matchRecipe3x3(new String[][] {
                {plankType, null, null},
                {plankType, null, null},
                {null, null, null}
            });
            assertNotNull(plankType, sticks);
            assertEquals(plankType, "stick", sticks.resultItemId);
            assertEquals(plankType, 4, sticks.resultCount);

            // Same pattern rotated 90°, placed in the bottom-right 2x2 region
            CraftingManager.CraftingRecipe rotated = manager.matchRecipe3x3(new String[][] {
                {null, null, null},
                {null, null, plankType},
                {null, null, plankType}
            });
            assertNotNull(plankType, rotated);
            assertEquals(plankType, "stick", rotated.resultItemId);
            assertEquals(plankType, 4, rotated.resultCount);
        }

        // Other 2x2 shaped recipes (block compacting) work on the table too
        CraftingManager.CraftingRecipe packed = manager.matchRecipe3x3(new String[][] {
            {null, null, null},
            {null, "ice", "ice"},
            {null, "ice", "ice"}
        });
        assertNotNull(packed);
        assertEquals("packed_ice", packed.resultItemId);

        // Negative: a stray extra item in the grid must NOT match the 2x2 pattern
        assertNull("stray item must break the shaped match", manager.matchRecipe3x3(new String[][] {
            {"oak_planks", null, null},
            {"oak_planks", null, "oak_planks"},
            {null, null, null}
        }));
    }

    @Test
    public void supportsBuildingMaterialCompacting() {
        CraftingManager.CraftingRecipe packedIce = manager.matchRecipe(new String[][] {
            {"ice", "ice"},
            {"ice", "ice"}
        });
        assertNotNull(packedIce);
        assertEquals("packed_ice", packedIce.resultItemId);

        CraftingManager.CraftingRecipe smoothSandstone = manager.matchRecipe(new String[][] {
            {"sandstone", "sandstone"},
            {"sandstone", "sandstone"}
        });
        assertNotNull(smoothSandstone);
        assertEquals("smooth_sandstone", smoothSandstone.resultItemId);
    }

    @Test
    public void supportsNineIngotCompactingAndDecompacting() {
        CraftingManager.CraftingRecipe compact = manager.matchRecipe3x3(new String[][] {
            {"iron_ingot", "iron_ingot", "iron_ingot"},
            {"iron_ingot", "iron_ingot", "iron_ingot"},
            {"iron_ingot", "iron_ingot", "iron_ingot"}
        });
        assertNotNull(compact);
        assertEquals("iron_block", compact.resultItemId);

        CraftingManager.CraftingRecipe unpack = manager.matchRecipe(new String[][] {
            {"iron_block", null},
            {null, null}
        });
        assertNotNull(unpack);
        assertEquals("iron_ingot", unpack.resultItemId);
        assertEquals(9, unpack.resultCount);
    }
}
