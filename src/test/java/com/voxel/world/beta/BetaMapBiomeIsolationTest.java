package com.voxel.world.beta;

import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import com.voxel.utils.BlockDataManager;
import com.voxel.world.BetaWorldGenerator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

/**
 * The map preview queries biomes on the logic thread while chunk generation
 * runs on the gen thread. The beta GenLayer chain + IntCache are single-threaded
 * (vanilla semantics), so {@link BetaWorldGenerator} gives the map a dedicated,
 * identically-seeded chain via getMapBiomeProvider(). This test verifies that
 * dedicated chain agrees with the shared (gen-thread) provider everywhere —
 * same seed, same biome layout — and that it is a different chain instance
 * (so the two threads never share GenLayer/IntCache state).
 */
public class BetaMapBiomeIsolationTest {

    private static final long SEED = 8078811528755789733L; // "New World" boot-log seed

    @Test
    public void mapProviderIsDedicatedButIdentical() {
        BetaWorldGenerator gen = new BetaWorldGenerator(SEED, new BlockDataManager());
        BiomeProvider shared = gen.getBiomeProvider();
        BiomeProvider map = gen.getMapBiomeProvider();
        assertNotNull("shared provider", shared);
        assertNotNull("map provider", map);

        // Different instances: the whole point is no shared mutable state.
        assertNotSame("shared and map providers must be different instances", shared, map);

        // Sample a wide grid around and beyond the spawn area, including
        // negative coordinates (where the map pans before the buffer recenters)
        // and the far corners of the map preview region (±128 chunks).
        for (int x = -2048; x <= 2048; x += 256) {
            for (int z = -2048; z <= 2048; z += 256) {
                Biome b1 = shared.getBiome(x, z);
                Biome b2 = map.getBiome(x, z);
                assertEquals("biome mismatch at (" + x + "," + z + ")",
                        b1 == null ? null : b1.name, b2 == null ? null : b2.name);
            }
        }

        // Dense sample of the immediate spawn area (the first thing the map shows).
        for (int x = -64; x <= 64; x += 8) {
            for (int z = -64; z <= 64; z += 8) {
                assertEquals("spawn-area biome mismatch at (" + x + "," + z + ")",
                        shared.getBiome(x, z).name, map.getBiome(x, z).name);
            }
        }
    }
}
