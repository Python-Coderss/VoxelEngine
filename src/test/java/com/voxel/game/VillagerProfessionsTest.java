package com.voxel.game;

import com.voxel.World;
import com.voxel.entity.VillagerEntity;
import org.joml.Vector3i;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Profession model: every trade has a job site, the career ladder climbs, and a
 * jobless villager can find the work standing around it.
 */
public class VillagerProfessionsTest {

    /**
     * World stub with a handcrafted block layout, so the job-site scan can be
     * asserted without a chunk pool or a GL context.
     */
    private static final class StubWorld extends World {
        StubWorld() { super(8); }

        @Override
        public int getVoxel(int x, int y, int z) {
            // A chest on the ground at (4,4,4) and the ground itself at y=3.
            if (x == 4 && y == 4 && z == 4) return VillagerProfessions.CHEST;
            // A ripe wheat crop at (8,4,8) standing on farmland at (8,3,8).
            if (x == 8 && y == 4 && z == 8) return FarmBlocks.BLOCK_WHEAT_7;
            if (x == 8 && y == 3 && z == 8) return FarmBlocks.BLOCK_FARMLAND_WET;
            if (y == 3) return 1; // stone floor everywhere else
            return 0;
        }
    }

    @Test
    public void everyTradeHasAJobSiteAndRoundTrips() {
        for (VillagerEntity.Profession profession : VillagerEntity.Profession.values()) {
            int block = VillagerProfessions.workstationBlock(profession);
            if (profession == VillagerEntity.Profession.NITWIT) {
                assertEquals("nitwits have no job site",
                        VillagerProfessions.NO_JOB_SITE, block);
                assertFalse(VillagerProfessions.isWorkstation(0));
                continue;
            }
            assertTrue(profession + " must have a job site", block != 0);
            assertTrue(profession + " workstation must be recognised",
                    VillagerProfessions.isWorkstation(block));
            assertEquals("job site must hand out the same profession",
                    profession, VillagerProfessions.professionForBlock(block));
        }
    }

    @Test
    public void cropsBelongToTheFarmerJobSite() {
        assertEquals(VillagerEntity.Profession.FARMER,
                VillagerProfessions.professionForBlock(FarmBlocks.BLOCK_WHEAT_0));
        assertEquals(VillagerEntity.Profession.FARMER,
                VillagerProfessions.professionForBlock(FarmBlocks.BLOCK_FARMLAND_DRY));
        assertNull("plain stone is not a job site",
                VillagerProfessions.professionForBlock(1));
    }

    @Test
    public void nearestWorkstationFindsTheChestAndStandsOnTheSoil() {
        World world = new StubWorld();
        Vector3i chest = VillagerProfessions.nearestWorkstation(world, 4f, 4f, 4f);
        assertNotNull(chest);
        assertEquals(4, chest.x);
        assertEquals(4, chest.y);
        assertEquals(4, chest.z);

        // A wheat block's job site is the soil under it, not the crop itself.
        Vector3i wheat = VillagerProfessions.nearestWorkstation(world, 8f, 4f, 8f);
        assertNotNull(wheat);
        assertEquals(3, wheat.y);
        assertTrue(FarmBlocks.isFarmland(world.getVoxel(wheat.x, wheat.y, wheat.z)));
    }

    @Test
    public void randomProfessionNeverPicksNitwit() {
        Random rng = new Random(7L);
        for (int i = 0; i < 500; i++) {
            VillagerEntity.Profession profession = VillagerProfessions.randomProfession(rng);
            assertTrue("nitwits are never handed out at random",
                    profession != VillagerEntity.Profession.NITWIT);
        }
    }

    @Test
    public void careerLadderClimbsAndTitlesChange() {
        assertEquals(1, VillagerProfessions.levelForXp(0));
        assertEquals(1, VillagerProfessions.levelForXp(19));
        assertEquals(2, VillagerProfessions.levelForXp(20));
        assertEquals(5, VillagerProfessions.levelForXp(10000));
        assertEquals(0, VillagerProfessions.xpForLevel(1));
        assertTrue(VillagerProfessions.xpForLevel(2) > VillagerProfessions.xpForLevel(1));
        assertTrue(VillagerProfessions.xpForLevel(5) > VillagerProfessions.xpForLevel(4));

        assertEquals("Novice Farmer", VillagerProfessions.title(
                VillagerEntity.Profession.FARMER, 1));
        assertEquals("Master Farmer", VillagerProfessions.title(
                VillagerEntity.Profession.FARMER, VillagerProfessions.MAX_LEVEL));
        assertEquals("Prime Anchor", VillagerProfessions.title(
                VillagerEntity.Profession.NEWS_ANCHOR, VillagerProfessions.MAX_LEVEL));
        assertEquals("Nitwit", VillagerProfessions.title(
                VillagerEntity.Profession.NITWIT, 4));
    }
}
