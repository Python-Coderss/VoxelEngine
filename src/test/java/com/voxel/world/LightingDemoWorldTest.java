package com.voxel.world;

import com.voxel.game.ChestManager;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Lighting Demo only earns its name if the halls really are lit differently:
 * one open to the sky, one sealed under a roof, and a cave below both with no
 * sky light at all. These assertions pin that geometry, the props that make the
 * frames comparable (a chest in every scene), and the routes between them.
 */
public class LightingDemoWorldTest {

    private static final int CHEST = 118;
    private static final int GRASS = 1;
    private static final int STONE = 2;
    private static final int GLASS = 3;
    private static final int DIRT = 13;
    private static final int LAVA = 21;
    private static final int COBBLESTONE = 71;

    /** Authored cells, and which cells the author actually wrote (0 = air). */
    private final Map<Long, Integer> voxels = new HashMap<>();
    private final Set<Long> written = new HashSet<>();

    private void build() {
        LightingDemoWorldAuthor.buildAll(new LightingDemoWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                long k = key(x, y, z);
                voxels.put(k, type);
                written.add(k);
            }
        }, new ChestManager());
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 42) | ((long) (y & 0xFFFFF) << 21) | (z & 0xFFFFF);
    }

    /**
     * Authored block, or the flat terrain the generator supplies for any cell the
     * author never wrote (grass at the surface, dirt below it, stone down to
     * bedrock). Without this fallback a miss would look like air and every
     * "is it solid?" assertion would be meaningless.
     */
    private int at(int x, int y, int z) {
        long k = key(x, y, z);
        if (written.contains(k)) return voxels.get(k);
        if (y > LightingDemoWorldAuthor.G) return 0;
        if (y == LightingDemoWorldAuthor.G) return GRASS;
        if (y >= LightingDemoWorldAuthor.G - 3) return DIRT;
        return STONE;
    }

    @Test
    public void darkHallIsSealedAndLightHallIsOpen() {
        build();

        // Roof height is G + 7: the light hall's west half must be open air above
        // it (the sun reaches the floor), its east half covered by glass, and the
        // dark hall covered by solid stone across its whole footprint.
        int roof = LightingDemoWorldAuthor.G + 7;
        assertEquals("light hall west half is open to the sky", 0, at(-28, roof, -5));
        assertEquals("light hall east half carries a glass skylight", GLASS, at(-12, roof, 0));
        assertTrue("dark hall roof is solid", at(20, roof, 0) != 0);
        assertTrue("dark hall roof is solid at the edge", at(11, roof, -9) != 0);
        assertEquals("no shadow gap along the dark hall's north wall", STONE, at(16, roof, -9));

        // Interior air: both halls are walkable at standing height.
        assertEquals("light hall interior is open", 0, at(-20, LightingDemoWorldAuthor.G + 1, 0));
        assertEquals("dark hall interior is open", 0, at(18, LightingDemoWorldAuthor.G + 1, 0));
    }

    @Test
    public void bothHallsCarryAChest() {
        build();
        int y = LightingDemoWorldAuthor.G + 1;
        assertEquals("light hall chest", CHEST, at(-16, y, 0));
        assertEquals("dark hall chest", CHEST, at(16, y, 0));
    }

    @Test
    public void caveIsCarvedUnderSolidRockAndReachedByStairs() {
        build();
        int floor = LightingDemoWorldAuthor.CAVE_FLOOR;

        // Interior is open for the full height of the chamber...
        for (int y = floor + 2; y <= floor + 6; y++) {
            assertEquals("cave interior at y=" + y, 0, at(0, y, 36));
        }
        // ...and the cell above every authored ceiling block is real rock, so no
        // sky light can reach the chamber.
        for (int x = LightingDemoWorldAuthor.CAVE_X0 + 3; x <= LightingDemoWorldAuthor.CAVE_X1 - 3; x += 4) {
            for (int z = LightingDemoWorldAuthor.CAVE_Z0 + 3; z <= LightingDemoWorldAuthor.CAVE_Z1 - 3; z += 4) {
                int top = -1;
                for (int y = floor + 1; y <= LightingDemoWorldAuthor.CAVE_CEIL + 2; y++) {
                    if (at(x, y, z) != 0) top = y;
                }
                assertTrue("cave has a roof over " + x + "," + z, top >= floor + 2);
            }
        }

        // The stairwell is continuous from the surface down to the chamber floor.
        for (int i = 0; i <= 18; i++) {
            int stepTop = LightingDemoWorldAuthor.G - i;
            int z = 14 + i;
            assertEquals("step surface at z=" + z, COBBLESTONE, at(2, stepTop, z));
            assertEquals("headroom over the stair at z=" + z, 0, at(2, stepTop + 1, z));
        }
    }

    @Test
    public void caveLightSourcesAndChestAreInPlace() {
        build();
        int floor = LightingDemoWorldAuthor.CAVE_FLOOR;

        // The lava basin is sunk below the chamber floor.
        assertEquals("sunken lava", LAVA,
                at(LightingDemoWorldAuthor.CAVE_LAVA_X, floor - 2, LightingDemoWorldAuthor.CAVE_LAVA_Z));
        // A chest sits on the floor near the drip pool, at chest height.
        assertEquals("cave chest", CHEST, at(LightingDemoWorldAuthor.CAVE_X0 + 11, floor + 1,
                LightingDemoWorldAuthor.CAVE_Z0 + 6));
    }

    @Test
    public void spawnColumnIsClear() {
        build();
        int g = LightingDemoWorldAuthor.G;
        assertTrue("corridor floor under the spawn", at(0, g, 0) != 0);
        assertEquals("headroom at spawn", 0, at(0, g + 1, 0));
        assertEquals("headroom at spawn (upper)", 0, at(0, g + 2, 0));
    }
}
