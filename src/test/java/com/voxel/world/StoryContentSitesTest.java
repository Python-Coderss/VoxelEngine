package com.voxel.world;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The MCSM, Minecraft Legends and Dungeons 1 &amp; 2 campaign sites must all be
 * registered with distinct map positions — two sites sharing a column would
 * stamp over each other and lose a level.
 */
public class StoryContentSitesTest {

    @Test
    public void familiesHaveTheExpectedSiteCounts() {
        assertEquals("Episodes 2-8 sites", 9, McsmStructures.episodeSiteCount());
        assertEquals("Legends sites", 4, LegendsStructures.siteCount());
        assertEquals("Dungeons sites", 11, DungeonsStructures.siteCount());
    }

    @Test
    public void allCampaignSitesAreOnDistinctColumns() {
        int[][] all = {
                McsmStructures.ENDERCON, McsmStructures.SORENS_SITE,
                McsmStructures.PUMPKIN_HIDEOUT, McsmStructures.REDSTONIA,
                McsmStructures.BOOM_TOWN, McsmStructures.SKY_CITY,
                McsmStructures.CROWN_MESA, McsmStructures.WITHER_STORM_ARENA,
                McsmStructures.ORDERS_TEMPLE, McsmStructures.BEACONTOWN,
                McsmStructures.FREDS_KEEP, McsmStructures.PORTAL_NETWORK,
                LegendsStructures.WELL_OF_FATE, LegendsStructures.GREAT_HOG_CAMP,
                LegendsStructures.HERO_OUTPOST, LegendsStructures.DEFENDED_VILLAGE,
                DungeonsStructures.CREEPER_WOODS, DungeonsStructures.PUMPKIN_PASTURES,
                DungeonsStructures.SOGGY_SWAMP, DungeonsStructures.CACTI_CANYON,
                DungeonsStructures.DESERT_TEMPLE, DungeonsStructures.HIGHBLOCK_HALLS,
                DungeonsStructures.FIERY_FORGE, DungeonsStructures.OBSIDIAN_PINNACLE,
                DungeonsStructures.ISLAND_REALMS, DungeonsStructures.RIFT_STOP,
                DungeonsStructures.ILLAGER_KEEP,
        };
        Set<String> seen = new HashSet<>();
        for (int[] at : all) {
            assertEquals(2, at.length);
            assertTrue("column (" + at[0] + "," + at[1] + ") is unique",
                    seen.add(at[0] + "," + at[1]));
        }
        assertEquals("every site counted once", all.length, seen.size());
    }

    @Test
    public void buildRangesArePositive() {
        assertTrue(McsmStructures.BUILD_RANGE > 0);
        assertTrue(LegendsStructures.BUILD_RANGE > 0);
        assertTrue(DungeonsStructures.BUILD_RANGE > 0);
    }
}
