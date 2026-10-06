package com.voxel.entity;

import com.voxel.ai.brain.Brains;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless contract for the bespoke campaign bosses: each loads its own model
 * (not a reused zombie/illager skin), carries its boss stats, attaches its
 * dedicated brain, and degrades safely when constructed without a GL texture
 * manager, player or entity manager.
 */
public class CampaignBossEntityTest {

    private static final Vector3f POS = new Vector3f(0f, 64f, 0f);

    @Test
    public void witherStormHasItsOwnModelAndStats() {
        WitherStormEntity storm =
                new WitherStormEntity(1, POS, null, null, null);
        assertEquals(WitherStormEntity.BOSS_HEALTH, storm.getMaxHealth(), 0.001f);
        assertEquals(WitherStormEntity.BOSS_HEALTH, storm.getHealth(), 0.001f);
        assertFalse(storm.isDead());
        assertTrue(storm.getPickWidth() >= 3f);
        assertTrue(storm.getPickHeight() >= 3f);
        assertNotNull("three-head model must load", storm.findPart("head_center"));
        assertNotNull(storm.findPart("head_left"));
        assertNotNull(storm.findPart("head_right"));
        assertNotNull(storm.findPart("core_glow"));
        assertNotNull("tentacles must load", storm.findPart("tentacle_a"));
        assertEquals(250, storm.xpDropValue());
        assertBrainsRespectGate(storm);
    }

    @Test
    public void greatHogHasItsOwnModelAndStats() {
        GreatHogEntity hog = new GreatHogEntity(2, POS, null, null, null);
        assertEquals(GreatHogEntity.BOSS_HEALTH, hog.getMaxHealth(), 0.001f);
        assertFalse(hog.isDead());
        assertNotNull(hog.findPart("head"));
        assertNotNull("hammer must load", hog.findPart("hammer_head"));
        assertEquals(180, hog.xpDropValue());
        assertBrainsRespectGate(hog);
    }

    @Test
    public void archIllagerHasItsOwnModelAndStats() {
        ArchIllagerEntity arch = new ArchIllagerEntity(3, POS, null, null, null);
        assertEquals(ArchIllagerEntity.BOSS_HEALTH, arch.getMaxHealth(), 0.001f);
        assertNotNull(arch.findPart("hood"));
        assertNotNull("staff must load", arch.findPart("staff_orb"));
        assertEquals(160, arch.xpDropValue());
        assertBrainsRespectGate(arch);
    }

    @Test
    public void heartOfEnderHasItsOwnModelAndStats() {
        HeartOfEnderEntity heart = new HeartOfEnderEntity(4, POS, null, null, null);
        assertEquals(HeartOfEnderEntity.BOSS_HEALTH, heart.getMaxHealth(), 0.001f);
        assertNotNull(heart.findPart("orb_main"));
        assertNotNull("eye must load", heart.findPart("eye"));
        assertNotNull("tendrils must load", heart.findPart("tendril_a"));
        assertEquals(220, heart.xpDropValue());
        assertBrainsRespectGate(heart);
    }

    @Test
    public void summoningAndLootAreNullSafeWithoutAManager() {
        WitherStormEntity storm = new WitherStormEntity(5, POS, null, null, null);
        assertEquals(0, storm.summonMinions(3));
        assertFalse(storm.hurlSkull(0f, 0f));
        assertFalse(storm.dropLoot(null));
        assertFalse(storm.markedDropped());
        storm.markDropped();
        assertTrue(storm.markedDropped());

        GreatHogEntity hog = new GreatHogEntity(6, POS, null, null, null);
        assertEquals(0, hog.summonHorde(3));
        hog.pound(POS); // null player must not throw
        assertFalse(hog.dropLoot(null));

        ArchIllagerEntity arch = new ArchIllagerEntity(7, POS, null, null, null);
        arch.callFangs(POS); // null player must not throw
        assertEquals(0, arch.summonVexes(2));
        assertFalse(arch.dropLoot(null));

        HeartOfEnderEntity heart = new HeartOfEnderEntity(8, POS, null, null, null);
        heart.pulseNova(); // null player must not throw
        assertEquals(0, heart.spawnMites(2));
        assertFalse(heart.dropLoot(null));
    }

    @Test
    public void blinkingMovesTheArchIllagerAwayFromTheThreat() {
        ArchIllagerEntity arch =
                new ArchIllagerEntity(9, new Vector3f(10f, 64f, 0f), null, null, null);
        arch.blinkAwayFrom(new Vector3f(0f, 64f, 0f), 7f);
        assertTrue("blink must increase the distance from the threat",
                arch.getPosX() > 10.5f);
    }

    private static void assertBrainsRespectGate(Entity boss) {
        if (Brains.ENABLED) {
            assertNotNull("dedicated brain must attach", boss.brain);
        } else {
            assertNull(boss.brain);
        }
    }
}
