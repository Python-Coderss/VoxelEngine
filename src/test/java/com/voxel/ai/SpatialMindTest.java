package com.voxel.ai;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Spatial reasoning must be exactly what the brains rely on: safe standing,
 * cliff refusal, dead-end avoidance, cover, flanking and crowd separation.
 * A tiny heightmap-backed grid keeps every case hand-readable.
 */
public class SpatialMindTest {

    private static final int GROUND_Y = 64;
    private static final int FEET = GROUND_Y + 1;

    /** Base terrain solid at/below GROUND_Y; overrides win. */
    private static final class Grid implements VoxelView {
        private final Map<Long, Integer> overrides = new HashMap<Long, Integer>();

        Grid put(int x, int y, int z, int id) {
            overrides.put(key(x, y, z), id);
            return this;
        }

        @Override
        public int getVoxel(int x, int y, int z) {
            Integer override = overrides.get(key(x, y, z));
            if (override != null) return override;
            return y <= GROUND_Y ? 1 : 0;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x1FFFFF) << 42)
                    | ((long) (y & 0x1FFFFF) << 21)
                    | (z & 0x1FFFFF);
        }
    }

    private static void wall(Grid grid, int x, int z) {
        grid.put(x, FEET, z, 1).put(x, FEET + 1, z, 1);
    }

    // ── Standing and cliffs ──────────────────────────────────────────────

    @Test
    public void flatGroundIsSafeToStandOn() {
        Grid grid = new Grid();
        assertTrue(SpatialMind.isSafeStand(grid, 0, FEET, 0));
        assertFalse("air below the feet is not ground",
                SpatialMind.isSafeStand(grid, 0, FEET + 6, 0));
        assertFalse("null world is never safe",
                SpatialMind.isSafeStand(null, 0, FEET, 0));
    }

    @Test
    public void headroomAndOccupiedFeetAreUnsafe() {
        Grid grid = new Grid();
        grid.put(4, FEET + 1, 4, 1);
        assertFalse("one block of clearance is not enough",
                SpatialMind.isSafeStand(grid, 4, FEET, 4));

        Grid occupied = new Grid();
        occupied.put(6, FEET, 6, 1);
        assertFalse("standing inside a block is unsafe",
                SpatialMind.isSafeStand(occupied, 6, FEET, 6));
    }

    @Test
    public void missingGroundIsUnsafe() {
        Grid grid = new Grid();
        grid.put(8, GROUND_Y, 8, 0);
        assertFalse(SpatialMind.isSafeStand(grid, 8, FEET, 8));
    }

    @Test
    public void cliffsAreRefusedAtTheThreeDropLimit() {
        Grid flat = new Grid();
        assertFalse("flat ground is not a cliff", SpatialMind.isCliff(flat, 0, FEET, 0));

        Grid twoDrop = new Grid();
        twoDrop.put(2, GROUND_Y, 2, 0).put(2, GROUND_Y - 1, 2, 0);
        assertFalse("a two-block drop is still a step",
                SpatialMind.isCliff(twoDrop, 2, FEET, 2));

        Grid threeDrop = new Grid();
        threeDrop.put(4, GROUND_Y, 4, 0)
                .put(4, GROUND_Y - 1, 4, 0)
                .put(4, GROUND_Y - 2, 4, 0);
        assertTrue("a three-block drop is a cliff",
                SpatialMind.isCliff(threeDrop, 4, FEET, 4));
    }

    // ── Openness ─────────────────────────────────────────────────────────

    @Test
    public void openFieldScoresHigherThanACorridor() {
        Grid flat = new Grid();
        assertEquals(8, SpatialMind.openness(flat, 0, FEET, 0));

        Grid corridor = new Grid();
        for (int z = -4; z <= 4; z++) {
            wall(corridor, -1, z);
            wall(corridor, 1, z);
        }
        int corridorOpenness = SpatialMind.openness(corridor, 0, FEET, 0);
        assertEquals("a one-wide corridor has only its two lengthwise exits",
                2, corridorOpenness);
        assertTrue(corridorOpenness < SpatialMind.openness(flat, 0, FEET, 0));
    }

    // ── Escape ───────────────────────────────────────────────────────────

    @Test
    public void escapeMovesAwayFromTheThreat() {
        Grid grid = new Grid();
        Vector3f self = new Vector3f(0.5f, FEET, 0.5f);
        Vector3f threat = new Vector3f(6.5f, FEET, 0.5f);
        Vector3i target = SpatialMind.escapeTarget(grid, self, threat, 4);
        assertNotNull(target);
        assertTrue("must run away from the threat, not toward it: " + target,
                target.x < 0);
        assertTrue(SpatialMind.isSafeStand(grid, target.x, target.y, target.z));
    }

    @Test
    public void escapePrefersOpenGroundOverADeadEnd() {
        Grid grid = new Grid();
        // Corridor running east: the "away" direction is a dead end.
        for (int x = 1; x <= 6; x++) {
            wall(grid, x, -1);
            wall(grid, x, 1);
        }
        Vector3f self = new Vector3f(0.5f, FEET, 0.5f);
        Vector3f threat = new Vector3f(-8.5f, FEET, 0.5f);
        Vector3i target = SpatialMind.escapeTarget(grid, self, threat, 4);
        assertNotNull(target);
        assertFalse("fleeing into a dead-end corridor is a mistake: " + target,
                target.z == 0 && target.x >= 1);
        assertTrue("the escape cell should be open ground: "
                        + SpatialMind.openness(grid, target.x, target.y, target.z),
                SpatialMind.openness(grid, target.x, target.y, target.z) >= 4);
    }

    @Test
    public void escapeReturnsNullWhenNothingIsSafe() {
        // A world that is solid everywhere: no standing cell exists.
        VoxelView solid = (x, y, z) -> 1;
        assertNull(SpatialMind.escapeTarget(solid,
                new Vector3f(0.5f, 10f, 0.5f), new Vector3f(3f, 10f, 0f), 2));
        assertNull(SpatialMind.escapeTarget(null, new Vector3f(), new Vector3f(), 2));
    }

    // ── Cover and flank ──────────────────────────────────────────────────

    @Test
    public void coverCellBreaksLineOfSight() {
        Grid grid = new Grid();
        for (int x = -4; x <= 4; x++) {
            wall(grid, x, 3);
        }
        Vector3f self = new Vector3f(0.5f, FEET, 0.5f);
        Vector3f threat = new Vector3f(0.5f, FEET, 6.5f);
        assertTrue("the wall must actually block the threat",
                !Raycaster.lineOfSight(grid, self.x, self.y + 1.2f, self.z,
                        threat.x, threat.y + 1.2f, threat.z));

        Vector3i cover = SpatialMind.coverCell(grid, self, threat, 3);
        assertNotNull(cover);
        assertTrue(SpatialMind.isSafeStand(grid, cover.x, cover.y, cover.z));
        assertFalse("cover must hide from the threat",
                Raycaster.lineOfSight(grid, cover.x + 0.5f, cover.y + 1.2f, cover.z + 0.5f,
                        threat.x, threat.y + 1.2f, threat.z));
    }

    @Test
    public void noCoverOnAnEmptyField() {
        Grid grid = new Grid();
        assertNull(SpatialMind.coverCell(grid,
                new Vector3f(0.5f, FEET, 0.5f), new Vector3f(0.5f, FEET, 5.5f), 3));
    }

    @Test
    public void flankingStepsToTheSideOfTheTarget() {
        Grid grid = new Grid();
        Vector3f self = new Vector3f(0.5f, FEET, 0.5f);
        Vector3f target = new Vector3f(5.5f, FEET, 0.5f);
        Vector3i flank = SpatialMind.flankCell(grid, self, target, 1, 3);
        assertNotNull(flank);
        assertEquals("flank keeps the target's distance along x", 5, flank.x);
        assertTrue("flank is perpendicular to the approach: " + flank, flank.z > 0);
        assertTrue(SpatialMind.isSafeStand(grid, flank.x, flank.y, flank.z));

        Vector3i other = SpatialMind.flankCell(grid, self, target, -1, 3);
        assertNotNull(other);
        assertTrue("the other side is the mirror", other.z < 0);
    }

    @Test
    public void flankHandlesDegenerateInput() {
        Grid grid = new Grid();
        Vector3f same = new Vector3f(1.5f, FEET, 1.5f);
        assertNull("no direction when standing on the target",
                SpatialMind.flankCell(grid, same, same, 0, 3));
        assertNull(SpatialMind.flankCell(null, new Vector3f(), new Vector3f(), 1, 3));
    }

    // ── Crowds ───────────────────────────────────────────────────────────

    @Test
    public void separationPushesAwayFromCrowdedNeighbours() {
        Vector3f self = new Vector3f(0f, 0f, 0f);
        List<Vector3f> others = new ArrayList<Vector3f>();
        others.add(new Vector3f(0.3f, 0f, 0f));

        Vector3f push = SpatialMind.separation(self, others, 2f);
        assertTrue("pushed away from the neighbour: " + push, push.x < 0f);
        assertTrue("push is bounded", push.length() <= 1.0001f);
    }

    @Test
    public void separationIsZeroWhenAloneOrFarApart() {
        assertEquals(new Vector3f(0f, 0f, 0f),
                SpatialMind.separation(new Vector3f(), new ArrayList<Vector3f>(), 2f));
        Vector3f push = SpatialMind.separation(new Vector3f(),
                Arrays.asList(new Vector3f(10f, 0f, 0f)), 2f);
        assertEquals(0f, push.length(), 1e-6);
        assertEquals(0f, SpatialMind.separation(null, null, 2f).length(), 1e-6);
    }

    @Test
    public void separationClampsInADenseCrowd() {
        List<Vector3f> crowd = new ArrayList<Vector3f>();
        for (int i = 0; i < 8; i++) {
            crowd.add(new Vector3f(0.1f, 0f, 0.05f * i));
        }
        Vector3f push = SpatialMind.separation(new Vector3f(), crowd, 2f);
        assertTrue("a dense crowd must not fling the mob: " + push.length(),
                push.length() <= 1.0001f);
        assertTrue(push.length() > 0f);
    }

    @Test
    public void sidesAreDeterministicOpposites() {
        for (int seed = 0; seed < 10; seed++) {
            assertEquals(SpatialMind.sideFor(seed), SpatialMind.sideFor(seed));
            assertTrue(SpatialMind.sideFor(seed) == 1 || SpatialMind.sideFor(seed) == -1);
        }
        assertEquals(-SpatialMind.sideFor(0), SpatialMind.sideFor(1));
    }
}
