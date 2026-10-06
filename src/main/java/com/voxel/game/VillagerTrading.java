package com.voxel.game;

import com.voxel.entity.Entity;
import com.voxel.entity.VillagerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Villager bartering. Opening a trade lists the offers available at the
 * villager's current career level; accepting one swaps the cost items out of
 * the player's inventory for the result and pays the villager job XP (which can
 * promote it mid-session).
 */
public final class VillagerTrading {

    private VillagerTrading() {
    }

    /** Populate the trade session on the context. False when there is nothing to trade. */
    public static boolean open(GameContext ctx, VillagerEntity villager) {
        if (ctx == null || villager == null) return false;
        List<VillagerTrades.Offer> offers = VillagerTrades.offers(
                villager.getProfession(), villager.getCareerLevel());
        if (offers.isEmpty()) {
            ctx.setStatus(villager.getProfessionTitle() + " has nothing to trade");
            return false;
        }
        if (ctx.itemDefinitions != null) {
            // Never offer a barter whose result this build cannot actually make.
            List<VillagerTrades.Offer> usable = new ArrayList<VillagerTrades.Offer>();
            for (VillagerTrades.Offer offer : offers) {
                if (ctx.itemDefinitions.getDefinition(offer.resultItem) != null) {
                    usable.add(offer);
                }
            }
            if (usable.isEmpty()) {
                ctx.setStatus(villager.getProfessionTitle() + " has nothing to trade");
                return false;
            }
            offers = usable;
        }
        ctx.tradeOffers = offers;
        ctx.tradeTitle = villager.getProfessionTitle();
        ctx.tradeVillagerId = villager.id;
        ctx.tradeOpen = true;
        ctx.setStatus(villager.getProfessionTitle() + " — " + offers.size()
                + (offers.size() == 1 ? " offer" : " offers"));
        return true;
    }

    /** Settle one offer by index. Returns true when the barter happened. */
    public static boolean accept(GameContext ctx, int index) {
        if (ctx == null || !ctx.tradeOpen) return false;
        if (index < 0 || index >= ctx.tradeOffers.size()) return false;
        if (ctx.playerInventory == null) return false;
        VillagerEntity traderCheck = findTrader(ctx);
        if (traderCheck == null) {
            // The villager wandered off or died: drop the session.
            close(ctx);
            ctx.setStatus("The trader is gone");
            return false;
        }
        VillagerTrades.Offer offer = ctx.tradeOffers.get(index);
        PlayerInventory inventory = ctx.playerInventory;
        if (!hasItems(inventory, offer.costItem, offer.costCount)) {
            ctx.setStatus("You need " + offer.costCount + " "
                    + VillagerTrades.pretty(offer.costItem) + " for that");
            return false;
        }
        if (!canFit(inventory, offer.resultItem, offer.resultCount)) {
            ctx.setStatus("No room for " + offer.resultCount + " "
                    + VillagerTrades.pretty(offer.resultItem));
            return false;
        }
        int removed = removeItems(inventory, offer.costItem, offer.costCount);
        if (removed < offer.costCount) {
            // Should not happen (checked above); put back what was taken.
            if (removed > 0) inventory.addItem(offer.costItem, removed);
            ctx.setStatus("Trade failed");
            return false;
        }
        if (!inventory.addItem(offer.resultItem, offer.resultCount)) {
            inventory.addItem(offer.costItem, offer.costCount);
            ctx.setStatus("Trade failed");
            return false;
        }
        traderCheck.addProfessionXp(offer.xp);
        ctx.setStatus("Traded " + offer.costCount + " " + VillagerTrades.pretty(offer.costItem)
                + " for " + offer.resultCount + " " + VillagerTrades.pretty(offer.resultItem));
        return true;
    }

    /** Close the session (and refresh the offer list for the trader's new level). */
    public static void close(GameContext ctx) {
        if (ctx == null) return;
        ctx.tradeOpen = false;
        ctx.tradeVillagerId = -1;
        ctx.tradeOffers = new ArrayList<VillagerTrades.Offer>();
        ctx.tradeTitle = "";
    }

    /** The villager currently being traded with, or null. */
    public static VillagerEntity findTrader(GameContext ctx) {
        if (ctx == null || ctx.tradeVillagerId < 0 || ctx.entityManager == null) return null;
        for (Entity entity : ctx.entityManager.getEntitiesSnapshot()) {
            if (entity instanceof VillagerEntity && entity.id == ctx.tradeVillagerId) {
                return (VillagerEntity) entity;
            }
        }
        return null;
    }

    /** Total count of an item in the player's back inventory. */
    public static int countItems(PlayerInventory inventory, String itemId) {
        if (inventory == null || itemId == null) return 0;
        int total = 0;
        for (int i = 0; i < inventory.getInventorySize(); i++) {
            ItemDefinitions.ItemStack stack = inventory.getSlot(i);
            if (stack != null && itemId.equals(stack.itemId)) {
                total += stack.count;
            }
        }
        return total;
    }

    public static boolean hasItems(PlayerInventory inventory, String itemId, int count) {
        return countItems(inventory, itemId) >= count;
    }

    /** Remove up to {@code count} items, returning how many were actually removed. */
    public static int removeItems(PlayerInventory inventory, String itemId, int count) {
        if (inventory == null || itemId == null || count <= 0) return 0;
        int remaining = count;
        for (int i = 0; i < inventory.getInventorySize() && remaining > 0; i++) {
            ItemDefinitions.ItemStack stack = inventory.getSlot(i);
            if (stack == null || !itemId.equals(stack.itemId)) continue;
            int taken = Math.min(stack.count, remaining);
            stack.count -= taken;
            remaining -= taken;
            if (stack.count <= 0) {
                inventory.setSlot(i, null);
            }
        }
        return count - remaining;
    }

    /** Whether the whole result stack fits without dropping anything. */
    private static boolean canFit(PlayerInventory inventory, String itemId, int count) {
        int room = 0;
        for (int i = 0; i < inventory.getInventorySize(); i++) {
            ItemDefinitions.ItemStack stack = inventory.getSlot(i);
            if (stack == null) {
                // 64 is the engine's default stack size; it only ever
                // over-estimates room, and addItem is still checked.
                room += 64;
            } else if (itemId.equals(stack.itemId)) {
                room += Math.max(0, 64 - stack.count);
            }
            if (room >= count) return true;
        }
        return room >= count;
    }
}
