package com.voxel.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins down the gear prism shapes used to render the kinetic blocks as
 * non-AABB N-sided gears. {@link KineticManager#gearDescriptor} is the Java
 * mirror of raytracer.comp's getGear(); these tests lock the contract the
 * shader must honor (axis, 16-sided convex profile, radius in block units).
 */
public class KineticGearTest {

    private static final float P = 1.0f / 16.0f;

    @Test
    public void allKineticBlocksAreGears() {
        for (int block = 291; block <= 296; block++) {
            assertNotNull("block " + block + " must be a gear", KineticManager.gearDescriptor(block));
        }
    }

    @Test
    public void nonKineticBlocksAreNotGears() {
        for (int block : new int[]{290, 297, 353, 394, 404, 2}) {
            assertNull("block " + block + " must not be a gear", KineticManager.gearDescriptor(block));
        }
    }

    @Test
    public void shaftsAreFourSidedSquareRods() {
        assertEquals(1, KineticManager.gearDescriptor(291).axis); // Y
        assertEquals(0, KineticManager.gearDescriptor(292).axis); // X
        assertEquals(2, KineticManager.gearDescriptor(293).axis); // Z
        for (int block = 291; block <= 293; block++) {
            KineticManager.GearDescriptor g = KineticManager.gearDescriptor(block);
            assertEquals("shaft radius (2px -> 4x4)", 2 * P, g.radius, 1e-6f);
            assertEquals("shaft spans the full block (16px)", 0.5f, g.halfThickness, 1e-6f);
            assertEquals("square cross-section", 4, g.sides);
        }
    }

    @Test
    public void squareShaftEntryIsAxisAligned() {
        // Axis-aligned square (4-gon) of circumradius 0.125: half-extent 0.125/√2.
        float half = (float) (0.125 / Math.sqrt(2.0));
        // Ray from the left hits the left face at t = 2 - half.
        assertEquals(2.0f - half, KineticManager.intersectGearEntry(-2f, 0f, 1f, 0f, 0f, 0f, 0.125f, 4), 1e-4f);
        // Ray offset above the square misses.
        assertEquals(-1f, KineticManager.intersectGearEntry(-2f, 0.2f, 1f, 0f, 0f, 0f, 0.125f, 4), 1e-6f);
    }

    @Test
    public void spinningSquareShaftHitboxRotates() {
        // Axis-aligned square (spinAng=0) of circumradius 0.125: half-extent 0.0884.
        float half = (float) (0.125 / Math.sqrt(2.0));
        // A ray at y=0.1 clears the axis-aligned square but is swept by the corner
        // of the same square once it has spun 45 deg (π/4): the corner then points
        // along -x at x=-0.125 and the left edge crosses y=0.1 at x=-0.025.
        assertEquals(-1f, KineticManager.intersectGearEntry(-2f, 0.1f, 1f, 0f, 0f, 0f, 0.125f, 4, 0.0f), 1e-6f);
        assertEquals(1.975f, KineticManager.intersectGearEntry(-2f, 0.1f, 1f, 0f, 0f, 0f, 0.125f, 4, (float) (Math.PI / 4.0)), 1e-3f);
        // Along the axis (y=0), the corner spins out to the full circumradius:
        assertEquals(2.0f - 0.125f, KineticManager.intersectGearEntry(-2f, 0f, 1f, 0f, 0f, 0f, 0.125f, 4, (float) (Math.PI / 4.0)), 1e-4f);
        // And at rest it stops short at the flat face:
        assertEquals(2.0f - half, KineticManager.intersectGearEntry(-2f, 0f, 1f, 0f, 0f, 0f, 0.125f, 4, 0.0f), 1e-4f);
    }

    @Test
    public void shaftThroughOnlyOnGearCenters() {
        assertTrue(KineticManager.isShaftThrough(294));
        assertTrue(KineticManager.isShaftThrough(295));
        assertTrue(KineticManager.isShaftThrough(296));
        for (int id = 422; id <= 437; id++) assertFalse("part " + id, KineticManager.isShaftThrough(id));
        for (int id : new int[]{291, 292, 293, 2, 0}) assertFalse(KineticManager.isShaftThrough(id));
    }

    @Test
    public void smallCogIsOneBlockFacetedDisc() {
        KineticManager.GearDescriptor g = KineticManager.gearDescriptor(294);
        assertEquals(1, g.axis); // horizontal (spins about Y)
        assertEquals(8 * P, g.radius, 1e-6f); // full block, teeth reach the rim
        assertEquals(3 * P, g.halfThickness, 1e-6f);
        assertEquals(16, g.sides);
        // Fills exactly one block (diameter == 1.0).
        assertEquals(1.0f, g.radius * 2.0f, 1e-6f);
    }

    @Test
    public void largeCogSpansThreeByThree() {
        KineticManager.GearDescriptor g = KineticManager.gearDescriptor(295);
        assertEquals(1, g.axis); // horizontal
        assertEquals(24 * P, g.radius, 1e-6f); // 1.5 blocks
        assertEquals(3 * P, g.halfThickness, 1e-6f);
        assertEquals(16, g.sides);
        // Render-only 3x1x3: diameter = 3.0 blocks.
        assertEquals(3.0f, g.radius * 2.0f, 1e-6f);
    }

    @Test
    public void waterWheelIsVerticalMultiblock() {
        KineticManager.GearDescriptor g = KineticManager.gearDescriptor(296);
        assertEquals(2, g.axis); // spins about Z (faces north/south)
        assertEquals(24 * P, g.radius, 1e-6f); // 3x3 footprint
        assertEquals(4 * P, g.halfThickness, 1e-6f);
        assertEquals(16, g.sides);
        assertEquals(3.0f, g.radius * 2.0f, 1e-6f);
    }

    @Test
    public void gearEntrySlabTestHitsAndMisses() {
        // Ray from the left crossing a 16-gon of radius 0.5 centred at (0,0).
        // The left edge is at -0.5, so the entry is at t = 1.5.
        assertEquals(1.5f, KineticManager.intersectGearEntry(-2f, 0f, 1f, 0f, 0f, 0f, 0.5f, 16), 1e-4f);
        // Ray offset sideways misses the disc entirely.
        assertEquals(-1f, KineticManager.intersectGearEntry(-2f, 1f, 1f, 0f, 0f, 0f, 0.5f, 16), 1e-6f);
        // Large cog (radius 1.5): left edge at -1.5, entry at t = 1.5 from x=-3.
        assertEquals(1.5f, KineticManager.intersectGearEntry(-3f, 0f, 1f, 0f, 0f, 0f, 1.5f, 16), 1e-4f);
        // Diagonal ray along x=z: closest approach is sqrt(8) - 0.5.
        float t = KineticManager.intersectGearEntry(-2f, -2f, 0.70710678f, 0.70710678f, 0f, 0f, 0.5f, 16);
        assertEquals(2.328427f, t, 1e-3f);
    }

    @Test
    public void multiblockPartsShareTheirCenterDescriptor() {
        // Large cogwheel parts (422-429) mirror the center's axis Y, 1.5-block radius.
        for (int id = 422; id <= 429; id++) {
            KineticManager.GearDescriptor g = KineticManager.gearDescriptor(id);
            assertNotNull("large cog part " + id, g);
            assertEquals(1, g.axis);
            assertEquals(24 * P, g.radius, 1e-6f);
            assertEquals(3 * P, g.halfThickness, 1e-6f);
            assertEquals(16, g.sides);
        }
        // Water wheel parts (430-437) mirror the center's axis Z, 1.5-block radius.
        for (int id = 430; id <= 437; id++) {
            KineticManager.GearDescriptor g = KineticManager.gearDescriptor(id);
            assertNotNull("water wheel part " + id, g);
            assertEquals(2, g.axis);
            assertEquals(24 * P, g.radius, 1e-6f);
            assertEquals(4 * P, g.halfThickness, 1e-6f);
            assertEquals(16, g.sides);
        }
    }
}
