package com.voxel.game;

import com.voxel.crafting.CraftingManager;

/**
 * Manages the player's inventory array, hotbar selection, carried stack,
 * crafting grid, and item add/remove operations.
 */
public class PlayerInventory {
    public static final int HOTBAR_SIZE = 5;
    public static final int INVENTORY_SIZE = 30;
    public static final int CRAFTING_SLOTS = 5;
    public static final int CRAFTING_RESULT_SLOT = 4;

    private final ItemDefinitions.ItemStack[] inventory = new ItemDefinitions.ItemStack[INVENTORY_SIZE];
    private String[][] craftingGrid = new String[2][2];
    private int selectedSlot = 0;
    private ItemDefinitions.ItemStack carriedStack;

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
        inventory[2] = new ItemDefinitions.ItemStack("stone", 32);
        inventory[3] = new ItemDefinitions.ItemStack("dirt", 32);
        inventory[4] = new ItemDefinitions.ItemStack("glass", 16);
        inventory[5] = new ItemDefinitions.ItemStack("wood_axe", 1);
        inventory[6] = new ItemDefinitions.ItemStack("oak_log", 32);
        inventory[7] = new ItemDefinitions.ItemStack("grass", 32);
        inventory[8] = new ItemDefinitions.ItemStack("flint_and_steel", 1);
        inventory[9] = new ItemDefinitions.ItemStack("water_bucket", 1);
        inventory[10] = new ItemDefinitions.ItemStack("eye_of_ender", 1);
        inventory[11] = new ItemDefinitions.ItemStack("obsidian", 16);
        inventory[12] = new ItemDefinitions.ItemStack("glowstone", 16);
        inventory[13] = new ItemDefinitions.ItemStack("end_stone", 16);
        inventory[14] = new ItemDefinitions.ItemStack("netherrack", 32);
        inventory[15] = new ItemDefinitions.ItemStack("soul_sand", 16);
        inventory[16] = new ItemDefinitions.ItemStack("nether_brick", 16);
        inventory[17] = new ItemDefinitions.ItemStack("redstone_block", 16);
        inventory[18] = new ItemDefinitions.ItemStack("redstone_ore", 16);
        inventory[19] = new ItemDefinitions.ItemStack("redstone_torch", 8);
        inventory[20] = new ItemDefinitions.ItemStack("redstone_lamp", 8);
        inventory[21] = new ItemDefinitions.ItemStack("redstone_wire", 32);
        inventory[22] = new ItemDefinitions.ItemStack("quartz_ore", 16);
        inventory[23] = new ItemDefinitions.ItemStack("aether_grass", 16);
        inventory[24] = new ItemDefinitions.ItemStack("holystone", 32);
        inventory[25] = new ItemDefinitions.ItemStack("skyroot_log", 16);
        inventory[26] = new ItemDefinitions.ItemStack("aerogel", 16);
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
}
