package com.voxel.ui;

import com.voxel.Main;
import com.voxel.camera.CameraController;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.Entity;
import com.voxel.entity.EntityManager;
import com.voxel.game.FurnaceManager;
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
    // Compact top-right loading-popup panel (loading_popup.png, 256x64).
    public int loadingPopupTextureId = 0;

    public UILayer.UIElement crosshairElement;
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
    public final UILayer.UIElement[] chestSlotBackgrounds = new UILayer.UIElement[20];
    public final UILayer.UIElement[] chestSlotItems       = new UILayer.UIElement[20];
    public final UILayer.UIElement[] chestCountBars       = new UILayer.UIElement[20];
    public final UILayer.UIElement[] chestCountDigit1     = new UILayer.UIElement[20];
    public final UILayer.UIElement[] chestCountDigit2     = new UILayer.UIElement[20];

    public final UILayer.UIElement[] playerHearts = new UILayer.UIElement[10];
    public final UILayer.UIElement[] heartBases   = new UILayer.UIElement[10];

    public UILayer.UITextElement commandTextElement;
    public UILayer.UITextElement statusTextElement;

    // ── TV overlay elements ──
    public UILayer.UIElement tvOverlayBg;
    public UILayer.UITextElement tvChannelNameText;
    public UILayer.UITextElement tvContentText;
    public UILayer.UITextElement tvInstructionsText;

    // ── Spawn loading overlay ──
    // These are appended last so the overlay is composited above the normal HUD.
    public UILayer.UIElement spawnLoadingBackground;
    public UILayer.UIElement loadingPopupBackground; // top-right toast panel
    public UILayer.UITextElement spawnLoadingTitle;
    public UILayer.UITextElement spawnLoadingStatus;
    public UILayer.UITextElement spawnLoadingSpinner;

    public double itemNameDisplayUntil = 0.0;
    public boolean inventoryUiDirty = true;

    public boolean prevInventoryOpenForUi = false;
    public boolean prevCommandModeForUi = false;
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
    }

    // ── Setup ─────────────────────────────────────────────────────────────────────

    public void setupUi() { setup(main.width, main.height); }

    public void setup(int width, int height) {
        uiManager = new UIManager(width, height);
        UILayer hudLayer = new UILayer();

        crosshairElement = new UILayer.UIElement(
            new Vector2f(width / 2f - 2, height / 2f - 2),
            new Vector2f(4, 4),
            new Vector4f(1, 1, 1, 1)
        );
        hudLayer.addElement(crosshairElement);

        inventoryPanelElement = new UILayer.UIElement(
            new Vector2f(Main.HOTBAR_X - 8, Main.HOTBAR_Y - 12),
            new Vector2f(Main.INVENTORY_PANEL_WIDTH, Main.INVENTORY_PANEL_HEIGHT),
            new Vector4f(0, 0, 0, 0.45f)
        );
        inventoryPanelElement.visible = false;
        hudLayer.addElement(inventoryPanelElement);

        tryLoadUiTexture();
        tryLoadFontTexture();
        tryLoadLoadingTexture();
        tryLoadLoadingPopupTexture();
        buildInventoryUi(hudLayer);
        uiLayers.add(hudLayer);
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

        // Furnace UI
        int furnaceX = Main.HOTBAR_X + 20;
        int furnaceY = Main.HOTBAR_Y - 160;
        int furnaceSlotW = Main.SLOT_W;
        int furnaceSlotH = Main.SLOT_H;
        int furnacePanelW = 4 * furnaceSlotW + 80;
        int furnacePanelH = 2 * furnaceSlotH + 60;

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

        // Chest UI
        float chestGridW = 10 * (Main.SLOT_W + 8) - 8;
        float chestGridH = 2 * Main.SLOT_H;
        int chestX = Main.HOTBAR_X;
        int chestY = Main.HOTBAR_Y - (int)chestGridH - 20;

        chestPanelBg = new UILayer.UIElement(new Vector2f(chestX - 8, chestY - 12), new Vector2f(chestGridW + 16, chestGridH + 24), new Vector4f(0.2f, 0.15f, 0.1f, 0.6f));
        chestPanelBg.visible = false;
        layer.addElement(chestPanelBg);

        for (int i = 0; i < 20; i++) {
            int row = i / 10;
            int col = i % 10;
            float cx = chestX + col * (Main.SLOT_W + 8);
            float cy = chestY + row * Main.SLOT_H;

            UILayer.UIElement bg = new UILayer.UIElement(new Vector2f(cx, cy), new Vector2f(Main.SLOT_W, Main.SLOT_H), new Vector4f(0.85f, 0.7f, 0.55f, 1));
            final int slotIdx = i;
            bg.onClick = () -> { main.handleChestSlotClick(slotIdx); inventoryUiDirty = true; };
            bg.visible = false;
            chestSlotBackgrounds[i] = bg;
            layer.addElement(bg);

            UILayer.UIElement itemEl = new UILayer.UIElement(new Vector2f(cx + 24, cy + 16), new Vector2f(40, 40), new Vector4f(0, 0, 0, 0));
            itemEl.visible = false;
            chestSlotItems[i] = itemEl;
            layer.addElement(itemEl);

            UILayer.UIElement countBar = new UILayer.UIElement(new Vector2f(cx + 12, cy + Main.SLOT_H - 12), new Vector2f(0, 6), new Vector4f(1, 1, 1, 0.9f));
            countBar.visible = false;
            chestCountBars[i] = countBar;
            layer.addElement(countBar);

            UILayer.UIElement digit1 = new UILayer.UIElement(new Vector2f(cx + Main.SLOT_W - 32, cy + Main.SLOT_H - 24), new Vector2f(16, 16), new Vector4f(1, 1, 1, 1));
            digit1.visible = false;
            digit1.textureId = fontTextureId;
            chestCountDigit1[i] = digit1;
            layer.addElement(digit1);

            UILayer.UIElement digit2 = new UILayer.UIElement(new Vector2f(cx + Main.SLOT_W - 18, cy + Main.SLOT_H - 24), new Vector2f(16, 16), new Vector4f(1, 1, 1, 1));
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
            carriedItemElement.textureId = textureManager.getTextureArrayId();
            carriedItemElement.textureType = 2;
            carriedItemElement.layer = carriedDef.iconLayer;
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
        if (!inventoryUiDirty && main.inventoryOpen == prevInventoryOpenForUi && main.commandMode == prevCommandModeForUi
                && selSlot == prevSelectedSlot && Math.abs(hp - prevHealth) < 0.05f) {
            return;
        }
        inventoryUiDirty = false;
        prevInventoryOpenForUi = main.inventoryOpen;
        prevCommandModeForUi = main.commandMode;
        prevSelectedSlot = selSlot;
        prevHealth = hp;
        double time = glfwGetTime();
        crosshairElement.visible = !main.inventoryOpen && !main.commandMode;
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
            for (int i = 0; i < 20; i++) {
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
                    furnaceInputItem.textureId = textureManager.getTextureArrayId();
                    furnaceInputItem.textureType = 2;
                    furnaceInputItem.layer = def.iconLayer;
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
                    furnaceFuelItem.textureId = textureManager.getTextureArrayId();
                    furnaceFuelItem.textureType = 2;
                    furnaceFuelItem.layer = def.iconLayer;
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
                    furnaceOutputItem.textureId = textureManager.getTextureArrayId();
                    furnaceOutputItem.textureType = 2;
                    furnaceOutputItem.layer = def.iconLayer;
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

            ItemStack[] chestInv = ctx.chestManager.getInventory(ctx.chestBlockX, ctx.chestBlockY, ctx.chestBlockZ);
            for (int i = 0; i < 20; i++) {
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
                itemEl.visible = true;
                itemEl.textureId = textureManager.getTextureArrayId();
                itemEl.textureType = 2;
                itemEl.layer = def.iconLayer;
                itemEl.color.set(1, 1, 1, 1);
                itemEl.size.set(40, 40);
                itemEl.pos.set(chestSlotBackgrounds[i].pos.x + 24, chestSlotBackgrounds[i].pos.y + 16);

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
                    countBar.pos.set(chestSlotBackgrounds[i].pos.x + 12, chestSlotBackgrounds[i].pos.y + Main.SLOT_H - 12);
                    countBar.size.set((Main.SLOT_W - 24) * Math.min(stack.count, def.maxStack) / (float) def.maxStack, 6);

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
            for (int i = 0; i < 20; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
                chestCountBars[i].visible = false;
                chestCountDigit1[i].visible = false;
                chestCountDigit2[i].visible = false;
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
                    craftingButtonItem.textureId = textureManager.getTextureArrayId();
                    craftingButtonItem.textureType = 2;
                    craftingButtonItem.layer = resultDef.iconLayer;
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
            for (int i = 0; i < 20; i++) {
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
                    craftingButtonItem.textureId = textureManager.getTextureArrayId();
                    craftingButtonItem.textureType = 2;
                    craftingButtonItem.layer = resultDef.iconLayer;
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
            itemElement.visible = true;
            itemElement.textureId = textureManager.getTextureArrayId();
            itemElement.textureType = 2; // Array
            itemElement.layer = definition.iconLayer;
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
    public void updateSpawnLoadingOverlay(double time) {
        boolean loading = ctx.spawnLoading;
        boolean preWorld = ctx.initializing;
        spawnLoadingBackground.visible = loading && preWorld;
        loadingPopupBackground.visible = loading && !preWorld;
        spawnLoadingTitle.visible = loading;
        spawnLoadingSpinner.visible = loading;
        spawnLoadingStatus.visible = loading;
        if (!loading) return;

        String title = "WORLD INITIALIZING";
        spawnLoadingTitle.text = title;

        String message = ctx.spawnLoadingMessage;
        spawnLoadingStatus.text = (message == null || message.isEmpty())
            ? "Preparing spawn..." : message;

        // A small, deterministic pulse makes it clear that the game is working.
        int spinnerFrame = (int) Math.floor(time * 8.0) % 4;
        spawnLoadingSpinner.text = new String[] { "|", "/", "-", "\\" }[spinnerFrame];
        float pulse = 0.72f + 0.28f * (float) Math.abs(Math.sin(time * 3.0));
        spawnLoadingSpinner.color.w = pulse;

        if (preWorld) {
            // Centered layout on the full-screen loading artwork.
            spawnLoadingTitle.pos.set(main.width / 2f - 175, main.height / 2f - 70);
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
