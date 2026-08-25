package com.voxel.ai;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RaycasterTest {

    private static final int GROUND_Y = 64;

    /** Flat plane with optional wall columns sitting under the test rays. */
    private static VoxelView worldWithWalls(int wallBlockX, int wallBlockZ) {
        return (x, y, z) -> {
            if (y == GROUND_Y) return 1;
            if (x == wallBlockX && z == wallBlockZ && y == GROUND_Y + 1) return 1;
            return 0;
        };
    }

    @Test
    public void clearLineIsVisible() {
        assertTrue(Raycaster.lineOfSight(worldWithWalls(99, 99),
                0, GROUND_Y + 1.5f, 0, 10, GROUND_Y + 1.5f, 0));
    }

    @Test
    public void wallBetweenBlocksSight() {
        // Wall cell sits directly on the (0,0)->(10,0) segment at x = 5.
        assertFalse(Raycaster.lineOfSight(worldWithWalls(5, 0),
                0, GROUND_Y + 1.5f, 0, 10, GROUND_Y + 1.5f, 0));
    }

    @Test
    public void visibleWhenWallIsOffToTheSide() {
        // Same ray, but the wall cell is at z = 5, one cell off the segment.
        assertTrue(Raycaster.lineOfSight(worldWithWalls(5, 5),
                0, GROUND_Y + 1.5f, 0, 10, GROUND_Y + 1.5f, 0));
    }

    @Test
    public void endpointsNeverBlock() {
        // Start and end inside the same solid column: endpoints are excluded,
        // so this zero-length check must succeed even though it is blocked.
        assertTrue(Raycaster.lineOfSight(worldWithWalls(5, 5),
                5, GROUND_Y + 1.5f, 5, 5, GROUND_Y + 1.5f, 5));
    }

    @Test
    public void zeroLengthRayIsVisible() {
        assertTrue(Raycaster.lineOfSight(worldWithWalls(99, 99),
                3, GROUND_Y + 1.5f, 3, 3, GROUND_Y + 1.5f, 3));
    }
}