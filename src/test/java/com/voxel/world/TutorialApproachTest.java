package com.voxel.world;

import com.voxel.game.ChestManager;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Pins the tutorial's spawn approach: the processional avenue, its lanterns,
 * the forecourt and the tree rows added by {@code buildApproach()}. Reads the
 * author's own output so a rebuild of the template cannot silently drop it.
 */
public class TutorialApproachTest {

    private static final int G = TutorialWorldGenerator.GROUND;

    @Test
    public void avenueLanternsForecourtAndTreesExist() {
        final Map<Long, Integer> probes = new HashMap<>();
        TutorialWorldAuthor.Sink sink = new TutorialWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                long key = key(x, y, z);
                if (PROBES.contains(key)) probes.put(key, type);
            }
        };
        TutorialWorldAuthor.buildAll(sink, new ChestManager());

        assertEquals("avenue centre line", 72, probe(probes, 0, G, 20));
        assertEquals("avenue shoulder", 131, probe(probes, -2, G, 20));
        assertEquals("avenue lantern", 17, probe(probes, 4, G + 2, 20));
        assertEquals("forecourt paving", 131, probe(probes, 0, G, 12));
        assertEquals("avenue tree trunk", 5, probe(probes, -5, G + 1, 18));
        // buildApproach must not build over the spawn cell itself (the original
        // world already had a loose decoration there before this pass).
        org.junit.Assert.assertNotEquals("spawn column stays free of the avenue",
                5, probe(probes, 0, G + 1, 6));
    }

    private static final java.util.Set<Long> PROBES = new java.util.HashSet<>();

    static {
        PROBES.add(key(0, G, 20));
        PROBES.add(key(-2, G, 20));
        PROBES.add(key(4, G + 2, 20));
        PROBES.add(key(0, G, 12));
        PROBES.add(key(-5, G + 1, 18));
        PROBES.add(key(0, G + 1, 6));
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 42) ^ ((long) (y + 64) << 21) ^ (z + 512);
    }

    private static int probe(Map<Long, Integer> probes, int x, int y, int z) {
        Integer value = probes.get(key(x, y, z));
        return value == null ? 0 : value;
    }
}
