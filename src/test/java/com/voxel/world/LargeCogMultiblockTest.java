package com.voxel.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins down the large cogwheel multiblock structure: a center block (295) plus
 * 8 ghost parts (422-429) that occupy a 3x1x3 footprint. The IDs and their
 * {dx,dz} offsets are the contract shared by placement (BlockInteraction) and
 * the drop mapping, so this is regression-tested here.
 */
public class LargeCogMultiblockTest {

    @Test
    public void isLargeCogRecognizesCenterAndParts() {
        assertTrue(KineticManager.isLargeCog(KineticManager.BLOCK_LARGE_COGWHEEL));
        for (int id = 422; id <= 429; id++) {
            assertTrue("part " + id, KineticManager.isLargeCog(id));
            assertTrue("part " + id, KineticManager.isLargeCogPart(id));
        }
        assertFalse(KineticManager.isLargeCogPart(KineticManager.BLOCK_LARGE_COGWHEEL));
        for (int id : new int[]{294, 296, 291, 404, 2, 0}) {
            assertFalse("id " + id, KineticManager.isLargeCog(id));
        }
    }

    @Test
    public void partsJoinTheKineticNetwork() {
        for (int id = 422; id <= 429; id++) {
            assertTrue("part " + id + " must be kinetic", KineticManager.isKinetic(id));
        }
        assertTrue(KineticManager.isKinetic(KineticManager.BLOCK_LARGE_COGWHEEL));
    }

    @Test
    public void partOffsetsCoverTheThreeByThreeFootprint() {
        boolean[] seen = new boolean[9];
        int[] ids = {422, 423, 424, 425, 426, 427, 428, 429};
        for (int id : ids) {
            int[] off = KineticManager.largeCogPartOffset(id);
            assertTrue("offset for " + id, off != null && off.length == 2);
            // Map (dx,dz) in {-1,0,1}^2 to an index 0..8 (center excluded -> 8 parts).
            int col = off[0] + 1;   // 0..2
            int row = off[1] + 1;   // 0..2
            int idx = row * 3 + col;
            assertFalse("duplicate offset for " + id, seen[idx]);
            seen[idx] = true;
        }
        // The center cell (1,1) is the only one not covered by a part.
        assertFalse("center must not be a part offset", seen[4]);
        for (int i = 0; i < 9; i++) {
            if (i != 4) assertTrue("cell " + i + " uncovered", seen[i]);
        }
    }

    @Test
    public void centerHasNoOffsetAndPartsRenderGearSlices() {
        assertNull(KineticManager.largeCogPartOffset(KineticManager.BLOCK_LARGE_COGWHEEL));
        assertNull(KineticManager.largeCogPartOffset(294));
        // Each part is a gear prism (axis Y, 1.5-block radius) sharing the center's
        // descriptor; the raytracer clips it to the part's own cell.
        for (int id = 422; id <= 429; id++) {
            KineticManager.GearDescriptor g = KineticManager.gearDescriptor(id);
            assertTrue("part " + id + " must render a gear slice", g != null);
            assertEquals(1, g.axis);
            assertEquals(24 * (1.0f / 16.0f), g.radius, 1e-6f);
        }
    }

    @Test
    public void partRangeIsContiguous() {
        assertEquals(422, KineticManager.BLOCK_LARGE_COG_PART_MIN);
        assertEquals(429, KineticManager.BLOCK_LARGE_COG_PART_MAX);
    }
}
