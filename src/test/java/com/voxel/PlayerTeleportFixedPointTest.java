package com.voxel;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.voxel.game.CommandProcessor;
import com.voxel.game.GameContext;
import com.voxel.utils.FixedPoint;

public class PlayerTeleportFixedPointTest {
    @Test
    public void relativeCommandUsesRawFixedPointCoordinates() {
        GameContext context = new GameContext();
        context.player = new Player(0, 0, 0);
        context.gameMode = GameContext.GameMode.SURVIVAL;
        long startX = 13_000_000L * FixedPoint.SCALE + 127L;
        long startY = 64L * FixedPoint.SCALE + 63L;
        long startZ = -13_000_000L * FixedPoint.SCALE - 91L;
        context.player.teleportFixed(startX, startY, startZ);

        new CommandProcessor(context).execute("/tp ~1 ~-2 ~3");

        assertEquals(startX + FixedPoint.SCALE, context.player.getFixedX());
        assertEquals(startY - 2L * FixedPoint.SCALE, context.player.getFixedY());
        assertEquals(startZ + 3L * FixedPoint.SCALE, context.player.getFixedZ());
        assertEquals(startX + FixedPoint.SCALE, context.player.getFixedPrevX());
        assertEquals(startY - 2L * FixedPoint.SCALE, context.player.getFixedPrevY());
        assertEquals(startZ + 3L * FixedPoint.SCALE, context.player.getFixedPrevZ());
        assertEquals(true, context.teleportLoading);
    }

    @Test
    public void rejectsCoordinatesOutsideIntegerWorldRange() {
        GameContext context = new GameContext();
        context.player = new Player(0, 0, 0);
        context.gameMode = GameContext.GameMode.CREATIVE;
        new CommandProcessor(context).execute("/tp 2147483648 0 0");

        assertEquals(0L, context.player.getFixedX());
        assertEquals(false, context.teleportLoading);
    }

    @Test
    public void preservesLargeFixedPointCoordinatesWithoutFloatConversion() {
        Player player = new Player(0, 0, 0);
        player.getVelocityD().set(1.0, -2.0, 3.0);

        long x = 13_000_000L * FixedPoint.SCALE + 127L;
        long y = -188L * FixedPoint.SCALE + 63L;
        long z = -13_000_000L * FixedPoint.SCALE - 91L;

        player.teleportFixed(x, y, z);

        assertEquals(x, player.getFixedX());
        assertEquals(y, player.getFixedY());
        assertEquals(z, player.getFixedZ());
        assertEquals(x, player.getFixedPrevX());
        assertEquals(y, player.getFixedPrevY());
        assertEquals(z, player.getFixedPrevZ());
        assertEquals(0.0, player.getVelocityD().x, 0.0);
        assertEquals(0.0, player.getVelocityD().y, 0.0);
        assertEquals(0.0, player.getVelocityD().z, 0.0);
    }
}
