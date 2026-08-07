package com.voxel.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AncientBuilderFacilityTest {
    @Test
    public void selectsClosestFacilityAbovePlayer() {
        assertEquals(188, AncientBuilderFacility.closestFacilityYAbove(0.0));
        assertEquals(188, AncientBuilderFacility.closestFacilityYAbove(188.0));
        assertEquals(400, AncientBuilderFacility.closestFacilityYAbove(189.0));
        assertEquals(704, AncientBuilderFacility.closestFacilityYAbove(700.0));
        assertEquals(1408, AncientBuilderFacility.closestFacilityYAbove(1400.0));
        assertEquals(1408, AncientBuilderFacility.closestFacilityYAbove(2000.0));
    }

    @Test
    public void everyFacilityBandHasItsArchiveAndConsolePrograms() {
        for (int facilityY : AncientBuilderFacility.FACILITY_YS) {
            assertTrue(AncientBuilderFacility.isPowerFragmentChest(
                    AncientBuilderFacility.FACILITY_X, facilityY + 1,
                    AncientBuilderFacility.FACILITY_Z - 3));
            assertEquals("dimension portal_hall", AncientBuilderFacility.defaultCommandAt(
                    AncientBuilderFacility.FACILITY_X - 3, facilityY + 1,
                    AncientBuilderFacility.FACILITY_Z));
            assertEquals("tp ~ ~ ~", AncientBuilderFacility.defaultCommandAt(
                    AncientBuilderFacility.FACILITY_X - 2, facilityY + 1,
                    AncientBuilderFacility.FACILITY_Z));
            assertEquals("dimension end", AncientBuilderFacility.defaultCommandAt(
                    AncientBuilderFacility.FACILITY_X + 1, facilityY + 1,
                    AncientBuilderFacility.FACILITY_Z));
            assertEquals("dimension aether", AncientBuilderFacility.defaultCommandAt(
                    AncientBuilderFacility.FACILITY_X + 3, facilityY + 1,
                    AncientBuilderFacility.FACILITY_Z));
        }
    }

    @Test
    public void facilityBandsAreContainedByTheirLoadedSections() {
        for (int facilityY : AncientBuilderFacility.FACILITY_YS) {
            int firstSection = facilityY >> 4;
            int lastSection = (facilityY + 8) >> 4;
            for (int sectionY = firstSection; sectionY <= lastSection; sectionY++) {
                assertTrue(AncientBuilderFacility.intersectsSection(sectionY, facilityY));
            }
        }
    }
}
