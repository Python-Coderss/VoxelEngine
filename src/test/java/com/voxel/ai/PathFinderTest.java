package com.voxel.ai;

import org.joml.Vector3i;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PathFinderTest {

    private static final int GROUND_Y = 64;

    /** Infinite flat plane: solid at y=64, air everywhere else. */
    private static VoxelView flat() {
        return (x, y, z) -> y == GROUND_Y ? 1 : 0;
    }

    @Test
    public void findsStraightPathOnFlatGround() {
        List<Vector3i> path = PathFinder.findPath(flat(), 0, GROUND_Y + 1, 0, 10, GROUND_Y + 1, 10);
        assertNotNull(path);
        assertFalse(path.isEmpty());

        Vector3i first = path.get(0);
        assertEquals(0, first.x);
        assertEquals(GROUND_Y + 1, first.y);
        assertEquals(0, first.z);

        Vector3i last = path.get(path.size() - 1);
        assertTrue("end within goal tolerance",
                Math.abs(last.x - 10) <= 1 && Math.abs(last.y - (GROUND_Y + 1)) <= 1
                        && Math.abs(last.z - 10) <= 1);

        for (Vector3i p : path) {
            assertEquals("all nodes stand on walkable cells", GROUND_Y + 1, p.y);
        }
    }

    @Test
    public void stepsOverKneeHighWall() {
        VoxelView walled = (x, y, z) -> {
            if (y == GROUND_Y) return 1;
            if (x == 5 && y == GROUND_Y + 1 && Math.abs(z) <= 6) return 1;
            return 0;
        };
        List<Vector3i> path = PathFinder.findPath(walled, 0, GROUND_Y + 1, 0, 10, GROUND_Y + 1, 0);
        assertFalse(path.isEmpty());
        for (Vector3i p : path) {
            assertFalse("path must not occupy the wall itself",
                    p.x == 5 && p.y == GROUND_Y + 1 && Math.abs(p.z) <= 6);
        }
    }

    @Test
    public void detoursAroundTallWall() {
        VoxelView walled = (x, y, z) -> {
            if (y == GROUND_Y) return 1;
            if (x == 5 && y >= GROUND_Y + 1 && y <= GROUND_Y + 3 && Math.abs(z) <= 6) return 1;
            return 0;
        };
        List<Vector3i> path = PathFinder.findPath(walled, 0, GROUND_Y + 1, 0, 10, GROUND_Y + 1, 2);
        assertFalse("should route around a 3-high wall", path.isEmpty());
        for (Vector3i p : path) {
            assertFalse("no node inside the wall",
                    p.x == 5 && p.y >= GROUND_Y + 1 && p.y <= GROUND_Y + 3 && Math.abs(p.z) <= 6);
        }
    }

    @Test
    public void sealedGoalReturnsEmpty() {
        VoxelView sealedBox = (x, y, z) -> {
            if (y == GROUND_Y) return 1;
            if (x >= 48 && x <= 52 && z >= 48 && z <= 52 && y >= GROUND_Y + 1) {
                return (x == 50 && z == 50 && y == GROUND_Y + 1) ? 0 : 1;
            }
            return 0;
        };
        List<Vector3i> path = PathFinder.findPath(sealedBox, 0, GROUND_Y + 1, 0, 50, GROUND_Y + 1, 50);
        assertTrue(path.isEmpty());
    }

    @Test
    public void climbsStaircase() {
        VoxelView stairs = (x, y, z) -> y <= GROUND_Y + clamp(x) ? 1 : 0;
        List<Vector3i> path = PathFinder.findPath(stairs, 0, GROUND_Y + 1, 0, 8, GROUND_Y + 9, 0);
        assertFalse("should climb one-block steps", path.isEmpty());
        int maxY = 0;
        for (Vector3i p : path) maxY = Math.max(maxY, p.y);
        assertTrue("arrival within legacy ±1 goal tolerance of the top step",
                maxY >= GROUND_Y + 8);
    }

    @Test
    public void findsNearOptimalDiagonalCostPathOnFlatGround() {
        // On flat ground the cheapest route uses diagonals wherever both axes
        // remain (8 diagonals at 1.414 + 12 straight at 1.0 = 23.312 for a
        // (0,0) -> (20,8) run). The relaxation must not let a worse
        // predecessor overwrite the cameFrom chain, which used to inflate
        // paths on exactly this kind of tie-dense terrain.
        List<Vector3i> path = PathFinder.findPath(flat(), 0, GROUND_Y + 1, 0,
                20, GROUND_Y + 1, 10);
        assertFalse(path.isEmpty());
        double cost = 0.0;
        for (int i = 1; i < path.size(); i++) {
            Vector3i a = path.get(i - 1);
            Vector3i b = path.get(i);
            double step = (a.x != b.x && a.z != b.z) ? 1.414 : 1.0;
            cost += step;
        }
        double optimal = 8 * 1.414 + 12 * 1.0;
        assertTrue("found cost " + cost + " vs optimal " + optimal,
                cost <= optimal + 0.5);
    }

    @Test
    public void respectsNodeBudget() {
        List<Vector3i> path = PathFinder.findPath(flat(), Walkability.HUMANOID,
                0, GROUND_Y + 1, 0, 200, GROUND_Y + 1, 200, 10);
        assertTrue(path.isEmpty());
    }

    @Test
    public void humanoidWalkabilityRequiresGroundAndClearance() {
        VoxelView view = flat();
        assertTrue(Walkability.HUMANOID.isWalkable(view, 3, GROUND_Y + 1, 3));
        assertFalse("floating cell has no floor",
                Walkability.HUMANOID.isWalkable(view, 3, GROUND_Y + 5, 3));
        assertFalse("inside solid ground",
                Walkability.HUMANOID.isWalkable(view, 3, GROUND_Y, 3));
    }

    private static int clamp(int x) {
        return Math.max(0, Math.min(x, 8));
    }
}
