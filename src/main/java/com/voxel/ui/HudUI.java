package com.voxel.ui;

import com.voxel.Main;
import com.voxel.camera.CameraController;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.Entity;
import com.voxel.entity.EntityManager;
import com.voxel.game.FurnaceManager;
import com.voxel.game.ChestManager;
import com.voxel.crafting.CraftingManager;
import com.voxel.game.GameContext;
import com.voxel.game.GameContext.ActiveUI;
import com.voxel.game.ItemDefinitions;
import com.voxel.game.ItemDefinitions.ItemDefinition;
import com.voxel.game.ItemDefinitions.ItemStack;
import com.voxel.game.PlayerInventory;
import com.voxel.utils.BiomeManager;
import com.voxel.utils.FixedPoint;
import com.voxel.utils.TextureManager;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;

/**
 * Owns the HUD / inventory / furnace / chest / crafting-table UIs.
 * Equivalent to the buildInventoryUi + setupUi + tryLoadUi/FontTexture + updateInventoryUi +
 * handleFurnaceSlotClick + handleChestSlotClick + showSelectedItemName + updateWindowTitle code
 * that previously lived in Main.java.
 *
 * All UI element fields are owned here and visible to Main via public accessors if needed.
 * Inventory slot clicks delegate straight through to PlayerInventory / FurnaceManager / ChestManager.
 */
public class HudUI {
    public final List<UILayer> uiLayers = new ArrayList<>();
    public UIManager uiManager;

    public int uiTextureId = 0;
    public Vector2i uiTextureSize = new Vector2i(1, 1);
    public int fontTextureId = 0;
    public Vector2i fontTextureSize = new Vector2i(1, 1);
    public int loadingTextureId = 0;
    public int menuBackgroundTextureId = 0;
    /** Dark-mode panorama variant of the menu backdrop. */
    public int menuBackgroundDarkTextureId = 0;
    // Compact top-right loading-popup panel (loading_popup.png, 256x64).
    public int loadingPopupTextureId = 0;

    public UILayer.UIElement crosshairElement;

    // Dedicated UI layers. The static layer holds rarely-changing chrome
    // (hotbar, hearts, inventory panels); the dynamic layer holds per-frame
    // moving things (the virtual cursor, cinematic overlays, interaction
    // billboards) and is added LAST so it renders on top of everything.
    public UILayer staticLayer;
    public UILayer dynamicLayer;

    // ── Cinematic overlays (movie mode) ──
    public UILayer.UIElement cineBarTop, cineBarBottom;
    public UILayer.UIElement cineFadeQuad;      // fullscreen black/red fade
    public UILayer.UITextElement cineTitleText, cineSubtitleText;
    public UILayer.UITextElement cineSkipHint;      // "ESC to skip" during scenes
    private double lowHealthPulseTime = 0;

    // ── MCSM interaction billboards ("click here" markers) ──
    // Procedurally generated exclamation-point glyph (white with glow).
    public int cursorTextureId = 0;
    // Virtual cursor asset (ui/cursor.png) — the in-world pointer reticle.
    public com.voxel.game.InteractionBillboardSystem billboards;
    private static final int MAX_BILLBOARDS = 16;
    private static final int MAX_MARKER_ACTIONS = 4;
    /** Quads per marker: 4 black square-outline strips + 4 white edges +
     *  black/white diagonal + black/white stem. */
    private static final int MARKER_PART_COUNT = 12;
    // Part indices (draw order matters: black beneath white).
    private static final int P_BL = 0, P_BR = 1, P_BT = 2, P_BB = 3;
    private static final int P_WL = 4, P_WR = 5, P_WT = 6, P_WB = 7;
    private static final int P_BDIA = 8, P_WDIA = 9, P_BSTEM = 10, P_WSTEM = 11;
    /** Grey outline width on both sides of every white line. */
    private static final float MARKER_OUTLINE = 1f;
    public final UILayer.UIElement[][] markerParts = new UILayer.UIElement[MAX_BILLBOARDS][MARKER_PART_COUNT];
    public final UILayer.UITextElement[] markerNameTexts = new UILayer.UITextElement[MAX_BILLBOARDS];
    public final UILayer.UITextElement[][] markerActionTexts = new UILayer.UITextElement[MAX_BILLBOARDS][MAX_MARKER_ACTIONS];

    public UILayer.UIElement hotbarActiveElement;
    public UILayer.UIElement inventoryPanelElement;
    public UILayer.UIElement carriedItemElement;
    public UILayer.UITextElement itemNameElement;
    public final UILayer.UIElement[] slotBackgrounds = new UILayer.UIElement[Main.INVENTORY_SIZE];
    public final UILayer.UIElement[] slotItemElements     = new UILayer.UIElement[Main.INVENTORY_SIZE];
    public final UILayer.UIElement[] slotCountBars        = new UILayer.UIElement[Main.INVENTORY_SIZE];
    public final UILayer.UIElement[] slotCountDigit1      = new UILayer.UIElement[Main.INVENTORY_SIZE];
    public final UILayer.UIElement[] slotCountDigit2      = new UILayer.UIElement[Main.INVENTORY_SIZE];

    public final UILayer.UIElement[] crafting3x3SlotBackgrounds = new UILayer.UIElement[9];
    public final UILayer.UIElement[] crafting3x3SlotItems       = new UILayer.UIElement[9];
    public UILayer.UIElement craftingTableBg;
    public UILayer.UIElement craftingButton;
    public UILayer.UIElement craftingButtonItem;
    public UILayer.UITextElement craftingButtonText;

    public UILayer.UIElement furnacePanelBg;
    public UILayer.UIElement furnaceInputBg, furnaceFuelBg, furnaceOutputBg;
    public UILayer.UIElement furnaceInputItem, furnaceFuelItem, furnaceOutputItem;
    public UILayer.UIElement furnaceProgressBar, furnaceFuelBar;
    public UILayer.UITextElement furnaceFuelText;

    public UILayer.UIElement chestPanelBg;
    public UILayer.UITextElement chestTitle;
    public static final int CHEST_COLS = 9;
    public static final int CHEST_ROWS = 3;
    private static final int CHEST_SLOT_W = 64;
    private static final int CHEST_SLOT_H = 64;
    public final UILayer.UIElement[] chestSlotBackgrounds = new UILayer.UIElement[ChestManager.CHEST_SLOTS];
    public final UILayer.UIElement[] chestSlotItems       = new UILayer.UIElement[ChestManager.CHEST_SLOTS];
    public final UILayer.UIElement[] chestCountBars       = new UILayer.UIElement[ChestManager.CHEST_SLOTS];
    public final UILayer.UIElement[] chestCountDigit1     = new UILayer.UIElement[ChestManager.CHEST_SLOTS];
    public final UILayer.UIElement[] chestCountDigit2     = new UILayer.UIElement[ChestManager.CHEST_SLOTS];

    // ── Creative item picker ──
    /** 10 columns x 6 rows of picker slots (fits 720p without scrolling for most filters). */
    public static final int CREATIVE_COLS = 10;
    public static final int CREATIVE_ROWS = 6;
    public UILayer.UIElement creativePanelBg;
    public UILayer.UITextElement creativeSearchText;
    public UILayer.UITextElement creativeCountText;
    public UILayer.UIElement creativeCloseBtn;
    public UILayer.UITextElement creativeCloseText;
    public final UILayer.UIElement[] creativeSlotBackgrounds = new UILayer.UIElement[CREATIVE_COLS * CREATIVE_ROWS];
    public final UILayer.UIElement[] creativeSlotItems       = new UILayer.UIElement[CREATIVE_COLS * CREATIVE_ROWS];
    /** Current item id shown in each slot (updated every refresh). */
    public final String[] creativeSlotItemIds = new String[CREATIVE_COLS * CREATIVE_ROWS];

    public final UILayer.UIElement[] playerHearts = new UILayer.UIElement[10];
    public final UILayer.UIElement[] heartBases   = new UILayer.UIElement[10];

    public UILayer.UITextElement commandTextElement;
    public UILayer.UITextElement statusTextElement;
    public UILayer.UITextElement gogglesOverlayElement;

    // ── Tutorial World zone title-card popup ──
    public UILayer.UIElement tutorialPopupBg;
    public UILayer.UITextElement tutorialTitleElement;
    public UILayer.UITextElement tutorialSubtitleElement;

    // ── Map overlay elements ──
    public UILayer.UIElement mapPanelBg;
    public UILayer.UIElement mapZoomInBtn, mapZoomOutBtn, mapCenterBtn, mapResetBtn;
    public UILayer.UITextElement mapZoomInText, mapZoomOutText, mapCenterText, mapResetText;
    public UILayer.UITextElement mapCoordinateText;
    public UILayer.UITextElement mapControlsHelpText;

    // ── TV overlay elements ──
    public UILayer.UIElement tvOverlayBg;
    public UILayer.UITextElement tvChannelNameText;
    public UILayer.UITextElement tvContentText;
    public UILayer.UITextElement tvInstructionsText;

    // ── Spawn loading overlay ──
    // These are appended last so the overlay is composited above the normal HUD.
    public UILayer.UIElement menuTextPanel;      // translucent backdrop behind menu text
    public UILayer.UIElement spawnLoadingBackground;
    public UILayer.UIElement menuBackground;
    public UILayer.UIElement loadingPopupBackground; // top-right toast panel
    public UILayer.UITextElement spawnLoadingTitle;
    public UILayer.UITextElement spawnLoadingStatus;
    public UILayer.UITextElement spawnLoadingSpinner;

    // ── Structured menu controls ──
    public static final int MENU_BUTTON_COUNT = 8;
    public final UILayer.UIElement[] menuButtonBackgrounds = new UILayer.UIElement[MENU_BUTTON_COUNT];
    public final UILayer.UITextElement[] menuButtonLabels = new UILayer.UITextElement[MENU_BUTTON_COUNT];
    public UILayer.UITextElement menuSubtitle;
    public UILayer.UITextElement menuHint;

    // ── In-game pause menu ──
    public UILayer.UIElement pauseDimmer;
    public UILayer.UIElement pausePanel;
    public final UILayer.UIElement[] pauseButtonBackgrounds = new UILayer.UIElement[3];
    public final UILayer.UITextElement[] pauseButtonLabels = new UILayer.UITextElement[3];
    public UILayer.UITextElement pauseTitle;
    public UILayer.UITextElement pauseHint;

    public double itemNameDisplayUntil = 0.0;
    public boolean inventoryUiDirty = true;

    public boolean prevInventoryOpenForUi = false;
    public boolean prevCommandModeForUi = false;
    public boolean prevMapOpenForUi = false;
    public int prevSelectedSlot = -1;
    public float prevHealth = -1;

    public Vector4f uvHeartFull  = new Vector4f(99, 2, 7, 7);
    public Vector4f uvHeartHalf  = new Vector4f(108, 2, 7, 7);
    public Vector4f uvHeartEmpty = new Vector4f(90, 2, 7, 7);
    public Vector4f uvHeartBase  = new Vector4f(62, 1, 9, 9);

    public double lastTitleUpdate = 0.0;

    private final GameContext ctx;
    private final Main main;
    private final CameraController camera;
    private final PlayerInventory playerInventory;
    private final TextureManager textureManager;
    private final ItemDefinitions itemDefinitions;
    private final BiomeManager biomeManager;

