package com.voxel.game;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SurfaceCraftingManagerTest {
    @Test
    public void storesIndependentTwoByTwoGridsByBlockPosition() {
        SurfaceCraftingManager manager = new SurfaceCraftingManager();
        String[][] first = new String[][] {
            {"oak_log", null},
            {null, "stick"}
        };
        String[][] second = new String[][] {
            {"sand", "sand"},
            {"sand", "sand"}
        };

        manager.setGrid(1, 2, 3, first);
        manager.setGrid(4, 5, 6, second);

        assertEquals("oak_log", manager.getGrid(1, 2, 3)[0][0]);
        assertEquals("stick", manager.getGrid(1, 2, 3)[1][1]);
        assertEquals("sand", manager.getGrid(4, 5, 6)[0][1]);
        assertFalse(SurfaceCraftingManager.isGridEmpty(manager.getGrid(4, 5, 6)));
    }

    @Test
    public void roundTripsSurfaceGridThroughBinarySave() throws Exception {
        File file = File.createTempFile("surface-crafting", ".dat");
        try {
            SurfaceCraftingManager saved = new SurfaceCraftingManager();
            saved.setGrid(10, 20, 30, new String[][] {
                {"oak_planks", "oak_planks"},
                {"oak_planks", "oak_planks"}
            });
            saved.saveToFile(file);

            SurfaceCraftingManager loaded = new SurfaceCraftingManager();
            loaded.loadFromFile(file);

            assertTrue(loaded.hasGrid(10, 20, 30));
            assertEquals("oak_planks", loaded.getGrid(10, 20, 30)[1][0]);
        } finally {
            file.delete();
        }
    }

    @Test
    public void updatedIngredientsRemainAttachedToTheSameBlock() {
        SurfaceCraftingManager manager = new SurfaceCraftingManager();
        manager.setGrid(32, 70, -11, new String[][] {
            {"oak_log", null},
            {null, "stick"}
        });

        // Simulate reopening the overlay: the manager, rather than a transient
        // UI buffer, is the source of truth for the block's ingredients.
        String[][] reopened = manager.getGrid(32, 70, -11);
        assertEquals("oak_log", reopened[0][0]);
        assertEquals("stick", reopened[1][1]);
    }
}
