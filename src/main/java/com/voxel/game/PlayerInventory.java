package com.voxel.game;

import com.voxel.crafting.CraftingManager;

/**
 * Manages the player's inventory array, hotbar selection, carried stack,
 * crafting grid, and item add/remove operations.
 */
public class PlayerInventory {
    public static final int HOTBAR_SIZE = 5;
    public static final int INVENTORY_SIZE = 20;
    public static final int CRAFTING_SLOTS = 5;
    public static final int CRAFTING_RESULT_SLOT = 4;
    public static final int CRAFTING_3X3_SLOTS = 9;   // 3x3 ingredient grid

    private final ItemDefinitions.ItemStack[] inventory = new ItemDefinitions.ItemStack[INVENTORY_SIZE];
    private String[][] craftingGrid = new String[2][2];
    private String[][] craftingGrid3x3 = new String[3][3];
    private int selectedSlot = 0;
    private ItemDefinitions.ItemStack carriedStack;

    // Crafting table preview state. Inputs stay in the grid until the Craft button is pressed.
    private boolean crafting3x3HasResult = false;
    private String crafting3x3ResultItemId;
    private int crafting3x3ResultCount = 0;

    private final GameContext ctx;

    public PlayerInventory(GameContext ctx) {
        this.ctx = ctx;
    }

    // --- Accessors ---
    public int getSelectedSlot() { return selectedSlot; }
    public void setSelectedSlot(int slot) { this.selectedSlot = slot; }
    public int getInventorySize() { return INVENTORY_SIZE; }
    public ItemDefinitions.ItemStack getSlot(int i) { return inventory[i]; }
    public void setSlot(int i, ItemDefinitions.ItemStack stack) { inventory[i] = stack; }
    public ItemDefinitions.ItemStack getCarriedStack() { return carriedStack; }
    public void setCarriedStack(ItemDefinitions.ItemStack stack) { this.carriedStack = stack; }
    /** Legacy 2x2 recipe buffer used by surface crafting; not rendered in inventory. */
    public String[][] getCraftingGrid() { return craftingGrid; }
    public String[][] getCraftingGrid3x3() { return craftingGrid3x3; }
    public boolean hasCrafting3x3Result() { return crafting3x3HasResult; }
    public String getCrafting3x3ResultItemId() { return crafting3x3ResultItemId; }
    public int getCrafting3x3ResultCount() { return crafting3x3ResultCount; }

    public void clearSlot(int i) {
        inventory[i] = null;
        if (carriedStack != null && i == selectedSlot && !ctx.inventoryOpen) carriedStack = null;
    }

    public ItemDefinitions.ItemStack getSelected() { return inventory[selectedSlot]; }

    /**
     * Replaces the currently selected slot item with a new item (keeping count=1).
     * Used by bucket interactions (bucket → water_bucket, water_bucket → bucket).
     */
    public void replaceSelected(String newItemId) {
        if (ctx.gameMode == GameContext.GameMode.CREATIVE) return; // Creative: no consumption
        ItemDefinitions.ItemStack sel = inventory[selectedSlot];
        if (sel != null) {
            sel.count--;
            if (sel.count <= 0) {
                inventory[selectedSlot] = new ItemDefinitions.ItemStack(newItemId, 1);
            } else {
                // Try to add the new item to inventory; if full, drop on ground
                if (!addItem(newItemId, 1)) {
                    if (ctx.droppedItemManager != null) {
                        ctx.droppedItemManager.spawn(newItemId, 1,
                            (int) ctx.player.getPosition().x,
                            (int) ctx.player.getPosition().y,
                            (int) ctx.player.getPosition().z);
                    }
                }
            }
        }
    }

    // --- Item management ---
    public boolean addItem(String itemId, int count) {
        ItemDefinitions.ItemDefinition def = ctx.itemDefinitions.getDefinition(itemId);
        if (def == null || count <= 0) return false;
        int remaining = count;

        // Stack onto existing stacks first
        if (def.maxStack > 1) {
            for (int i = 0; i < INVENTORY_SIZE && remaining > 0; i++) {
                ItemDefinitions.ItemStack stack = inventory[i];
                if (stack != null && stack.itemId.equals(itemId) && stack.count < def.maxStack) {
                    int moved = Math.min(def.maxStack - stack.count, remaining);
                    stack.count += moved;
                    remaining -= moved;
                }
            }
        }

        // Fill empty slots
        for (int i = 0; i < INVENTORY_SIZE && remaining > 0; i++) {
            if (inventory[i] == null) {
                int moved = Math.min(def.maxStack, remaining);
                inventory[i] = new ItemDefinitions.ItemStack(itemId, moved);
                remaining -= moved;
            }
        }
        return remaining == 0;
    }

