package com.voxel.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure rules behind the enchanting table and bed sleeping. */
public class EnchantingRulesTest {

    @Test
    public void displayNamesUseRomanNumerals() {
        assertEquals("Sharpness I", Enchantments.label(Enchantments.SHARPNESS, 1));
        assertEquals("Sharpness II", Enchantments.label(Enchantments.SHARPNESS, 2));
        assertEquals("Efficiency III", Enchantments.label(Enchantments.EFFICIENCY, 3));
        assertNull(Enchantments.label(null, 2));
        assertNull(Enchantments.label(Enchantments.SHARPNESS, 0));
        assertEquals("Bogus I", Enchantments.label("bogus", 1));
    }

    @Test
    public void effectsScaleWithLevel() {
        assertEquals(5.0f, Enchantments.attackBonus(Enchantments.SHARPNESS, 2), 1e-6f);
        assertEquals(0f, Enchantments.attackBonus(Enchantments.EFFICIENCY, 3), 1e-6f);
        assertEquals(1.9f, Enchantments.miningMultiplier(Enchantments.EFFICIENCY, 3), 1e-6f);
        assertEquals(1f, Enchantments.miningMultiplier(Enchantments.SHARPNESS, 3), 1e-6f);
        assertEquals(1f, Enchantments.miningMultiplier(null, 0), 1e-6f);
    }

    @Test
    public void idsNormaliseAndRejectUnknownValues() {
        assertEquals(Enchantments.SHARPNESS, Enchantments.normalize(" Sharpness "));
        assertEquals(Enchantments.EFFICIENCY, Enchantments.normalize("EFFICIENCY"));
        assertNull(Enchantments.normalize(""));
        assertNull(Enchantments.normalize("fortune"));
    }

    @Test
    public void bookshelvesUnlockLevels() {
        assertEquals(1, EnchantingTable.maxLevelFor(0));
        assertEquals(1, EnchantingTable.maxLevelFor(3));
        assertEquals(2, EnchantingTable.maxLevelFor(4));
        assertEquals(2, EnchantingTable.maxLevelFor(7));
        assertEquals(3, EnchantingTable.maxLevelFor(8));
        assertEquals(3, EnchantingTable.maxLevelFor(30));
    }

    @Test
    public void onlyToolsCanBeEnchanted() {
        assertFalse(EnchantingTable.canEnchant(null));
        ItemDefinitions defs = new ItemDefinitions();
        // The registry is empty until setup(), so a missing id is not enchantable.
        assertNull(EnchantingTable.enchantmentFor(defs.getDefinition("missing")));
        assertFalse(EnchantingTable.canEnchant(defs.getDefinition("missing")));
    }

    @Test
    public void bedWindowRunsDuskToDawn() {
        assertTrue(BedSleep.isNight(1080f));
        assertTrue(BedSleep.isNight(1300f));
        assertTrue(BedSleep.isNight(1439f));
        assertTrue(BedSleep.isNight(0f));
        assertTrue(BedSleep.isNight(239f));
        assertFalse(BedSleep.isNight(240f));
        assertFalse(BedSleep.isNight(720f));
        assertFalse(BedSleep.isNight(1079f));
        // Negative/large times normalise instead of falling through.
        assertTrue(BedSleep.isNight(-200f));      // = 1240
        assertFalse(BedSleep.isNight(1440f + 720f));
        assertEquals(240f, BedSleep.wakeTime(), 0f);
    }

    @Test
    public void stackCopyCarriesEnchantment() {
        ItemDefinitions.ItemStack stack = new ItemDefinitions.ItemStack("iron_pickaxe", 1);
        stack.enchantment = Enchantments.EFFICIENCY;
        stack.enchantLevel = 2;
        ItemDefinitions.ItemStack copy = stack.copy();
        assertEquals(Enchantments.EFFICIENCY, copy.enchantment);
        assertEquals(2, copy.enchantLevel);
        assertEquals("Efficiency II", copy.enchantLabel());
    }
}
