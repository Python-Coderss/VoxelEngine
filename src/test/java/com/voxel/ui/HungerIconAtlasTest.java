package com.voxel.ui;

import org.joml.Vector4f;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Guards the HUD hunger icons: they must be sampled from the ui.png atlas
 * (not borrowed from the cooked_chicken item icon) at the UVs documented in
 * {@link UIConstants}, and the atlas must actually contain pixels there.
 */
public class HungerIconAtlasTest {

    private static final int ATLAS = UIConstants.UI_TEXTURE_SIZE;

    @Test
    public void hungerUvsAreDistinctAndInsideTheAtlas() {
        Vector4f empty = UIConstants.HUNGER_EMPTY;
        Vector4f half = UIConstants.HUNGER_HALF;
        Vector4f full = UIConstants.HUNGER_FULL;
        for (Vector4f uv : new Vector4f[] {empty, half, full}) {
            assertEquals("9x9 icon", 9f, uv.z, 0.001f);
            assertEquals("9x9 icon", 9f, uv.w, 0.001f);
            assertTrue("inside atlas", uv.x >= 0 && uv.y >= 0 && uv.x + uv.z <= ATLAS && uv.y + uv.w <= ATLAS);
        }
        assertEquals("empty at x=0", 0f, empty.x, 0.001f);
        assertEquals("half at x=10", 10f, half.x, 0.001f);
        assertEquals("full at x=20", 20f, full.x, 0.001f);
        assertEquals("same row", empty.y, full.y, 0.001f);
    }

    @Test
    public void atlasContainsOpaqueHungerPixels() throws Exception {
        File file = new File("src/main/resources/ui/ui.png");
        assumeTrue("ui.png present", file.exists());
        BufferedImage img = ImageIO.read(file);
        assertNotNull("atlas readable", img);
        assertEquals("atlas width", ATLAS, img.getWidth());
        assertEquals("atlas height", ATLAS, img.getHeight());

        // Every variant has visible pixels, and empty differs from full (only
        // the coloured variants carry the warm drumstick tint).
        assertTrue("empty icon opaque", hasOpaque(img, UIConstants.HUNGER_EMPTY));
        assertTrue("half icon opaque", hasOpaque(img, UIConstants.HUNGER_HALF));
        assertTrue("full icon opaque", hasOpaque(img, UIConstants.HUNGER_FULL));
        assertTrue("full icon is tinted", hasMeatTint(img, UIConstants.HUNGER_FULL));
        assertTrue("empty icon is not tinted", !hasMeatTint(img, UIConstants.HUNGER_EMPTY));
    }

    private static boolean hasOpaque(BufferedImage img, Vector4f uv) {
        for (int y = (int) uv.y; y < uv.y + uv.w; y++)
            for (int x = (int) uv.x; x < uv.x + uv.z; x++)
                if ((img.getRGB(x, y) >>> 24) > 8) return true;
        return false;
    }

    private static boolean hasMeatTint(BufferedImage img, Vector4f uv) {
        for (int y = (int) uv.y; y < uv.y + uv.w; y++)
            for (int x = (int) uv.x; x < uv.x + uv.z; x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) <= 8) continue;
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                if (r > 120 && g > 40 && g < r && b < g) return true;
            }
        return false;
    }
}
