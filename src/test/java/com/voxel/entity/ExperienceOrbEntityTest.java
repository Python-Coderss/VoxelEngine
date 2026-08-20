package com.voxel.entity;

import com.voxel.Player;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests ExperienceOrbEntity pickup + XP grant + expiry. Subclass with a
 * no-op loadModel() to skip GL.
 */
public class ExperienceOrbEntityTest {

    private static ExperienceOrbEntity newBareOrb(int xpValue) {
        return new ExperienceOrbEntity(1, new Vector3f(0, 64, 0), xpValue, null) {
            @Override
            public void loadModel(String path, TextureManager textureManager) {
                // Skip GL model load.
            }
        };
    }

    @Test
    public void xpValueClampedAtMinimumOne() {
        ExperienceOrbEntity orb = newBareOrb(0);
        assertEquals("xpValue floored at 1", 1, orb.xpValue);
        ExperienceOrbEntity orb5 = newBareOrb(5);
        assertEquals(5, orb5.xpValue);
    }

    @Test
    public void orbStartsActive() {
        ExperienceOrbEntity orb = newBareOrb(10);
        assertFalse(orb.isExpired());
    }

    @Test
    public void pickupAddsXpToPlayer() {
        Player player = new Player(0, 64, 0);
        assertEquals(0, player.getExperienceLevel());
        ExperienceOrbEntity orb = newBareOrb(15);
        orb.setNearestPlayer(player);
        // Run enough ticks for the orb (10 blocks away) to drift into the
        // player. Drift speed = 4 blocks/sec → ~3 seconds.
        for (int i = 0; i < 200; i++) {
            orb.update(0.05f);
            if (orb.isExpired()) break;
        }
        assertTrue("orb should expire after pickup", orb.isExpired());
        assertTrue("player should gain XP", player.getTotalExperience() >= 15);
    }

    @Test
    public void xpCurveDoublesCorrectly() {
        Player player = new Player(0, 64, 0);
        // Level 0 → 7 XP for level 1; 0..6 stored, level 1 at 7 XP.
        player.addExperience(7);
        assertEquals("level after 7 XP", 1, player.getExperienceLevel());
        assertEquals("carry-over", 0, player.getExperiencePoints());
    }

    @Test
    public void levelUpListenerFiresOnBoundary() {
        Player player = new Player(0, 64, 0);
        final int[] levelUps = { 0 };
        final int[] levels = { 0 };
        player.setLevelUpListener(new Player.LevelUpListener() {
            @Override
            public void onLevelUp(int newLevel) {
                levelUps[0]++;
                levels[0] = newLevel;
            }
        });
        // 7 XP hits the level-1 boundary.
        player.addExperience(7);
        assertEquals("one level-up emitted", 1, levelUps[0]);
        assertEquals("level 1 reached", 1, levels[0]);
        // A second 7 XP — at level 1 the threshold is 9, so 7 isn't enough.
        player.addExperience(7);
        assertEquals("still one level-up after partial", 1, levelUps[0]);
        // 9 XP pushes us over the level-2 threshold.
        player.addExperience(9);
        assertEquals("two level-ups emitted", 2, levelUps[0]);
        assertEquals("level 2 reached", 2, levels[0]);
    }
}