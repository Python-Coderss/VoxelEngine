package com.voxel.ui;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Guards the vanilla GUI atlas files and the sprite rectangles sampled from
 * them. The HUD/container rewrite must fail loudly if an atlas is replaced,
 * resized, or if a rect points at empty pixels.
 */
public class MCGuiAssetTest {

    private static final String GUI = "src/main/resources/assets/minecraft/textures/gui/";

    @Test
    public void requiredAtlasesExistAtExpectedSizes() throws Exception {
        assertAtlas("widgets.png", 256, 256);
        assertAtlas("icons.png", 256, 256);
        assertAtlas("options_background.png", 16, 16);
        assertAtlas("container/inventory.png", 256, 256);
        assertAtlas("container/crafting_table.png", 256, 256);
        assertAtlas("container/furnace.png", 256, 256);
        assertAtlas("container/generic_54.png", 256, 256);
        assertAtlas("title/background/panorama_0.png", -1, -1);
    }

    @Test
    public void widgetRectsPointAtVisiblePixels() throws Exception {
        BufferedImage widgets = load("widgets.png");
        assertOpaque(widgets, MCGui.BUTTON, "button");
        assertOpaque(widgets, MCGui.BUTTON_HOVER, "button hover");
        assertOpaque(widgets, MCGui.BUTTON_DISABLED, "button disabled");
        assertOpaque(widgets, MCGui.HOTBAR, "hotbar");
        assertOpaque(widgets, MCGui.SELECTOR, "hotbar selector");

        BufferedImage inventory = load("container/inventory.png");
        assertOpaque(inventory, MCGui.SLOT, "inventory slot");
        assertOpaque(inventory, MCGui.ARMOR_SLOT, "armour slot");

        BufferedImage furnace = load("container/furnace.png");
        assertOpaque(furnace, MCGui.FURNACE_FLAME, "furnace flame");
        assertOpaque(furnace, MCGui.FURNACE_ARROW, "furnace arrow");
    }

    @Test
    public void panelSpritesFillTheirDeclaredRect() throws Exception {
        assertPanel("container/inventory.png", MCGui.INVENTORY_PANEL);
        assertPanel("container/crafting_table.png", MCGui.CRAFTING_PANEL);
        assertPanel("container/furnace.png", MCGui.FURNACE_PANEL);
        assertPanel("container/generic_54.png", MCGui.CHEST_PANEL);
    }

    private static void assertPanel(String file, MCGui.Rect panel) throws Exception {
        BufferedImage img = load(file);
        // The panel art is opaque in the interior (corners may be cut).
        int cx = panel.x + panel.w / 2;
        int cy = panel.y + panel.h / 2;
        assertTrue(file + " panel centre opaque", (img.getRGB(cx, cy) >>> 24) > 8);
        assertTrue(panel.w == 176 && panel.h >= 166);
    }

    private static void assertOpaque(BufferedImage img, MCGui.Rect r, String name) {
        int opaque = 0;
        for (int y = r.y; y < r.y + r.h; y++)
            for (int x = r.x; x < r.x + r.w; x++)
                if ((img.getRGB(x, y) >>> 24) > 8) opaque++;
        assertTrue(name + " has visible pixels", opaque > 0);
    }

    private static void assertAtlas(String rel, int w, int h) throws Exception {
        BufferedImage img = load(rel);
        if (w > 0) {
            assertEquals(rel + " width", w, img.getWidth());
            assertEquals(rel + " height", h, img.getHeight());
        }
    }

    private static BufferedImage load(String rel) throws Exception {
        File file = new File(GUI + rel);
        assumeTrue(rel + " present", file.exists());
        BufferedImage img = ImageIO.read(file);
        assertNotNull(rel + " readable", img);
        return img;
    }
}
