package com.voxel.ui;

import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * The instant title screen.
 *
 * <p>Boot used to stay on a native black window until every texture, block
 * model, recipe and the raytracer compute program had loaded — several seconds
 * on a cold driver. This class renders an MC-styled title frame using only the
 * GUI assets and the tiny UI shader, so the window can show a real title screen
 * before the heavy world/content initialization begins.</p>
 *
 * <p>It owns its own {@link UIManager} (UI shader + framebuffer), the MC GUI kit
 * and the vanilla font atlas; nothing here depends on the world, the item
 * registry or the raytracer. {@link com.voxel.Main} presents
 * {@link #getTexture()} through the existing fullscreen quad.</p>
 */
public final class BootScreen {

    private final UIManager ui;
    private final int width, height;
    private final int fontTextureId;
    private int customBackground;
    private String status = "Preparing the world...";
    private float progress = 0f;
    private static final String[] TIPS = {
        "Punch a tree to start your first build",
        "Shift while walking to sneak past mobs",
        "Coal and sticks make your first torches",
        "Sleep in a bed to skip the night",
        "Craft a bucket before exploring caves",
        "Hold shift to place blocks against others",
        "Cook food in a furnace for more hunger",
        "Torches stop mobs spawning on the ground"
    };

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
        try {
            java.io.File menu = new java.io.File("src/main/resources/ui/menu_background.png");
            if (menu.isFile()) customBackground = UIManager.loadTexture(menu.getPath());
        } catch (RuntimeException ignored) {
        }
    }

    public int getTexture() {
        return ui.getUITexture();
    }

    /** Updates the loading line and progress (0..1) for the next presentation. */
    public void setProgress(String status, float progress) {
        if (status != null) this.status = status;
        this.progress = Math.max(0f, Math.min(1f, progress));
    }

    /** Draws the title frame into the UI framebuffer. */
    public void render() {
        ui.begin();

        // Story Mode cavern art first (the shipped Season 3 backdrop), then the
        // MC panorama, then the bundled menu artwork.
        if (MCGui.MCSM_MENU_BG != 0) {
            ui.drawQuad(new Vector2f(0, 0), new Vector2f(width, height), 0f,
                    new Vector4f(1, 1, 1, 1), MCGui.MCSM_MENU_BG);
        } else if (MCGui.PANORAMA != 0) {
            float vScale = Math.min(1f, height / (float) width);
            float vOffset = (1f - vScale) * 0.5f;
            ui.drawQuad(new Vector2f(0, 0), new Vector2f(width, height), 0f,
                    new Vector4f(1, 1, 1, 1), MCGui.PANORAMA,
                    new Vector2f(0f, vOffset), new Vector2f(1f, vScale));
        } else if (customBackground != 0) {
            ui.drawQuad(new Vector2f(0, 0), new Vector2f(width, height), 0f,
                    new Vector4f(1, 1, 1, 1), customBackground);
        } else {
            ui.drawQuad(new Vector2f(0, 0), new Vector2f(width, height), 0f,
                    new Vector4f(0.090f, 0.065f, 0.050f, 1f), 0);
        }
        // Darken so the pixel-art landscape reads as a backdrop, not the focus.
        ui.drawQuad(new Vector2f(0, 0), new Vector2f(width, height), 0f,
                new Vector4f(0.050f, 0.035f, 0.025f, 0.55f), 0);

        // MC dirt panel behind the wordmark, exactly the menu/dialog backdrop.
        float panelW = Math.min(width - 80f, 860f);
        float panelX = (width - panelW) / 2f;
        float panelY = height * 0.24f;
        float panelH = 190f;
        if (MCGui.OPTIONS_BACKGROUND != 0) {
            ui.drawQuadTiled(new Vector2f(panelX, panelY), new Vector2f(panelW, panelH),
                    new Vector4f(0.46f, 0.36f, 0.25f, 0.94f), MCGui.OPTIONS_BACKGROUND, 16f, 16f);
        } else {
            ui.drawQuad(new Vector2f(panelX, panelY), new Vector2f(panelW, panelH), 0f,
                    UiPalette.unencoded(UiPalette.PANEL_DEEP, 0.94f), 0);
        }

        // Wordmark: the shipped Story Mode art when present, the text logo as
        // the fallback, then the season line and tagline in the MC font.
        float textBottom = panelY + 36f;
        if (MCGui.MCSM_TITLE != 0) {
            float logoW = Math.min(panelW - 120f, 620f);
            float logoH = logoW * (MCGui.MCSM_TITLE_RECT.h / (float) MCGui.MCSM_TITLE_RECT.w);
            float logoX = (width - logoW) / 2f;
            // UVs come from the image's opaque bounds measured at load time.
            Vector4f uv = MCGui.spriteUV(MCGui.MCSM_TITLE, MCGui.MCSM_TITLE_RECT);
            ui.drawQuad(new Vector2f(logoX, panelY + 24f), new Vector2f(logoW, logoH), 0f,
                    new Vector4f(1f, 1f, 1f, 1f), MCGui.MCSM_TITLE,
                    new Vector2f(uv.x, uv.y), new Vector2f(uv.z, uv.w));
            textBottom = panelY + 24f + logoH;
            if (fontTextureId != 0) {
                String season = "SEASON THREE";
                float seasonScale = 2.2f;
                float seasonW = season.length() * 8f * seasonScale;
                ui.drawStringShadow(season, (width - seasonW) / 2f, textBottom + 6f, seasonScale,
                        UiPalette.unencoded(UiPalette.GOLD, 1f), fontTextureId, 0, 0);
                textBottom += 8f * seasonScale + 8f;
            }
        } else if (fontTextureId != 0) {
            String title = "VOXEL ENGINE";
            float titleScale = Math.min(6.4f, (panelW - 120f) / (title.length() * 8f));
            float titleW = title.length() * 8f * titleScale;
            ui.drawStringShadow(title, (width - titleW) / 2f, panelY + 36f, titleScale,
                    new Vector4f(1f, 1f, 1f, 1f), fontTextureId, 0, 0);
            textBottom = panelY + 36f + 8f * titleScale;
        }
        if (fontTextureId != 0) {
            String tagline = "A MINECRAFT-STYLE VOXEL SANDBOX";
            float subScale = 1.8f;
            float subW = tagline.length() * 8f * subScale;
            ui.drawStringShadow(tagline, (width - subW) / 2f, textBottom + 12f,
                    subScale, UiPalette.unencoded(UiPalette.GOLD, 1f), fontTextureId, 0, 0);
        }

        // MC XP-style progress trough + green fill, sampled from icons.png at
        // native size and stretched 2.8x so it keeps the chunky pixel look.
        float barW = Math.min(panelW - 160f, 520f);
        float barH = 22f;
        float barX = (width - barW) / 2f;
        float barY = panelY + panelH + 46f;
        ui.drawQuad(new Vector2f(barX - 10f, barY - 10f), new Vector2f(barW + 20f, barH + 20f), 0f,
                UiPalette.unencoded(UiPalette.INK, 0.9f), 0);
        if (MCGui.ICONS != 0) {
            ui.drawQuad(new Vector2f(barX, barY), new Vector2f(barW, barH), 0f,
                    new Vector4f(1, 1, 1, 1), MCGui.ICONS,
                    new Vector2f(MCGui.XP_BACKGROUND.x / 256f, MCGui.XP_BACKGROUND.y / 256f),
                    new Vector2f(MCGui.XP_BACKGROUND.w / 256f, MCGui.XP_BACKGROUND.h / 256f));
            if (progress > 0.001f) {
                ui.drawQuad(new Vector2f(barX, barY), new Vector2f(barW * progress, barH), 0f,
                        new Vector4f(1, 1, 1, 1), MCGui.ICONS,
                        new Vector2f(MCGui.XP_FILL.x / 256f, MCGui.XP_FILL.y / 256f),
                        new Vector2f(MCGui.XP_FILL.w / 256f * progress, MCGui.XP_FILL.h / 256f));
            }
        } else {
            ui.drawQuad(new Vector2f(barX, barY), new Vector2f(barW, barH), 0f,
                    UiPalette.unencoded(UiPalette.PANEL_LIFT, 1f), 0);
            if (progress > 0.001f) {
                ui.drawQuad(new Vector2f(barX, barY), new Vector2f(barW * progress, barH), 0f,
                        new Vector4f(0.42f, 0.78f, 0.36f, 1f), 0);
            }
        }

        if (fontTextureId != 0) {
            float now = System.nanoTime() * 1e-9f;
            int pct = Math.round(progress * 100f);
            String pctText = pct + "%";
            ui.drawStringShadow(pctText, barX + barW - pctText.length() * 8f * 1.5f, barY - 30f, 1.5f,
                    new Vector4f(1f, 1f, 1f, 1f), fontTextureId, 0, 0);

            String dots = new String[] { "", ".", "..", "..." }[((int) (now * 3f)) & 3];
            float statusScale = 1.5f;
            String statusLine = status + dots;
            float statusW = statusLine.length() * 8f * statusScale;
            ui.drawStringShadow(statusLine, (width - statusW) / 2f, barY + barH + 20f, statusScale,
                    UiPalette.unencoded(UiPalette.CREAM, 1f), fontTextureId, 0, 0);

            String tipLine = "TIP: " + TIPS[((int) (now / 4f)) % TIPS.length];
            float tipW = tipLine.length() * 8f * 1.4f;
            ui.drawStringShadow(tipLine, (width - tipW) / 2f, barY + barH + 54f, 1.4f,
                    new Vector4f(0.95f, 0.87f, 0.52f,
                            0.65f + 0.35f * (float) Math.abs(Math.sin(now * 3f))),
                    fontTextureId, 0, 0);

            String hint = "Please wait";
            ui.drawStringShadow(hint, 18f, height - 30f, 1.3f,
                    UiPalette.unencoded(UiPalette.MUTED, 0.9f), fontTextureId, 0, 0);
        }

        ui.end();
    }
}
