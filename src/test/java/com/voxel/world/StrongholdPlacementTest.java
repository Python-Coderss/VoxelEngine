package com.voxel.world;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests StrongholdPlacement procedural ring algorithm. Mirrors the
 * Mojang 1.12.2 formula: random angle θ in [0, 2π), random radius r in
 * [1260, 1650] chunks (≈ 0.9·1400 to 1.1·1500).
 */
public class StrongholdPlacementTest {

    @Test
    public void deterministicForSameSeed() {
        StrongholdPlacement.Resolution a = StrongholdPlacement.resolve(42L);
        StrongholdPlacement.Resolution b = StrongholdPlacement.resolve(42L);
        assertEquals("same seed yields same chunk X", a.chunkX, b.chunkX);
        assertEquals("same seed yields same chunk Z", a.chunkZ, b.chunkZ);
    }

    @Test
    public void differentSeedsYieldDifferentLocations() {
        StrongholdPlacement.Resolution a = StrongholdPlacement.resolve(42L);
        StrongholdPlacement.Resolution b = StrongholdPlacement.resolve(43L);
        boolean differs = a.chunkX != b.chunkX || a.chunkZ != b.chunkZ;
        assertTrue("different seeds usually yield different strongholds", differs);
    }

    @Test
    public void zeroSeedDoesNotThrow() {
        // Random(0) throws IllegalArgumentException; the helper hashes a
        // zero seed into a non-zero constant so we still get a valid ring
        // pick.
        StrongholdPlacement.Resolution r = StrongholdPlacement.resolve(0L);
        // The location should sit on the 1260..1650 chunk ring.
        double dist = Math.sqrt((double) r.chunkX * r.chunkX
                + (double) r.chunkZ * r.chunkZ);
        assertTrue("stronghold must be inside the ring outer radius (1650)", dist <= 1650.0);
        assertTrue("stronghold must be outside the ring inner radius (1260)", dist >= 1260.0);
    }

    @Test
    public void alwaysInsideTheRingForAnySeed() {
        long[] seeds = { 1L, 42L, 0xC0FFEEL, 0xDEADBEEFL, Long.MAX_VALUE };
        for (long seed : seeds) {
            StrongholdPlacement.Resolution r = StrongholdPlacement.resolve(seed);
            double dist = Math.sqrt((double) r.chunkX * r.chunkX
                    + (double) r.chunkZ * r.chunkZ);
            assertTrue("seed " + seed + " produced dist " + dist + " > 1650",
                    dist <= 1650.0);
            assertTrue("seed " + seed + " produced dist " + dist + " < 1260",
                    dist >= 1260.0);
        }
    }

    @Test
    public void baseYIsSensibleForSurvival() {
        StrongholdPlacement.Resolution r = StrongholdPlacement.resolve(42L);
        assertTrue("base Y must be inside the build height range",
                r.baseY > 0 && r.baseY < 256);
    }
}