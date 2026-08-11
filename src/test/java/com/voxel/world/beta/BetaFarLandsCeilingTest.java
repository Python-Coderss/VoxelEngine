package com.voxel.world.beta;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BetaFarLandsCeilingTest {

    private static final int BUFFER_TOP_EXCLUSIVE = 2048;
    private static final int PROBE_START_Y = 2000;

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
     * Probes a degraded far-lands column (chunk 500,500 = block 8000+).
     * The old explicit Y=2000 cap forced every sample from 2000 through 2047
     * to air. The generator must now be allowed to produce terrain in that
     * bounded buffer range; this test intentionally does not impose a new
     * upper terrain cap.
     */
    private static void assertNoArtificialY2000Cutoff(BetaNumericProfile profile) {
        BetaChunkProvider provider = makeProvider(profile);
        int bx = 500 * 16 + 8;
        int bz = 500 * 16 + 8;

        boolean solidAtOrAbove2000 = false;
        for (int y = PROBE_START_Y; y < BUFFER_TOP_EXCLUSIVE; y++) {
            if (provider.getBetaBlock(bx, bz, y) != 0) {
                solidAtOrAbove2000 = true;
                break;
            }
        }

        // This fixed Overworld column is known to contain upper Far Lands mass.
        // Assert the exact former cutoff boundary and the bounded buffer top.
        assertTrue("Y=2000 cap still removes all terrain through the buffer top",
                solidAtOrAbove2000);
        assertEquals("Y=2000 should retain the generated Far Lands mass", 1,
                provider.getBetaBlock(bx, bz, PROBE_START_Y));
        assertEquals("unexpected block at the bounded buffer top", 0,
                provider.getBetaBlock(bx, bz, BUFFER_TOP_EXCLUSIVE - 1));
    }

    @Test
    public void overworldCanGenerateFarLandsAboveY2000() {
        assertNoArtificialY2000Cutoff(BetaNumericProfile.OVERWORLD);
    }

    @Test
    public void bedrockIsOnlyOneLayerAtYZero() {
        BetaChunkProvider provider = makeProvider(BetaNumericProfile.OVERWORLD);
        int x = 8;
        int z = 8;

        assertEquals("Y=0 must be the single bedrock layer", 7,
                provider.getBetaBlock(x, z, 0));
        assertTrue("Y=-1 must remain generated terrain, not bedrock",
                provider.getBetaBlock(x, z, -1) != 7);
        assertTrue("Y=-64 must no longer be forced to bedrock",
                provider.getBetaBlock(x, z, -64) != 7);
        assertTrue("Y=-65 must no longer be forced to bedrock",
                provider.getBetaBlock(x, z, -65) != 7);
    }

}
