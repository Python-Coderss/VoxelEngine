package com.voxel.game;

import com.voxel.entity.VillagerEntity;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Barter offers per profession/level, plus the inventory math they need. */
public class VillagerTradesTest {

    @Test
    public void everyWorkingProfessionTradesAtLevelOne() {
        assertEquals(0, VillagerTrades.offers(VillagerEntity.Profession.NITWIT, 5).size());
        assertFalse(VillagerTrades.trades(VillagerEntity.Profession.NITWIT));
        for (VillagerEntity.Profession profession : VillagerEntity.Profession.values()) {
            if (profession == VillagerEntity.Profession.NITWIT) continue;
            List<VillagerTrades.Offer> offers = VillagerTrades.offers(profession, 1);
            assertTrue(profession + " must have a level-1 offer", !offers.isEmpty());
            assertTrue(profession + " must have live offers at level 5",
                    !VillagerTrades.offers(profession, 5).isEmpty());
        }
    }

    @Test
    public void higherLevelsUnlockMoreOffers() {
        int levelOne = VillagerTrades.offers(VillagerEntity.Profession.SHOPKEEPER, 1).size();
        int levelFive = VillagerTrades.offers(VillagerEntity.Profession.SHOPKEEPER,
                VillagerProfessions.MAX_LEVEL).size();
        assertTrue("career levels must add offers", levelFive > levelOne);
        for (VillagerTrades.Offer offer : VillagerTrades.offers(
                VillagerEntity.Profession.SHOPKEEPER, 1)) {
            assertEquals(1, offer.minLevel);
        }
    }

    @Test
    public void offersAreRealBarters() {
        for (VillagerTrades.Offer offer : VillagerTrades.allOffers(
                VillagerEntity.Profession.FARMER)) {
            assertTrue(offer.costCount > 0);
            assertTrue(offer.resultCount > 0);
            assertFalse("an offer must not trade an item for itself",
                    offer.costItem.equals(offer.resultItem));
            assertTrue(offer.xp > 0);
        }
        assertEquals("18 wheat -> 1 emerald",
                VillagerTrades.allOffers(VillagerEntity.Profession.FARMER).get(0).label());
    }

    @Test
    public void inventoryCountAndRemovalSpanSlots() {
        GameContext ctx = new GameContext();
        PlayerInventory inventory = new PlayerInventory(ctx);
        inventory.setSlot(2, new ItemDefinitions.ItemStack("wheat", 10));
        inventory.setSlot(5, new ItemDefinitions.ItemStack("wheat", 7));
        inventory.setSlot(6, new ItemDefinitions.ItemStack("bread", 3));

        assertEquals(17, VillagerTrading.countItems(inventory, "wheat"));
        assertTrue(VillagerTrading.hasItems(inventory, "wheat", 17));
        assertFalse(VillagerTrading.hasItems(inventory, "wheat", 18));

        assertEquals("removes across stacks", 12,
                VillagerTrading.removeItems(inventory, "wheat", 12));
        assertEquals(5, VillagerTrading.countItems(inventory, "wheat"));
        assertEquals("untouched item stacks survive", 3,
                VillagerTrading.countItems(inventory, "bread"));

        // Emptying a stack clears the slot rather than leaving a 0-count stack.
        assertEquals(5, VillagerTrading.removeItems(inventory, "wheat", 5));
        assertEquals(0, VillagerTrading.countItems(inventory, "wheat"));
        assertEquals(null, inventory.getSlot(2));
        assertEquals(null, inventory.getSlot(5));
    }
}