    /** Returns whether the entire stack can fit without partially mutating inventory. */
    private boolean canAddItem(String itemId, int count) {
        ItemDefinitions.ItemDefinition def = ctx.itemDefinitions.getDefinition(itemId);
        if (def == null || count <= 0) return false;
        int capacity = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemDefinitions.ItemStack stack = inventory[i];
            if (stack == null) {
                capacity += def.maxStack;
            } else if (stack.itemId.equals(itemId) && stack.count < def.maxStack) {
                capacity += def.maxStack - stack.count;
            }
            if (capacity >= count) return true;
        }
        return false;
    }

    public void populateStarting() {
        inventory[0] = new ItemDefinitions.ItemStack("wood_pickaxe", 1);
        inventory[1] = new ItemDefinitions.ItemStack("wood_shovel", 1);
        inventory[2] = new ItemDefinitions.ItemStack("wood_axe", 1);
        inventory[3] = new ItemDefinitions.ItemStack("oak_log", 32);
        inventory[4] = new ItemDefinitions.ItemStack("skyroot_planks", 32);
        inventory[5] = new ItemDefinitions.ItemStack("dirt", 32);
        inventory[6] = new ItemDefinitions.ItemStack("stone", 32);
        inventory[7] = new ItemDefinitions.ItemStack("crafting_table", 8);
    }

    // --- Slot click handling ---
    public void handleInventorySlotClick(int slotIndex) {
        if (!ctx.inventoryOpen) return;
        ItemDefinitions.ItemStack slotStack = inventory[slotIndex];

        if (carriedStack == null) {
            if (slotStack == null) return;
            carriedStack = slotStack;
            inventory[slotIndex] = null;
            return;
        }
        if (slotStack == null) {
            inventory[slotIndex] = carriedStack;
            carriedStack = null;
            return;
        }
        if (slotStack.itemId.equals(carriedStack.itemId)) {
            ItemDefinitions.ItemDefinition def = ctx.itemDefinitions.getDefinition(slotStack.itemId);
            if (def != null && def.maxStack > 1 && slotStack.count < def.maxStack) {
                int moved = Math.min(def.maxStack - slotStack.count, carriedStack.count);
                slotStack.count += moved;
                carriedStack.count -= moved;
                if (carriedStack.count <= 0) carriedStack = null;
                return;
            }
        }
        inventory[slotIndex] = carriedStack;
        carriedStack = slotStack;
    }

    public void handleCraftingSlotClick(int slotIndex) {
        if (!ctx.inventoryOpen || ctx.activeUI != GameContext.ActiveUI.SURFACE_CRAFTING) return;
        if (slotIndex == CRAFTING_RESULT_SLOT) {
            CraftingManager.CraftingRecipe match = ctx.craftingManager.matchRecipe(craftingGrid);
            if (match != null) {
                if (addItem(match.resultItemId, match.resultCount)) {
                    ctx.craftingManager.consumeItems(craftingGrid);
                }
            }
        } else {
            int gridRow = slotIndex / 2;
            int gridCol = slotIndex % 2;
            String gridItem = craftingGrid[gridRow][gridCol];
            if (carriedStack == null) {
                if (gridItem != null) {
                    carriedStack = new ItemDefinitions.ItemStack(gridItem, 1);
                    craftingGrid[gridRow][gridCol] = null;
                }
            } else {
                if (gridItem == null) {
                    craftingGrid[gridRow][gridCol] = carriedStack.itemId;
                    carriedStack.count--;
                    if (carriedStack.count <= 0) carriedStack = null;
                }
            }
        }
        // Keep the ingredients attached to the targeted block immediately. Do
        // not wait for the overlay to close: dimension switches, interruptions,
        // or another UI transition can otherwise discard the temporary buffer.
        saveSurfaceCraftingGrid();
        if (ctx.worldSaveManager != null) {
            ctx.worldSaveManager.saveSurfaceCraftingData(ctx.activeDimension, ctx.surfaceCraftingManager);
        }
    }

    /** Opens/refreshes the 2x2 surface-crafting buffer for a target block. */
    public void loadSurfaceCraftingGrid(int x, int y, int z) {
        ctx.surfaceCraftingBlockX = x;
        ctx.surfaceCraftingBlockY = y;
        ctx.surfaceCraftingBlockZ = z;
        String[][] saved = ctx.surfaceCraftingManager.getGrid(x, y, z);
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                craftingGrid[r][c] = saved != null ? saved[r][c] : null;
            }
        }
    }

    public void saveSurfaceCraftingGrid() {
        ctx.surfaceCraftingManager.setGrid(ctx.surfaceCraftingBlockX, ctx.surfaceCraftingBlockY,
            ctx.surfaceCraftingBlockZ, craftingGrid);
    }

    /** Returns the currently valid surface-crafting recipe, if any. */
    public CraftingManager.CraftingRecipe getSurfaceCraftingPreview() {
        return ctx.craftingManager.matchRecipe(craftingGrid);
    }

    /** Crafts the surface 2x2 recipe after the explicit Craft button is pressed. */
    public boolean craftSurface2x2() {
        if (!ctx.inventoryOpen || ctx.activeUI != GameContext.ActiveUI.SURFACE_CRAFTING) return false;
        CraftingManager.CraftingRecipe match = getSurfaceCraftingPreview();
        if (match == null || !canAddItem(match.resultItemId, match.resultCount)) return false;
        addItem(match.resultItemId, match.resultCount);
        ctx.craftingManager.consumeItems(craftingGrid);
        saveSurfaceCraftingGrid();
        ctx.setStatus("Crafted " + match.resultItemId.replace('_', ' '));
        return true;
    }

    /**
     * Persists surface ingredients and safely returns the cursor-carried stack
     * when the overlay closes. The four grid ingredients stay on the block.
     */
    public void returnSurfaceCraftingItems() {
        if (ctx.activeUI != GameContext.ActiveUI.SURFACE_CRAFTING) return;
        saveSurfaceCraftingGrid();
        returnCarriedStackToInventory();
    }

    /** Never silently destroys a stack held by the inventory cursor. */
    public void returnCarriedStackToInventory() {
        if (carriedStack == null) return;
        ItemDefinitions.ItemStack held = carriedStack;
        carriedStack = null;
        if (canAddItem(held.itemId, held.count)) {
            addItem(held.itemId, held.count);
        } else if (ctx.droppedItemManager != null && ctx.player != null) {
            ctx.droppedItemManager.spawn(held.itemId, held.count,
                (int) Math.floor(ctx.player.getPosition().x),
                (int) Math.floor(ctx.player.getPosition().y),
                (int) Math.floor(ctx.player.getPosition().z));
        }
    }

    public void handleCrafting3x3SlotClick(int slotIndex) {
        if (!ctx.inventoryOpen) return;

        int gridRow = slotIndex / 3;
        int gridCol = slotIndex % 3;
        String gridItem = craftingGrid3x3[gridRow][gridCol];

        if (carriedStack == null) {
            // Pick up item from slot (if it's not the result slot, or result is not active)
            if (gridItem != null) {
                carriedStack = new ItemDefinitions.ItemStack(gridItem, 1);
                craftingGrid3x3[gridRow][gridCol] = null;
            }
        } else {
            // Place item into slot
            if (gridItem == null) {
                craftingGrid3x3[gridRow][gridCol] = carriedStack.itemId;
                carriedStack.count--;
                if (carriedStack.count <= 0) carriedStack = null;
            }
        }

        // Refresh the preview without consuming any ingredients. The Craft button
        // performs the actual consume-and-give operation.
        checkCrafting3x3Recipe();
    }

    // --- CraftingTableManager sync ---

    /**
     * Loads the 3x3 grid from the CraftingTableManager for a given block position.
     */
    public void loadFromCraftingTable(int x, int y, int z) {
        String[][] grid = ctx.craftingTableManager.getGrid(x, y, z);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                craftingGrid3x3[r][c] = (grid != null) ? grid[r][c] : null;
            }
        }
        crafting3x3HasResult = false;
        crafting3x3ResultItemId = null;
        crafting3x3ResultCount = 0;
        checkCrafting3x3Recipe();
    }

    /**
     * Saves only the real ingredient grid. A recipe preview is derived state and
     * must never replace or clear the center ingredient while persisting.
     */
    public void saveToCraftingTable(int x, int y, int z) {
        ctx.craftingTableManager.setGrid(x, y, z, craftingGrid3x3);
    }

    /**
     * Attempts the currently previewed 3x3 recipe. Inputs are consumed only after
     * the result has been accepted by the player's inventory.
     *
     * @return true when a result was added and the ingredient grid was consumed
     */
    public boolean craft3x3() {
        if (!ctx.inventoryOpen) return false;

        CraftingManager.CraftingRecipe match = ctx.craftingManager.matchRecipe3x3(craftingGrid3x3);
        if (match == null) {
            checkCrafting3x3Recipe();
            return false;
        }
        // Check capacity before mutating either side. addItem intentionally keeps
        // its historical partial-stack behavior for non-crafting callers.
        if (!canAddItem(match.resultItemId, match.resultCount)) {
            ctx.setStatus("Inventory is full");
            return false;
        }
        addItem(match.resultItemId, match.resultCount);
        ctx.craftingManager.consumeItems3x3(craftingGrid3x3);
        ctx.craftingTableManager.setGrid(ctx.craftingTableBlockX, ctx.craftingTableBlockY,
            ctx.craftingTableBlockZ, craftingGrid3x3);
        checkCrafting3x3Recipe();
        if (ctx.worldSaveManager != null) {
            ctx.worldSaveManager.saveCraftingData(ctx.activeDimension, ctx.craftingTableManager);
        }
        crafting3x3HasResult = false;
        crafting3x3ResultItemId = null;
        crafting3x3ResultCount = 0;
        ctx.setStatus("Crafted " + match.resultItemId.replace('_', ' '));
        return true;
    }

    /** Refreshes the preview state without mutating the ingredient grid. */
    private void checkCrafting3x3Recipe() {
        CraftingManager.CraftingRecipe match = ctx.craftingManager.matchRecipe3x3(craftingGrid3x3);
        crafting3x3HasResult = match != null;
        crafting3x3ResultItemId = match != null ? match.resultItemId : null;
        crafting3x3ResultCount = match != null ? match.resultCount : 0;
    }
}