    public HudUI(GameContext ctx, Main main, CameraController camera,
                 PlayerInventory playerInventory, TextureManager textureManager,
                 ItemDefinitions itemDefinitions, BiomeManager biomeManager) {
        this.ctx = ctx;
        this.main = main;
        this.camera = camera;
        this.playerInventory = playerInventory;
        this.textureManager = textureManager;
        this.itemDefinitions = itemDefinitions;
        this.biomeManager = biomeManager;
        // MCSM interaction billboards — needs Main for camera/projection helpers.
        this.billboards = new com.voxel.game.InteractionBillboardSystem(ctx, main);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────────

    public void setupUi() { setup(main.width, main.height); }

    public void setup(int width, int height) {
        uiManager = new UIManager(width, height);
        // Two dedicated layers: static chrome (hotbar/hearts/inventory) and
        // dynamic per-frame elements (cursor, cinematic overlays, billboards).
        // dynamicLayer is added LAST so it renders on top of everything.
        staticLayer = new UILayer();
        dynamicLayer = new UILayer();

        // --- Static layer: inventory panel + hotbar/hearts/crafting chrome ---
        inventoryPanelElement = new UILayer.UIElement(
            new Vector2f(Main.HOTBAR_X - 8, Main.HOTBAR_Y - 12),
            new Vector2f(Main.INVENTORY_PANEL_WIDTH, Main.INVENTORY_PANEL_HEIGHT),
            new Vector4f(0, 0, 0, 0.45f)
        );
        inventoryPanelElement.visible = false;
        staticLayer.addElement(inventoryPanelElement);

        // --- Dynamic layer: virtual cursor, cinematic overlays, billboards ---
        // The virtual cursor: a textured reticle (ui/cursor.png). Falls back to
        // a solid white quad if the asset fails to load.
        cursorTextureId = loadCursorTexture();
        crosshairElement = new UILayer.UIElement(
            new Vector2f(width / 2f - 12, height / 2f - 12),
            new Vector2f(24, 24),
            new Vector4f(1, 1, 1, 1)
        );
        crosshairElement.textureId = cursorTextureId;
        dynamicLayer.addElement(crosshairElement);

        // Cinematic overlays: letterbox bars, fullscreen fade, title cards.
        cineBarTop = new UILayer.UIElement(
            new Vector2f(0, 0), new Vector2f(width, 0), new Vector4f(0, 0, 0, 1));
        cineBarTop.visible = false;
        dynamicLayer.addElement(cineBarTop);
        cineBarBottom = new UILayer.UIElement(
            new Vector2f(0, height), new Vector2f(width, 0), new Vector4f(0, 0, 0, 1));
        cineBarBottom.visible = false;
        dynamicLayer.addElement(cineBarBottom);
        cineFadeQuad = new UILayer.UIElement(
            new Vector2f(0, 0), new Vector2f(width, height), new Vector4f(0, 0, 0, 0));
        cineFadeQuad.visible = false;
        dynamicLayer.addElement(cineFadeQuad);
        cineTitleText = new UILayer.UITextElement(
            new Vector2f(width / 2f - 200, height * 0.68f), "", 3.0f,
            new Vector4f(0.95f, 0.9f, 0.75f, 0), fontTextureId);
        cineTitleText.visible = false;
        dynamicLayer.addElement(cineTitleText);
        cineSubtitleText = new UILayer.UITextElement(
            new Vector2f(width / 2f - 160, height * 0.68f + 40), "", 1.5f,
            new Vector4f(0.85f, 0.85f, 0.85f, 0), fontTextureId);
        cineSubtitleText.visible = false;
        dynamicLayer.addElement(cineSubtitleText);
        cineSkipHint = new UILayer.UITextElement(
            new Vector2f(width / 2f - 55f, height * 0.82f), "", 1.3f,
            new Vector4f(0.8f, 0.8f, 0.8f, 0), fontTextureId);
        cineSkipHint.visible = false;
        dynamicLayer.addElement(cineSkipHint);

        // Point-and-click prompts, MCSM-style: a hollow square on the target,
        // a 45° elbow line out of its corner, then a vertical stem running to
        // the name + action text list. Built from solid colour quads (no
        // textures) so the lines stay pixel-crisp at any resolution.
        // Every white line gets a pure-black backing quad extending 2px on
        // both sides (the outline). Parts per marker, added in draw order so
        // black always renders beneath white:
        // [0..3] black square-outline strips (top/bottom/left/right)
        // [4..7] white square edges (L,R,T,B)
        // [8] black diagonal backing   [9] white diagonal
        // [10] black stem backing      [11] white stem
        for (int i = 0; i < MAX_BILLBOARDS; i++) {
            for (int p = 0; p < MARKER_PART_COUNT; p++) {
                UILayer.UIElement part = new UILayer.UIElement(
                    new Vector2f(-100, -100), new Vector2f(2, 2),
                    new Vector4f(1, 1, 1, 0)); // textureId 0 = solid quad
                part.visible = false;
                dynamicLayer.addElement(part);
                markerParts[i][p] = part;
            }
            UILayer.UITextElement name = new UILayer.UITextElement(
                new Vector2f(-100, -100), "", 1.3f,
                new Vector4f(1, 1, 1, 0), fontTextureId);
            name.visible = false;
            dynamicLayer.addElement(name);
            markerNameTexts[i] = name;
            for (int j = 0; j < MAX_MARKER_ACTIONS; j++) {
                UILayer.UITextElement act = new UILayer.UITextElement(
                    new Vector2f(-100, -100), "", 1.2f,
                    new Vector4f(1, 1, 1, 0), fontTextureId);
                act.visible = false;
                dynamicLayer.addElement(act);
                markerActionTexts[i][j] = act;
            }
        }

        // Load shared UI assets, then build the static inventory chrome onto
        // the static layer.
        tryLoadUiTexture();
        tryLoadFontTexture();
        tryLoadLoadingTexture();
        tryLoadLoadingPopupTexture();
        buildInventoryUi(staticLayer);

        // Order matters: static first, dynamic on top.
        uiLayers.add(staticLayer);
        uiLayers.add(dynamicLayer);
    }

    public void tryLoadUiTexture() {
        try {
            java.io.File uiFile = new java.io.File("src/main/resources/ui/ui.png");
            if (uiFile.exists()) {
                uiTextureId = UIManager.loadTexture(uiFile.getPath());
                uiTextureSize = UIManager.getTextureSize(uiTextureId);
            }
        } catch (Exception e) {
            System.err.println("Note: ui.png not found at src/main/resources/ui/");
        }
    }

    /**
     * Load the virtual-cursor asset (ui/cursor.png). If the PNG is missing it
     * is generated once on disk (an MCSM-style pointer: a glowing arrow tip
     * inside a broken ring reticle) so the asset is real, editable, and
     * reloadable. Returns a GL texture id, or 0 if loading fails (the
     * crosshair then falls back to a solid quad).
     */
    public int loadCursorTexture() {
        java.io.File cursorFile = new java.io.File("src/main/resources/ui/cursor.png");
        if (!cursorFile.exists()) {
            generateCursorAsset(cursorFile);
        }
        try {
            int tex = UIManager.loadTexture(cursorFile.getPath());
            if (tex != 0) System.out.println("[UI] Loaded virtual cursor asset: " + cursorFile.getPath());
            return tex;
        } catch (Exception e) {
            System.err.println("Note: cursor.png could not be loaded; using solid quad");
            return 0;
        }
    }

    /**
     * Procedurally write a 24x24 cursor PNG: a bold black square center with
     * a thick bright white outline — a high-contrast crosshair that reads
     * against any background. The drawn content is an 8x8 black core framed
     * by a 2px white ring, centered in the 24x24 canvas.
     */
    private void generateCursorAsset(java.io.File out) {
        try {
            int size = 24;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            // 8x8 black core at pixels 8..15, framed by a 2px white ring
            // (pixels 6..17 = 12x12 outline square).
            int coreMin = 8, coreMax = 15;             // 8px black core
            int outlineMin = coreMin - 2, outlineMax = coreMax + 2; // 2px white ring
            // White outline (filled square, then overdrawn by black core).
            g.setColor(new java.awt.Color(255, 255, 255, 255));
            g.fillRect(outlineMin, outlineMin,
                outlineMax - outlineMin + 1, outlineMax - outlineMin + 1);
            // Black center core.
            g.setColor(new java.awt.Color(0, 0, 0, 255));
            g.fillRect(coreMin, coreMin, coreMax - coreMin + 1, coreMax - coreMin + 1);
            g.dispose();
            out.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "PNG", out);
            System.out.println("[UI] Generated virtual cursor asset: " + out.getPath());
        } catch (Exception e) {
            System.err.println("Note: failed to generate cursor.png: " + e.getMessage());
        }
    }

    public void tryLoadFontTexture() {
        try {
            java.io.File fontFile = new java.io.File("src/main/resources/assets/minecraft/textures/font/ascii.png");
            if (fontFile.exists()) {
                fontTextureId = UIManager.loadTexture(fontFile.getPath());
                fontTextureSize = UIManager.getTextureSize(fontTextureId);
            }
        } catch (Exception e) {
            System.err.println("Note: ascii.png not found");
        }
    }

    public void tryLoadLoadingTexture() {
        try {
            java.io.File loadingFile = new java.io.File("src/main/resources/ui/loading.png");
            if (loadingFile.exists()) {
                loadingTextureId = UIManager.loadTexture(loadingFile.getPath());
            } else {
                System.err.println("Note: loading.png not found; using the bright fallback color");
            }

            // Load the custom menu background (light + dark panorama variants)
            java.io.File menuBgFile = new java.io.File("src/main/resources/ui/menu_background.png");
            if (menuBgFile.exists()) {
                menuBackgroundTextureId = UIManager.loadTexture(menuBgFile.getPath());
            }
            java.io.File menuBgDarkFile = new java.io.File("src/main/resources/ui/menu_background_dark.png");
            if (menuBgDarkFile.exists()) {
                menuBackgroundDarkTextureId = UIManager.loadTexture(menuBgDarkFile.getPath());
            } else {
                menuBackgroundDarkTextureId = menuBackgroundTextureId;
            }
        } catch (Exception e) {
            System.err.println("Note: loading.png could not be loaded; using the bright fallback color");
        }
    }

    public void tryLoadLoadingPopupTexture() {
        try {
            java.io.File popupFile = new java.io.File("src/main/resources/ui/loading_popup.png");
            if (popupFile.exists()) {
                loadingPopupTextureId = UIManager.loadTexture(popupFile.getPath());
            } else {
                System.err.println("Note: loading_popup.png not found; using the flat fallback panel");
            }
        } catch (Exception e) {
            System.err.println("Note: loading_popup.png could not be loaded; using the flat fallback panel");
        }
    }

