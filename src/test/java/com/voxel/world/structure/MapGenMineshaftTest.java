package com.voxel.world.structure;

import com.voxel.World;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The overhauled mineshaft must actually furnish itself — rails along the
 * corridors, torches on the support frames, ore veins in the walls and a
 * treasure room with a chest and a spawner. The old generator declared these
 * constants as {@code 0} and never placed them.
 */
public class MapGenMineshaftTest {

    private static final int AIR = 0, OAK_LOG = 5, PLANKS = 72, TORCH = 211,
            RAIL_NS = 391, RAIL_EW = 392, CHEST = 118, SPAWNER = 258;
    private static final int[] ORES = {61, 81, 82, 83};

    /** Records every write so the carved layout can be inspected. */
    private static final class RecordingWorld extends World {
        final Map<String, Integer> voxels = new HashMap<>();

        RecordingWorld() { super(8); }

        @Override
        public void setVoxel(int x, int y, int z, int type) {
            voxels.put(x + "," + y + "," + z, type);
        }

        @Override
        public int getVoxel(int x, int y, int z) {
            Integer v = voxels.get(x + "," + y + "," + z);
            return v == null ? AIR : v;
        }

        boolean has(int id) {
            return voxels.containsValue(id);
        }

        int count(int id) {
            int n = 0;
            for (int v : voxels.values()) if (v == id) n++;
            return n;
        }
    }

    private static RecordingWorld stamp(long seed) {
        RecordingWorld w = new RecordingWorld();
        new MapGenMineshaft().generate(w, 4, 4, new Random(seed), true);
        return w;
    }

    @Test
    public void corridorsAreFurnishedWithRailsFramesAndTorches() {
        RecordingWorld w = stamp(1L);
        assertTrue("a rail line", w.has(RAIL_NS) || w.has(RAIL_EW));
        assertTrue("support frames", w.count(OAK_LOG) > 0);
        assertTrue("torches", w.has(TORCH));
    }

    @Test
    public void treasureRoomHasAChestAndASpawner() {
        RecordingWorld w = stamp(1L);
        assertTrue("a chest", w.has(CHEST));
        assertTrue("a spawner", w.has(SPAWNER));
    }

    @Test
    public void tunnelIsCarvedAndLinedWithFloor() {
        RecordingWorld w = stamp(1L);
        assertTrue("plank floor", w.count(PLANKS) > 0);
        assertTrue("the tunnel is largely carved air", w.count(AIR) > 200);
    }

    @Test
    public void corridorsAreOrientedToTheirDirection() {
        // Over many seeds both orientations must appear, since the corridor
        // runs N-S or E-W depending on the rolled direction.
        boolean ns = false, ew = false;
        for (long seed = 0; seed < 40 && !(ns && ew); seed++) {
            RecordingWorld w = stamp(seed);
            ns |= w.has(RAIL_NS);
            ew |= w.has(RAIL_EW);
        }
        assertTrue("north-south rails", ns);
        assertTrue("east-west rails", ew);
    }

    @Test
    public void oreVeinsAppearInTheWalls() {
        boolean anyOre = false;
        for (long seed = 0; seed < 30 && !anyOre; seed++) {
            RecordingWorld w = stamp(seed);
            for (int ore : ORES) if (w.has(ore)) anyOre = true;
        }
        assertTrue("ore veins stud the walls", anyOre);
    }

    @Test
    public void generationIsDeterministicForASeed() {
        assertEquals("same seed, same voxels",
                stamp(7L).voxels, stamp(7L).voxels);
    }

    @Test
    public void chanceRollSkipsGenerationWhenNotForced() {
        // The unforced overload must respect the 1-in-10 chance gate; over a
        // handful of seeds at least one chunk declines to generate.
        boolean skipped = false;
        for (long seed = 0; seed < 10 && !skipped; seed++) {
            RecordingWorld w = new RecordingWorld();
            new MapGenMineshaft().generate(w, 0, 0, new Random(seed));
            if (w.voxels.isEmpty()) skipped = true;
        }
        assertTrue(skipped);
    }
}
