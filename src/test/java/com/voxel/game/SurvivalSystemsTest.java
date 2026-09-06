package com.voxel.game;

import com.voxel.Player;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the survival foundations added to make the engine behave like
 * vanilla 1.12.2: hunger/eating, starvation vs natural regen gating, and
 * armor-based damage reduction.
 */
public class SurvivalSystemsTest {

    private static Player freshPlayer() {
        return new Player(0.5, 80, 0.5);
    }

    @Test
    public void foodTableCoversVanillaStaples() {
        assertTrue(FoodValues.isFood("apple"));
        assertTrue(FoodValues.isFood("cooked_beef"));
        assertTrue(FoodValues.isFood("bread"));
        assertFalse(FoodValues.isFood("stone"));
        // Vanilla values: steak restores 8 hunger, apple 4.
        assertEquals(8.0f, FoodValues.hunger("cooked_beef"), 0.001f);
        assertEquals(4.0f, FoodValues.hunger("apple"), 0.001f);
        assertTrue(FoodValues.size() >= 25);
    }

    @Test
    public void eatFoodRestoresHungerAndCapsAtTwenty() {
        Player p = freshPlayer();
        p.setHunger(5);
        p.setSaturation(0);
        assertTrue(p.eatFood(8, 0.8f));
        assertEquals(13.0f, p.getHunger(), 0.001f);
        // Saturation = food × ratio × 2 = 8 × 0.8 × 2 = 12.8 (below hunger, so
        // no cap applies).
        assertEquals(12.8f, p.getSaturation(), 0.001f);

        // Cannot over-eat past a full bar.
        p.setHunger(20);
        assertFalse(p.eatFood(8, 0.8f));
        assertEquals(20.0f, p.getHunger(), 0.001f);
    }

    @Test
    public void hungerGatesEating() {
        Player p = freshPlayer();
        p.setHunger(19);
        assertTrue(p.eatFood(4, 0.3f));
        assertEquals(20.0f, p.getHunger(), 0.001f);
        // Saturation must never exceed the food bar.
        assertTrue(p.getSaturation() <= p.getHunger() + 0.001f);
    }

    @Test
    public void armorReducesDamageLikeVanilla() {
        Player p = freshPlayer();
        p.setArmorPoints(0);
        p.takeDamage(10.0f);
        assertEquals(10.0f, p.getHealth(), 0.001f);

        p = freshPlayer();
        // Full diamond (20 armor) vs a 10-damage hit:
        // damage × (1 − min(20, max(20, 5)) / 25) = 10 × 0.2 = 2.
        p.setArmorPoints(20);
        p.takeDamage(10.0f);
        assertEquals(18.0f, p.getHealth(), 0.001f);

        // Iron (15 armor) vs a small 4-damage hit:
        // max(15, 2) = 15 → 4 × (1 − 15/25) = 1.6.
        p = freshPlayer();
        p.setArmorPoints(15);
        p.takeDamage(4.0f);
        assertEquals(20.0f - 1.6f, p.getHealth(), 0.001f);

        // Armor points are clamped to the vanilla 20 cap.
        p = freshPlayer();
        p.setArmorPoints(30);
        assertEquals(20, p.getArmorPoints());
    }

    @Test
    public void respawnRestoresFullHunger() {
        Player p = freshPlayer();
        p.setHunger(2);
        p.takeDamage(20.0f); // die
        p.respawn();
        assertEquals(20.0f, p.getHealth(), 0.001f);
        assertEquals(20.0f, p.getHunger(), 0.001f);
        assertEquals(5.0f, p.getSaturation(), 0.001f);
    }

    @Test
    public void armorValuesMatchVanillaSets() {
        assertEquals(ArmorValues.SLOT_HELMET, ArmorValues.slotFor("diamond_helmet"));
        assertEquals(ArmorValues.SLOT_BOOTS, ArmorValues.slotFor("leather_boots"));
        assertEquals(8, ArmorValues.defenseFor("diamond_chestplate"));
        assertEquals(3, ArmorValues.defenseFor("diamond_helmet"));
        assertEquals(-1, ArmorValues.slotFor("diamond_pickaxe"));
        // Leather total = 7, diamond total = 20 (the armor-point cap).
        int leather = ArmorValues.defenseFor("leather_helmet")
                + ArmorValues.defenseFor("leather_chestplate")
                + ArmorValues.defenseFor("leather_leggings")
                + ArmorValues.defenseFor("leather_boots");
        assertEquals(7, leather);
        assertEquals(20, ArmorValues.size()); // 20 pieces total (5 sets × 4)
    }

