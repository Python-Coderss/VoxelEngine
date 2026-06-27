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

    public final UILayer.UIElement[] craftingSlotBackgrounds = new UILayer.UIElement[Main.CRAFTING_SLOTS];
    public final UILayer.UIElement[] craftingSlotItems       = new UILayer.UIElement[Main.CRAFTING_SLOTS];

    public final UILayer.UIElement[] crafting3x3SlotBackgrounds = new UILayer.UIElement[9];
    public final UILayer.UIElement[] crafting3x3SlotItems       = new UILayer.UIElement[9];
    public UILayer.UIElement craftingTableBg;

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

    public void buildInventoryUi(UILayer layer) {
        float halfU = 0.5f / uiTextureSize.x;
        float halfV = 0.5f / uiTextureSize.y;
        float uScaleInset = (float) (Main.SLOT_TEX_W - 1) / uiTextureSize.x;
        float vScaleInset = (float) (Main.SLOT_TEX_H - 1) / uiTextureSize.y;

        // 2x2 crafting grid + result
        int craftingGridX = Main.HOTBAR_X + 440;
        int craftingGridY = Main.HOTBAR_Y + 20;
        for (int index = 0; index < Main.CRAFTING_SLOTS; index++) {
            boolean isResult = index == Main.CRAFTING_RESULT_SLOT;
            int row = index / 2;
            int col = index % 2;
            float cx = craftingGridX + col * (Main.SLOT_W + 8);
            float cy = craftingGridY + row * Main.SLOT_H;
            if (isResult) {
                cx = craftingGridX + 2 * (Main.SLOT_W + 8) + 16;
                cy = craftingGridY + Main.SLOT_H / 2;
            }

            UILayer.UIElement background = new UILayer.UIElement(
                new Vector2f(cx, cy),
                new Vector2f(Main.SLOT_W, Main.SLOT_H),
                new Vector4f(isResult ? 1.0f : 0.9f, isResult ? 0.9f : 0.9f, isResult ? 0.8f : 0.9f, 1)
            );
            if (uiTextureId != 0) {
                background.textureId = uiTextureId;
                background.uvOffset = new Vector2f(halfU, halfV);
                background.uvScale = new Vector2f(uScaleInset, vScaleInset);
            }
            final int slotIndex = index;
            background.onClick = () -> { playerInventory.handleCraftingSlotClick(slotIndex); inventoryUiDirty = true; };
            background.visible = false;
            craftingSlotBackgrounds[index] = background;
            layer.addElement(background);

            UILayer.UIElement itemElement = new UILayer.UIElement(
                new Vector2f(cx + 24, cy + 16),
                new Vector2f(40, 40),
                new Vector4f(0, 0, 0, 0)
            );
            itemElement.visible = false;
            craftingSlotItems[index] = itemElement;
            layer.addElement(itemElement);
        }

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

        statusTextElement = new UILayer.UITextElement(new Vector2f(20, main.height - 70), "", 2.0f, new Vector4f(1, 1, 0.5f, 1), fontTextureId);
        statusTextElement.charLineLimit = 20;
        statusTextElement.visible = false;
        layer.addElement(statusTextElement);
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

        commandTextElement.visible = main.commandMode;
        if (main.commandMode) commandTextElement.text = main.commandBuffer.toString() + "_";

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
        boolean useFurnace = ctx.furnaceOpen && ctx.activeUI == ActiveUI.FURNACE;
        boolean useChest = ctx.chestOpen && ctx.activeUI == ActiveUI.CHEST;

        // Slot/UI updates (inlined from Main.updateInventoryUi)
// --- Furnace UI ---
        if (useFurnace) {
            // Hide crafting UIs
            for (int i = 0; i < Main.CRAFTING_SLOTS; i++) {
                craftingSlotBackgrounds[i].visible = false;
                craftingSlotItems[i].visible = false;
            }
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
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
            for (int i = 0; i < Main.CRAFTING_SLOTS; i++) {
                craftingSlotBackgrounds[i].visible = false;
                craftingSlotItems[i].visible = false;
            }
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
            for (int i = 0; i < Main.CRAFTING_SLOTS; i++) {
                craftingSlotBackgrounds[i].visible = false;
                craftingSlotItems[i].visible = false;
            }
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
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
        } else if (!useFurnace && !useChest) {
            // Show 2x2 crafting slots (default inventory mode)
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            // Hide furnace/chest in default mode
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

            for (int i = 0; i < Main.CRAFTING_SLOTS; i++) {
                boolean isResult = i == Main.CRAFTING_RESULT_SLOT;
                boolean slotVisible = main.inventoryOpen;
                craftingSlotBackgrounds[i].visible = slotVisible;

                UILayer.UIElement itemElement = craftingSlotItems[i];
                String itemId = null;

                if (isResult) {
                    CraftingManager.CraftingRecipe match = ctx.craftingManager.matchRecipe(playerInventory.getCraftingGrid());
                    if (match != null) {
                        itemId = match.resultItemId;
                    }
                } else {
                    int gridRow = i / 2;
                    int gridCol = i % 2;
                    itemId = playerInventory.getCraftingGrid()[gridRow][gridCol];
                }

                if (slotVisible && itemId != null) {
                    ItemDefinition definition = itemDefinitions.getDefinition(itemId);
                    itemElement.visible = true;
                    itemElement.textureId = textureManager.getTextureArrayId();
                    itemElement.textureType = 2; // Array
                    itemElement.layer = definition.iconLayer;
                    itemElement.color.set(1, 1, 1, 1);
                    itemElement.pos.set(craftingSlotBackgrounds[i].pos.x + 24, craftingSlotBackgrounds[i].pos.y + 16);
                    itemElement.size.set(40, 40);
                } else {
                    itemElement.visible = false;
                }
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

    public void updateWindowTitle() {
        double now = glfwGetTime();
        if (now - lastTitleUpdate < 0.25) return;
        lastTitleUpdate = now;
        StringBuilder title = new StringBuilder("Voxel Engine | FPS: ").append(ctx.lastMeasuredFps);
        title.append(" | ").append(ctx.gameMode == GameContext.GameMode.CREATIVE ? "creative" : "survival");
        com.voxel.Player p = main.player;
        Vector3f pos = p.getPosition();
        title.append(String.format(Locale.US, " | XYZ: %.2f, %.2f, %.2f", pos.x, pos.y, pos.z));
        int bx = (int) Math.floor(pos.x);
        int bz = (int) Math.floor(pos.z);
        com.voxel.biome.Biome biome = biomeManager.getBiomeProvider().getBiome(bx, bz);
        title.append(" | ").append(biome.name);
        int pcx = (int) Math.floor(pos.x) >> 4;
        int pcz = (int) Math.floor(pos.z) >> 4;
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
