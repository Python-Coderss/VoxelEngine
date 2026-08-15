package com.voxel.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins down the water wheel multiblock structure: a center block (296) plus 8
 * parts (430-437) occupying a 3x3x1 footprint in the XY plane. The IDs and
 * their {dx,dy} offsets are the contract shared by placement (BlockInteraction)
 * and the drop mapping, so this is regression-tested here.
 */
public class WaterWheelMultiblockTest {

    @Test
    public void isWaterWheelRecognizesCenterAndParts() {
        assertTrue(KineticManager.isWaterWheel(KineticManager.BLOCK_WATER_WHEEL));
        for (int id = 430; id <= 437; id++) {
            assertTrue("part " + id, KineticManager.isWaterWheel(id));
            assertTrue("part " + id, KineticManager.isWaterWheelPart(id));
        }
        assertFalse(KineticManager.isWaterWheelPart(KineticManager.BLOCK_WATER_WHEEL));
        for (int id : new int[]{294, 295, 291, 404, 2, 0}) {
            assertFalse("id " + id, KineticManager.isWaterWheel(id));
        }
    }

    @Test
    public void partsJoinTheKineticNetwork() {
        for (int id = 430; id <= 437; id++) {
            assertTrue("part " + id + " must be kinetic", KineticManager.isKinetic(id));
        }
        assertTrue(KineticManager.isKinetic(KineticManager.BLOCK_WATER_WHEEL));
    }

    @Test
    public void partOffsetsCoverTheThreeByThreeFootprint() {
        boolean[] seen = new boolean[9];
        int[] ids = {430, 431, 432, 433, 434, 435, 436, 437};
        for (int id : ids) {
            int[] off = KineticManager.waterWheelPartOffset(id);
            assertTrue("offset for " + id, off != null && off.length == 2);
            int col = off[0] + 1; // 0..2 (dx)
            int row = off[1] + 1; // 0..2 (dy)
            int idx = row * 3 + col;
            assertFalse("duplicate offset for " + id, seen[idx]);
            seen[idx] = true;
        }
        assertFalse("center must not be a part offset", seen[4]);
        for (int i = 0; i < 9; i++) {
            if (i != 4) assertTrue("cell " + i + " uncovered", seen[i]);
        }
    }

    @Test
    public void centerHasNoOffsetAndPartsRenderGearSlices() {
        assertNull(KineticManager.waterWheelPartOffset(KineticManager.BLOCK_WATER_WHEEL));
        assertNull(KineticManager.waterWheelPartOffset(294));
        for (int id = 430; id <= 437; id++) {
            KineticManager.GearDescriptor g = KineticManager.gearDescriptor(id);
            assertNotNull("part " + id + " must render a gear slice", g);
            assertEquals(2, g.axis);
            assertEquals(24 * (1.0f / 16.0f), g.radius, 1e-6f);
        }
    }

    @Test
    public void partRangeIsContiguous() {
        assertEquals(430, KineticManager.BLOCK_WATER_WHEEL_PART_MIN);
        assertEquals(437, KineticManager.BLOCK_WATER_WHEEL_PART_MAX);
    }
}
