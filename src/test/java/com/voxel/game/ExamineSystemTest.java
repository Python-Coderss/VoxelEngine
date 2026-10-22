package com.voxel.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Every looked-at object must produce lore and a spoken monologue, and the
 * notable story objects must win over their category entry.
 */
public class ExamineSystemTest {

    @Test
    public void everyEntryHasTitleBodyAndMonologue() {
        assertTrue("the story objects need named entries", ExamineSystem.namedCount() >= 8);
        assertTrue("the world needs category coverage",
                ExamineSystem.categoryCount() >= 10);
        String[] sample = {"command_block", "diamond_ore", "oak_door_lower",
                "torch", "chest", "cracked_stonebrick", "water", "unknown_thing"};
        for (String name : sample) {
            ExamineSystem.Lore lore = ExamineSystem.describe(name);
            assertNotNull(name, lore);
            assertFalse(name + " has no title", lore.title.trim().isEmpty());
            assertFalse(name + " has no body", lore.body.trim().isEmpty());
            assertFalse(name + " has no monologue", lore.monologue.trim().isEmpty());
            assertTrue(name + " lore is too thin to be lore", lore.body.length() > 40);
        }
    }

    @Test
    public void namedStoryObjectsBeatTheirCategory() {
        assertEquals("The Command Block",
                ExamineSystem.describe("command_block").title);
        assertEquals("Rift Frame",
                ExamineSystem.describe("rift_frame").title);
        // A door is both timber and a named story object: the named entry wins.
        assertEquals("Door", ExamineSystem.describe("oak_door_lower").title);
    }

    @Test
    public void categoriesCoverTheCommonWorld() {
        assertEquals("Stonework", ExamineSystem.describe("stonebrick").title);
        assertEquals("Ore", ExamineSystem.describe("diamond_ore").title);
        assertEquals("Timber", ExamineSystem.describe("spruce_planks").title);
        assertEquals("Light", ExamineSystem.describe("torch").title);
        assertEquals("Mechanism", ExamineSystem.describe("redstone_repeater").title);
        assertEquals("Growing Things", ExamineSystem.describe("wheat_crop").title);
        assertEquals("Ground", ExamineSystem.describe("coarse_dirt").title);
        assertEquals("Fluid", ExamineSystem.describe("lava").title);
        assertEquals("Ruin", ExamineSystem.describe("mossy_stonebrick").title);
        assertEquals("Storage", ExamineSystem.describe("furnace").title);
    }

    @Test
    public void unknownBlocksStillGetALoreEntry() {
        ExamineSystem.Lore lore = ExamineSystem.describe("modded_flux_capacitor");
        assertEquals("Unfamiliar Work", lore.title);
        assertFalse(lore.monologue.isEmpty());
        assertSame(ExamineSystem.describe(null).title, lore.title);
    }

    @Test
    public void normalizationHandlesIdsAndDisplayNames() {
        assertEquals("oak_door_lower", ExamineSystem.normalize("Oak Door Lower"));
        assertEquals("stonebrick", ExamineSystem.normalize("  stonebrick  "));
        assertEquals("", ExamineSystem.normalize(null));
        assertEquals(ExamineSystem.describe("oak_door").title,
                ExamineSystem.describe("Oak Door").title);
    }
}
