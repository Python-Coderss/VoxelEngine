package com.voxel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.joml.Vector3f;
import org.junit.Test;

import com.voxel.entity.MinecartEntity;
import com.voxel.game.BlockInteraction;

public class RailAndMinecartTest {

    /** World with the chunks covering the test volume (x/z 0..15, y 48..63) allocated. */
    private World makeWorld() {
        World world = new World(8);
        int slot = 0;
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                world.setChunkSlot(cx, 3, cz, slot);
                world.clearChunkPoolSlot(slot);
                slot++;
            }
        }
        return world;
    }

    @Test
    public void railAxisFollowsNeighbours() {
        // East neighbour rail → the new rail must run E-W to connect.
        World world = makeWorld();
        world.setVoxel(5, 60, 5, MinecartEntity.RAIL_NS);
        world.setVoxel(6, 60, 5, MinecartEntity.RAIL_EW);
        assertEquals(MinecartEntity.RAIL_EW,
                BlockInteraction.chooseRailAxis(world, 5, 60, 5, 0, 1));

        // South neighbour rail (no east neighbour) → the new rail runs N-S.
        World world2 = makeWorld();
        world2.setVoxel(5, 60, 5, MinecartEntity.RAIL_NS);
        world2.setVoxel(5, 60, 6, MinecartEntity.RAIL_NS);
        assertEquals(MinecartEntity.RAIL_NS,
                BlockInteraction.chooseRailAxis(world2, 5, 60, 5, 1, 0));
    }

    @Test
    public void railAxisFallsBackToLookDirection() {
        World world = makeWorld();
        // Free-standing: looking mostly along Z → N-S rail
        assertEquals(MinecartEntity.RAIL_NS,
                BlockInteraction.chooseRailAxis(world, 5, 60, 5, 0, 1));
        // Looking mostly along X → E-W rail
        assertEquals(MinecartEntity.RAIL_EW,
                BlockInteraction.chooseRailAxis(world, 5, 60, 5, 1, 0));
    }

    @Test
    public void cartMovesAlongRailAndStopsAtTrackEnd() {
        World world = makeWorld();
        for (int z = 0; z <= 2; z++) world.setVoxel(0, 60, z, MinecartEntity.RAIL_NS);
        MinecartEntity cart = new MinecartEntity(1,
                new Vector3f(0.5f, 60f + MinecartEntity.RAIL_TOP, 0.5f));

        // Drive forward for a bit: it should advance along the rail, stay centered.
        for (int i = 0; i < 20; i++) cart.updateCart(world, 0.05f, 1.0f);
        assertTrue("cart should advance along the rail", cart.getPosZ() > 0.5f);
        assertTrue("cart should stay on rails", cart.isOnRails());
        assertEquals("cart stays centered on the N-S track", 0.5f, cart.getPosX(), 0.001f);
        assertEquals("cart rides on top of the rail", 60f + MinecartEntity.RAIL_TOP, cart.getPosY(), 0.001f);

        // Keep driving: it must stop at the end of the 3-cell track, not fall off.
        for (int i = 0; i < 300; i++) cart.updateCart(world, 0.05f, 1.0f);
        assertTrue("cart must stop at the track end", cart.getPosZ() < 3.1f);
        assertTrue("cart still on rails after stopping", cart.isOnRails());
    }

    @Test
    public void cartFallsAndRestsWhenRailRemoved() {
        World world = makeWorld();
        world.setVoxel(0, 60, 0, MinecartEntity.RAIL_NS);
        world.setVoxel(0, 59, 0, 2); // stone floor below
        MinecartEntity cart = new MinecartEntity(1,
                new Vector3f(0.5f, 60f + MinecartEntity.RAIL_TOP, 0.5f));
        world.setVoxel(0, 60, 0, 0); // remove the rail under the cart

        for (int i = 0; i < 200; i++) cart.updateCart(world, 0.05f, 0);
        assertFalse("cart should leave the rails", cart.isOnRails());
        assertEquals("cart should rest on the ground below", 60.0, cart.getPosY(), 0.01);
    }

    @Test
    public void cartCoastsToStopWithoutInput() {
        World world = makeWorld();
        for (int z = 0; z <= 5; z++) world.setVoxel(0, 60, z, MinecartEntity.RAIL_NS);
        MinecartEntity cart = new MinecartEntity(1,
                new Vector3f(0.5f, 60f + MinecartEntity.RAIL_TOP, 0.5f));

        // Give it a push for 10 ticks, then release the controls.
        for (int i = 0; i < 10; i++) cart.updateCart(world, 0.05f, 1.0f);
        assertTrue("cart should be moving after the push", cart.getSpeed() > 0);
        for (int i = 0; i < 100; i++) cart.updateCart(world, 0.05f, 0);
        assertEquals("cart coasts to a stop", 0.0f, cart.getSpeed(), 0.02f);
    }
}
