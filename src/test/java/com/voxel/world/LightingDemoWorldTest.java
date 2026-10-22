package com.voxel.world;

import com.voxel.game.ChestManager;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Lighting Demo only earns its name if the two halls really are lit
 * differently: one open to the sky, one sealed under a roof. These assertions
 * pin that geometry, the chest in each hall (the model under test sits in both
 * lighting conditions), and a clear spawn column.
 */
public class LightingDemoWorldTest {

    private static final int CHEST = 118;
    private static final int AIR = 0;

    private final Map<Long, Integer> voxels = new HashMap<>();

    private void build() {
        LightingDemoWorldAuthor.buildAll(new LightingDemoWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                voxels.put(key(x, y, z), type);
            }
        }, new ChestManager());
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 42) | ((long) (y & 0xFFFFF) << 21) | (z & 0xFFFFF);
    }

    private int at(int x, int y, int z) {
        Integer v = voxels.get(key(x, y, z));
        return v == null ? AIR : v;
    }

    @Test
    public void darkHallIsSealedAndLightHallIsOpen() {
        LightingDemoWorldAuthor.buildAll(new LightingDemoWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                voxels.put(key(x, y, z), type);
            }
        }, new ChestManager());

        // Roof height is G + 7; the light hall's west half must be open air above
        // it (the sun reaches the floor), its east half covered by glass, and the
        // dark hall covered by solid stone across its whole footprint.
        int roof = LightingDemoWorldAuthor.G + 7;
        assertEquals("light hall west half is open to the sky", AIR, at(-28, roof, -5));
        assertTrue("light hall east half carries a glass skylight", at(-12, roof, 0) == 3);
        assertTrue("dark hall roof is solid", at(20, roof, 0) != AIR);
        assertTrue("dark hall roof is solid at the edge", at(11, roof, -9) != AIR);
        assertEquals("no shadow gap along the dark hall's north wall", 2, at(16, roof, -9));

        // Interior air: both halls are walkable at standing height.
        assertEquals("light hall interior is open", AIR, at(-20, LightingDemoWorldAuthor.G + 1, 0));
        assertEquals("dark hall interior is open", AIR, at(18, LightingDemoWorldAuthor.G + 1, 0));
    }

    @Test
    public void bothHallsCarryAChest() {
        build();
        int y = LightingDemoWorldAuthor.G + 1;
        assertEquals("light hall chest", CHEST, at(-16, y, 0));
        assertEquals("dark hall chest", CHEST, at(16, y, 0));
    }

    @Test
    public void spawnColumnIsClear() {
        build();
        int g = LightingDemoWorldAuthor.G;
        assertTrue("corridor floor under the spawn", at(0, g, 0) != AIR);
        assertEquals("headroom at spawn", AIR, at(0, g + 1, 0));
        assertEquals("headroom at spawn (upper)", AIR, at(0, g + 2, 0));
    }
}
