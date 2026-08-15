package com.voxel.world;

import com.voxel.game.BlockInteraction;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Locks the gear axle orientation contract: per-voxel facing bits (16-18) pick
 * the spin axis, and the multiblock footprint planes (large cog XZ/ZY/XY,
 * water wheel XY/ZY) are derived from that axis. These are the Java mirrors of
 * raytracer.comp's axisFromFacing()/localToWorld()/getGear() and must stay in
 * sync with them.
 */
public class GearOrientationTest {

    private static final float P = 1.0f / 16.0f;

    @Test
    public void facingMapsToAxis() {
        assertEquals(1, KineticManager.axisFromFacing(0)); // down -> Y
        assertEquals(1, KineticManager.axisFromFacing(1)); // up -> Y
        assertEquals(2, KineticManager.axisFromFacing(2)); // north -> Z
        assertEquals(2, KineticManager.axisFromFacing(3)); // south -> Z
        assertEquals(0, KineticManager.axisFromFacing(4)); // west -> X
        assertEquals(0, KineticManager.axisFromFacing(5)); // east -> X
    }

    @Test
    public void cogwheelAxleFollowsFacing() {
        assertEquals(1, KineticManager.gearDescriptor(294, 1).axis); // up -> Y
        assertEquals(0, KineticManager.gearDescriptor(294, 5).axis); // east -> X
        assertEquals(2, KineticManager.gearDescriptor(294, 3).axis); // south -> Z
        // The cogwheel model is laid out for a horizontal (axis-Y) disc.
        assertEquals(1, KineticManager.gearDescriptor(294, 5).modelAxis);
    }

    @Test
    public void largeCogAxleFollowsFacing() {
        assertEquals(1, KineticManager.gearDescriptor(295, 0).axis);
        assertEquals(0, KineticManager.gearDescriptor(295, 4).axis);
        assertEquals(2, KineticManager.gearDescriptor(295, 2).axis);
    }

    @Test
    public void waterWheelNeverSpinsAboutY() {
        // Water wheels are vertical discs (horizontal axle); an up/down facing
        // (axis Y) falls back to axis Z, the default orientation.
        assertEquals(2, KineticManager.gearDescriptor(296, 0).axis);
        assertEquals(2, KineticManager.gearDescriptor(296, 1).axis);
        assertEquals(2, KineticManager.gearDescriptor(296, 2).axis);
        assertEquals(0, KineticManager.gearDescriptor(296, 5).axis); // east -> X
        assertEquals(0, KineticManager.gearDescriptor(296, 4).axis); // west -> X
    }

    @Test
    public void localToWorldMapsDiscPlanePerAxis() {
        // axis Y: (x,z) = (du,dv)
        assertArrayEquals(new int[]{1, 0, -1}, KineticManager.localToWorld(1, -1, 1));
        // axis X: (z,y) = (du,dv)
        assertArrayEquals(new int[]{0, -1, 1}, KineticManager.localToWorld(1, -1, 0));
        // axis Z: (x,y) = (du,dv)
        assertArrayEquals(new int[]{1, -1, 0}, KineticManager.localToWorld(1, -1, 2));
    }

    @Test
    public void largeCogPartFootprintSwitchesPlaneWithFacing() {
        // North part (422) has canonical (du,dv) = (0,-1).
        // axis Y -> (0,0,-1); axis X -> (0,-1,0); axis Z -> (0,-1,0).
        assertArrayEquals(new int[]{0, 0, -1}, KineticManager.largeCogPartWorldOffset(422, 1));
        assertArrayEquals(new int[]{0, -1, 0}, KineticManager.largeCogPartWorldOffset(422, 5));
        assertArrayEquals(new int[]{0, -1, 0}, KineticManager.largeCogPartWorldOffset(422, 3));
        // East part (425) has canonical (du,dv) = (1,0).
        assertArrayEquals(new int[]{1, 0, 0}, KineticManager.largeCogPartWorldOffset(425, 1));
        assertArrayEquals(new int[]{0, 0, 1}, KineticManager.largeCogPartWorldOffset(425, 5));
        assertArrayEquals(new int[]{1, 0, 0}, KineticManager.largeCogPartWorldOffset(425, 3));
    }

    @Test
    public void waterWheelPartFootprintSwitchesPlaneWithFacing() {
        // Up part (430) has canonical (du,dv) = (0,1).
        // axis Z -> (0,1,0); axis X -> (0,1,0).
        assertArrayEquals(new int[]{0, 1, 0}, KineticManager.waterWheelPartWorldOffset(430, 3));
        assertArrayEquals(new int[]{0, 1, 0}, KineticManager.waterWheelPartWorldOffset(430, 5));
        // Right part (433) has canonical (du,dv) = (1,0).
        assertArrayEquals(new int[]{1, 0, 0}, KineticManager.waterWheelPartWorldOffset(433, 3));
        assertArrayEquals(new int[]{0, 0, 1}, KineticManager.waterWheelPartWorldOffset(433, 5));
    }

    @Test
    public void partCenterOffsetsPointBackToTheCenter() {
        // For each part, center = part cell + centerOff; the descriptor's (cx,cy,cz)
        // is the negation of the part's world offset.
        for (int id = 422; id <= 429; id++) {
            for (int facing : new int[]{1, 5, 3}) {
                int[] off = KineticManager.largeCogPartWorldOffset(id, facing);
                KineticManager.GearDescriptor g = KineticManager.gearDescriptor(id, facing);
                assertArrayEquals("large cog part " + id + " facing " + facing,
                        new int[]{-off[0], -off[1], -off[2]}, new int[]{g.cx, g.cy, g.cz});
            }
        }
        for (int id = 430; id <= 437; id++) {
            for (int facing : new int[]{3, 5}) {
                int[] off = KineticManager.waterWheelPartWorldOffset(id, facing);
                KineticManager.GearDescriptor g = KineticManager.gearDescriptor(id, facing);
                assertArrayEquals("water wheel part " + id + " facing " + facing,
                        new int[]{-off[0], -off[1], -off[2]}, new int[]{g.cx, g.cy, g.cz});
            }
        }
    }

    @Test
    public void partRadiusUnchangedAcrossFacing() {
        for (int id = 422; id <= 429; id++) {
            assertEquals(24 * P, KineticManager.gearDescriptor(id, 5).radius, 1e-6f);
            assertEquals(3 * P, KineticManager.gearDescriptor(id, 5).halfThickness, 1e-6f);
        }
    }

    @Test
    public void placementFacingFollowsClickedFace() {
        int[] hit = {1, 0, 0, 0, 0, 0}; // clicked +X face (block at x=1, place at x=0)
        assertEquals(5, BlockInteraction.facingFromClickedFace(hit, 0, 0, 0));
        int[] hitWest = {-1, 0, 0, 0, 0, 0};
        assertEquals(4, BlockInteraction.facingFromClickedFace(hitWest, 0, 0, 0));
        int[] hitUp = {0, 1, 0, 0, 0, 0};
        assertEquals(1, BlockInteraction.facingFromClickedFace(hitUp, 0, 0, 0));
        int[] hitDown = {0, -1, 0, 0, 0, 0};
        assertEquals(0, BlockInteraction.facingFromClickedFace(hitDown, 0, 0, 0));
        int[] hitSouth = {0, 0, 1, 0, 0, 0};
        assertEquals(3, BlockInteraction.facingFromClickedFace(hitSouth, 0, 0, 0));
        int[] hitNorth = {0, 0, -1, 0, 0, 0};
        assertEquals(2, BlockInteraction.facingFromClickedFace(hitNorth, 0, 0, 0));
    }
}
