package com.voxel.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shape library is the skeleton under every MCSM/Legends/Dungeons site.
 * Each shape must stamp a non-empty, deterministic, idempotent footprint that
 * reads as the place it stands for — and every enclosed building it draws must
 * carry a real, walkable door.
 */
public class SiteShapesTest {

    /** Number of shapes exercised by {@link SiteShapesTest#stamp}. */
    private static final int SHAPE_COUNT = 14;

    @Test
    public void plazaIsPavedAndIdempotent() {
        SiteShapes.RecordingSink a = new SiteShapes.RecordingSink();
        SiteShapes.RecordingSink b = new SiteShapes.RecordingSink();
        SiteShapes.plaza(a, 0, 0, 68, 10, 131, 135);
        SiteShapes.plaza(b, 0, 0, 68, 10, 131, 135);
        assertFalse(a.voxels.isEmpty());
        assertEquals("re-stamping is identical", a.voxels, b.voxels);
        assertEquals(131, (int) a.voxels.get("0,68,0"));
        assertEquals(135, (int) a.voxels.get("0,67,0"));
    }

    @Test
    public void keepRaisesWallsAndCornerTowers() {
        SiteShapes.RecordingSink s = new SiteShapes.RecordingSink();
        SiteShapes.keep(s, 0, 0, 68, 10, 8, 131, 72, 17);
        assertTrue("walls", s.count(131) > 200);
        assertTrue("lights", s.count(17) > 0);
        // A corner tower tops out above the wall height.
        assertTrue("tower reaches high", maxKeyY(s) >= 68 + 8 + 3);
    }

    @Test
    public void spireRisesToItsHeight() {
        SiteShapes.RecordingSink s = new SiteShapes.RecordingSink();
        SiteShapes.spire(s, 0, 0, 68, 24, 16, 904, 17);
        assertTrue("body stone", s.count(16) > 0);
        assertTrue("core", s.count(904) > 0);
        assertEquals(68 + 24, maxKeyY(s));
    }

    @Test
    public void everyBuildingShapeHasAWorkingDoor() {
        for (int which : new int[] {1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13}) {
            SiteShapes.RecordingSink s = new SiteShapes.RecordingSink();
            stamp(which, s);
            assertTrue("shape " + which + " places a real door", doorCount(s) > 0);
        }
    }

    @Test
    public void everyDoorSitsOnSomethingSolid() {
        // A door leaf must not float: the cell underneath it has to be filled.
        for (int which : new int[] {1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13}) {
            SiteShapes.RecordingSink s = new SiteShapes.RecordingSink();
            stamp(which, s);
            for (java.util.Map.Entry<String, Integer> e : s.voxels.entrySet()) {
                if (!isDoor(e.getValue())) continue;
                String[] parts = e.getKey().split(",");
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                Integer below = s.voxels.get(x + "," + (y - 1) + "," + z);
                assertTrue("shape " + which + ": door leaf at " + e.getKey() + " floats",
                        below != null && below != 0);
            }
        }
    }

    @Test
    public void everyShapeStampsSomethingAndIsIdempotent() {
        for (int which = 0; which < SHAPE_COUNT; which++) {
            SiteShapes.RecordingSink a = new SiteShapes.RecordingSink();
            SiteShapes.RecordingSink b = new SiteShapes.RecordingSink();
            stamp(which, a);
            stamp(which, b);
            assertFalse("shape " + which + " writes voxels", a.voxels.isEmpty());
            assertEquals("shape " + which + " is idempotent", a.voxels, b.voxels);
        }
    }

    private static boolean isDoor(int id) {
        if (id < DecorBlocks.FIRST_ID
                || id >= DecorBlocks.FIRST_ID + DecorBlocks.NAMES.length) return false;
        return DecorBlocks.NAMES[id - DecorBlocks.FIRST_ID].contains("door");
    }

    /** Counts door voxels from the shared decor table. */
    private static int doorCount(SiteShapes.RecordingSink s) {
        int n = 0;
        for (int id : s.voxels.values()) if (isDoor(id)) n++;
        return n;
    }

    private static void stamp(int which, SiteShapes.Sink s) {
        switch (which) {
            case 0: SiteShapes.plaza(s, 0, 0, 68, 8, 131, 135); break;
            case 1: SiteShapes.town(s, 0, 0, 68, 9, 131, 72, 5, 17); break;
            case 2: SiteShapes.arena(s, 0, 0, 68, 10, 905, 91, 17, 18); break;
            case 3: SiteShapes.temple(s, 0, 0, 68, 7, 131, 140, 457); break;
            case 4: SiteShapes.keep(s, 0, 0, 68, 8, 7, 131, 72, 17); break;
            case 5: SiteShapes.spire(s, 0, 0, 68, 16, 16, 904, 17); break;
            case 6: SiteShapes.canyon(s, 0, 0, 68, 8, 135, 72, 91); break;
            case 7: SiteShapes.swampHut(s, 0, 0, 68, 9, 15, 5, 72, 132); break;
            case 8: SiteShapes.forge(s, 0, 0, 68, 8, 905, 21, 137, 17); break;
            case 9: SiteShapes.outpost(s, 0, 0, 68, 9, 1, 5, 138, 457); break;
            case 10: SiteShapes.riftHall(s, 0, 0, 68, 9, 900, 933, 934, 17); break;
            case 11: SiteShapes.pumpkinField(s, 0, 0, 68, 13, 1, 13, 42, 131); break;
            case 12: SiteShapes.forestCamp(s, 0, 0, 68, 14, 1, 5, 4, 17); break;
            default: SiteShapes.skyIsland(s, 0, 0, 68, 10, 1, 13, 18, 17); break;
        }
    }

    private static int maxKeyY(SiteShapes.RecordingSink s) {
        int max = Integer.MIN_VALUE;
        for (String key : s.voxels.keySet()) {
            int y = Integer.parseInt(key.split(",")[1]);
            if (y > max) max = y;
        }
        return max;
    }
}
