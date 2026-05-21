package com.voxel.ui;

import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * Central constants for all UI texture coordinates (UVs).
 *
 * ui.png is 256x256 pixels. All coordinates are in raw pixel space (x, y, width, height)
 * unless otherwise noted. Divide by 256 to get normalized UV coordinates.
 *
 * Texture layout in ui.png (row 0):
 *   0-21: Slot background
 *  22-43: Slot active/highlight border
 *  44-51: (empty/gap)
 *  52-60: Heart (empty)    — 9x9 px
 *  61-69: Heart (half)     — 9x9 px
 *  70-78: Heart (full)     — 9x9 px
 *
 * Slot textures repeat vertically for additional variations:
 *   Row 1 (y=20): slot bg variation
 *   Row 2 (y=40): slot bg variation
 *   etc.
 */
public final class UIConstants {

    // ============================================================
    //  Texture atlas info
    // ============================================================

    /** ui.png dimensions (256x256). */
    public static final int UI_TEXTURE_SIZE = 256;

    // ============================================================
    //  Heart icons — used by health bars
    // ============================================================

    /** Full heart: draws at pixel (70, 0) with size 9×9. */
    public static final Vector4f HEART_FULL    = new Vector4f(70, 0, 9, 9);
    /** Half heart: draws at pixel (61, 0) with size 9×9. */
    public static final Vector4f HEART_HALF    = new Vector4f(61, 0, 9, 9);
    /** Empty heart: draws at pixel (52, 0) with size 9×9. */
    public static final Vector4f HEART_EMPTY   = new Vector4f(52, 0, 9, 9);

    // ============================================================
    //  Inventory slots
    // ============================================================

    /** Slot background texture size (pixels in ui.png). */
    public static final int SLOT_TEX_W = 22;
    public static final int SLOT_TEX_H = 20;

    /** Pixel origin of the slot background in ui.png. */
    public static final int SLOT_BG_X = 0;
    public static final int SLOT_BG_Y = 0;

    /** Pixel origin of the active-slot highlight in ui.png. */
    public static final int SLOT_ACTIVE_X = 22;
    public static final int SLOT_ACTIVE_Y = 0;

    // Normalized UV helpers for use with UIManager
    public static final float SLOT_UV_W = (float) SLOT_TEX_W / UI_TEXTURE_SIZE;
    public static final float SLOT_UV_H = (float) SLOT_TEX_H / UI_TEXTURE_SIZE;

    public static final Vector2f SLOT_BG_UV_OFFSET    = new Vector2f(0.0f, 0.0f);
    public static final Vector2f SLOT_BG_UV_SCALE     = new Vector2f(SLOT_UV_W, SLOT_UV_H);
    public static final Vector2f SLOT_ACTIVE_UV_OFFSET = new Vector2f((float) SLOT_ACTIVE_X / UI_TEXTURE_SIZE, 0.0f);
    public static final Vector2f SLOT_ACTIVE_UV_SCALE = new Vector2f(SLOT_UV_W, SLOT_UV_H);

    // ============================================================
    //  Font atlas (assets/minecraft/textures/font/ascii.png)
    // ============================================================

    /** Font atlas is a 16×16 grid of characters (standard Minecraft format). */
    public static final int FONT_GRID_SIZE = 16;
    public static final float FONT_UV_STEP = 1.0f / FONT_GRID_SIZE;

    /**
     * Returns normalized UV offset for a given ASCII character in the font atlas.
     * The atlas uses code-point indexing: row = charCode / 16, col = charCode % 16.
     */
    public static Vector2f fontUvForChar(char c) {
        int charCode = (int) c;
        return new Vector2f((charCode % FONT_GRID_SIZE) * FONT_UV_STEP,
                            (charCode / FONT_GRID_SIZE) * FONT_UV_STEP);
    }

    // ============================================================
    //  Where to change these in the codebase
    // ============================================================
    //
    //  Java side (player health bar in inventory):
    //    Main.java fields: uvHeartFull, uvHeartHalf, uvHeartEmpty
    //      → Initialize these to UIConstants.HEART_FULL / HALF / EMPTY
    //    Runtime tweak: /setuv full|x|y|w|h
    //
    //  Shader side (entity health bars — raytracer.comp):
    //    Uniform: layout(location=6) vec4 u_HeartUVs[3]  (sent from Java)
    //    HARDCODED FALLBACK at ~line 359:
    //      if (heartValueRatio >= 0.9) uvBase = vec2(70, 0);
    //      else if (heartValueRatio >= 0.4) uvBase = vec2(61, 0);
    //      else uvBase = vec2(52, 0);
    //    ↑ These hardcoded values need updating to match if you change the constants.
    //
    //  Inventory slots:
    //    Main.java buildInventoryUi() uses:
    //      uvOffset = new Vector2f(0.0f, row * vScale)
    //      uvScale = new Vector2f(SLOT_TEX_W/uiSize.x, SLOT_TEX_H/uiSize.y)
    //
    //  Font:
    //    Main.java updateInventoryUi() at ~line 1472:
    //      digit1.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f)
    //
    // ============================================================

    private UIConstants() {}
}
