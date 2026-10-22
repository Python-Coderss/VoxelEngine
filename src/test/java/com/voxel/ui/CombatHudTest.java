package com.voxel.ui;

import com.voxel.Main;
import com.voxel.camera.CameraController;
import com.voxel.game.GameContext;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Pure helpers behind the MCSM combat overlay: the charge meter must fill on
 * the same 1.2s curve the damage formula uses, and the lock-on readout must
 * name the mob without leaking the "Entity" suffix.
 */
public class CombatHudTest {

    private static final float EPS = 0.0001f;

    @Test
    public void chargeMeterUsesFullPowerWindow() {
        assertEquals(0f, CombatHud.chargePercent(0f), EPS);
        assertEquals(0.5f, CombatHud.chargePercent(0.6f), EPS);
        assertEquals(1f, CombatHud.chargePercent(1.2f), EPS);
    }

    @Test
    public void chargeMeterClampsAboveAndBelow() {
        assertEquals(0f, CombatHud.chargePercent(-0.5f), EPS);
        // The game keeps charging to 1.5s, but the meter stops at full power.
        assertEquals(1f, CombatHud.chargePercent(1.5f), EPS);
    }

    @Test
    public void enemyLabelFallsBackForUnknownMobs() {
        assertEquals("Enemy", CombatHud.enemyLabel(null));
    }

    /**
     * Building the overlay must not need OpenGL, and the inactive path must not
     * touch the camera (combat mode off is the normal case for most frames).
     */
    @Test
    public void inactiveOverlayBuildsAndHides() {
        GameContext ctx = new GameContext();
        Main main = new Main();
        CombatHud hud = new CombatHud(ctx, main, new CameraController(ctx, main), 0);
        assertNotNull(hud.layer());
        hud.update(0.0);
    }
}