    public void buildInventoryUi(UILayer layer) {
        float halfU = 0.5f / uiTextureSize.x;
        float halfV = 0.5f / uiTextureSize.y;
        float uScaleInset = (float) (Main.SLOT_TEX_W - 1) / uiTextureSize.x;
        float vScaleInset = (float) (Main.SLOT_TEX_H - 1) / uiTextureSize.y;

        // 3x3 crafting table grid
        float ctGridW = 3 * (Main.SLOT_W + 8) - 8;
        float ctGridH = 3 * Main.SLOT_H;
        int ctX = (int)((main.width - ctGridW) / 2);
        int ctY = (int)((main.height - ctGridH) / 2);

        craftingTableBg = new UILayer.UIElement(
            new Vector2f(ctX - 10, ctY - 10),
            new Vector2f(ctGridW + 20, ctGridH + 20),
            new Vector4f(0.65f, 0.5f, 0.35f, 0.4f)
        );
        craftingTableBg.visible = false;
        layer.addElement(craftingTableBg);

        for (int i = 0; i < 9; i++) {
            int r = i / 3;
            int c = i % 3;
            float cx = ctX + c * (Main.SLOT_W + 8);
            float cy = ctY + r * Main.SLOT_H;

            UILayer.UIElement bg = new UILayer.UIElement(
                new Vector2f(cx, cy),
                new Vector2f(Main.SLOT_W, Main.SLOT_H),
                new Vector4f(0.9f, 0.9f, 0.9f, 1)
            );
            final int slotIndex = i;
            bg.onClick = () -> { playerInventory.handleCrafting3x3SlotClick(slotIndex); inventoryUiDirty = true; };
            bg.visible = false;
            crafting3x3SlotBackgrounds[i] = bg;
            layer.addElement(bg);

            UILayer.UIElement itemEl = new UILayer.UIElement(
                new Vector2f(cx + 24, cy + 16),
                new Vector2f(40, 40),
                new Vector4f(0, 0, 0, 0)
            );
            itemEl.visible = false;
            crafting3x3SlotItems[i] = itemEl;
            layer.addElement(itemEl);
        }

        // MCSM-style action button. It lives beside the 3x3 grid and is only
        // made visible when the current ingredient pattern has a valid result.
        float craftButtonX = ctX + ctGridW + 28;
        float craftButtonY = ctY + ctGridH / 2f - 28;
        craftingButton = new UILayer.UIElement(
            new Vector2f(craftButtonX, craftButtonY),
            new Vector2f(156, 56),
            new Vector4f(0.12f, 0.62f, 0.48f, 1.0f)
        );
        craftingButton.onClick = () -> {
            boolean crafted = (ctx.activeUI == ActiveUI.SURFACE_CRAFTING)
                ? playerInventory.craftSurface2x2()
                : playerInventory.craft3x3();
            if (crafted) inventoryUiDirty = true;
        };
        craftingButton.visible = false;
        layer.addElement(craftingButton);

        craftingButtonText = new UILayer.UITextElement(
            new Vector2f(craftButtonX + 58, craftButtonY + 17),
            "CRAFT",
            1.8f,
            new Vector4f(1, 1, 1, 1),
            fontTextureId
        );
        craftingButtonText.visible = false;
        layer.addElement(craftingButtonText);

        craftingButtonItem = new UILayer.UIElement(
            new Vector2f(craftButtonX + 10, craftButtonY + 8),
            new Vector2f(40, 40),
            new Vector4f(1, 1, 1, 1)
        );
        craftingButtonItem.visible = false;
        layer.addElement(craftingButtonItem);

        // Inventory slots (4 columns × 5 rows)
        for (int index = 0; index < Main.INVENTORY_SIZE; index++) {
            int row = index % Main.HOTBAR_SIZE;
            int column = index / Main.HOTBAR_SIZE;
            float x = Main.HOTBAR_X + column * (Main.SLOT_W + 12);
            float y = Main.HOTBAR_Y + row * Main.SLOT_H;

            UILayer.UIElement background = new UILayer.UIElement(
                new Vector2f(x, y),
                new Vector2f(Main.SLOT_W, Main.SLOT_H),
                new Vector4f(column == 0 ? 1 : 0.95f, column == 0 ? 1 : 0.95f, column == 0 ? 1 : 0.95f, 1)
            );
            if (uiTextureId != 0) {
                background.textureId = uiTextureId;
                background.uvOffset = new Vector2f(halfU, column == 0 ? row * (float) Main.SLOT_TEX_H / uiTextureSize.y + halfV : halfV);
                background.uvScale = new Vector2f(uScaleInset, vScaleInset);
            }
            final int slotIndex = index;
            background.onClick = () -> { playerInventory.handleInventorySlotClick(slotIndex); inventoryUiDirty = true; };
            slotBackgrounds[index] = background;
            layer.addElement(background);

            UILayer.UIElement itemElement = new UILayer.UIElement(
                new Vector2f(x + 24, y + 16),
                new Vector2f(40, 40),
                new Vector4f(0, 0, 0, 0)
            );
            itemElement.visible = false;
            slotItemElements[index] = itemElement;
            layer.addElement(itemElement);

            UILayer.UIElement countBar = new UILayer.UIElement(
                new Vector2f(x + 12, y + Main.SLOT_H - 12),
                new Vector2f(0, 6),
                new Vector4f(1, 1, 1, 0.9f)
            );
            countBar.visible = false;
            slotCountBars[index] = countBar;
            layer.addElement(countBar);

            UILayer.UIElement digit1 = new UILayer.UIElement(
                new Vector2f(x + Main.SLOT_W - 32, y + Main.SLOT_H - 24),
                new Vector2f(16, 16),
                new Vector4f(1, 1, 1, 1)
            );
            digit1.visible = false;
            digit1.textureId = fontTextureId;
            slotCountDigit1[index] = digit1;
            layer.addElement(digit1);

            UILayer.UIElement digit2 = new UILayer.UIElement(
                new Vector2f(x + Main.SLOT_W - 18, y + Main.SLOT_H - 24),
                new Vector2f(16, 16),
                new Vector4f(1, 1, 1, 1)
            );
            digit2.visible = false;
            digit2.textureId = fontTextureId;
            slotCountDigit2[index] = digit2;
            layer.addElement(digit2);
        }

        hotbarActiveElement = new UILayer.UIElement(
            new Vector2f(Main.HOTBAR_X, Main.HOTBAR_Y + playerInventory.getSelectedSlot() * Main.SLOT_H),
            new Vector2f(Main.SLOT_W, Main.SLOT_H),
            new Vector4f(1, 1, 1, 1)
        );
        if (uiTextureId != 0) {
            hotbarActiveElement.textureId = uiTextureId;
            hotbarActiveElement.uvOffset = new Vector2f((22.0f + 0.5f) / uiTextureSize.x, halfV);
            hotbarActiveElement.uvScale = new Vector2f(uScaleInset, vScaleInset);
        }
        layer.addElement(hotbarActiveElement);

        carriedItemElement = new UILayer.UIElement(new Vector2f(0, 0), new Vector2f(28, 28), new Vector4f(0, 0, 0, 0));
        carriedItemElement.visible = false;
        layer.addElement(carriedItemElement);

        itemNameElement = new UILayer.UITextElement(
            new Vector2f(Main.HOTBAR_X + 100, Main.HOTBAR_Y - 40),
            "",
            2.5f,
            new Vector4f(1, 1, 1, 1),
            fontTextureId
        );
        itemNameElement.visible = false;
        layer.addElement(itemNameElement);

        // Hearts HUD
        for (int i = 0; i < 10; i++) {
            heartBases[i] = new UILayer.UIElement(
                new Vector2f(Main.HOTBAR_X + i * 30 - 3, Main.HOTBAR_Y - 30 - 3),
                new Vector2f(27, 27),
                new Vector4f(1, 1, 1, 1)
            );
            heartBases[i].textureId = uiTextureId;
            heartBases[i].visible = true;
            layer.addElement(heartBases[i]);

            playerHearts[i] = new UILayer.UIElement(
                new Vector2f(Main.HOTBAR_X + i * 30, Main.HOTBAR_Y - 30),
                new Vector2f(21, 21),
                new Vector4f(1, 1, 1, 1)
            );
            playerHearts[i].textureId = uiTextureId;
            playerHearts[i].visible = true;
            layer.addElement(playerHearts[i]);
        }

        // Furnace UI: derive the origin from the panel bounds instead of the
        // top-left hotbar. The old HOTBAR_Y - 160 anchor clipped the panel on
        // normal 720px windows and made the furnace appear off-center.
        int furnaceSlotW = Main.SLOT_W;
        int furnaceSlotH = Main.SLOT_H;
        int furnacePanelW = 4 * furnaceSlotW + 80;
        int furnacePanelH = 2 * furnaceSlotH + 60;
        int furnaceX = Math.max(8, (main.width - furnacePanelW) / 2);
        int furnaceY = Math.max(8, (main.height - furnacePanelH) / 2);

        furnacePanelBg = new UILayer.UIElement(
            new Vector2f(furnaceX - 10, furnaceY - 10),
            new Vector2f(furnacePanelW, furnacePanelH),
            new Vector4f(0.3f, 0.3f, 0.3f, 0.6f)
        );
        furnacePanelBg.visible = false;
        layer.addElement(furnacePanelBg);

        furnaceInputBg = new UILayer.UIElement(new Vector2f(furnaceX, furnaceY + furnaceSlotH / 2), new Vector2f(furnaceSlotW, furnaceSlotH), new Vector4f(0.9f, 0.9f, 0.9f, 1));
        furnaceInputBg.visible = false;
        furnaceInputBg.onClick = () -> { main.handleFurnaceSlotClick(0); inventoryUiDirty = true; };
        layer.addElement(furnaceInputBg);

        furnaceInputItem = new UILayer.UIElement(new Vector2f(furnaceX + 24, furnaceY + furnaceSlotH / 2 + 16), new Vector2f(40, 40), new Vector4f(0, 0, 0, 0));
        furnaceInputItem.visible = false;
        layer.addElement(furnaceInputItem);

        furnaceFuelBg = new UILayer.UIElement(new Vector2f(furnaceX, furnaceY + furnaceSlotH + furnaceSlotH / 2 + 12), new Vector2f(furnaceSlotW, furnaceSlotH), new Vector4f(0.85f, 0.85f, 0.7f, 1));
        furnaceFuelBg.visible = false;
        furnaceFuelBg.onClick = () -> { main.handleFurnaceSlotClick(1); inventoryUiDirty = true; };
        layer.addElement(furnaceFuelBg);

        furnaceFuelItem = new UILayer.UIElement(new Vector2f(furnaceX + 24, furnaceY + furnaceSlotH + furnaceSlotH / 2 + 28), new Vector2f(40, 40), new Vector4f(0, 0, 0, 0));
        furnaceFuelItem.visible = false;
        layer.addElement(furnaceFuelItem);

        furnaceFuelBar = new UILayer.UIElement(new Vector2f(furnaceX + furnaceSlotW + 12, furnaceY + furnaceSlotH + furnaceSlotH / 2 + 24), new Vector2f(0, 20), new Vector4f(1.0f, 0.5f, 0.0f, 0.8f));
        furnaceFuelBar.visible = false;
        layer.addElement(furnaceFuelBar);

        furnaceFuelText = new UILayer.UITextElement(new Vector2f(furnaceX + furnaceSlotW + 12, furnaceY + furnaceSlotH + furnaceSlotH / 2 + 26), "", 1.5f, new Vector4f(1, 1, 1, 1), fontTextureId);
        furnaceFuelText.visible = false;
        layer.addElement(furnaceFuelText);

        int outputX = furnaceX + 2 * furnaceSlotW + 30;
        furnaceOutputBg = new UILayer.UIElement(new Vector2f(outputX, furnaceY + furnaceSlotH / 2), new Vector2f(furnaceSlotW, furnaceSlotH), new Vector4f(0.9f, 0.8f, 0.7f, 1));
        furnaceOutputBg.visible = false;
        furnaceOutputBg.onClick = () -> { main.handleFurnaceSlotClick(2); inventoryUiDirty = true; };
        layer.addElement(furnaceOutputBg);

        furnaceOutputItem = new UILayer.UIElement(new Vector2f(outputX + 24, furnaceY + furnaceSlotH / 2 + 16), new Vector2f(40, 40), new Vector4f(0, 0, 0, 0));
        furnaceOutputItem.visible = false;
        layer.addElement(furnaceOutputItem);

        furnaceProgressBar = new UILayer.UIElement(new Vector2f(furnaceX + furnaceSlotW + 12, furnaceY + furnaceSlotH / 2 + furnaceSlotH / 2 - 8), new Vector2f(0, 16), new Vector4f(0.8f, 0.8f, 0.2f, 0.9f));
        furnaceProgressBar.visible = false;
        layer.addElement(furnaceProgressBar);

        // Chest UI: the vanilla single-chest layout is 9 columns × 3 rows.
        // Center it in the viewport so it is never clipped by the HUD's small
        // top-left hotbar, and leave a title strip above the slots.
        float chestGridW = CHEST_COLS * (CHEST_SLOT_W + 8) - 8;
        float chestGridH = CHEST_ROWS * CHEST_SLOT_H;
        int preferredChestX = Main.HOTBAR_X + Main.INVENTORY_PANEL_WIDTH + 24;
        int chestX = preferredChestX + chestGridW <= main.width
                ? preferredChestX
                : Math.max(8, (int) ((main.width - chestGridW) / 2f));
        int chestY = Math.max(64, (int) ((main.height - chestGridH) / 2f) + 22);

        chestPanelBg = new UILayer.UIElement(new Vector2f(chestX - 16, chestY - 52),
            new Vector2f(chestGridW + 32, chestGridH + 68),
            new Vector4f(0.10f, 0.07f, 0.045f, 0.94f));
        chestPanelBg.visible = false;
        layer.addElement(chestPanelBg);
        chestTitle = new UILayer.UITextElement(new Vector2f(chestX, chestY - 40),
            "CHEST", 2.0f, new Vector4f(1.0f, 0.82f, 0.42f, 1.0f), fontTextureId);
        chestTitle.visible = false;
        layer.addElement(chestTitle);

        for (int i = 0; i < ChestManager.CHEST_SLOTS; i++) {
            int row = i / CHEST_COLS;
            int col = i % CHEST_COLS;
            float cx = chestX + col * (CHEST_SLOT_W + 8);
            float cy = chestY + row * CHEST_SLOT_H;

            UILayer.UIElement bg = new UILayer.UIElement(            new Vector2f(cx, cy), new Vector2f(CHEST_SLOT_W, CHEST_SLOT_H), new Vector4f(0.85f, 0.7f, 0.55f, 1));
            final int slotIdx = i;
            bg.onClick = () -> { main.handleChestSlotClick(slotIdx); inventoryUiDirty = true; };
            bg.visible = false;
            chestSlotBackgrounds[i] = bg;
            layer.addElement(bg);

            UILayer.UIElement itemEl = new UILayer.UIElement(new Vector2f(cx + 12, cy + 12), new Vector2f(40, 40), new Vector4f(0, 0, 0, 0));
            itemEl.visible = false;
            chestSlotItems[i] = itemEl;
            layer.addElement(itemEl);

            UILayer.UIElement countBar = new UILayer.UIElement(new Vector2f(cx + 8, cy + CHEST_SLOT_H - 10), new Vector2f(0, 5), new Vector4f(1, 1, 1, 0.9f));
            countBar.visible = false;
            chestCountBars[i] = countBar;
            layer.addElement(countBar);

            UILayer.UIElement digit1 = new UILayer.UIElement(new Vector2f(cx + CHEST_SLOT_W - 26, cy + CHEST_SLOT_H - 22), new Vector2f(13, 13), new Vector4f(1, 1, 1, 1));
            digit1.visible = false;
            digit1.textureId = fontTextureId;
            chestCountDigit1[i] = digit1;
            layer.addElement(digit1);

            UILayer.UIElement digit2 = new UILayer.UIElement(new Vector2f(cx + CHEST_SLOT_W - 13, cy + CHEST_SLOT_H - 22), new Vector2f(13, 13), new Vector4f(1, 1, 1, 1));
            digit2.visible = false;
            digit2.textureId = fontTextureId;
            chestCountDigit2[i] = digit2;
            layer.addElement(digit2);
        }

        commandTextElement = new UILayer.UITextElement(new Vector2f(20, main.height - 40), "", 2.0f, new Vector4f(1, 1, 1, 1), fontTextureId);
        commandTextElement.visible = false;
        layer.addElement(commandTextElement);

        statusTextElement = new UILayer.UITextElement(new Vector2f(main.width / 2f - 200, main.height - 50), "", 2.0f, new Vector4f(1, 1, 0.5f, 1), fontTextureId);
        statusTextElement.charLineLimit = 40;
        statusTextElement.visible = false;
        layer.addElement(statusTextElement);

        // ── Tutorial World zone title-card popup (centered band) ──
        tutorialPopupBg = new UILayer.UIElement(
            new Vector2f(main.width / 2f - 360, main.height / 2f - 70),
            new Vector2f(720, 110),
            new Vector4f(0.05f, 0.06f, 0.10f, 0.72f)
        );
        tutorialPopupBg.visible = false;
        tutorialPopupBg.onClick = null;
        layer.addElement(tutorialPopupBg);

        tutorialTitleElement = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 160, main.height / 2f - 50), "", 3.0f, new Vector4f(1, 0.88f, 0.3f, 1), fontTextureId);
        tutorialTitleElement.charLineLimit = 40;
        tutorialTitleElement.visible = false;
        layer.addElement(tutorialTitleElement);

        tutorialSubtitleElement = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 330, main.height / 2f + 8), "", 1.5f, new Vector4f(1, 1, 1, 1), fontTextureId);
        tutorialSubtitleElement.charLineLimit = 55;
        tutorialSubtitleElement.visible = false;
        layer.addElement(tutorialSubtitleElement);

        // Goggles overlay: machine name + power state under the crosshair.
        gogglesOverlayElement = new UILayer.UITextElement(new Vector2f(main.width / 2f - 200, main.height / 2f + 14), "", 1.5f, new Vector4f(0.4f, 1, 0.9f, 1), fontTextureId);
        gogglesOverlayElement.charLineLimit = 40;
        gogglesOverlayElement.visible = false;
        layer.addElement(gogglesOverlayElement);

        // ── Creative item picker (full item grid, creative mode only) ──
        float cSlotW = 64, cSlotH = 64, cGap = 8;
        float cGridW = CREATIVE_COLS * cSlotW + (CREATIVE_COLS - 1) * cGap;
        float cGridH = CREATIVE_ROWS * cSlotH + (CREATIVE_ROWS - 1) * cGap;
        // Sit to the RIGHT of the survival inventory (which occupies the left
        // edge) instead of centering, so the two panes never overlap. On narrow
        // windows fall back to centering.
        float cX = Main.HOTBAR_X - 8 + Main.INVENTORY_PANEL_WIDTH + 16;
        if (cX + cGridW + 28 > main.width) cX = (main.width - cGridW) / 2f;
        float cY = (main.height - cGridH) / 2f - 20;
        creativePanelBg = new UILayer.UIElement(
            new Vector2f(cX - 14, cY - 44),
            new Vector2f(cGridW + 28, cGridH + 92),
            new Vector4f(0.08f, 0.10f, 0.14f, 0.92f)
        );
        creativePanelBg.visible = false;
        layer.addElement(creativePanelBg);
        creativeSearchText = new UILayer.UITextElement(
            new Vector2f(cX, cY - 32), "SEARCH: ", 1.6f, new Vector4f(0.9f, 0.95f, 1f, 1f), fontTextureId);
        creativeSearchText.visible = false;
        creativeSearchText.charLineLimit = 30;
        layer.addElement(creativeSearchText);
        creativeCountText = new UILayer.UITextElement(
            new Vector2f(cX + cGridW - 140, cY - 32), "", 1.3f, new Vector4f(0.7f, 0.8f, 0.9f, 1f), fontTextureId);
        creativeCountText.visible = false;
        creativeCountText.charLineLimit = 20;
        layer.addElement(creativeCountText);

        // Clear "X" close button in the panel's top-right corner.
        float cCloseSize = 30;
        float cCloseX = (cX + cGridW + 14) - cCloseSize - 8;
        float cCloseY = (cY - 44) + 8;
        creativeCloseBtn = new UILayer.UIElement(
            new Vector2f(cCloseX, cCloseY), new Vector2f(cCloseSize, cCloseSize),
            new Vector4f(0.72f, 0.22f, 0.22f, 0.95f));
        creativeCloseBtn.visible = false;
        creativeCloseBtn.onClick = () -> main.toggleCreativeMenu();
        layer.addElement(creativeCloseBtn);
        creativeCloseText = new UILayer.UITextElement(
            new Vector2f(cCloseX + 9, cCloseY + 4), "X", 2.0f, new Vector4f(1, 1, 1, 1), fontTextureId);
        creativeCloseText.visible = false;
        layer.addElement(creativeCloseText);

        for (int i = 0; i < CREATIVE_COLS * CREATIVE_ROWS; i++) {
            int col = i % CREATIVE_COLS;
            int row = i / CREATIVE_COLS;
            float sx = cX + col * (cSlotW + cGap);
            float sy = cY + row * (cSlotH + cGap);
            UILayer.UIElement bg = new UILayer.UIElement(
                new Vector2f(sx, sy), new Vector2f(cSlotW, cSlotH),
                new Vector4f(0.16f, 0.19f, 0.24f, 0.95f));
            bg.visible = false;
            layer.addElement(bg);
            creativeSlotBackgrounds[i] = bg;
            UILayer.UIElement itemEl = new UILayer.UIElement(
                new Vector2f(sx + 12, sy + 12), new Vector2f(40, 40),
                new Vector4f(0, 0, 0, 0));
            itemEl.visible = false;
            layer.addElement(itemEl);
            creativeSlotItems[i] = itemEl;
            final int slotIdx = i;
            bg.onClick = () -> {
                String id = creativeSlotItemIds[slotIdx];
                if (id != null) main.creativeGiveItem(id);
            };
        }

        // ── Map overlay UI elements (top-right control panel) ──
        mapPanelBg = new UILayer.UIElement(
            new Vector2f(main.width - 230, 12),
            new Vector2f(218, 208),
            new Vector4f(0.06f, 0.08f, 0.12f, 0.88f)
        );
        mapPanelBg.visible = false;
        layer.addElement(mapPanelBg);

        // Zoom In button
        mapZoomInBtn = new UILayer.UIElement(
            new Vector2f(main.width - 220, 84),
            new Vector2f(64, 40),
            new Vector4f(0.16f, 0.55f, 0.35f, 0.95f)
        );
        mapZoomInBtn.visible = false;
        mapZoomInBtn.onClick = () -> { ctx.mapTargetZoom = Math.max(0.25f, ctx.mapTargetZoom - 0.5f); };
        layer.addElement(mapZoomInBtn);

        mapZoomInText = new UILayer.UITextElement(
            new Vector2f(main.width - 196, 93),
            "+",
            2.2f,
            new Vector4f(1, 1, 1, 1),
            fontTextureId
        );
        mapZoomInText.visible = false;
        layer.addElement(mapZoomInText);

        // Zoom Out button
        mapZoomOutBtn = new UILayer.UIElement(
            new Vector2f(main.width - 150, 84),
            new Vector2f(64, 40),
            new Vector4f(0.16f, 0.55f, 0.35f, 0.95f)
        );
        mapZoomOutBtn.visible = false;
        mapZoomOutBtn.onClick = () -> { ctx.mapTargetZoom = Math.min(16f, ctx.mapTargetZoom + 0.5f); };
        layer.addElement(mapZoomOutBtn);

        mapZoomOutText = new UILayer.UITextElement(
            new Vector2f(main.width - 126, 93),
            "-",
            2.2f,
            new Vector4f(1, 1, 1, 1),
            fontTextureId
        );
        mapZoomOutText.visible = false;
        layer.addElement(mapZoomOutText);

        // Reset Zoom button
        mapResetBtn = new UILayer.UIElement(
            new Vector2f(main.width - 220, 132),
            new Vector2f(134, 36),
            new Vector4f(0.55f, 0.45f, 0.2f, 0.95f)
        );
        mapResetBtn.visible = false;
        mapResetBtn.onClick = () -> { ctx.mapTargetZoom = 1.0f; };
        layer.addElement(mapResetBtn);

        mapResetText = new UILayer.UITextElement(
            new Vector2f(main.width - 186, 141),
            "RESET ZOOM",
            1.5f,
            new Vector4f(1, 1, 1, 1),
            fontTextureId
        );
        mapResetText.visible = false;
        layer.addElement(mapResetText);

        // Center on Player button
        mapCenterBtn = new UILayer.UIElement(
            new Vector2f(main.width - 220, 176),
            new Vector2f(134, 36),
            new Vector4f(0.25f, 0.45f, 0.75f, 0.95f)
        );
        mapCenterBtn.visible = false;
        mapCenterBtn.onClick = () -> {
            ctx.mapPanX = main.player.getPosition().x;
            ctx.mapPanY = main.player.getPosition().z;
        };
        layer.addElement(mapCenterBtn);

        mapCenterText = new UILayer.UITextElement(
            new Vector2f(main.width - 196, 185),
            "CENTER",
            1.5f,
            new Vector4f(1, 1, 1, 1),
            fontTextureId
        );
        mapCenterText.visible = false;
        layer.addElement(mapCenterText);

        // Coordinate readout (top of panel)
        mapCoordinateText = new UILayer.UITextElement(
            new Vector2f(main.width - 222, 20),
            "",
            1.4f,
            new Vector4f(0.85f, 0.92f, 1.0f, 1.0f),
            fontTextureId
        );
        mapCoordinateText.visible = false;
        layer.addElement(mapCoordinateText);

        // Controls help (top-left corner)
        mapControlsHelpText = new UILayer.UITextElement(
            new Vector2f(20, 12),
            "",
            1.3f,
            new Vector4f(0.8f, 0.85f, 0.9f, 0.9f),
            fontTextureId
        );
        mapControlsHelpText.visible = false;
        mapControlsHelpText.charLineLimit = 70;
        layer.addElement(mapControlsHelpText);

        // ── TV overlay ──
        tvOverlayBg = new UILayer.UIElement(
            new Vector2f(50, 40),
            new Vector2f(main.width - 100, main.height - 180),
            new Vector4f(0.05f, 0.05f, 0.1f, 0.85f)
        );
        tvOverlayBg.visible = false;
        layer.addElement(tvOverlayBg);

        tvChannelNameText = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 150, 55),
            "", 3.0f, new Vector4f(1, 0.9f, 0.3f, 1), fontTextureId
        );
        tvChannelNameText.visible = false;
        tvChannelNameText.charLineLimit = 50;
        layer.addElement(tvChannelNameText);

        tvContentText = new UILayer.UITextElement(
            new Vector2f(70, 110),
            "", 2.2f, new Vector4f(1, 1, 1, 1), fontTextureId
        );
        tvContentText.visible = false;
        tvContentText.charLineLimit = 55;
        layer.addElement(tvContentText);

        tvInstructionsText = new UILayer.UITextElement(
            new Vector2f(70, main.height - 120),
            "LEFT/RIGHT: Change Channel  |  ESC: Exit TV",
            1.8f, new Vector4f(0.7f, 0.7f, 0.7f, 0.9f), fontTextureId
        );
        tvInstructionsText.visible = false;
        tvInstructionsText.charLineLimit = 60;
        layer.addElement(tvInstructionsText);

        // ── Spawn loading overlay ──
        // Keep this in the same layer, but append it after every other element so
        // it remains visually above the inventory/HUD while world generation runs.
        spawnLoadingBackground = new UILayer.UIElement(
            new Vector2f(0, 0),
            new Vector2f(main.width, main.height),
            new Vector4f(loadingTextureId != 0 ? 1.0f : 0.88f,
                         loadingTextureId != 0 ? 1.0f : 0.94f,
                         loadingTextureId != 0 ? 1.0f : 0.78f,
                         1.0f)
        );
        if (loadingTextureId != 0) {
            // The generated texture is a normal 2D image; white prevents
            // vertex-color tinting from muddying the bright daytime artwork.
            spawnLoadingBackground.textureId = loadingTextureId;
        }
        // Consume clicks while loading so hidden inventory controls cannot be
        // activated through the overlay.
        spawnLoadingBackground.onClick = () -> { };
        spawnLoadingBackground.visible = false;
        layer.addElement(spawnLoadingBackground);

        // Full-screen menu background (custom dark backdrop for world-size selection)
        menuBackground = new UILayer.UIElement(
            new Vector2f(0, 0),
            new Vector2f(main.width, main.height),
            new Vector4f(1.0f, 1.0f, 1.0f, 1.0f)
        );
        if (menuBackgroundTextureId != 0) {
            menuBackground.textureId = menuBackgroundTextureId;
        }
        menuBackground.onClick = () -> { };
        menuBackground.visible = false;
        layer.addElement(menuBackground);

        // ── Top-right loading popup ──
        // A compact toast panel shown while spawn chunks generate (world boot /
        // dimension switch). Rendered above the world so the game stays visible;
        // replaces the old full-screen loading overlay at runtime.
        loadingPopupBackground = new UILayer.UIElement(
            new Vector2f(main.width - 268, 12),
            new Vector2f(256, 64),
            new Vector4f(1, 1, 1, 1)
        );
        if (loadingPopupTextureId != 0) {
            // Textured panel: white vertex color shows the artwork un-tinted.
            loadingPopupBackground.textureId = loadingPopupTextureId;
        } else {
            // Flat fallback panel so the popup still reads over any world.
            loadingPopupBackground.color.set(0.10f, 0.12f, 0.16f, 0.92f);
        }
        loadingPopupBackground.visible = false;
        layer.addElement(loadingPopupBackground);

        // Translucent panel behind the menu text so the title + options stay
        // readable over the 3D panorama. Inserted before the title/status so it
        // renders underneath them; theme-aware color set in
        // updateSpawnLoadingOverlay().
        menuTextPanel = new UILayer.UIElement(
            new Vector2f(main.width / 2f - 350, main.height / 2f - 220),
            new Vector2f(700, 500),
            new Vector4f(0.05f, 0.06f, 0.09f, 0.55f)
        );
        menuTextPanel.visible = false;
        // Pure backdrop: never intercept clicks (the menu is keyboard-driven and
        // the full-screen menuBackground already blocks stray in-game clicks).
        menuTextPanel.onClick = null;
        layer.addElement(menuTextPanel);

        spawnLoadingTitle = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 175, main.height / 2f - 70),
            "WORLD INITIALIZING",
            3.0f,
            new Vector4f(0.06f, 0.22f, 0.32f, 1.0f),
            fontTextureId
        );
        spawnLoadingTitle.visible = false;
        spawnLoadingTitle.charLineLimit = 40;
        layer.addElement(spawnLoadingTitle);

        spawnLoadingSpinner = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 12, main.height / 2f - 10),
            "|",
            3.0f,
            new Vector4f(0.06f, 0.48f, 0.48f, 1.0f),
            fontTextureId
        );
        spawnLoadingSpinner.visible = false;
        layer.addElement(spawnLoadingSpinner);

        spawnLoadingStatus = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 230, main.height / 2f + 55),
            "Generating spawn chunks...",
            1.8f,
            new Vector4f(0.10f, 0.28f, 0.34f, 1.0f),
            fontTextureId
        );
        spawnLoadingStatus.visible = false;
        spawnLoadingStatus.charLineLimit = 52;
        layer.addElement(spawnLoadingStatus);

        // Structured main-menu rows. Backgrounds receive clicks; labels are
        // intentionally non-interactive so they do not steal the hit target.
        float menuX = main.width / 2f - 250f;
        float menuY = main.height / 2f - 42f;
        for (int i = 0; i < MENU_BUTTON_COUNT; i++) {
            final int row = i;
            UILayer.UIElement bg = new UILayer.UIElement(
                new Vector2f(menuX, menuY + i * 48f),
                new Vector2f(500, 40),
                new Vector4f(0.08f, 0.12f, 0.18f, 0.92f));
            bg.visible = false;
            bg.onClick = () -> main.requestMenuSelection(row);
            menuButtonBackgrounds[i] = bg;
            layer.addElement(bg);

            UILayer.UITextElement label = new UILayer.UITextElement(
                new Vector2f(menuX + 20, menuY + i * 48f + 8),
                "", 1.55f, new Vector4f(0.92f, 0.95f, 1.0f, 1.0f), fontTextureId);
            label.visible = false;
            label.charLineLimit = 54;
            menuButtonLabels[i] = label;
            layer.addElement(label);
        }
        menuSubtitle = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 250f, main.height / 2f - 104f),
            "", 1.35f, new Vector4f(0.72f, 0.80f, 0.90f, 1.0f), fontTextureId);
        menuSubtitle.visible = false;
        menuSubtitle.charLineLimit = 62;
        layer.addElement(menuSubtitle);
        menuHint = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 250f, main.height - 48f),
            "", 1.2f, new Vector4f(0.78f, 0.84f, 0.92f, 0.95f), fontTextureId);
        menuHint.visible = false;
        menuHint.charLineLimit = 80;
        layer.addElement(menuHint);

        // Pause menu sits above the world and below its button labels.
        pauseDimmer = new UILayer.UIElement(
            new Vector2f(0, 0), new Vector2f(main.width, main.height),
            new Vector4f(0.015f, 0.025f, 0.05f, 0.58f));
        pauseDimmer.visible = false;
        pauseDimmer.onClick = null;
        layer.addElement(pauseDimmer);
        pausePanel = new UILayer.UIElement(
            new Vector2f(main.width / 2f - 250f, main.height / 2f - 160f),
            new Vector2f(500, 330),
            new Vector4f(0.045f, 0.065f, 0.11f, 0.96f));
        pausePanel.visible = false;
        layer.addElement(pausePanel);
        pauseTitle = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 210f, main.height / 2f - 130f),
            "PAUSED", 3.2f, new Vector4f(0.95f, 0.82f, 0.35f, 1), fontTextureId);
        pauseTitle.visible = false;
        layer.addElement(pauseTitle);
        for (int i = 0; i < pauseButtonBackgrounds.length; i++) {
            final int row = i;
            float y = main.height / 2f - 60f + i * 54f;
            UILayer.UIElement bg = new UILayer.UIElement(
                new Vector2f(main.width / 2f - 210f, y), new Vector2f(420, 44),
                new Vector4f(0.10f, 0.15f, 0.23f, 0.96f));
            bg.visible = false;
            bg.onClick = () -> main.requestPauseSelection(row);
            pauseButtonBackgrounds[i] = bg;
            layer.addElement(bg);
            UILayer.UITextElement label = new UILayer.UITextElement(
                new Vector2f(main.width / 2f - 190f, y + 9), "", 1.55f,
                new Vector4f(0.92f, 0.95f, 1.0f, 1), fontTextureId);
            label.visible = false;
            pauseButtonLabels[i] = label;
            layer.addElement(label);
        }
        pauseHint = new UILayer.UITextElement(
            new Vector2f(main.width / 2f - 210f, main.height / 2f + 126f),
            "ESC resume   ENTER select", 1.15f,
            new Vector4f(0.70f, 0.78f, 0.88f, 1), fontTextureId);
        pauseHint.visible = false;
        layer.addElement(pauseHint);
    }

    // ── Slot click handlers ───────────────────────────────────────────────────────

    public void handleFurnaceSlotClick(int slot) {

        // slot 0 = input, 1 = fuel, 2 = output
        FurnaceManager.FurnaceState state = ctx.furnaceManager.getState(ctx.furnaceBlockX, ctx.furnaceBlockY, ctx.furnaceBlockZ);
        ItemStack carried = playerInventory.getCarriedStack();

        if (slot == 2) {
            // Output slot: take items out to inventory or carried
            if (state.output != null) {
                if (carried == null) {
                    if (playerInventory.addItem(state.output.itemId, state.output.count)) {
                        state.output = null;
                    }
                } else if (carried.itemId.equals(state.output.itemId) && carried.count + state.output.count <= 64) {
                    carried.count += state.output.count;
                    state.output = null;
                }
            }
            return;
        }

        // Input (0) or fuel (1) slot
        ItemStack slotStack = (slot == 0) ? state.input : state.fuel;

        if (carried == null && slotStack != null) {
            // Pick up the item from the slot
            playerInventory.setCarriedStack(slotStack.copy());
            if (slot == 0) state.input = null;
            else state.fuel = null;
        } else if (carried != null && slotStack == null) {
            // Place carried item into the slot
            ItemStack placed = carried.copy();
            placed.count = 1;
            if (slot == 0) state.input = placed;
            else state.fuel = placed;
            carried.count--;
            if (carried.count <= 0) playerInventory.setCarriedStack(null);
        } else if (carried != null && slotStack != null && carried.itemId.equals(slotStack.itemId) && slotStack.count < 64) {
            // Stack items
            int transfer = Math.min(carried.count, 64 - slotStack.count);
            slotStack.count += transfer;
            carried.count -= transfer;
            if (carried.count <= 0) playerInventory.setCarriedStack(null);
        }

        // Save furnace state after modification
        if (ctx.worldSaveManager != null) {
            ctx.worldSaveManager.saveFurnaceData(ctx.activeDimension, ctx.furnaceManager);
        }
    
    }


    /** Applies the correct atlas and crop for an inventory/creative icon. */
    private void applyItemIcon(UILayer.UIElement element, ItemDefinition def) {
        element.textureId = def.entityIcon
                ? textureManager.getEntityTextureArrayId()
                : textureManager.getTextureArrayId();
        element.textureType = 2;
        element.layer = def.iconLayer;
        element.uvOffset.set(def.iconUv.x, def.iconUv.y);
        element.uvScale.set(def.iconUv.z, def.iconUv.w);
    }

    public void handleChestSlotClick(int slot) {

        ItemStack[] chestInv = ctx.chestManager.getInventory(ctx.chestBlockX, ctx.chestBlockY, ctx.chestBlockZ);
        if (chestInv == null) return;

        ItemStack slotStack = chestInv[slot];
        ItemStack carried = playerInventory.getCarriedStack();

        if (carried == null && slotStack != null) {
            // Pick up from chest
            playerInventory.setCarriedStack(slotStack.copy());
            chestInv[slot] = null;
        } else if (carried != null && slotStack == null) {
            // Place into chest
            ItemStack placed = carried.copy();
            chestInv[slot] = placed;
            playerInventory.setCarriedStack(null);
        } else if (carried != null && slotStack != null && carried.itemId.equals(slotStack.itemId) && slotStack.count < 64) {
            // Stack
            int transfer = Math.min(carried.count, 64 - slotStack.count);
            slotStack.count += transfer;
            carried.count -= transfer;
            if (carried.count <= 0) playerInventory.setCarriedStack(null);
        }

        ctx.chestManager.setInventory(ctx.chestBlockX, ctx.chestBlockY, ctx.chestBlockZ, chestInv);
        if (ctx.worldSaveManager != null) {
            ctx.worldSaveManager.saveChestData(ctx.activeDimension, ctx.chestManager);
        }
    
    }


    public void showSelectedItemName() {
        ItemStack stack = playerInventory.getSlot(playerInventory.getSelectedSlot());
        if (stack != null) {
            ItemDefinition def = itemDefinitions.getDefinition(stack.itemId);
            if (def != null) {
                itemNameElement.text = def.displayName;
                itemNameElement.visible = true;
                itemNameElement.color.w = 1.0f;
                itemNameDisplayUntil = glfwGetTime() + 3.0;
            }
        }
    }
    public boolean handleClickForLayer(float mouseX, float mouseY) {
        // Forward to the existing UI layer click handler (Main handles this for now)
        return false; // (handled by Main.uiLayers loop)
    }

    public boolean handleMouseClick(float mouseX, float mouseY) {
        // Delegate to the existing main.uiLayers click handling.
        for (int i = uiLayers.size() - 1; i >= 0; i--) {
            if (uiLayers.get(i).handleMouseClick(mouseX, mouseY)) return true;
        }
        return false;
    }

    /** Required to allow crafting-cell raycast from InputHandler. */
    public int raycastCraftingCell() {
        return camera.raycastCraftingCell();
    }

    // ── Per-frame update ─────────────────────────────────────────────────────────

    public void updateInventoryUi() {
        // Carried item follow-mouse
        ItemStack carried = playerInventory.getCarriedStack();
        carriedItemElement.visible = main.inventoryOpen && carried != null;
        if (carriedItemElement.visible) {
            ItemDefinition carriedDef = itemDefinitions.getDefinition(carried.itemId);
            applyItemIcon(carriedItemElement, carriedDef);
            carriedItemElement.color.set(1, 1, 1, 0.9f);
            carriedItemElement.pos.set(main.lastMouseX - 14, main.lastMouseY - 14);
            carriedItemElement.size.set(28, 28);
        }

        commandTextElement.visible = main.commandMode || ctx.commandBlockEditorOpen;
        if (ctx.commandBlockEditorOpen) {
            commandTextElement.text = "COMMAND CONSOLE > " + ctx.commandBlockEditorCommand + "_";
            commandTextElement.pos.set(80, main.height / 2f - 30);
            commandTextElement.color.set(0.55f, 1.0f, 0.8f, 1.0f);
        } else if (main.commandMode) {
            commandTextElement.text = main.commandBuffer.toString() + "_";
            commandTextElement.pos.set(20, main.height - 40);
            commandTextElement.color.set(1, 1, 1, 1);
        }

        int selSlot = playerInventory.getSelectedSlot();
        float hp = main.player.getHealth();
        // While the map is open, always refresh (coordinate readout + hover states).
        boolean mapForceRefresh = ctx.mapOpen;
        if (!mapForceRefresh && !inventoryUiDirty && main.inventoryOpen == prevInventoryOpenForUi && main.commandMode == prevCommandModeForUi
                && selSlot == prevSelectedSlot && Math.abs(hp - prevHealth) < 0.05f
                && ctx.mapOpen == prevMapOpenForUi) {
            return;
        }
        inventoryUiDirty = false;
        prevInventoryOpenForUi = main.inventoryOpen;
        prevCommandModeForUi = main.commandMode;
        prevMapOpenForUi = ctx.mapOpen;
        prevSelectedSlot = selSlot;
        prevHealth = hp;
        double time = glfwGetTime();
        // NOTE: the virtual cursor (crosshairElement) is positioned every
        // frame in updateBillboards(), NOT here — updateInventoryUi has an
        // early-return dirty guard that would freeze the cursor in place
        // whenever nothing in the inventory changed.
        inventoryPanelElement.visible = main.inventoryOpen;
        hotbarActiveElement.visible = true;
        hotbarActiveElement.pos.y = Main.HOTBAR_Y + playerInventory.getSelectedSlot() * Main.SLOT_H;

        boolean use3x3 = ctx.craftingTableOpen && ctx.activeUI == ActiveUI.CRAFTING_TABLE;
        boolean useSurface = ctx.surfaceCraftingOpen && ctx.activeUI == ActiveUI.SURFACE_CRAFTING;
        boolean useCommandBlock = ctx.commandBlockEditorOpen && ctx.activeUI == ActiveUI.COMMAND_BLOCK;
        boolean useFurnace = ctx.furnaceOpen && ctx.activeUI == ActiveUI.FURNACE;
        boolean useChest = ctx.chestOpen && ctx.activeUI == ActiveUI.CHEST;

        // Slot/UI updates (inlined from Main.updateInventoryUi)
        if (useCommandBlock) {
            craftingTableBg.visible = false;
            craftingButton.visible = false;
            craftingButtonText.visible = false;
            craftingButtonItem.visible = false;
        }
// --- Furnace UI ---
        if (useFurnace) {
            // Hide crafting UIs
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            craftingButton.visible = false;
            craftingButtonText.visible = false;
            craftingButtonItem.visible = false;
            chestPanelBg.visible = false;
            chestTitle.visible = false;
            for (int i = 0; i < ChestManager.CHEST_SLOTS; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
                chestCountBars[i].visible = false;
                chestCountDigit1[i].visible = false;
                chestCountDigit2[i].visible = false;
            }

            FurnaceManager.FurnaceState state = ctx.furnaceManager.getState(ctx.furnaceBlockX, ctx.furnaceBlockY, ctx.furnaceBlockZ);

            furnacePanelBg.visible = main.inventoryOpen;
            furnaceInputBg.visible = main.inventoryOpen;
            furnaceFuelBg.visible = main.inventoryOpen;
            furnaceOutputBg.visible = main.inventoryOpen;
            furnaceProgressBar.visible = main.inventoryOpen;
            furnaceFuelBar.visible = main.inventoryOpen;
            furnaceFuelText.visible = main.inventoryOpen;

            // Input slot
            if (main.inventoryOpen && state.input != null) {
                ItemDefinition def = itemDefinitions.getDefinition(state.input.itemId);
                if (def != null) {
                    furnaceInputItem.visible = true;
                    applyItemIcon(furnaceInputItem, def);
                    furnaceInputItem.color.set(1, 1, 1, 1);
                } else {
                    furnaceInputItem.visible = false;
                }
            } else {
                furnaceInputItem.visible = false;
            }

            // Fuel slot
            if (main.inventoryOpen && state.fuel != null) {
                ItemDefinition def = itemDefinitions.getDefinition(state.fuel.itemId);
                if (def != null) {
                    furnaceFuelItem.visible = true;
                    applyItemIcon(furnaceFuelItem, def);
                    furnaceFuelItem.color.set(1, 1, 1, 1);
                } else {
                    furnaceFuelItem.visible = false;
                }
            } else {
                furnaceFuelItem.visible = false;
            }

            // Output slot
            if (main.inventoryOpen && state.output != null) {
                ItemDefinition def = itemDefinitions.getDefinition(state.output.itemId);
                if (def != null) {
                    furnaceOutputItem.visible = true;
                    applyItemIcon(furnaceOutputItem, def);
                    furnaceOutputItem.color.set(1, 1, 1, 1);
                } else {
                    furnaceOutputItem.visible = false;
                }
            } else {
                furnaceOutputItem.visible = false;
            }

            // Progress bar
            if (main.inventoryOpen && state.fuelBurnTime > 0 && state.input != null) {
                float progress = Math.min(1.0f, state.smeltProgress);
                furnaceProgressBar.size.set(20, (int)(50 * progress));
                furnaceProgressBar.color.set(0.8f + 0.2f * progress, 0.4f + 0.4f * progress, 0.2f, 0.9f);
            } else {
                furnaceProgressBar.size.set(0, 0);
            }

            // Fuel bar
            if (main.inventoryOpen && state.isLit()) {
                float fuelPct = state.fuelBurnTime / state.maxFuelBurnTime;
                furnaceFuelBar.size.set(60 * fuelPct, 20);
                furnaceFuelBar.color.set(1.0f, 0.5f, 0.0f, 0.8f);
                furnaceFuelText.text = String.format("%.1fs", state.fuelBurnTime);
            } else {
                furnaceFuelBar.size.set(0, 0);
                furnaceFuelText.text = "No fuel";
            }
        } else {
            furnacePanelBg.visible = false;
            furnaceInputBg.visible = false;
            furnaceFuelBg.visible = false;
            furnaceOutputBg.visible = false;
            furnaceInputItem.visible = false;
            furnaceFuelItem.visible = false;
            furnaceOutputItem.visible = false;
            furnaceProgressBar.visible = false;
            furnaceFuelBar.visible = false;
            furnaceFuelText.visible = false;
        }

        // --- Chest UI ---
        if (useChest) {
            // Hide crafting/furnace UIs
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            furnacePanelBg.visible = false;
            furnaceInputBg.visible = false;
            furnaceFuelBg.visible = false;
            furnaceOutputBg.visible = false;
            furnaceInputItem.visible = false;
            furnaceFuelItem.visible = false;
            furnaceOutputItem.visible = false;
            furnaceProgressBar.visible = false;
            furnaceFuelBar.visible = false;
            furnaceFuelText.visible = false;

            chestPanelBg.visible = main.inventoryOpen;
            chestTitle.visible = main.inventoryOpen;

            ItemStack[] chestInv = ctx.chestManager.getInventory(ctx.chestBlockX, ctx.chestBlockY, ctx.chestBlockZ);
            for (int i = 0; i < ChestManager.CHEST_SLOTS; i++) {
                boolean slotVisible = main.inventoryOpen;
                chestSlotBackgrounds[i].visible = slotVisible;

                ItemStack stack = (chestInv != null && i < chestInv.length) ? chestInv[i] : null;
                UILayer.UIElement itemEl = chestSlotItems[i];
                UILayer.UIElement countBar = chestCountBars[i];
                UILayer.UIElement digit1 = chestCountDigit1[i];
                UILayer.UIElement digit2 = chestCountDigit2[i];

                if (!slotVisible || stack == null) {
                    itemEl.visible = false;
                    countBar.visible = false;
                    digit1.visible = false;
                    digit2.visible = false;
                    continue;
                }

                ItemDefinition def = itemDefinitions.getDefinition(stack.itemId);
                if (def == null || def.iconLayer < 0) {
                    itemEl.visible = false;
                    countBar.visible = false;
                    digit1.visible = false;
                    digit2.visible = false;
                    continue;
                }
                itemEl.visible = true;
                applyItemIcon(itemEl, def);
                itemEl.color.set(1, 1, 1, 1);
                itemEl.size.set(40, 40);
                itemEl.pos.set(chestSlotBackgrounds[i].pos.x + 12, chestSlotBackgrounds[i].pos.y + 12);

                if (main.inventoryOpen && chestSlotBackgrounds[i].isPointInside(main.lastMouseX, main.lastMouseY)) {
                    if (def != null) {
                        itemNameElement.text = def.displayName;
                        itemNameElement.visible = true;
                        itemNameElement.color.w = 1.0f;
                        itemNameDisplayUntil = time + 0.1;
                    }
                }

                if (def != null && def.maxStack > 1 && stack.count > 1) {
                    countBar.visible = true;
                    countBar.color.set(def.color.x, def.color.y, def.color.z, 0.85f);
                    countBar.pos.set(chestSlotBackgrounds[i].pos.x + 8, chestSlotBackgrounds[i].pos.y + CHEST_SLOT_H - 10);
                    countBar.size.set((CHEST_SLOT_W - 16) * Math.min(stack.count, def.maxStack) / (float) def.maxStack, 5);

                    if (fontTextureId != 0) {
                        if (stack.count >= 10) {
                            digit1.visible = true;
                            int d1 = stack.count / 10;
                            int charCode = 48 + d1;
                            digit1.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                            digit1.uvScale.set(1 / 16.0f, 1 / 16.0f);
                            digit2.visible = true;
                            int d2 = stack.count % 10;
                            charCode = 48 + d2;
                            digit2.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                            digit2.uvScale.set(1 / 16.0f, 1 / 16.0f);
                        } else {
                            digit1.visible = false;
                            digit2.visible = true;
                            int d2 = stack.count;
                            int charCode = 48 + d2;
                            digit2.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                            digit2.uvScale.set(1 / 16.0f, 1 / 16.0f);
                        }
                    } else {
                        digit1.visible = false;
                        digit2.visible = false;
                    }
                } else {
                    countBar.visible = false;
                    digit1.visible = false;
                    digit2.visible = false;
                }
            }
        } else {
            chestPanelBg.visible = false;
            chestTitle.visible = false;
            for (int i = 0; i < ChestManager.CHEST_SLOTS; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
                chestCountBars[i].visible = false;
                chestCountDigit1[i].visible = false;
                chestCountDigit2[i].visible = false;
            }
        }

        // ── Creative item picker (overrides the survival bag layout) ──
        boolean useCreative = main.inventoryOpen && ctx.creativeMenuOpen
            && ctx.gameMode == GameContext.GameMode.CREATIVE
            && ctx.activeUI == ActiveUI.INVENTORY;
        if (useCreative) {
            // Hide the other inventory UI panes so only the picker shows.
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            furnacePanelBg.visible = false;
            chestPanelBg.visible = false;
            chestTitle.visible = false;
            for (int i = 0; i < ChestManager.CHEST_SLOTS; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
            }
            inventoryPanelElement.visible = true;

            // Collect all registered items, filtered by the search box.
            String search = ctx.creativeSearch.toString().toLowerCase(java.util.Locale.ROOT);
            java.util.List<ItemDefinition> all = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, ItemDefinition> e : itemDefinitions.getRegistry().entrySet()) {
                ItemDefinition def = e.getValue();
                if (def == null || def.iconLayer < 0) continue;
                if (!search.isEmpty()) {
                    if (!def.id.toLowerCase(java.util.Locale.ROOT).contains(search)
                        && !def.displayName.toLowerCase(java.util.Locale.ROOT).contains(search)) {
                        continue;
                    }
                }
                all.add(def);
            }
            all.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));

            // Scroll clamp: keep the view inside the filtered list.
            int rows = (int) Math.ceil(all.size() / (float) CREATIVE_COLS);
            int maxScroll = Math.max(0, rows - CREATIVE_ROWS);
            if (ctx.creativeScroll > maxScroll) ctx.creativeScroll = maxScroll;

            creativePanelBg.visible = true;
            creativeSearchText.visible = true;
            creativeSearchText.text = "SEARCH: " + ctx.creativeSearch + "_";
            creativeCountText.visible = true;
            creativeCountText.text = all.size() + " items";
            creativeCloseBtn.visible = true;
            creativeCloseText.visible = true;

            for (int i = 0; i < CREATIVE_COLS * CREATIVE_ROWS; i++) {
                int col = i % CREATIVE_COLS;
                int row = i / CREATIVE_COLS;
                int listIdx = (ctx.creativeScroll + row) * CREATIVE_COLS + col;
                UILayer.UIElement bg = creativeSlotBackgrounds[i];
                UILayer.UIElement itemEl = creativeSlotItems[i];
                creativeSlotItemIds[i] = null;
                if (listIdx >= all.size()) {
                    bg.visible = false;
                    itemEl.visible = false;
                    continue;
                }
                ItemDefinition def = all.get(listIdx);
                creativeSlotItemIds[i] = def.id;
                bg.visible = true;
                itemEl.visible = true;
                applyItemIcon(itemEl, def);
                itemEl.color.set(1, 1, 1, 1);
                itemEl.size.set(40, 40);
                itemEl.pos.set(bg.pos.x + 12, bg.pos.y + 12);
                // Hover tooltip
                if (bg.isPointInside(main.lastMouseX, main.lastMouseY)) {
                    itemNameElement.text = def.displayName;
                    itemNameElement.visible = true;
                    itemNameElement.color.w = 1.0f;
                    itemNameDisplayUntil = time + 0.1;
                }
            }
        } else {
            creativePanelBg.visible = false;
            creativeSearchText.visible = false;
            creativeCountText.visible = false;
            creativeCloseBtn.visible = false;
            creativeCloseText.visible = false;
            for (int i = 0; i < CREATIVE_COLS * CREATIVE_ROWS; i++) {
                creativeSlotBackgrounds[i].visible = false;
                creativeSlotItems[i].visible = false;
                creativeSlotItemIds[i] = null;
            }
        }

        // --- 3x3 Crafting table UI ---
        if (use3x3) {
            // Hide other UIs
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            craftingButton.visible = main.inventoryOpen && playerInventory.hasCrafting3x3Result();
            craftingButtonText.visible = craftingButton.visible;
            craftingButtonItem.visible = craftingButton.visible;
            if (craftingButton.visible) {
                ItemDefinition resultDef = itemDefinitions.getDefinition(playerInventory.getCrafting3x3ResultItemId());
                if (resultDef != null) {
                    applyItemIcon(craftingButtonItem, resultDef);
                    craftingButtonItem.color.set(1, 1, 1, 1);
                } else {
                    craftingButtonItem.visible = false;
                }
            }
            // Hide furnace/chest during crafting table
            furnacePanelBg.visible = false;
            furnaceInputBg.visible = false;
            furnaceFuelBg.visible = false;
            furnaceOutputBg.visible = false;
            furnaceInputItem.visible = false;
            furnaceFuelItem.visible = false;
            furnaceOutputItem.visible = false;
            furnaceProgressBar.visible = false;
            furnaceFuelBar.visible = false;
            furnaceFuelText.visible = false;
            chestPanelBg.visible = false;
            chestTitle.visible = false;
            for (int i = 0; i < ChestManager.CHEST_SLOTS; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
                chestCountBars[i].visible = false;
                chestCountDigit1[i].visible = false;
                chestCountDigit2[i].visible = false;
            }
        } else if (useSurface) {
            // Surface crafting uses the four quadrants on the targeted block's
            // top face. The world raycast handles the cells; only the action
            // button is screen-space UI here.
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            craftingButton.visible = main.inventoryOpen && playerInventory.getSurfaceCraftingPreview() != null;
            craftingButtonText.visible = craftingButton.visible;
            craftingButtonItem.visible = craftingButton.visible;
            if (craftingButton.visible) {
                ItemDefinition resultDef = itemDefinitions.getDefinition(
                    playerInventory.getSurfaceCraftingPreview().resultItemId);
                if (resultDef != null) {
                    applyItemIcon(craftingButtonItem, resultDef);
                    craftingButtonItem.color.set(1, 1, 1, 1);
                } else {
                    craftingButtonItem.visible = false;
                }
            }
        } else if (!useFurnace && !useChest) {
            // No 2x2 crafting grid is shown in the inventory anymore.
            craftingTableBg.visible = false;
            craftingButton.visible = false;
            craftingButtonText.visible = false;
            craftingButtonItem.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
        }

        for (int index = 0; index < Main.INVENTORY_SIZE; index++) {
            boolean slotVisible = index < Main.HOTBAR_SIZE || main.inventoryOpen;
            slotBackgrounds[index].visible = slotVisible;

            ItemStack stack = playerInventory.getSlot(index);
            UILayer.UIElement itemElement = slotItemElements[index];
            UILayer.UIElement countBar = slotCountBars[index];
            UILayer.UIElement digit1 = slotCountDigit1[index];
            UILayer.UIElement digit2 = slotCountDigit2[index];

            if (!slotVisible || stack == null) {
                itemElement.visible = false;
                countBar.visible = false;
                digit1.visible = false;
                digit2.visible = false;
                continue;
            }

            ItemDefinition definition = itemDefinitions.getDefinition(stack.itemId);
            if (definition == null) {
                // Unknown item id in save data — skip rendering rather than crash.
                itemElement.visible = false;
                countBar.visible = false;
                digit1.visible = false;
                digit2.visible = false;
                continue;
            }
            itemElement.visible = true;
            applyItemIcon(itemElement, definition);
            itemElement.color.set(1, 1, 1, 1);
            
            if (definition.kind == ItemDefinitions.ItemKind.TOOL) {
                itemElement.size.set(40, 40);
                itemElement.pos.set(slotBackgrounds[index].pos.x + 24, slotBackgrounds[index].pos.y + 14);
            } else {
                itemElement.size.set(40, 40);
                itemElement.pos.set(slotBackgrounds[index].pos.x + 24, slotBackgrounds[index].pos.y + 16);
            }

            if (main.inventoryOpen && slotBackgrounds[index].isPointInside(main.lastMouseX, main.lastMouseY)) {
                itemNameElement.text = definition.displayName;
                itemNameElement.visible = true;
                itemNameElement.color.w = 1.0f;
                itemNameDisplayUntil = time + 0.1; // Stay while hovering
            }

            if (definition.maxStack > 1 && stack.count > 1) {
                countBar.visible = true;
                countBar.color.set(definition.color.x, definition.color.y, definition.color.z, 0.85f);
                countBar.pos.set(slotBackgrounds[index].pos.x + 12, slotBackgrounds[index].pos.y + Main.SLOT_H - 12);
                countBar.size.set((Main.SLOT_W - 24) * Math.min(stack.count, definition.maxStack) / (float) definition.maxStack, 6);

                if (fontTextureId != 0) {
                    if (stack.count >= 10) {
                        digit1.visible = true;
                        int d1 = stack.count / 10;
                        int charCode = 48 + d1;
                        digit1.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                        digit1.uvScale.set(1 / 16.0f, 1 / 16.0f);
                        
                        digit2.visible = true;
                        int d2 = stack.count % 10;
                        charCode = 48 + d2;
                        digit2.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                        digit2.uvScale.set(1 / 16.0f, 1 / 16.0f);
                    } else {
                        digit1.visible = false;
                        digit2.visible = true;
                        int d2 = stack.count;
                        int charCode = 48 + d2;
                        digit2.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                        digit2.uvScale.set(1 / 16.0f, 1 / 16.0f);
                    }
                } else {
                    digit1.visible = false;
                    digit2.visible = false;
                }
            } else {
                countBar.visible = false;
                digit1.visible = false;
                digit2.visible = false;
            }
        }

        if (itemNameDisplayUntil > time) {
            itemNameElement.visible = true;
            float alpha = (float) Math.min(1.0, (itemNameDisplayUntil - time) / 0.5);
            itemNameElement.color.w = alpha;
        } else {
            itemNameElement.visible = false;
        }

        statusTextElement.visible = !main.statusMessage.isEmpty() && time < main.statusUntil;
        if (statusTextElement.visible) {
            statusTextElement.text = main.statusMessage;
            statusTextElement.lineOffset = main.statusLineOffset;
            float alpha = (float) Math.min(1.0, (main.statusUntil - time) / 0.5);
            statusTextElement.color.w = alpha;
        }

        // Goggles overlay: driven by Main.tick() via ctx.machineLookInfo.
        boolean gogglesVisible = ctx.machineLookInfo != null && !ctx.machineLookInfo.isEmpty()
                && !ctx.inventoryOpen && !ctx.commandMode;
        gogglesOverlayElement.visible = gogglesVisible;
        if (gogglesVisible) {
            gogglesOverlayElement.text = ctx.machineLookInfo;
            gogglesOverlayElement.color.w = 1f;
        }

        // ── Map overlay updates ──
        boolean mapVisible = ctx.mapOpen;
        mapPanelBg.visible = mapVisible;
        mapZoomInBtn.visible = mapVisible;
        mapZoomInText.visible = mapVisible;
        mapZoomOutBtn.visible = mapVisible;
        mapZoomOutText.visible = mapVisible;
        mapResetBtn.visible = mapVisible;
        mapResetText.visible = mapVisible;
        mapCenterBtn.visible = mapVisible;
        mapCenterText.visible = mapVisible;
        mapCoordinateText.visible = mapVisible;
        mapControlsHelpText.visible = mapVisible;

        if (mapVisible) {
            mapCoordinateText.text = ctx.mapCoordinateText;
            mapControlsHelpText.text =
                "WASD/Arrows: Pan   Scroll / +/-: Zoom   Drag: Pan   C/Home: Center   0: Reset zoom   M: Close";
        }

        // Update Player Hearts
        hp = main.player.getHealth();
        for (int i = 0; i < 10; i++) {
            float texW = uiTextureSize.x;
            float texH = uiTextureSize.y;

            // Heart base/container behind each heart (always visible, 27x27)
            UILayer.UIElement heartBase = heartBases[i];
            heartBase.visible = !main.commandMode;
            heartBase.pos.set(Main.HOTBAR_X + i * 30 - 3, main.height - 40 - 3);
            heartBase.uvOffset.set((uvHeartBase.x + 0.5f) / texW, (uvHeartBase.y + 0.5f) / texH);
            heartBase.uvScale.set((uvHeartBase.z - 1.0f) / texW, (uvHeartBase.w - 1.0f) / texH);

            // Heart icon on top (only show when not empty — base alone handles the empty look)
            float heartValue = hp - (i * 2);
            UILayer.UIElement heart = playerHearts[i];
            if (heartValue < 1.0f) {
                // Empty: hide heart icon, base container shows through
                heart.visible = false;
            } else {
                heart.visible = !main.commandMode;
                heart.pos.set(Main.HOTBAR_X + i * 30, main.height - 40);

                Vector4f uv = (heartValue >= 2.0f) ? uvHeartFull : uvHeartHalf;
                // Inset UV by half a pixel to prevent texture atlas bleeding
                heart.uvOffset.set((uv.x + 0.5f) / texW, (uv.y + 0.5f) / texH);
                heart.uvScale.set((uv.z - 1.0f) / texW, (uv.w - 1.0f) / texH);
            }
        }
    
    }

    /**
     * Always update the spawn overlay regardless of inventory UI dirty state.
     * Called from Main.loop() so generation and surface detection remain visible
     * while the logic thread intentionally pauses gameplay.
     *
     * Two presentations:
     *  - Pre-world init (ctx.initializing): nothing renders yet, so the full-screen
     *    artwork overlay stays up.
     *  - Runtime (spawn generation): a compact toast panel in the top-right
     *    corner, leaving the world visible behind it.
     */
    /** Shows/hides the Tutorial World zone title-card popup. */
    public void updateTutorialPopup(double time) {
        boolean visible = time < main.tutorialPopupUntil && !main.tutorialPopupTitle.isEmpty();
        tutorialPopupBg.visible = visible;
        tutorialTitleElement.visible = visible;
        tutorialSubtitleElement.visible = visible;
        if (visible) {
            float alpha = (float) Math.min(1.0, (main.tutorialPopupUntil - time) / 0.5);
            tutorialTitleElement.text = main.tutorialPopupTitle;
            tutorialSubtitleElement.text = main.tutorialPopupSubtitle;
            tutorialPopupBg.color.w = 0.72f * alpha;
            tutorialTitleElement.color.w = alpha;
            tutorialSubtitleElement.color.w = alpha;
        }
    }

    /** Updates structured startup and pause controls, including hover feedback. */
    public void updateMenuControls(double time) {
        boolean inMenu = ctx.menuScreen != GameContext.MenuScreen.IN_GAME;
        boolean paused = ctx.pauseMenuOpen && !inMenu;
        String[] labels = new String[MENU_BUTTON_COUNT];
        int count = 0;
        menuSubtitle.text = "";
        menuHint.text = "";

        if (inMenu) {
            switch (ctx.menuScreen) {
                case MAIN:
                    labels = new String[] { "NEW WORLD", "TUTORIAL WORLD", "POINT & CLICK DEMO", "LOAD SAVE", "OPTIONS", "", "", "" };
                    count = 5;
                    menuSubtitle.text = "A small voxel world with big ideas";
                    menuHint.text = "ARROWS / WHEEL select   ENTER / CLICK confirm   ESC back";
                    break;
                case NEW_WORLD_NAME:
                    labels = new String[] { "CONTINUE", "BACK", "", "", "", "", "", "" };
                    count = 2;
                    menuSubtitle.text = "WORLD NAME: " + ctx.menuTextInput + "_";
                    menuHint.text = "TYPE A NAME   ENTER / CLICK CONTINUE   ESC back";
                    break;
                case NEW_WORLD_SEED:
                    labels = new String[] { "CONTINUE", "BACK", "", "", "", "", "", "" };
                    count = 2;
                    menuSubtitle.text = "SEED: " + (ctx.menuTextInput.length() == 0 ? "RANDOM" : ctx.menuTextInput + "_");
                    menuHint.text = "BLANK USES A RANDOM SEED   ENTER / CLICK CONTINUE";
                    break;
                case NEW_WORLD_SIZE:
                    com.voxel.world.WorldSize[] sizes = com.voxel.world.WorldSize.values();
                    count = Math.min(sizes.length + 1, MENU_BUTTON_COUNT);
                    for (int i = 0; i < count - 1; i++) labels[i] = sizes[i].displayName();
                    labels[count - 1] = "BACK";
                    menuSubtitle.text = "WORLD SIZE   •   BORDER " + sizes[ctx.worldSizeSelection].borderRadius() / 1000 + "K";
                    menuHint.text = "ARROWS / CLICK choose size   ENTER confirm";
                    break;
                case NEW_WORLD_MODE:
                    labels = new String[] { "SURVIVAL", "CREATIVE", "CREATE WORLD", "BACK", "", "", "", "" };
                    count = 4;
                    menuSubtitle.text = "GAME MODE: " + (ctx.gameMode == GameContext.GameMode.CREATIVE ? "CREATIVE" : "SURVIVAL");
                    menuHint.text = "CLICK A MODE, THEN CREATE WORLD   ESC back";
                    break;
                case LOAD_SAVE:
                    int saves = Math.min(ctx.saveList.size(), 5);
                    count = Math.min(saves + 3, MENU_BUTTON_COUNT);
                    for (int i = 0; i < saves; i++) labels[i] = ctx.saveList.get(i);
                    if (count >= 3) {
                        labels[count - 3] = "LOAD SELECTED";
                        labels[count - 2] = "DELETE SELECTED";
                        labels[count - 1] = "BACK";
                    }
                    menuSubtitle.text = saves == 0 ? "NO SAVES FOUND" : "SELECT A WORLD TO CONTINUE";
                    menuHint.text = "CLICK A WORLD TO SELECT   ENTER LOAD   BACKSPACE DELETE   ESC back";
                    break;
                case OPTIONS:
                    labels = new String[] { "THEME: " + (ctx.uiTheme == GameContext.UiTheme.DARK ? "DARK" : "LIGHT"), "BACK", "", "", "", "", "", "" };
                    count = 2;
                    menuSubtitle.text = "CONTROLS & ACCESSIBILITY\nWASD MOVE   MOUSE LOOK   E INVENTORY   M MAP";
                    menuHint.text = "ESC back   ENTER / CLICK select";
                    break;
                default: break;
            }
        }

        for (int i = 0; i < MENU_BUTTON_COUNT; i++) {
            boolean visible = inMenu && !paused && i < count && labels[i] != null && !labels[i].isEmpty();
            menuButtonBackgrounds[i].visible = visible;
            menuButtonLabels[i].visible = visible;
            if (!visible) continue;
            boolean hover = menuButtonBackgrounds[i].isPointInside(ctx.lastMouseX, ctx.lastMouseY);
            boolean selected = i == ctx.menuSelection;
            if (ctx.menuScreen == GameContext.MenuScreen.LOAD_SAVE && i < Math.min(ctx.saveList.size(), 5)) {
                selected = i == ctx.saveListSelection;
            } else if (ctx.menuScreen == GameContext.MenuScreen.NEW_WORLD_SIZE
                    && i < com.voxel.world.WorldSize.values().length) {
                selected = i == ctx.worldSizeSelection;
            } else if (ctx.menuScreen == GameContext.MenuScreen.NEW_WORLD_MODE && i < 2) {
                selected = (i == 1) == (ctx.gameMode == GameContext.GameMode.CREATIVE);
            }
            menuButtonBackgrounds[i].color.set(
                hover || selected ? 0.18f : 0.08f,
                hover || selected ? 0.38f : 0.12f,
                hover || selected ? 0.42f : 0.18f,
                hover ? 0.98f : 0.92f);
            menuButtonLabels[i].text = labels[i];
            float scale = menuButtonLabels[i].scale;
            float textWidth = labels[i].length() * 8f * scale;
            menuButtonLabels[i].pos.set(menuButtonBackgrounds[i].pos.x
                    + Math.max(18f, (menuButtonBackgrounds[i].size.x - textWidth) * 0.5f),
                    menuButtonBackgrounds[i].pos.y + 8f);
        }
        menuSubtitle.visible = inMenu && !paused;
        menuHint.visible = inMenu && !paused;

        pauseDimmer.visible = paused;
        pausePanel.visible = paused;
        pauseTitle.visible = paused;
        pauseHint.visible = paused;
        String[] pauseLabels = { "RESUME", "THEME: " + (ctx.uiTheme == GameContext.UiTheme.DARK ? "DARK" : "LIGHT"), "SAVE & QUIT" };
        for (int i = 0; i < pauseButtonBackgrounds.length; i++) {
            pauseButtonBackgrounds[i].visible = paused;
            pauseButtonLabels[i].visible = paused;
            pauseButtonLabels[i].text = pauseLabels[i];
            if (paused) {
                boolean hover = pauseButtonBackgrounds[i].isPointInside(ctx.lastMouseX, ctx.lastMouseY);
                boolean selected = i == ctx.pauseSelection;
                pauseButtonBackgrounds[i].color.set(hover || selected ? 0.18f : 0.10f,
                    hover || selected ? 0.38f : 0.15f,
                    hover || selected ? 0.42f : 0.23f,
                    hover ? 1.0f : 0.96f);
            }
        }
    }

    public void updateSpawnLoadingOverlay(double time) {
        updateMenuControls(time);
        boolean inMenu = ctx.menuScreen != GameContext.MenuScreen.IN_GAME;
        boolean loading = ctx.spawnLoading;
        boolean preWorld = ctx.initializing || inMenu;
        boolean menuActive = inMenu;
        menuBackground.visible = menuActive;
        if (main.panoramaActive) {
            // The 3D panorama renders behind the menu: fade the 2D backdrop image
            // out entirely, but keep the element visible (alpha 0) so it still
            // consumes clicks and hidden inventory controls can't be hit.
            menuBackground.color.w = 0f;
        } else {
            menuBackground.color.w = 1f;
            if (menuActive && menuBackground.textureId == 0) {
                menuBackground.textureId = ctx.uiTheme == GameContext.UiTheme.DARK
                    ? menuBackgroundDarkTextureId : menuBackgroundTextureId;
            }
        }
        spawnLoadingBackground.visible = loading && preWorld && !menuActive;
        loadingPopupBackground.visible = loading && !preWorld && !menuActive;
        // Structured controls replace the old monolithic menu text. Keep the
        // legacy title/spinner/status for world loading only.
        spawnLoadingTitle.visible = loading && !menuActive;
        spawnLoadingSpinner.visible = loading && !menuActive;
        spawnLoadingStatus.visible = loading && !menuActive;
        if (!loading && !menuActive) return;

        String title = "WORLD INITIALIZING";
        if (inMenu) {
            switch (ctx.menuScreen) {
                case MAIN: title = "VOXEL ENGINE"; break;
                case NEW_WORLD_NAME: title = "NEW WORLD"; break;
                case NEW_WORLD_SEED: title = "NEW WORLD"; break;
                case NEW_WORLD_SIZE: title = "NEW WORLD"; break;
                case NEW_WORLD_MODE: title = "NEW WORLD"; break;
                case LOAD_SAVE: title = "LOAD SAVE"; break;
                default: break;
            }
        }
        spawnLoadingTitle.text = title;

        String message = ctx.spawnLoadingMessage;
        spawnLoadingStatus.text = (message == null || message.isEmpty())
            ? "Preparing spawn..." : message;

        // Theme-aware palette: light menus get dark text on a pale backdrop.
        boolean dark = ctx.uiTheme == GameContext.UiTheme.DARK;

        // Translucent text backdrop (menu only): dark pane in dark mode, pale
        // pane in light mode so the theme's text color stays readable over the
        // 3D panorama behind it.
        menuTextPanel.visible = menuActive && !ctx.pauseMenuOpen;
        if (dark) {
            menuTextPanel.color.set(0.05f, 0.06f, 0.09f, 0.55f);
        } else {
            menuTextPanel.color.set(1.0f, 1.0f, 1.0f, 0.40f);
        }

        float titleR = dark ? 0.95f : 0.12f;
        float titleG = dark ? 0.85f : 0.10f;
        float titleB = dark ? 0.45f : 0.55f;
        float bodyR = dark ? 0.85f : 0.10f;
        float bodyG = dark ? 0.78f : 0.15f;
        float bodyB = dark ? 0.55f : 0.35f;

        // A small, deterministic pulse makes it clear that the game is working.
        int spinnerFrame = (int) Math.floor(time * 8.0) % 4;
        spawnLoadingSpinner.text = new String[] { "|", "/", "-", "\\" }[spinnerFrame];
        float pulse = 0.72f + 0.28f * (float) Math.abs(Math.sin(time * 3.0));
        spawnLoadingSpinner.color.w = pulse;

        if (menuActive) {
            // Full-screen main menu centered on the panorama backdrop.
            spawnLoadingTitle.pos.set(main.width / 2f - 210f, main.height / 2f - 150f);
            spawnLoadingTitle.scale = 4.5f;
            spawnLoadingTitle.color.set(titleR, titleG, titleB, 1.0f);
            spawnLoadingTitle.charLineLimit = 40;
            spawnLoadingSpinner.pos.set(main.width / 2f - 12, main.height / 2f - 10);
            spawnLoadingSpinner.scale = 3.0f;
            spawnLoadingSpinner.color.set(dark ? 0.95f : 0.2f, dark ? 0.65f : 0.4f, dark ? 0.20f : 0.6f, pulse);
            spawnLoadingStatus.pos.set(main.width / 2f - 300f, main.height / 2f - 90f);
            spawnLoadingStatus.scale = 2.0f;
            spawnLoadingStatus.color.set(bodyR, bodyG, bodyB, 1.0f);
            spawnLoadingStatus.charLineLimit = 46;
        } else if (preWorld) {
            // Centered layout on the full-screen loading artwork.
            spawnLoadingTitle.pos.set(main.width / 2f - 175f, main.height / 2f - 70);
            spawnLoadingTitle.scale = 3.0f;
            spawnLoadingTitle.color.set(0.06f, 0.22f, 0.32f, 1.0f);
            spawnLoadingTitle.charLineLimit = 40;
            spawnLoadingSpinner.pos.set(main.width / 2f - 12, main.height / 2f - 10);
            spawnLoadingSpinner.scale = 3.0f;
            spawnLoadingSpinner.color.set(0.06f, 0.48f, 0.48f, pulse);
            spawnLoadingStatus.pos.set(main.width / 2f - 230, main.height / 2f + 55);
            spawnLoadingStatus.scale = 1.8f;
            spawnLoadingStatus.color.set(0.10f, 0.28f, 0.34f, 1.0f);
            spawnLoadingStatus.charLineLimit = 52;
        } else {
            // Top-right toast: panel at (width-268, 12) sized 256x64.
            float popupX = main.width - 268f;
            float popupY = 12f;
            loadingPopupBackground.pos.set(popupX, popupY);
            spawnLoadingTitle.pos.set(popupX + 42f, popupY + 12f);
            spawnLoadingTitle.scale = 1.3f;
            spawnLoadingTitle.color.set(0.92f, 0.95f, 1.0f, 1.0f);
            spawnLoadingTitle.charLineLimit = 22;
            spawnLoadingSpinner.pos.set(popupX + 222f, popupY + 12f);
            spawnLoadingSpinner.scale = 1.4f;
            spawnLoadingSpinner.color.set(0.98f, 0.78f, 0.36f, pulse);
            spawnLoadingStatus.pos.set(popupX + 42f, popupY + 38f);
            spawnLoadingStatus.scale = 1.0f;
            spawnLoadingStatus.color.set(0.72f, 0.78f, 0.88f, 1.0f);
            spawnLoadingStatus.charLineLimit = 26;
        }
    }

    /** Always update TV overlay regardless of dirty flag. Called from Main.loop(). */
    public void updateTVOverlay(double time, float worldTime) {
        boolean showTV = ctx.tvWatching && ctx.tvSystem != null;
        tvOverlayBg.visible = showTV;
        tvChannelNameText.visible = showTV;
        tvContentText.visible = showTV;
        tvInstructionsText.visible = showTV;
        if (showTV) {
            String channelName = ctx.tvSystem.getChannelName(ctx.tvChannel);
            tvChannelNameText.text = "[" + channelName + "]";
            String display = ctx.tvSystem.getChannelDisplay(ctx.tvChannel, worldTime);
            tvContentText.text = display;
            // Pulse the instructions text
            float pulse = 0.6f + 0.4f * (float)Math.abs(Math.sin(time * 2.0));
            tvInstructionsText.color.set(0.7f, 0.7f, 0.7f, pulse);
        }
    }

    /**
     * Cinematic overlay pass (render thread): letterbox bars, fade-to-black/red,
     * title cards, and the low-health red pulse. Reads state from ctx.cinematic.
     */
    public void updateCinematic(double time) {
        com.voxel.cinematic.CinematicSystem cine = ctx.cinematic;
        float barH = 0f, fadeA = 0f, fadeR = 0f, textA = 0f;
        String title = null, subtitle = null;

        if (cine != null) {
            barH = cine.letterbox * main.height * 0.12f;
            fadeA = cine.fadeAlpha;
            fadeR = cine.fadeRed;
            textA = cine.textAlpha;
            title = cine.title;
            subtitle = cine.subtitle;
        }

        // Letterbox bars
        boolean barsVisible = barH > 0.5f;
        cineBarTop.visible = barsVisible;
        cineBarBottom.visible = barsVisible;
        if (barsVisible) {
            cineBarTop.size.set(main.width, barH);
            cineBarTop.pos.set(0, 0);
            cineBarBottom.size.set(main.width, barH);
            cineBarBottom.pos.set(0, main.height - barH);
        }

        // Fade overlay (black or dark red)
        boolean fadeVisible = fadeA > 0.01f;
        cineFadeQuad.visible = fadeVisible;
        if (fadeVisible) {
            cineFadeQuad.color.set(fadeR * 0.35f, 0f, 0f, fadeA);
        }

        // Title cards
        boolean titleVisible = textA > 0.02f && title != null && !title.isEmpty();
        cineTitleText.visible = titleVisible;
        cineSubtitleText.visible = titleVisible && subtitle != null && !subtitle.isEmpty();
        if (titleVisible) {
            cineTitleText.text = title;
            cineTitleText.textureId = fontTextureId; // built pre-font-load
            cineTitleText.color.w = textA;
            cineSubtitleText.text = subtitle == null ? "" : subtitle;
            cineSubtitleText.textureId = fontTextureId; // built pre-font-load
            cineSubtitleText.color.w = textA * 0.9f;
        }

        // "ESC to skip" prompt while a scene plays (gentle pulse)
        boolean skipHint = cine != null && cine.skipHintVisible;
        cineSkipHint.visible = skipHint;
        if (skipHint) {
            cineSkipHint.pos.set(main.width / 2f - 55f, main.height - barH - 26f);
            cineSkipHint.text = "ESC to skip";
            cineSkipHint.textureId = fontTextureId;
            cineSkipHint.color.w = 0.45f + 0.25f * (float) Math.sin(time * 4.0);
        }

        // Low-health red pulse (polish; independent of scenes)
        boolean hurtPulse = ctx.player != null && !ctx.player.isDead() && ctx.player.getHealth() <= 6.0f;
        if (hurtPulse) {
            lowHealthPulseTime += 1.0 / 60.0;
            float pulse = 0.10f + 0.08f * (float) Math.sin(lowHealthPulseTime * 4.0);
            if (fadeVisible) {
                cineFadeQuad.color.set(
                    Math.max(cineFadeQuad.color.x(), 0.45f), 0f, 0f,
                    Math.min(1f, fadeA + pulse));
            } else {
                cineFadeQuad.visible = true;
                cineFadeQuad.color.set(0.45f, 0f, 0f, pulse);
            }
        }
    }

    /**
     * Billboard pass: drive detection + project markers, then position the
     * prompt geometry and text list. Everything brightens to white when the
     * cursor hovers a marker's anchor.
     */
    public void updateBillboards(double time) {
        // --- Virtual cursor (runs every frame, no dirty guard) ---
        // This MUST live here rather than in updateInventoryUi(), whose early-
        // return dirty check would freeze the cursor in place whenever no
        // inventory state changed.
        if (crosshairElement != null) {
            crosshairElement.visible = !main.inventoryOpen && !main.commandMode;
            if (crosshairElement.visible && main.pointAndClickMode
                    && ctx.menuScreen == GameContext.MenuScreen.IN_GAME) {
                // Small black-core/white-outline cursor (4x4 content in a 16x16
                // texture). Rendered ~16px so the outline is crisp; grows to 20px
                // + green tint when an interactable is under the cursor.
                float sz = main.pacHoveringInteractable ? 20f : 16f;
                float half = sz / 2f;
                crosshairElement.pos.set(main.getSmoothedCursorX() - half, main.getSmoothedCursorY() - half);
                crosshairElement.size.set(sz, sz);
                crosshairElement.color.set(
                    main.pacHoveringInteractable ? 0.4f : 1f,
                    main.pacHoveringInteractable ? 1f : 1f,
                    main.pacHoveringInteractable ? 0.4f : 1f,
                    1f);
            } else {
                // FPS mouselook or menus: tiny centered crosshair.
                float sz = 4f;
                crosshairElement.pos.set(main.width / 2f - sz / 2f, main.height / 2f - sz / 2f);
                crosshairElement.size.set(sz, sz);
                crosshairElement.color.set(1, 1, 1, 1);
            }
        }

        if (billboards == null) return;
        billboards.update(time);
        int count = billboards.getMarkerCount();
        for (int i = 0; i < MAX_BILLBOARDS; i++) {
            boolean on = i < count;
            for (int p = 0; p < MARKER_PART_COUNT; p++) markerParts[i][p].visible = on;
            UILayer.UITextElement nameEl = markerNameTexts[i];
            if (!on) {
                nameEl.visible = false;
                for (int j = 0; j < MAX_MARKER_ACTIONS; j++) markerActionTexts[i][j].visible = false;
                continue;
            }
            float x = billboards.getMarkerX(i);
            float y = billboards.getMarkerY(i);
            float a = billboards.getMarkerAlpha(i);
            boolean hot = billboards.isMarkerHighlighted(i);
            String name = billboards.getMarkerName(i);
            int nAct = Math.min(billboards.getMarkerActionCount(i), MAX_MARKER_ACTIONS);

            // --- Text metrics (needed before choosing line direction) ---
            float nameScale = hot ? 2.67f : 2.17f;
            float actScale = hot ? 2.25f : 1.92f;
            float nameLineH = 12f * nameScale;
            float actLineH = 11f * actScale;
            float listH = nameLineH + nAct * actLineH;

            // --- Palette: white lines with a grey 1px outline on both sides ---
            Vector4f white = new Vector4f(1f, 1f, 1f, a);
            Vector4f grey = new Vector4f(0.55f, 0.55f, 0.55f, a);

            // --- Geometry: hollow square -> 45° elbow -> vertical stem ---
            float sq = hot ? 45f : 35f, half = sq / 2f;
            float t = 5f;                       // line thickness
            float o = MARKER_OUTLINE;           // grey outline width per side
            float d = hot ? 130f : 100f;        // diagonal advance per axis
            float stemLen = hot ? 210f : 170f;

            // Flip the elbow downward when the text list would run off-screen.
            boolean up = (y - half - d - stemLen - listH) > 8f;
            int dir = up ? 1 : -1;

            // Hollow square centred on the anchor (white edges inside the
            // square bounds; black strips wrap 2px around the outside,
            // covering corners via the full-width top/bottom strips).
            UILayer.UIElement wl = markerParts[i][P_WL], wr = markerParts[i][P_WR];
            UILayer.UIElement wt = markerParts[i][P_WT], wb = markerParts[i][P_WB];
            wl.pos.set(x - half, y - half);           wl.size.set(t, sq);
            wr.pos.set(x + half - t, y - half);       wr.size.set(t, sq);
            wt.pos.set(x - half, y - half);           wt.size.set(sq, t);
            wb.pos.set(x - half, y + half - t);       wb.size.set(sq, t);
            UILayer.UIElement bl = markerParts[i][P_BL], br = markerParts[i][P_BR];
            UILayer.UIElement bt = markerParts[i][P_BT], bb = markerParts[i][P_BB];
            bt.pos.set(x - half - o, y - half - o);   bt.size.set(sq + 2 * o, o);
            bb.pos.set(x - half - o, y + half);       bb.size.set(sq + 2 * o, o);
            bl.pos.set(x - half - o, y - half);       bl.size.set(o, sq);
            br.pos.set(x + half, y - half);           br.size.set(o, sq);

            // 45° elbow out of the square's right corner. The UI shader
            // rotates around uPos (the quad's top-left corner), NOT its
            // centre — so back off along the rotated thickness axis to place
            // the bar's leading edge exactly on the corner. With y-down and
            // clockwise-positive rotation, +45° runs up-right and -45° down-
            // right; sin(45°)=cos(45°)=k.
            float k = 0.70710678f;
            float sSin = dir > 0 ? k : -k;      // sin(±45°)
            float diagLen = d * 1.4142f;
            float cornerX = x + half, cornerY = up ? y - half : y + half;
            UILayer.UIElement wdia = markerParts[i][P_WDIA];
            wdia.rotation = dir > 0 ? 45f : -45f;
            wdia.pos.set(cornerX - (t / 2f) * sSin, cornerY - (t / 2f) * k);
            wdia.size.set(diagLen, t);
            // Black backing: same pivot math with thickness t+2o and length
            // diagLen+2o; its local top-left sits at (-o,-o) relative to the
            // white bar's frame → world offset R·(-o,-o) = (-o(c+s), o(s-c)).
            UILayer.UIElement bdia = markerParts[i][P_BDIA];
            bdia.rotation = wdia.rotation;
            bdia.pos.set(wdia.pos.x - o * (k + sSin), wdia.pos.y + o * (sSin - k));
            bdia.size.set(diagLen + 2 * o, t + 2 * o);

            // Vertical stem from the elbow to the text list.
            float elbowX = cornerX + d, elbowY = cornerY - dir * d;
            float stemTop = dir > 0 ? elbowY - stemLen : elbowY;
            UILayer.UIElement wstem = markerParts[i][P_WSTEM];
            wstem.pos.set(elbowX - t / 2f, stemTop);
            wstem.size.set(t, stemLen);
            UILayer.UIElement bstem = markerParts[i][P_BSTEM];
            bstem.pos.set(elbowX - t / 2f - o, stemTop - o);
            bstem.size.set(t + 2 * o, stemLen + 2 * o);

            for (int pIdx : new int[]{P_BL, P_BR, P_BT, P_BB, P_BDIA, P_BSTEM}) {
                markerParts[i][pIdx].color.set(grey);
            }
            for (int pIdx : new int[]{P_WL, P_WR, P_WT, P_WB, P_WDIA, P_WSTEM}) {
                markerParts[i][pIdx].color.set(white);
            }

            // --- Name + action list beside the end of the stem ---
            float listX = elbowX + 20f;
            float widest = (name == null ? 0 : name.length()) * 8f * nameScale;
            for (int j = 0; j < nAct; j++) {
                String s = billboards.getMarkerAction(i, j);
                widest = Math.max(widest, (s == null ? 0 : s.length()) * 8f * actScale);
            }
            if (listX + widest > main.width - 40f) {
                listX = elbowX - 20f - widest; // mirror to the left of the stem
            }

            // Grey outline shared by all prompt text (alpha tracks the fill).
            Vector4f textOutline = new Vector4f(0.35f, 0.35f, 0.35f, 1f);

            nameEl.visible = true;
            // These elements are built before tryLoadFontTexture() runs, so
            // they captured fontTextureId == 0; refresh the id every frame.
            nameEl.textureId = fontTextureId;
            nameEl.text = name == null ? "" : name;
            nameEl.scale = nameScale;
            nameEl.color.set(white);
            nameEl.outlined = true;
            nameEl.outlineColor.set(textOutline);
            nameEl.outlineWidth = 2f;
            // Stack: name first, then actions; block starts just past the stem.
            float lineY = dir > 0 ? elbowY - stemLen - listH - 4f : elbowY + stemLen + 4f;
            nameEl.pos.set(listX, lineY);
            lineY += nameLineH;
            for (int j = 0; j < MAX_MARKER_ACTIONS; j++) {
                UILayer.UITextElement act = markerActionTexts[i][j];
                if (j >= nAct) { act.visible = false; continue; }
                act.visible = true;
                act.textureId = fontTextureId;
                act.outlined = true;
                act.outlineColor.set(textOutline);
                act.outlineWidth = 2f;
                String s = billboards.getMarkerAction(i, j);
                act.text = s == null ? "" : "- " + s;
                act.scale = actScale;
                float acol = hot ? 1f : 0.7f;
                act.color.set(acol, acol, acol, a);
                act.pos.set(listX, lineY);
                lineY += actLineH;
            }
        }
    }

    public void updateWindowTitle() {
        double now = glfwGetTime();
        if (now - lastTitleUpdate < 0.25) return;
        lastTitleUpdate = now;
        StringBuilder title = new StringBuilder("Voxel Engine | FPS: ").append(ctx.lastMeasuredFps);
        // During the loading-screen phase the heavy world init still runs on the
        // LOGIC thread (GameContext.initializing) and chunkManager / world are
        // not yet wired. Show just the FPS until Main.initializeWorldPhase()
        // finishes; otherwise we'd NPE on ctx.chunkManager.isChunkLoaded below.
        if (ctx.initializing || ctx.chunkManager == null) {
            glfwSetWindowTitle(main.window, title.toString());
            return;
        }
        title.append(" | ").append(ctx.gameMode == GameContext.GameMode.CREATIVE ? "creative" : "survival");
        com.voxel.Player p = main.player;
        long pfx = p.getFixedX(), pfy = p.getFixedY(), pfz = p.getFixedZ();
        title.append(String.format(Locale.US, " | XYZ: %d.%02d, %d.%02d, %d.%02d",
            FixedPoint.camBlock(pfx), (int)(FixedPoint.camFrac(pfx) * 100 + 0.5f),
            FixedPoint.camBlock(pfy), (int)(FixedPoint.camFrac(pfy) * 100 + 0.5f),
            FixedPoint.camBlock(pfz), (int)(FixedPoint.camFrac(pfz) * 100 + 0.5f)));
        int bx = FixedPoint.camBlock(pfx);
        int bz = FixedPoint.camBlock(pfz);
        // Dimension/provider wiring can briefly race the first render frame, and
        // an unmapped biome id is also allowed to return null. The title is
        // diagnostic UI, so keep startup alive and show a neutral fallback.
        String biomeName = "Unknown";
        if (biomeManager != null && biomeManager.getBiomeProvider() != null) {
            com.voxel.biome.Biome biome = biomeManager.getBiomeProvider().getBiome(bx, bz);
            if (biome != null && biome.name != null && !biome.name.isEmpty()) {
                biomeName = biome.name;
            }
        }
        title.append(" | ").append(biomeName);
        int pcx = FixedPoint.camBlock(pfx) >> 4;
        int pcz = FixedPoint.camBlock(pfz) >> 4;
        if (!ctx.chunkManager.isChunkLoaded(pcx, pcz)) title.append(" [WAITING FOR CHUNKS]");
        if (main.commandMode) {
            title.append(" | CMD ").append(main.commandBuffer);
        } else if (!main.statusMessage.isEmpty() && glfwGetTime() < main.statusUntil) {
            title.append(" | ").append(main.statusMessage);
        }
        glfwSetWindowTitle(main.window, title.toString());
    }

    public void beginFrame() {
        uiManager.begin();
        for (UILayer layer : uiLayers) layer.render(uiManager);
        uiManager.end();
    }
}