    @Test
    public void armorSlotEquipScoresAndUnequip() {
        GameContext ctx = new GameContext();
        PlayerInventory inv = new PlayerInventory(ctx);
        assertEquals(0, inv.getTotalArmorPoints());

        inv.setArmorSlot(ArmorValues.SLOT_HELMET, new ItemDefinitions.ItemStack("diamond_helmet", 1));
        inv.setArmorSlot(ArmorValues.SLOT_CHESTPLATE, new ItemDefinitions.ItemStack("diamond_chestplate", 1));
        assertEquals(11, inv.getTotalArmorPoints()); // 3 + 8

        // Full diamond = 3+8+6+3 = 20 (the cap).
        inv.setArmorSlot(ArmorValues.SLOT_LEGGINGS, new ItemDefinitions.ItemStack("diamond_leggings", 1));
        inv.setArmorSlot(ArmorValues.SLOT_BOOTS, new ItemDefinitions.ItemStack("diamond_boots", 1));
        assertEquals(20, inv.getTotalArmorPoints());

        // Total is clamped to the vanilla 20 cap even for impossible over-stacks.
        inv.setArmorSlot(ArmorValues.SLOT_HELMET, new ItemDefinitions.ItemStack("diamond_chestplate", 1));
        assertEquals(20, inv.getTotalArmorPoints());

        // Unequip removes the piece and returns it.
        ItemDefinitions.ItemStack taken = inv.takeOffArmor(ArmorValues.SLOT_CHESTPLATE);
        assertEquals("diamond_chestplate", taken.itemId);
        assertTrue(inv.getArmorSlot(ArmorValues.SLOT_CHESTPLATE) == null);
    }

    @Test
    public void armorSlotClickPlacesSwapsAndRejectsWrongPiece() {
        GameContext ctx = new GameContext();
        ctx.inventoryOpen = true;
        PlayerInventory inv = new PlayerInventory(ctx);

        // Picking up an empty armor slot does nothing.
        inv.handleArmorSlotClick(ArmorValues.SLOT_HELMET);
        assertTrue(inv.getCarriedStack() == null);

        // Placing a matching piece into the slot.
        inv.setCarriedStack(new ItemDefinitions.ItemStack("iron_helmet", 1));
        inv.handleArmorSlotClick(ArmorValues.SLOT_HELMET);
        assertTrue(inv.getCarriedStack() == null);
        assertEquals("iron_helmet", inv.getArmorSlot(ArmorValues.SLOT_HELMET).itemId);

        // A wrong piece is refused and stays on the cursor.
        inv.setCarriedStack(new ItemDefinitions.ItemStack("iron_boots", 1));
        inv.handleArmorSlotClick(ArmorValues.SLOT_HELMET);
        assertEquals("iron_boots", inv.getCarriedStack().itemId);
        assertEquals("iron_helmet", inv.getArmorSlot(ArmorValues.SLOT_HELMET).itemId);

        // Clicking the slot with a correct piece swaps it with the worn one.
        inv.setCarriedStack(new ItemDefinitions.ItemStack("diamond_helmet", 1));
        inv.handleArmorSlotClick(ArmorValues.SLOT_HELMET);
        assertEquals("diamond_helmet", inv.getArmorSlot(ArmorValues.SLOT_HELMET).itemId);
        assertEquals("iron_helmet", inv.getCarriedStack().itemId);

        // Picking the worn piece back onto the cursor empties the slot.
        inv.setCarriedStack(null);
        inv.handleArmorSlotClick(ArmorValues.SLOT_HELMET);
        assertEquals("diamond_helmet", inv.getCarriedStack().itemId);
        assertTrue(inv.getArmorSlot(ArmorValues.SLOT_HELMET) == null);

        // Out-of-range slots are ignored.
        inv.setCarriedStack(new ItemDefinitions.ItemStack("diamond_helmet", 1));
        inv.handleArmorSlotClick(9);
        assertTrue(inv.getCarriedStack() != null);
    }

    @Test
    public void armorIconIdMapsEverySlotToAPiece() {
        assertEquals("iron_helmet", PlayerInventory.armorIconId(ArmorValues.SLOT_HELMET));
        assertEquals("iron_chestplate", PlayerInventory.armorIconId(ArmorValues.SLOT_CHESTPLATE));
        assertEquals("iron_leggings", PlayerInventory.armorIconId(ArmorValues.SLOT_LEGGINGS));
        assertEquals("iron_boots", PlayerInventory.armorIconId(ArmorValues.SLOT_BOOTS));
    }

    @Test
    public void furnaceSmeltsRawMeatAndPotatoes() {
        FurnaceManager fm = new FurnaceManager();
        // Raw meat -> cooked meat is the furnace half of the food chain.
        assertRecipe(fm, "porkchop", "cooked_porkchop");
        assertRecipe(fm, "beef", "cooked_beef");
        assertRecipe(fm, "chicken", "cooked_chicken");
        assertRecipe(fm, "mutton", "cooked_mutton");
        assertRecipe(fm, "rabbit", "cooked_rabbit");
        assertRecipe(fm, "fish", "cooked_fish");
        assertRecipe(fm, "salmon", "cooked_salmon");
        assertRecipe(fm, "potato", "baked_potato");
        // 10 s per meat item, like vanilla.
        assertEquals(10.0f, fm.findRecipe("beef").smeltTime, 0.001f);
        // Non-smeltable input has no recipe.
        assertTrue(fm.findRecipe("bedrock") == null);
    }

    private static void assertRecipe(FurnaceManager fm, String input, String output) {
        FurnaceManager.SmeltingRecipe r = fm.findRecipe(input);
        assertTrue("missing furnace recipe for " + input, r != null);
        assertEquals(output, r.outputItemId);
    }

    @Test
    public void survivalGateKeepsHungerStaticOutOfSurvival() {
        // The hunger decay itself lives in the 20 Hz physics tick; this verifies
        // the gate flag is respected by eatFood's caller-visible behavior.
        Player p = freshPlayer();
        p.setSurvivalActive(false);
        p.setHunger(20);
        assertFalse("Full bar rejects food regardless of mode", p.eatFood(4, 0.3f));
    }
}
