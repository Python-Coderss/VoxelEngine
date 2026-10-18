package com.voxel.game;

import com.voxel.Main;
import com.voxel.world.DimensionType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the daylight behind the 3D main-menu panorama.
 *
 * <p>The panorama is drawn by the same raytracer pass as the world, and that
 * pass shades blocks with {@code ambient + sunColor * celRamp(sunDot) * skyNorm}
 * where {@code at.*} comes straight from the uniforms
 * {@link AtmosphereRenderer#upload} writes. The moon term never reaches terrain,
 * and {@code sunColor} is multiplied by {@code smoothstep(-0.1, 0.1, sunDir.y)},
 * so once the sun drops below the horizon the menu scene keeps only ambient and
 * renders as near-black voxels. These checks assert the data being handed to the
 * shader (via {@link AtmosphereRenderer#overworld}, the exact source of those
 * uniforms) rather than any GPU behaviour.</p>
 */
public class PanoramaLightingTest {

    private static final float[] SUN = new float[3];
    private static final float[] ATMO = new float[21];

    @Test
    public void panoramaStartsInDaylight() {
        assertTrue("start is on the 1440-unit day",
                Main.PANORAMA_START_TIME >= 0f && Main.PANORAMA_START_TIME < 1440f);
        assertTrue("start is after sunrise (360), not night",
                Main.PANORAMA_START_TIME > 360f);
        assertTrue("start is before noon (720), i.e. morning",
                Main.PANORAMA_START_TIME < 720f);
    }

    @Test
    public void theUploadedAtmosphereIsDaylightAcrossTheDriftWindow() {
        assertTrue("sun light at the start (" + sunLuminanceAt(Main.PANORAMA_START_TIME) + ")",
                sunLuminanceAt(Main.PANORAMA_START_TIME) > 1.5f);
        float mid = (Main.PANORAMA_START_TIME + Main.PANORAMA_END_TIME) * 0.5f;
        assertTrue("sun light at the midpoint (" + sunLuminanceAt(mid) + ")",
                sunLuminanceAt(mid) > 1.5f);
        assertTrue("sun light at the far end (" + sunLuminanceAt(Main.PANORAMA_END_TIME) + ")",
                sunLuminanceAt(Main.PANORAMA_END_TIME) > 1.5f);
        assertTrue("drift window ends before dusk (1080)",
                Main.PANORAMA_END_TIME < 1080f);
    }

    @Test
    public void aNightClockFeedsTheShaderNoSunAndNearBlackAmbient() {
        // The value the panorama clock used to sit at: 1600 on the 1440-unit day
        // is ~02:40, so the horizon fade zeroes u_SunColor and u_Ambient drops to
        // its night floor. That is the whole failure — the shader consumed exactly
        // what the CPU sent, and what the CPU sent was night.
        AtmosphereRenderer.overworld(1600f, ATMO);
        assertEquals("sun colour collapses at 1600", 0f,
                ATMO[6] + ATMO[7] + ATMO[8], 1e-4f);
        assertTrue("ambient is the night floor at 1600 (lum="
                        + (ATMO[18] + ATMO[19] + ATMO[20]) + ")",
                ATMO[18] + ATMO[19] + ATMO[20] < 0.2f);
        AtmosphereRenderer.computeSunDir(DimensionType.OVERWORLD, 1600f, SUN);
        assertTrue("1600 is below the horizon (y=" + SUN[1] + ")", SUN[1] < 0f);
    }

    @Test
    public void dayLengthMatchesTheAtmosphereSunCycle() {
        // The clock is expressed in the atmosphere's 1440-unit day. If the two
        // ever disagreed the menu would jump to a random hour (potentially at
        // night) as it drifted.
        AtmosphereRenderer.computeSunDir(DimensionType.OVERWORLD, 480f, SUN);
        float[] before = { SUN[0], SUN[1], SUN[2] };
        AtmosphereRenderer.computeSunDir(DimensionType.OVERWORLD, 480f + 1440f, SUN);
        for (int i = 0; i < 3; i++) {
            assertTrue("sun direction repeats after one 1440-unit day (axis " + i + ")",
                    Math.abs(before[i] - SUN[i]) < 1e-4f);
        }
    }

    /** r+g+b of the sun colour the renderer uploads as {@code u_SunColor}. */
    private static float sunLuminanceAt(float time) {
        AtmosphereRenderer.overworld(time, ATMO);
        return ATMO[6] + ATMO[7] + ATMO[8];
    }
}
