package com.voxel.world.beta;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the far-lands fade-out ceiling: degraded coordinates previously
 * packed Beta columns solid all the way to the buffer top (y=2047), starving
 * the chunk pool and leaving the far lands with no sky. Terrain must now
 * always fade back to air by y≈2000 — in every Beta dimension profile — while
 * the far-lands mass below the ceiling still exists.
 */
public class BetaFarLandsCeilingTest {

    private static final int CEILING = 2000;

    private static BetaChunkProvider makeProvider(BetaNumericProfile profile) {
        int[] snowLevels = {0, 78, 78, 78, 78, 78, 78, 78, 78};
        return new BetaChunkProvider(12345L, profile,
                1, 2, 3, 7,             // stone, grass, dirt, bedrock
                9, 11, 12, 13,          // water, lava, sand, gravel
                24, 79, 78, 49,         // sandstone, ice, snow, obsidian
                18, 17,                 // leaves, wood
                37, 38, 31, 32,         // dandelion, rose, tallgrass, deadbush
                81, 86,                 // cactus, pumpkin
                14, 15, 16, 56, 73, 21, 89, // ores + glowstone
                83, 82, 4, 48,          // sugarcane, clay, cobble, mossy
                54, 52, snowLevels);    // chest, spawner
    }

    /**
     * Probes a far-lands column (chunk 500,500 = block 8000+, well inside the
     * degradation bands) and asserts: nothing solid at/above y=2000, and the
     * far-lands mass still exists somewhere below it.
     */
    private static void assertFadeOut(BetaNumericProfile profile) {
        BetaChunkProvider p = makeProvider(profile);
        int bx = 500 * 16 + 8, bz = 500 * 16 + 8;

        // The far-lands fill must still exist below the ceiling.
        boolean solidBelow = false;
        for (int y = 500; y < CEILING; y += 50) {
            if (p.getBetaBlock(bx, bz, y) != 0) { solidBelow = true; break; }
        }
        assertTrue("no far-lands fill at all below y=" + CEILING, solidBelow);

        // Nothing may be solid at or above the ceiling.
        for (int y = CEILING; y < 2048; y++) {
            assertEquals("solid voxel at y=" + y, 0, p.getBetaBlock(bx, bz, y));
        }
    }

    @Test
    public void overworldAggressiveProfileFadesByY2000() {
        assertFadeOut(BetaNumericProfile.OVERWORLD);
    }

    @Test
    public void error502ExperimentalProfileFadesByY2000() {
        assertFadeOut(BetaNumericProfile.DEFAULT);
    }
}
