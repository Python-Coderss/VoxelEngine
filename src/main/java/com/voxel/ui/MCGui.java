package com.voxel.ui;

import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector4f;

/**
 * The Minecraft GUI asset kit: every HUD/menu surface is sampled from the
 * vanilla 1.12-style textures already shipped under
 * {@code assets/minecraft/textures/gui} instead of the project's old custom
 * {@code ui/ui.png} atlas.
 *
 * <p>Sprite coordinates are raw pixel rectangles inside the source PNG, exactly
 * as vanilla hard-codes them (widgets.png buttons/hotbar/selector, icons.png
 * hearts/food/armour, container/*.png slots and panel art, the title panorama).
 * {@link #apply} converts a rect to the normalized UV pair a
 * {@link UILayer.UIElement} expects, so call sites never hand-compute UVs.</p>
 *
 * <p>Loading is best-effort: when a PNG is missing the id stays 0 and the
 * element falls back to the flat colour it was created with. Nothing in this
 * file touches OpenGL until {@link #loadAll()} is called on the GL thread.</p>
 */
public final class MCGui {

    /** Pixel rectangle inside a GUI texture. */
    public static final class Rect {
        public final int x, y, w, h;

        public Rect(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private static final String GUI = "src/main/resources/assets/minecraft/textures/gui/";

    // ── Texture ids (0 when the asset is unavailable) ──
    public static int WIDGETS;
    public static int ICONS;
    public static int INVENTORY;
    public static int CRAFTING_TABLE;
    public static int FURNACE;
    public static int CHEST;
    public static int CREATIVE_INVENTORY;
    public static int BARS;
    public static int TOASTS;
    public static int OPTIONS_BACKGROUND;
    public static int PANORAMA;
    public static int TITLE_LOGO;
    public static int WORLD_SELECTION;
    /** Seamless MCSM parchment grain the HUD panels are printed on. */
    public static int MCSM_PARCHMENT;
    /**
     * The Story Mode UI atlas: wordmark plus the menu row plates, cut from the
     * shipped MCSM menu sheet into fixed 256x256 rects so {@link #apply} can
     * compute exact UVs (the raw sheet is 512x256 and its sprites sit off-grid).
     */
    public static int MCSM_UI;
    /** Wordmark inside {@link #MCSM_UI}. */
    public static final Rect MCSM_LOGO = new Rect(0, 0, 196, 64);
    /** Selected / hovered menu row: the dark plate. */
    public static final Rect MCSM_ROW_HOT = new Rect(0, 68, 185, 27);
    /** Idle menu row: the plain grey plate. */
    public static final Rect MCSM_ROW = new Rect(0, 104, 181, 23);
    /**
     * Story Mode wordmark as a standalone image. Its sprite rectangle is read
     * from the PNG at load time ({@link UIManager#loadTextureWithBounds}), so
     * the UVs always match the art whatever padding the crop carries.
     */
    public static int MCSM_TITLE;
    /** Opaque content box of {@link #MCSM_TITLE}, in its own pixels. */
    public static Rect MCSM_TITLE_RECT = new Rect(0, 0, 1, 1);
    /** Story Mode pause backdrop (warm cavern art). */
    public static int MCSM_PAUSE_BG;
    /** Story Mode menu/loading backdrop (torch-lit cavern). */
    public static int MCSM_MENU_BG;

    // ── widgets.png (256x256) ──
    public static final Rect HOTBAR = new Rect(0, 0, 182, 22);
    public static final Rect SELECTOR = new Rect(0, 22, 24, 24);
    /** Greyed-out button, used for unavailable menu rows. */
    public static final Rect BUTTON_DISABLED = new Rect(0, 46, 200, 20);
    public static final Rect BUTTON = new Rect(0, 66, 200, 20);
    public static final Rect BUTTON_HOVER = new Rect(0, 86, 200, 20);

    // ── icons.png (256x256) ──
    public static final Rect CROSSHAIR = new Rect(0, 0, 15, 15);
    public static final Rect HEART_EMPTY = new Rect(16, 0, 9, 9);
    public static final Rect HEART_HALF = new Rect(25, 0, 9, 9);
    public static final Rect HEART_FULL = new Rect(34, 0, 9, 9);
    // Hunger shanks live on the second icon row (y=27), NOT on the heart row:
    // sampling y=0 here returned the empty/half/full HEART sprites, which is
    // why the food bar looked like bones. Verified against the shipped atlas
    // (pixel dump): 52 = filled meat shank, 61 = the same shank cut in half,
    // 16 = the empty (black silhouette) container. 70/79 are the pale
    // hunger-effect variants, not the empty state — using 70 as "empty" made an
    // empty bar look full.
    public static final Rect FOOD_FULL = new Rect(52, 27, 9, 9);
    public static final Rect FOOD_HALF = new Rect(61, 27, 9, 9);
    public static final Rect FOOD_EMPTY = new Rect(16, 27, 9, 9);

    // ── icons.png XP-style bar (used for loading progress) ──
    /** 182x5 empty trough. */
    public static final Rect XP_BACKGROUND = new Rect(0, 64, 182, 5);
    /** 182x5 green fill; clip the U width to the progress fraction. */
    public static final Rect XP_FILL = new Rect(0, 69, 182, 5);
    public static final Rect ARMOR_EMPTY = new Rect(16, 9, 9, 9);
    public static final Rect ARMOR_HALF = new Rect(25, 9, 9, 9);
    public static final Rect ARMOR_FULL = new Rect(34, 9, 9, 9);

    // ── container/inventory.png (256x256) ──
    /** The 18x18 sunken slot used by the vanilla inventory pane. */
    public static final Rect SLOT = new Rect(7, 83, 18, 18);
    public static final Rect ARMOR_SLOT = new Rect(7, 7, 18, 18);

    // ── container panels (native GUI sizes) ──
    public static final Rect INVENTORY_PANEL = new Rect(0, 0, 176, 166);
    public static final Rect CRAFTING_PANEL = new Rect(0, 0, 176, 166);
    public static final Rect FURNACE_PANEL = new Rect(0, 0, 176, 166);
    public static final Rect CHEST_PANEL = new Rect(0, 0, 176, 222);

    // ── container/furnace.png progress sprites ──
    /** Lit flame, clipped bottom-up by burn progress. */
    public static final Rect FURNACE_FLAME = new Rect(176, 0, 14, 14);
    /** Smelting arrow, clipped left-to-right by cook progress. */
    public static final Rect FURNACE_ARROW = new Rect(176, 14, 24, 17);

    private static boolean loaded;

    private MCGui() {}

    /**
     * Loads the MC GUI textures once. Call on the GL thread. Missing files are
     * skipped so a trimmed resource pack still boots with flat-colour chrome.
     */
    public static synchronized void loadAll() {
        if (loaded) return;
        loaded = true;
        WIDGETS = load("widgets.png", false);
        ICONS = load("icons.png", false);
        INVENTORY = load("container/inventory.png", false);
        CRAFTING_TABLE = load("container/crafting_table.png", false);
        FURNACE = load("container/furnace.png", false);
        CHEST = load("container/generic_54.png", false);
        CREATIVE_INVENTORY = load("container/creative_inventory/tab_inventory.png", false);
        BARS = load("bars.png", false);
        TOASTS = load("toasts.png", false);
        // 16x16 dirt, tiled behind menus and dialogs exactly like vanilla.
        OPTIONS_BACKGROUND = load("options_background.png", true);
        PANORAMA = load("title/background/panorama_0.png", false);
        TITLE_LOGO = load("title/minecraft.png", false);
        WORLD_SELECTION = load("world_selection.png", false);
        // Generated by tools/gen_mcsm_ui.py; repeats so panels keep the
        // paper grain at native pixel density.
        MCSM_PARCHMENT = load("mcsm_parchment.png", true);
        // Story Mode art shipped with the game (Season 3 branding).
        MCSM_UI = load("mcsm_ui.png", false);
        int[] bounds = new int[4];
        MCSM_TITLE = loadWithBounds("title/mcsm_title.png", bounds);
        if (MCSM_TITLE != 0) {
            MCSM_TITLE_RECT = new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
        }
        MCSM_PAUSE_BG = load("mcsm_pause_bg.png", false);
        MCSM_MENU_BG = load("mcsm_menu_bg.png", false);
    }

    /** Loads a standalone image and keeps its opaque content box for the UVs. */
    private static int loadWithBounds(String relative, int[] boundsOut) {
        try {
            return UIManager.loadTextureWithBounds(GUI + relative, false, boundsOut);
        } catch (RuntimeException e) {
            System.err.println("Note: MC GUI asset missing: " + relative);
            return 0;
        }
    }

    private static int load(String relative, boolean repeat) {
        try {
            return UIManager.loadTexture(GUI + relative, repeat);
        } catch (RuntimeException e) {
            System.err.println("Note: MC GUI asset missing: " + relative);
            return 0;
        }
    }

    /** Points an element at a sprite, sized in the element's own quad space. */
    public static void apply(UILayer.UIElement element, int textureId, Rect sprite) {
        if (element == null || textureId == 0 || sprite == null) return;
        element.textureId = textureId;
        element.textureType = 0;
        element.tiled = false;
        element.uvOffset.set(sprite.x / 256f, sprite.y / 256f);
        element.uvScale.set(sprite.w / 256f, sprite.h / 256f);
    }

    /** Normalized UV pair for a sprite (u, v, scaleU, scaleV). */
    public static Vector4f uv(Rect sprite) {
        return new Vector4f(sprite.x / 256f, sprite.y / 256f,
                sprite.w / 256f, sprite.h / 256f);
    }

    /** A normalized UV offset vector for a sprite. */
    public static Vector2f uvOffset(Rect sprite) {
        return new Vector2f(sprite.x / 256f, sprite.y / 256f);
    }

    /** A normalized UV scale vector for a sprite. */
    public static Vector2f uvScale(Rect sprite) {
        return new Vector2f(sprite.w / 256f, sprite.h / 256f);
    }

    /**
     * Styles an element as a vanilla slot: inventory.png's sunken 18x18 frame,
     * either stretched across the caller's click area or centred inside it.
     */
    public static void slot(UILayer.UIElement element) {
        apply(element, INVENTORY != 0 ? INVENTORY : ICONS, SLOT);
    }

    /** Styles an element as a vanilla dirt-backed dialog panel. */
    public static void panel(UILayer.UIElement element) {
        if (element == null || OPTIONS_BACKGROUND == 0) return;
        element.textureId = OPTIONS_BACKGROUND;
        element.textureType = 0;
        element.tiled = true;
        element.tileW = 16f;
        element.tileH = 16f;
        element.uvOffset.set(0f, 0f);
        element.uvScale.set(1f, 1f);
    }

    /**
     * Points an element at a sprite of a standalone (non-atlas) texture: the
     * UVs are the sprite rectangle divided by that texture's own size, not by
     * the 256x256 GUI-sheet constant.
     */
    public static void sprite(UILayer.UIElement element, int textureId, Rect sprite) {
        if (element == null || textureId == 0 || sprite == null) return;
        Vector2i size = UIManager.getTextureSize(textureId);
        element.textureId = textureId;
        element.textureType = 0;
        element.tiled = false;
        element.uvOffset.set(sprite.x / (float) size.x, sprite.y / (float) size.y);
        element.uvScale.set(sprite.w / (float) size.x, sprite.h / (float) size.y);
    }

    /** UV pair (offset + scale) for a sprite of a standalone texture. */
    public static Vector4f spriteUV(int textureId, Rect sprite) {
        Vector2i size = UIManager.getTextureSize(textureId);
        return new Vector4f(sprite.x / (float) size.x, sprite.y / (float) size.y,
                sprite.w / (float) size.x, sprite.h / (float) size.y);
    }

    /**
     * Points an element at a whole texture (no atlas UV maths), stretched to
     * the element's quad. Used by the Story Mode art, which ships as complete
     * images rather than sprites inside a sheet.
     */
    public static void full(UILayer.UIElement element, int textureId) {
        if (element == null || textureId == 0) return;
        element.textureId = textureId;
        element.textureType = 0;
        element.tiled = false;
        element.uvOffset.set(0f, 0f);
        element.uvScale.set(1f, 1f);
    }

    /**
     * Styles an element as a sheet of MCSM parchment: the generated warm grain
     * tiles across the caller's quad while the element's own colour tints it to
     * the panel shade it needs. Falling back to the flat colour keeps a trimmed
     * resource pack usable.
     */
    public static void mcsmPanel(UILayer.UIElement element) {
        if (element == null || MCSM_PARCHMENT == 0) return;
        element.textureId = MCSM_PARCHMENT;
        element.textureType = 0;
        element.tiled = true;
        element.tileW = 16f;
        element.tileH = 16f;
        element.uvOffset.set(0f, 0f);
        element.uvScale.set(1f, 1f);
    }

    /** Styles an element as a vanilla widget.png button (hover swaps sprite). */
    public static void button(UILayer.UIElement element, boolean hover) {
        apply(element, WIDGETS, hover ? BUTTON_HOVER : BUTTON);
    }

    /**
     * Clips a sprite to a left-to-right progress fraction (XP bar, smelting
     * arrow). The caller owns the element position and height; set the width to
     * {@code fullWidth * progress} so the pixels keep their scale.
     */
    public static void applyProgressFill(UILayer.UIElement element, Rect sprite, float progress) {
        if (element == null || sprite == null) return;
        float p = Math.max(0f, Math.min(1f, progress));
        element.textureType = 0;
        element.tiled = false;
        element.uvOffset.set(sprite.x / 256f, sprite.y / 256f);
        element.uvScale.set(sprite.w / 256f * p, sprite.h / 256f);
    }

    /**
     * Clips a sprite to a bottom-up progress fraction (furnace flame). The
     * caller positions the element at the bottom of the full sprite area.
     */
    public static void applyFlameFill(UILayer.UIElement element, Rect sprite, float progress) {
        if (element == null || sprite == null) return;
        float p = Math.max(0f, Math.min(1f, progress));
        element.textureType = 0;
        element.tiled = false;
        element.uvOffset.set(sprite.x / 256f,
                (sprite.y + sprite.h * (1f - p)) / 256f);
        element.uvScale.set(sprite.w / 256f, sprite.h / 256f * p);
    }

    /** True once {@link #loadAll()} has found the core widget atlas. */
    public static boolean available() {
        return WIDGETS != 0 && ICONS != 0;
    }
}
