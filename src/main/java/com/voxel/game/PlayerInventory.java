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
    public static final int CRAFTING_3X3_SLOTS = 9;   // 3x3 grid, center slot (index 4) is result

    private final ItemDefinitions.ItemStack[] inventory = new ItemDefinitions.ItemStack[INVENTORY_SIZE];
    private String[][] craftingGrid = new String[2][2];
    private String[][] craftingGrid3x3 = new String[3][3];
    private int selectedSlot = 0;
    private ItemDefinitions.ItemStack carriedStack;

    // Crafting table result tracking
    private boolean crafting3x3HasResult = false;
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
    public String[][] getCraftingGrid() { return craftingGrid; }
    public String[][] getCraftingGrid3x3() { return craftingGrid3x3; }
    public boolean hasCrafting3x3Result() { return crafting3x3HasResult; }
    public int getCrafting3x3ResultCount() { return crafting3x3ResultCount; }

    public void clearSlot(int i) {
        inventory[i] = null;
        if (carriedStack != null && i == selectedSlot && !ctx.inventoryOpen) carriedStack = null;
    }

    public ItemDefinitions.ItemStack getSelected() { return inventory[selectedSlot]; }

    // --- Item management ---
    public boolean addItem(String itemId, int count) {
        ItemDefinitions.ItemDefinition def = ctx.itemDefinitions.getDefinition(itemId);
        if (def == null) return false;
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
        if (!ctx.inventoryOpen) return;
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
    }

    public void handleCrafting3x3SlotClick(int slotIndex) {
        if (!ctx.inventoryOpen) return;

        // Center slot (index 4) acts as result slot when a recipe is matched
        if (slotIndex == 4 && crafting3x3HasResult) {
            // Collect the result from the center slot
            String resultItemId = craftingGrid3x3[1][1];
            if (resultItemId != null && addItem(resultItemId, crafting3x3ResultCount)) {
                // Clear entire grid and reset result state
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        craftingGrid3x3[r][c] = null;
                    }
                }
                crafting3x3HasResult = false;
                crafting3x3ResultCount = 0;
            }
            return;
        }

        int gridRow = slotIndex / 3;
        int gridCol = slotIndex % 3;
        String gridItem = craftingGrid3x3[gridRow][gridCol];

        if (carriedStack == null) {
            // Pick up item from slot (if it's not the result slot, or result is not active)
            if (gridItem != null) {
                carriedStack = new ItemDefinitions.ItemStack(gridItem, 1);
                craftingGrid3x3[gridRow][gridCol] = null;
                // Clear result state if center slot was modified
                if (slotIndex == 4) {
                    crafting3x3HasResult = false;
                    crafting3x3ResultCount = 0;
                }
            }
        } else {
            // Place item into slot
            if (gridItem == null) {
                craftingGrid3x3[gridRow][gridCol] = carriedStack.itemId;
                carriedStack.count--;
                if (carriedStack.count <= 0) carriedStack = null;
            }
        }

        // After modifying non-center slots, auto-check for recipe match
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
        crafting3x3ResultCount = 0;
        checkCrafting3x3Recipe();
    }

    /**
     * Saves the current 3x3 grid back to the CraftingTableManager.
     * Clears the result slot before saving (result is virtual, not actual items).
     */
    public void saveToCraftingTable(int x, int y, int z) {
        // Clear the virtual result slot before persisting
        if (crafting3x3HasResult) {
            craftingGrid3x3[1][1] = null;
            crafting3x3HasResult = false;
            crafting3x3ResultCount = 0;
        }
        ctx.craftingTableManager.setGrid(x, y, z, craftingGrid3x3);
    }

    /**
     * Auto-checks if the current 3x3 grid matches a recipe.
     * If so, consumes the input items and places the result in the center slot.
     */
    private void checkCrafting3x3Recipe() {
        if (crafting3x3HasResult) return; // Already have a result, don't override

        CraftingManager.CraftingRecipe match = ctx.craftingManager.matchRecipe3x3(craftingGrid3x3);
        if (match != null) {
            // Consume all input slots (clear them)
            ctx.craftingManager.consumeItems3x3(craftingGrid3x3);
            // Place result in center slot
            craftingGrid3x3[1][1] = match.resultItemId;
            crafting3x3HasResult = true;
            crafting3x3ResultCount = match.resultCount;
        }
    }
}
