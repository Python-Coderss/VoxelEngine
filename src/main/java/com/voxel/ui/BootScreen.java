package com.voxel.ui;

import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * The instant title screen.
 *
 * <p>Boot used to stay on a native black window until every texture, block
 * model, recipe and the raytracer compute program had loaded — several seconds
 * on a cold driver. This class renders the title frame using only the GUI
 * assets and the tiny UI shader, so the window can show a real title screen
 * before the heavy world/content initialization begins.</p>
 *
 * <p>It owns its own {@link UIManager} (UI shader + framebuffer), the MC GUI kit
 * and the vanilla font atlas; nothing here depends on the world, the item
 * registry or the raytracer. {@link com.voxel.Main} presents
 * {@link #getTexture()} through the existing fullscreen quad.</p>
 *
 * <p>The frame is deliberately just the wordmark on black: no backdrop art, no
 * panels, no season line, no progress chrome. Loading feedback belongs to the
 * HUD's loading overlay, not to the title card.</p>
 */
public final class BootScreen {

    private final UIManager ui;
    private final int width, height;
    private final int fontTextureId;
    private String status = "Preparing the world...";
    private float progress = 0f;

    public BootScreen(int width, int height) {
        this.width = width;
        this.height = height;
        this.ui = new UIManager(width, height);
        MCGui.loadAll();
        int font = 0;
        try {
            java.io.File file = new java.io.File(
                    "src/main/resources/assets/minecraft/textures/font/ascii.png");
            if (file.isFile()) font = UIManager.loadTexture(file.getPath());
        } catch (RuntimeException e) {
            System.err.println("Note: boot font unavailable");
        }
        this.fontTextureId = font;
    }

    public int getTexture() {
        return ui.getUITexture();
    }

    /**
     * Kept for the boot sequence's progress reporting. The title card no longer
     * draws it, so it is only retained as state for callers.
     */
    public void setProgress(String status, float progress) {
        if (status != null) this.status = status;
        this.progress = Math.max(0f, Math.min(1f, progress));
    }

    /** Draws the title frame into the UI framebuffer. */
    public void render() {
        ui.begin();
        // Black backdrop: the title card is the logo and nothing else.
        ui.drawQuad(new Vector2f(0, 0), new Vector2f(width, height), 0f,
                new Vector4f(0f, 0f, 0f, 1f), 0);

        // Wordmark only: the shipped Story Mode logo, or the text logo when the
        // artwork is missing from a trimmed resource pack.
        float logoW = Math.min(width * 0.52f, 640f);
        if (MCGui.MCSM_TITLE != 0) {
            float aspect = MCGui.MCSM_TITLE_RECT.h / (float) MCGui.MCSM_TITLE_RECT.w;
            float logoH = logoW * aspect;
            Vector4f uv = MCGui.spriteUV(MCGui.MCSM_TITLE, MCGui.MCSM_TITLE_RECT);
            ui.drawQuad(new Vector2f((width - logoW) / 2f, (height - logoH) / 2f),
                    new Vector2f(logoW, logoH), 0f,
                    new Vector4f(1f, 1f, 1f, 1f), MCGui.MCSM_TITLE,
                    new Vector2f(uv.x, uv.y), new Vector2f(uv.z, uv.w));
        } else if (fontTextureId != 0) {
            String title = "VOXEL ENGINE";
            float scale = Math.min(6.4f, (width * 0.8f) / (title.length() * 8f));
            float textW = title.length() * 8f * scale;
            ui.drawStringShadow(title, (width - textW) / 2f,
                    (height - 8f * scale) / 2f, scale,
                    new Vector4f(1f, 1f, 1f, 1f), fontTextureId, 0, 0);
        }

        ui.end();
    }
}
