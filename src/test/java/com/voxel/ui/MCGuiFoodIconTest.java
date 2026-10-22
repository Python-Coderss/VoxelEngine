package com.voxel.ui;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Pins the hunger shanks to the food row of the shipped icons.png.
 *
 * <p>The first MC-icons pass sampled the food sprites from y=0, which is the
 * HEART row in the vanilla atlas: the HUD showed bones/hearts instead of
 * drumsticks. These tests fail if the sprites drift back onto that row or off
 * the atlas, and also cover the XP-bar sprites used by the loading screen.</p>
 */
public class MCGuiFoodIconTest {

    private static final String ATLAS = "src/main/resources/assets/minecraft/textures/gui/icons.png";
    private static final int TEX = 256;

    @Test
    public void foodSpritesUseTheSecondAtlasRow() {
        MCGui.Rect[] food = { MCGui.FOOD_EMPTY, MCGui.FOOD_HALF, MCGui.FOOD_FULL };
        for (MCGui.Rect r : food) {
            assertEquals("food row", 27, r.y);
            assertEquals("9x9 icon", 9, r.w);
            assertEquals("9x9 icon", 9, r.h);
            assertTrue("inside atlas", r.x + r.w <= TEX && r.y + r.h <= TEX);
        }
        assertEquals(52, MCGui.FOOD_FULL.x);
        assertEquals(61, MCGui.FOOD_HALF.x);
        assertEquals(70, MCGui.FOOD_EMPTY.x);
    }

    @Test
    public void foodRowIsNotTheHeartRow() throws Exception {
        BufferedImage img = loadAtlas();
        for (MCGui.Rect r : new MCGui.Rect[] { MCGui.FOOD_EMPTY, MCGui.FOOD_HALF, MCGui.FOOD_FULL }) {
            assertTrue("food sprite has pixels", hasOpaque(img, r));
            assertFalse("food sprite differs from the heart row at the same x",
                    samePixels(img, r, new MCGui.Rect(r.x, 0, r.w, r.h)));
        }
    }

    @Test
    public void foodSpritesShowMeatTintOnlyWhenFilled() throws Exception {
        BufferedImage img = loadAtlas();
        int full = maxMeatSaturation(img, MCGui.FOOD_FULL);
        int empty = maxMeatSaturation(img, MCGui.FOOD_EMPTY);
        assertTrue("full shank carries a roast highlight, red-green=" + full, full > 70);
        assertTrue("empty shank is paler than the full one (" + empty + " vs " + full + ")",
                empty < full - 20);
    }

    @Test
    public void loadingBarSpritesAreInsideTheAtlas() throws Exception {
        BufferedImage img = loadAtlas();
        for (MCGui.Rect r : new MCGui.Rect[] { MCGui.XP_BACKGROUND, MCGui.XP_FILL }) {
            assertEquals("182px trough", 182, r.w);
            assertEquals("5px trough", 5, r.h);
            assertTrue("inside atlas", r.x + r.w <= TEX && r.y + r.h <= TEX);
            assertTrue("bars have pixels", hasOpaque(img, r));
        }
    }

    private static BufferedImage loadAtlas() throws Exception {
        File file = new File(ATLAS);
        assumeTrue("icons.png present", file.exists());
        BufferedImage img = ImageIO.read(file);
        assertNotNull("atlas readable", img);
        assertEquals(TEX, img.getWidth());
        assertEquals(TEX, img.getHeight());
        return img;
    }

    private static boolean hasOpaque(BufferedImage img, MCGui.Rect r) {
        for (int y = r.y; y < r.y + r.h; y++)
            for (int x = r.x; x < r.x + r.w; x++)
                if ((img.getRGB(x, y) >>> 24) > 8) return true;
        return false;
    }

    private static boolean samePixels(BufferedImage img, MCGui.Rect a, MCGui.Rect b) {
        for (int y = 0; y < a.h; y++)
            for (int x = 0; x < a.w; x++)
                if (img.getRGB(a.x + x, a.y + y) != img.getRGB(b.x + x, b.y + y)) return false;
        return true;
    }

    /** Highest red-minus-green (= roast warmth) among opaque pixels. */
    private static int maxMeatSaturation(BufferedImage img, MCGui.Rect r) {
        int best = 0;
        for (int y = r.y; y < r.y + r.h; y++)
            for (int x = r.x; x < r.x + r.w; x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) <= 8) continue;
                int red = (argb >> 16) & 0xFF, green = (argb >> 8) & 0xFF;
                best = Math.max(best, red - green);
            }
        return best;
    }
}
