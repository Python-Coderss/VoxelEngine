package com.voxel.game;

import com.voxel.World;
import com.voxel.entity.VillagerEntity;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end profession behaviour without a GL context: a villager spawned in
 * a village with a workbench adopts that trade and then actually works it,
 * earning career XP at the job site.
 */
public class VillagerProfessionWorkTest {

    private static final int STONE = 1;
    private static final int GROUND_Y = 63;
    /** Villagers stand at y=64 on top of the stone plate. */

    /**
     * Flat village ground with a single job-site block standing on it.
     */
    private static final class VillageWorld extends World {
        private final Vector3i jobSite;

        VillageWorld(Vector3i jobSite) {
            super(8);
            this.jobSite = new Vector3i(jobSite);
        }

        @Override
        public int getVoxel(int x, int y, int z) {
            if (y <= GROUND_Y) return STONE;
            if (y == jobSite.y && x == jobSite.x && z == jobSite.z) {
                return com.voxel.game.VillagerProfessions.CHEST;
            }
            return 0;
        }
    }

    private VillagerEntity villager;

    @After
    public void tearDown() {
        if (villager != null) {
            villager.setWorld(null);
        }
    }

    @Test
    public void villagerAdoptsTheJobSiteStandingNextToIt() {
        VillageWorld world = new VillageWorld(new Vector3i(52, 64, 50));
        villager = new VillagerEntity(90001, new Vector3f(50f, 64f, 50f), null);
        villager.setWorld(world);
        villager.setVillage(new Vector3i(50, 64, 50), 48);

        assertTrue("the chest must hand out a job",
                villager.assignJobSiteIfAny());
        assertEquals("a chest is a shopkeeper's job site",
                VillagerEntity.Profession.SHOPKEEPER, villager.getProfession());
        assertNotNull(villager.getWorkstationPos());
        assertEquals(52, villager.getWorkstationPos().x);
        assertEquals(64, villager.getWorkstationPos().y);
        assertTrue(villager.aiWorkstationStillValid());
    }

    @Test
    public void workingAtTheJobSiteEarnsCareerXp() {
        VillageWorld world = new VillageWorld(new Vector3i(52, 64, 50));
        villager = new VillagerEntity(90002, new Vector3f(51f, 64f, 50f), null);
        villager.setWorld(world);
        villager.setVillage(new Vector3i(50, 64, 50), 48);
        assertTrue(villager.assignJobSiteIfAny());
        assertEquals(VillagerEntity.Profession.SHOPKEEPER, villager.getProfession());

        // Twenty simulated seconds is several work beats at the stall.
        for (int tick = 0; tick < 200; tick++) {
            villager.update(0.1f);
        }

        assertTrue("standing at the job site must earn XP, got "
                        + villager.getProfessionXp(),
                villager.getProfessionXp() >= VillagerProfessions.XP_TEND);
        assertEquals("job XP comes in work beats", 0,
                villager.getProfessionXp() % VillagerProfessions.XP_TEND);
        assertEquals("time on the job has not levelled this one yet",
                1, villager.getCareerLevel());
        assertEquals("Novice Trader", villager.getProfessionTitle());
    }

    @Test
    public void careerXpPromotesThroughTheLadder() {
        villager = new VillagerEntity(90003, new Vector3f(50f, 64f, 50f), null);
        villager.setProfession(VillagerEntity.Profession.FARMER);
        assertEquals(1, villager.getCareerLevel());
        assertEquals("Novice Farmer", villager.getProfessionTitle());

        assertTrue("crossing the level-2 threshold promotes",
                villager.addProfessionXp(VillagerProfessions.xpForLevel(2)));
        assertEquals(2, villager.getCareerLevel());
        assertEquals("Farmer", villager.getProfessionTitle());

        // One big award can clear several levels at once.
        villager.addProfessionXp(VillagerProfessions.xpForLevel(
                VillagerProfessions.MAX_LEVEL));
        assertEquals(VillagerProfessions.MAX_LEVEL, villager.getCareerLevel());
        assertEquals("Master Farmer", villager.getProfessionTitle());
    }

    @Test
    public void nitwitsEarnNothingUntilTheyTakeAJob() {
        villager = new VillagerEntity(90004, new Vector3f(50f, 64f, 50f), null);
        villager.setProfession(VillagerEntity.Profession.NITWIT);
        assertTrue(!villager.addProfessionXp(500));
        assertEquals(1, villager.getCareerLevel());
        assertEquals("Nitwit", villager.getProfessionTitle());
    }
}
