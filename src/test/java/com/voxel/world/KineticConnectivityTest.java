package com.voxel.world;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the orientation-aware kinetic coupling contract used by
 * {@link KineticManager#bfsNetwork}. Shafts only mesh end-to-end along their
 * axle, discs mesh in their plane (or stack coaxially), a shaft drives a disc
 * only along the axle, and non-directional components (machines, clutch,
 * gearshift, engines, cranks, bearings, sails, belts) couple from any side.
 */
public class KineticConnectivityTest {

    @Test
    public void shaftClassification() {
        for (int id : new int[]{291, 292, 293}) assertTrue("shaft " + id, KineticManager.isShaft(id));
        for (int id : new int[]{294, 295, 296, 422, 430, 353, 355, 404, 2, 0}) {
            assertFalse("not a shaft: " + id, KineticManager.isShaft(id));
        }
        assertTrue("shaft Y", KineticManager.shaftAxis(291) == 1);
        assertTrue("shaft X", KineticManager.shaftAxis(292) == 0);
        assertTrue("shaft Z", KineticManager.shaftAxis(293) == 2);
    }

    @Test
    public void discClassification() {
        for (int id : new int[]{294, 295, 422, 429, 296, 430, 437}) {
            assertTrue("disc " + id, KineticManager.isDisc(id));
        }
        for (int id : new int[]{291, 292, 293, 353, 355, 404, 2, 0}) {
            assertFalse("not a disc: " + id, KineticManager.isDisc(id));
        }
    }

    @Test
    public void shaftsCoupleEndToEndOnly() {
        int shaftY = 291, shaftX = 292;
        // Two vertical shafts stacked: end-to-end along Y -> coupled.
        assertTrue(KineticManager.canConnect(shaftY, 1, shaftY, 1, new int[]{0, 1, 0}));
        // Two vertical shafts side by side: parallel rods -> not coupled.
        assertFalse(KineticManager.canConnect(shaftY, 1, shaftY, 1, new int[]{1, 0, 0}));
        // A vertical shaft never couples to a horizontal shaft (axis mismatch).
        assertFalse(KineticManager.canConnect(shaftY, 1, shaftX, 0, new int[]{1, 0, 0}));
    }

    @Test
    public void shaftDrivesDiscAlongTheAxle() {
        int shaftX = 292, cog = 294;
        // Cog (axis X) on the end of an X shaft -> coupled.
        assertTrue(KineticManager.canConnect(shaftX, 0, cog, 0, new int[]{1, 0, 0}));
        // Cog beside the shaft (not on its end) -> not coupled.
        assertFalse(KineticManager.canConnect(shaftX, 0, cog, 0, new int[]{0, 1, 0}));
        // Water wheel (axis X) on the end of an X shaft -> coupled.
        int wheelX = KineticManager.gearAxis(296, 5);
        assertTrue(KineticManager.canConnect(shaftX, 0, 296, wheelX, new int[]{1, 0, 0}));
    }

    @Test
    public void discsMeshInPlaneAndCoaxially() {
        int cog = 294;
        // Axis-X cogs are vertical discs (YZ plane): meshing vertically or
        // north-south is in-plane, and face-to-face along X is a coaxial stack.
        assertTrue(KineticManager.canConnect(cog, 0, cog, 0, new int[]{0, 1, 0}));
        assertTrue(KineticManager.canConnect(cog, 0, cog, 0, new int[]{0, 0, 1}));
        assertTrue(KineticManager.canConnect(cog, 0, cog, 0, new int[]{1, 0, 0}));
        // Perpendicular cogs (axis X vs axis Y) don't mesh.
        assertFalse(KineticManager.canConnect(cog, 0, cog, 1, new int[]{0, 1, 0}));
    }

    @Test
    public void largeCogMeshesCoaxiallyWithWaterWheel() {
        // The tutorial machine works stacks the large cog (axis X) above the water
        // wheel (axis X) — both vertical discs sharing an axle, coupled along Y.
        int largeCogAxis = KineticManager.gearAxis(295, 5);  // facing east -> X
        int wheelAxis = KineticManager.gearAxis(296, 5);     // facing east -> X
        assertTrue(KineticManager.canConnect(296, wheelAxis, 295, largeCogAxis, new int[]{0, 1, 0}));
    }

    @Test
    public void oldVerticalShaftRowDoesNotReachTheWheel() {
        // The pre-fix layout had vertical (Y) shafts in a horizontal row next to a
        // Z-axle water wheel. That must now fail so the layout is corrected.
        int shaftY = 291;
        int wheelZ = KineticManager.gearAxis(296, 0); // default facing -> Z
        assertFalse(KineticManager.canConnect(shaftY, 1, 296, wheelZ, new int[]{1, 0, 0}));
    }

    @Test
    public void nonDirectionalComponentsCoupleFromAnySide() {
        int shaftY = 291;
        int[] dir = {1, 0, 0}; // side-on, perpendicular to the shaft axis
        for (int id : new int[]{353, 355, 396, 404, 405, 406, 408, 413}) {
            assertTrue("id " + id, KineticManager.canConnect(shaftY, 1, id, -1, dir));
            assertTrue("id " + id, KineticManager.canConnect(id, -1, shaftY, 1, dir));
        }
    }
}
