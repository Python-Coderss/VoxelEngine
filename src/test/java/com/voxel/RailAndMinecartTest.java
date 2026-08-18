package com.voxel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.joml.Vector3f;
import org.junit.Test;

import com.voxel.entity.MinecartEntity;
import com.voxel.entity.ModelPart;
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
                BlockInteraction.chooseRailShape(world, 5, 60, 5, 0, 1));

        // South neighbour rail (no east neighbour) → the new rail runs N-S.
        World world2 = makeWorld();
        world2.setVoxel(5, 60, 5, MinecartEntity.RAIL_NS);
        world2.setVoxel(5, 60, 6, MinecartEntity.RAIL_NS);
        assertEquals(MinecartEntity.RAIL_NS,
                BlockInteraction.chooseRailShape(world2, 5, 60, 5, 1, 0));
    }

    @Test
    public void railAxisFallsBackToLookDirection() {
        World world = makeWorld();
        // Free-standing: looking mostly along Z → N-S rail
        assertEquals(MinecartEntity.RAIL_NS,
                BlockInteraction.chooseRailShape(world, 5, 60, 5, 0, 1));
        // Looking mostly along X → E-W rail
        assertEquals(MinecartEntity.RAIL_EW,
                BlockInteraction.chooseRailShape(world, 5, 60, 5, 1, 0));
    }

    @Test
    public void railCornersBecomeCurves() {
        // One N-S neighbour (north) + one E-W neighbour (east) → an NE curve.
        World world = makeWorld();
        world.setVoxel(5, 60, 4, MinecartEntity.RAIL_NS);   // north neighbour
        world.setVoxel(6, 60, 5, MinecartEntity.RAIL_EW);   // east neighbour
        assertEquals(MinecartEntity.RAIL_CURVE_NE,
                BlockInteraction.chooseRailShape(world, 5, 60, 5, 1, 0));

        // South + west neighbours → SW curve.
        World world2 = makeWorld();
        world2.setVoxel(5, 60, 6, MinecartEntity.RAIL_NS);  // south neighbour
        world2.setVoxel(4, 60, 5, MinecartEntity.RAIL_EW);  // west neighbour
        assertEquals(MinecartEntity.RAIL_CURVE_SW,
                BlockInteraction.chooseRailShape(world2, 5, 60, 5, 0, 1));
    }

    @Test
    public void cartDrivesThroughCurveCorner() {
        World world = makeWorld();
        // Track: NS rail x=0 (z 0..2), NE curve at (0,3), EW rail z=3 (x 1..3).
        for (int z = 0; z <= 2; z++) world.setVoxel(0, 60, z, MinecartEntity.RAIL_NS);
        world.setVoxel(0, 60, 3, MinecartEntity.RAIL_CURVE_NE);
        for (int x = 1; x <= 3; x++) world.setVoxel(x, 60, 3, MinecartEntity.RAIL_EW);
        MinecartEntity cart = new MinecartEntity(1,
                new Vector3f(0.5f, 60f + MinecartEntity.RAIL_TOP, 0.5f));

        // Drive south past the corner: the cart must arc east and keep riding.
        for (int i = 0; i < 400; i++) cart.updateCart(world, 0.05f, 1.0f);
        assertTrue("cart should have rounded the corner eastward", cart.getPosX() > 2.0f);
        assertEquals("cart rides the EW rail after the corner", 60f + MinecartEntity.RAIL_TOP, cart.getPosY(), 0.001f);
        assertTrue("cart still on rails after the corner", cart.isOnRails());
        assertTrue("cart must not teleport through the corner", cart.getPosX() < 4.0f);
    }

    @Test
    public void cartRoundsCurveExitingWest() {
        World world = makeWorld();
        // N-S rail at x=16 (z 0..3) feeds an NW curve at (16,4) that exits west
        // onto the E-W rail at z=4 (x 0..15). This is the negative-direction
        // exit that used to leave the cart parked on the curve cell boundary.
        for (int z = 0; z <= 3; z++) world.setVoxel(16, 60, z, MinecartEntity.RAIL_NS);
        world.setVoxel(16, 60, 4, MinecartEntity.RAIL_CURVE_NW);
        for (int x = 0; x <= 15; x++) world.setVoxel(x, 60, 4, MinecartEntity.RAIL_EW);
        MinecartEntity cart = new MinecartEntity(1,
                new Vector3f(16.5f, 60f + MinecartEntity.RAIL_TOP, 0.5f));

        for (int i = 0; i < 500; i++) cart.updateCart(world, 0.05f, 1.0f);
        assertTrue("cart must round the west-exit corner", cart.getPosX() < 14.0f);
        assertEquals("cart rides the E-W rail after the corner", 4.5f, cart.getPosZ(), 0.2f);
        assertTrue("cart still on rails after the corner", cart.isOnRails());
    }

    @Test
    public void cartRoundsCurveExitingNorth() {
        World world = makeWorld();
        // E-W rail at z=16 (x 0..3) feeds an NW curve at (4,16) that exits north
        // onto the N-S rail at x=4 (z 0..15).
        for (int x = 0; x <= 3; x++) world.setVoxel(x, 60, 16, MinecartEntity.RAIL_EW);
        world.setVoxel(4, 60, 16, MinecartEntity.RAIL_CURVE_NW);
        for (int z = 0; z <= 15; z++) world.setVoxel(4, 60, z, MinecartEntity.RAIL_NS);
        MinecartEntity cart = new MinecartEntity(1,
                new Vector3f(0.5f, 60f + MinecartEntity.RAIL_TOP, 16.5f));

        for (int i = 0; i < 500; i++) cart.updateCart(world, 0.05f, 1.0f);
        assertTrue("cart must round the north-exit corner", cart.getPosZ() < 14.0f);
        assertEquals("cart rides the N-S rail after the corner", 4.5f, cart.getPosX(), 0.2f);
        assertTrue("cart still on rails after the corner", cart.isOnRails());
    }

    @Test
    public void cartStopsBeforeTrackEndAfterCurve() {
        World world = makeWorld();
        // Short corner: NS rail, NE curve, single EW cell — the track ends there.
        world.setVoxel(0, 60, 0, MinecartEntity.RAIL_NS);
        world.setVoxel(0, 60, 1, MinecartEntity.RAIL_NS);
        world.setVoxel(0, 60, 2, MinecartEntity.RAIL_NS);
        world.setVoxel(0, 60, 3, MinecartEntity.RAIL_CURVE_NE);
        world.setVoxel(1, 60, 3, MinecartEntity.RAIL_EW);
        MinecartEntity cart = new MinecartEntity(1,
                new Vector3f(0.5f, 60f + MinecartEntity.RAIL_TOP, 0.5f));

        for (int i = 0; i < 600; i++) cart.updateCart(world, 0.05f, 1.0f);
        assertTrue("cart parks at the end of the stub rail", cart.getPosX() >= 1.9f && cart.getPosX() < 2.1f);
        assertTrue("cart stays on rails at the end", cart.isOnRails());
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
    public void fillLevelMovesDirtPartUp() {
        MinecartEntity cart = new MinecartEntity(1, new Vector3f(0.5f, 0.5f, 0.5f));
        // The physics-only constructor loads no model, so attach a dirt part manually.
        cart.addPart(new ModelPart("dirt", new Vector3f(-8, -1, -6), new Vector3f(16, 1, 12), 0));

        // Empty: the dirt slab sits just below the cart floor (hidden).
        cart.setFillLevel(0f);
        assertEquals(1f, cart.findPart("dirt").offset.y, 0.001f);
        assertEquals(0f, cart.getFillLevel(), 0.001f);

        // Half full: slab bottom halfway up the 8-unit-tall interior.
        cart.setFillLevel(0.5f);
        assertEquals(5f, cart.findPart("dirt").offset.y, 0.001f);

        // Full: slab top flush with the wall tops (y = 0.625 blocks).
        cart.setFillLevel(1f);
        assertEquals(9f, cart.findPart("dirt").offset.y, 0.001f);

        // Out-of-range values clamp to [0, 1].
        cart.setFillLevel(2f);
        assertEquals(1f, cart.getFillLevel(), 0.001f);
        assertEquals(9f, cart.findPart("dirt").offset.y, 0.001f);
        cart.setFillLevel(-3f);
        assertEquals(0f, cart.getFillLevel(), 0.001f);
        assertEquals(1f, cart.findPart("dirt").offset.y, 0.001f);
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
