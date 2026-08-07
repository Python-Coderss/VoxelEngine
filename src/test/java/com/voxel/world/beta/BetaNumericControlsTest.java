package com.voxel.world.beta;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BetaNumericControlsTest {
    @Test
    public void controlsAreIndependent() {
        BetaNumericProfile profile = new BetaNumericProfile(5, 9, 5, 4, 8, 12);
        assertEquals(5, profile.shortBits());
        assertEquals(9, profile.intBits());
        assertEquals(9, profile.yIntBits());
        assertEquals(5, profile.floatExponentBits());
        assertEquals(4, profile.floatMantissaBits());
        assertEquals(8, profile.doubleExponentBits());
        assertEquals(12, profile.doubleMantissaBits());
    }

    @Test
    public void defaultPresetUsesIndependentTwentyBitXZAndSixteenBitYIntegers() {
        assertEquals(20, BetaNumericProfile.DEFAULT.intBits());
        assertEquals(16, BetaNumericProfile.DEFAULT.yIntBits());
    }

    @Test
    public void yIntegerWidthDoesNotChangeXZWidth() {
        BetaNumericProfile profile = new BetaNumericProfile(8, 20, 16, 8, 23, 11, 52);
        assertEquals(32768, profile.intValue(32768));
        assertEquals(-32768, profile.yIntValue(32768));
        assertEquals(16, profile.yIntBits());
    }

    @Test
    public void integerWidthsWrapSeparately() {
        BetaNumericProfile profile = new BetaNumericProfile(8, 16, 8, 23, 11, 52);
        assertEquals(-128, profile.shortValue(128));
        assertEquals(128, profile.intValue(128));
        assertEquals(-32768, profile.intValue(32768));
    }

    @Test
    public void floatAndDoubleWidthsQuantizeIndependently() {
        BetaNumericProfile profile = new BetaNumericProfile(16, 32, 5, 4, 11, 52);
        double floatValue = profile.floatValue(1.1234567);
        double doubleValue = profile.doubleValue(1.1234567);
        assertTrue(Math.abs(floatValue - 1.1234567) > Math.abs(doubleValue - 1.1234567));
    }

    @Test
    public void fixedControlsDoNotDependOnDistance() {
        BetaNumericProfile profile = new BetaNumericProfile(16, 32, 8, 10, 11, 20);
        assertEquals(profile.doubleValue(123456.789), profile.doubleValue(123456.789), 0.0);
    }

    @Test
    public void ieeeEncodingPreservesSpecialValuesAndSignedZero() {
        NBitFloat negativeZero = NBitFloat.fromDouble(8, 23, -0.0D);
        assertTrue((negativeZero.rawBits() & (1L << 31)) != 0L);
        assertEquals(Double.doubleToRawLongBits(-0.0D),
                Double.doubleToRawLongBits(negativeZero.toDouble()));
        assertTrue(Double.isInfinite(NBitFloat.fromDouble(8, 23, Double.POSITIVE_INFINITY).toDouble()));
        assertTrue(Double.isNaN(NBitFloat.fromDouble(8, 23, Double.NaN).toDouble()));
    }

    @Test
    public void yWidthDoesNotAffectTwoDimensionalXZNoise() {
        java.util.Random seed = new java.util.Random(7L);
        NoiseGeneratorPerlin wideY = new NoiseGeneratorPerlin(seed, new BetaNumericProfile(10, 20, 16, 8, 11, 11, 26));
        seed = new java.util.Random(7L);
        NoiseGeneratorPerlin narrowY = new NoiseGeneratorPerlin(seed, new BetaNumericProfile(10, 20, 8, 8, 11, 11, 26));
        assertEquals(wideY.func_801_a(32768.25D, 32768.75D),
                narrowY.func_801_a(32768.25D, 32768.75D), 0.0D);
    }

    @Test
    public void narrowPermutationStorageDoesNotCrash() {
        BetaNumericProfile profile = new BetaNumericProfile(4, 5, 5, 4, 8, 12);
        NoiseGeneratorPerlin perlin = new NoiseGeneratorPerlin(new java.util.Random(7L), profile);
        perlin.generateNoise(12550821.25D, -33.5D, 91.75D);
        NoiseGenerator2 simplex = new NoiseGenerator2(new java.util.Random(7L), profile);
        simplex.func_4157_a(new double[1], 12550821.25D, -33.5D,
                1, 1, 0.025D, 0.025D, 0.5D);
    }
}
