package com.voxel.world.beta;

import com.voxel.world.DimensionType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BetaNumericControlsTest {
    @Test
    public void error502DimensionIsRegisteredSeparatelyForTheCurrentBetaPreset() {
        assertEquals(4, DimensionType.ERROR502.id);
        assertEquals("error502", DimensionType.ERROR502.name);
        assertEquals(64, DimensionType.ERROR502.baseHeight);
        assertEquals(BetaNumericProfile.DEFAULT.floatMantissaBits(),
                BetaPrecisionTuning.XZ_FLOAT_MANTISSA_BITS);
        assertEquals(BetaNumericProfile.DEFAULT.yDoubleMantissaBits(),
                BetaPrecisionTuning.Y_DOUBLE_MANTISSA_BITS);
    }

    @Test
    public void controlsAreIndependent() {
        BetaNumericProfile profile = new BetaNumericProfile(5, 9, 5, 4, 8, 12);
        assertEquals(5, profile.shortBits());
        assertEquals(9, profile.intBits());
        assertEquals(9, profile.yIntBits());
        assertEquals(5, profile.floatExponentBits());
        assertEquals(4, profile.floatMantissaBits());
        assertEquals(5, profile.yFloatExponentBits());
        assertEquals(4, profile.yFloatMantissaBits());
        assertEquals(8, profile.doubleExponentBits());
        assertEquals(12, profile.doubleMantissaBits());
        assertEquals(8, profile.yDoubleExponentBits());
        assertEquals(12, profile.yDoubleMantissaBits());
    }

    @Test
    public void standardBetaUsesIntegerOnlyFarLandsPrecision() {
        BetaNumericProfile profile = BetaNumericProfile.STANDARD_BETA;
        assertEquals(10, profile.shortBits());
        assertEquals(20, profile.intBits());
        assertEquals(15, profile.yIntBits());
        assertEquals(8, profile.floatExponentBits());
        assertEquals(23, profile.floatMantissaBits());
        assertEquals(8, profile.yFloatExponentBits());
        assertEquals(23, profile.yFloatMantissaBits());
        assertEquals(11, profile.doubleExponentBits());
        assertEquals(52, profile.doubleMantissaBits());
        assertEquals(11, profile.yDoubleExponentBits());
        assertEquals(52, profile.yDoubleMantissaBits());

    }

    @Test
    public void defaultPresetUsesIndependentTwentyBitXZAndFifteenBitYIntegers() {
        assertEquals(BetaPrecisionTuning.XZ_INT_BITS, BetaNumericProfile.DEFAULT.intBits());
        assertEquals(BetaPrecisionTuning.Y_INT_BITS, BetaNumericProfile.DEFAULT.yIntBits());
        assertEquals(BetaPrecisionTuning.XZ_FLOAT_MANTISSA_BITS,
                BetaNumericProfile.DEFAULT.floatMantissaBits());
        assertEquals(20, BetaNumericProfile.DEFAULT.intBits());
        assertEquals(15, BetaNumericProfile.DEFAULT.yIntBits());
        assertEquals(14, BetaNumericProfile.DEFAULT.floatMantissaBits());
        assertEquals(8, BetaNumericProfile.DEFAULT.yFloatExponentBits());
        assertEquals(11, BetaNumericProfile.DEFAULT.yFloatMantissaBits());
        assertEquals(11, BetaNumericProfile.DEFAULT.yDoubleExponentBits());
        assertEquals(11, BetaNumericProfile.DEFAULT.yDoubleMantissaBits());
    }

    @Test
    public void coordinateBandsSelectIndependentAxisPrecision() {
        BetaPrecisionTuning error502 = new Error502BetaPrecision();
        assertEquals(0, error502.coordinateBand(128.0D));
        assertEquals(0, error502.coordinateBand(-2048.0D));
        assertEquals(23, error502.xFloatMantissaBits(2048.0D));
        assertEquals(4, error502.yFloatMantissaBits(2048.0D));
        assertEquals(26, error502.zDoubleMantissaBits(20_000.0D));
        assertEquals(BetaPrecisionTuning.Axis.X,
                error502.dominantXZAxis(3000.0D, 100.0D));
        assertEquals(BetaPrecisionTuning.Axis.Z,
                error502.dominantXZAxis(100.0D, -3000.0D));
    }

    @Test
    public void overworldPolicyClassDegradesWithDistance() {
        // OverworldBetaPrecision now backs the ERROR502 preset (after the
        // preset swap) and deliberately degrades float/double mantissas with
        // distance while integer widths stay at the far-lands baseline.
        OverworldBetaPrecision ow = new OverworldBetaPrecision();
        assertEquals(20, ow.xIntBits(100_000.0D));
        assertEquals(20, ow.zIntBits(100_000.0D));
        assertEquals(15, ow.yIntBits(10_000.0D));
        assertEquals(23, ow.xFloatMantissaBits(0.0D));
        assertEquals(2, ow.xFloatMantissaBits(100_000.0D));
        assertEquals(52, ow.xDoubleMantissaBits(0.0D));
        assertEquals(6, ow.xDoubleMantissaBits(100_000.0D));
        assertTrue(ow.xFloatMantissaBits(4000.0D) < ow.xFloatMantissaBits(0.0D));
        assertTrue(ow.yDoubleMantissaBits(1500.0D) < ow.yDoubleMantissaBits(0.0D));
    }

    @Test
    public void tunedPresetsUseSwappedPoliciesAndDegradeAtDistance() {
        // The error502 and overworld precision policies were swapped between
        // the two presets; both presets are coordinate-tuned (STANDARD_BETA
        // is the fixed-width reference and has no tuning policy).
        assertTrue(BetaNumericProfile.OVERWORLD.tuning() instanceof Error502BetaPrecision);
        assertTrue(BetaNumericProfile.DEFAULT.tuning() instanceof OverworldBetaPrecision);
        assertTrue(BetaNumericProfile.STANDARD_BETA.tuning() == null);

        // At 4,000 blocks (band 1) both swapped policies use a 16-bit float
        // mantissa, so both presets lose precision there (chunk-aligned to
        // 3,984).
        double ow = BetaNumericProfile.OVERWORLD.xFloatValueAtDistance(0.1D, 4000.0D);
        double ew = BetaNumericProfile.DEFAULT.xFloatValueAtDistance(0.1D, 4000.0D);
        assertTrue(Math.abs(ow - 0.1D) > 0.005D);
        assertTrue(Math.abs(ew - 0.1D) > 0.005D);
    }

    @Test
    public void overworldProfileTracksStandardBetaTerrainNearSpawn() {
        // After the preset swap the overworld runs the error502-style policy,
        // whose X/Z doubles stay at 26 bits, so near-spawn terrain matches the
        // legacy fixed-width profile only within that double-quantization
        // tolerance. Far-lands terrain intentionally diverges once the
        // float/double degradation bands kick in.
        BetaNumericProfile legacy = BetaNumericProfile.STANDARD_BETA;
        BetaNumericProfile overworld = BetaNumericProfile.OVERWORLD;
        double[][] samples = {
                {0.0D, 32.0D, 0.0D},
                {3000.25D, 64.25D, 3001.75D},
        };
        for (double[] s : samples) {
            NoiseGeneratorPerlin legacyNoise = new NoiseGeneratorPerlin(new java.util.Random(42L), legacy);
            NoiseGeneratorPerlin overworldNoise = new NoiseGeneratorPerlin(new java.util.Random(42L), overworld);
            assertEquals(legacyNoise.generateNoise(s[0], s[1], s[2]),
                    overworldNoise.generateNoise(s[0], s[1], s[2]), 1.0E-3D);
            assertEquals(legacyNoise.func_801_a(s[0], s[2]),
                    overworldNoise.func_801_a(s[0], s[2]), 1.0E-3D);
        }
    }

    @Test
    public void defaultProfileIsBackedByPrecisionTuningFile() {
        BetaNumericProfile profile = BetaNumericProfile.DEFAULT;
        assertEquals(BetaPrecisionTuning.SHORT_BITS, profile.shortBits());
        assertEquals(BetaPrecisionTuning.XZ_INT_BITS, profile.intBits());
        assertEquals(BetaPrecisionTuning.Y_INT_BITS, profile.yIntBits());
        assertEquals(BetaPrecisionTuning.XZ_FLOAT_EXPONENT_BITS, profile.floatExponentBits());
        assertEquals(BetaPrecisionTuning.XZ_FLOAT_MANTISSA_BITS, profile.floatMantissaBits());
        assertEquals(BetaPrecisionTuning.Y_FLOAT_EXPONENT_BITS, profile.yFloatExponentBits());
        assertEquals(BetaPrecisionTuning.Y_FLOAT_MANTISSA_BITS, profile.yFloatMantissaBits());
        assertEquals(BetaPrecisionTuning.XZ_DOUBLE_EXPONENT_BITS, profile.doubleExponentBits());
        assertEquals(BetaPrecisionTuning.XZ_DOUBLE_MANTISSA_BITS, profile.doubleMantissaBits());
        assertEquals(BetaPrecisionTuning.Y_DOUBLE_EXPONENT_BITS, profile.yDoubleExponentBits());
        assertEquals(BetaPrecisionTuning.Y_DOUBLE_MANTISSA_BITS, profile.yDoubleMantissaBits());
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
    public void floatQuantizationUsesWorldDistanceForDerivedValues() {
        BetaNumericProfile profile = new BetaNumericProfile(10, 20, 15, 8, 11, 8, 11, 11, 26, 11, 11);
        assertEquals(0.25D, profile.floatValueAtDistance(0.25D, 0.0D), 0.0D);
        assertEquals(0.25D, profile.floatValueAtDistance(0.25D, 128.0D), 0.0D);
        assertEquals(0.0D, profile.floatValueAtDistance(0.25D, 1024.0D), 0.0D);
        assertEquals(profile.floatValueAtDistance(0.25D, 3000.0D),
                profile.floatValueAtDistance(0.25D, -3000.0D), 0.0D);
        assertEquals(0.25D, profile.floatValue(0.25D), 0.0D);
    }

    @Test
    public void worldDistanceUsesSignedDominantAxisRoundedToChunkCorner() {
        // The ULP context is the corner of the dominant axis' 16-block chunk
        // closest to 0,0,0 (positive chunks round down, negative round up).
        BetaNumericProfile profile = BetaNumericProfile.DEFAULT;
        assertEquals(-2992.0D, profile.worldDistance(-3000.0D, 100.0D, 200.0D), 0.0D);
        assertEquals(2992.0D, profile.worldDistance(100.0D, -200.0D, 3000.0D), 0.0D);
        assertEquals(-2032.0D, profile.worldDistance(-2048.0D, 1024.0D), 0.0D);
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
        NoiseGeneratorPerlin wideY = new NoiseGeneratorPerlin(seed,
                new BetaNumericProfile(10, 20, 15, 8, 11, 8, 11, 11, 26, 11, 11));
        seed = new java.util.Random(7L);
        NoiseGeneratorPerlin narrowY = new NoiseGeneratorPerlin(seed,
                new BetaNumericProfile(10, 20, 8, 8, 11, 4, 3, 11, 26, 4, 3));
        assertEquals(wideY.func_801_a(32768.25D, 32768.75D),
                narrowY.func_801_a(32768.25D, 32768.75D), 0.0D);
    }

    @Test
    public void defaultXZFloatStageIsEightTimesFinerAtTheOldBoundary() {
        BetaNumericProfile old = new BetaNumericProfile(10, 20, 15,
                8, 11, 8, 11, 11, 26, 11, 11);
        BetaNumericProfile current = BetaNumericProfile.DEFAULT;
        assertEquals(11, old.floatMantissaBits());
        assertEquals(14, current.floatMantissaBits());
        assertTrue(Math.abs(current.floatValueAtDistance(0.25D, 3060.0D)) >
                Math.abs(old.floatValueAtDistance(0.25D, 3060.0D)));

        NoiseGeneratorPerlin oldNoise = new NoiseGeneratorPerlin(new java.util.Random(42L), old);
        NoiseGeneratorPerlin currentNoise = new NoiseGeneratorPerlin(new java.util.Random(42L), current);
        double old2d = oldNoise.func_801_a(3060.25D, 3061.0D);
        double current2d = currentNoise.func_801_a(3060.25D, 3061.0D);
        double old3d = oldNoise.generateNoise(3060.25D, 32.25D, 3061.0D);
        double current3d = currentNoise.generateNoise(3060.25D, 32.25D, 3061.0D);
        assertTrue(old2d != current2d);
        assertTrue(old3d != current3d);
    }

    @Test
    public void yFloatingControlsAreIndependentAndQuantizeAtLargeY() {
        BetaNumericProfile profile = new BetaNumericProfile(
                10, 20, 15,
                8, 11,
                8, 5,
                11, 26,
                11, 5);
        assertEquals(15, profile.yIntBits());
        assertEquals(8, profile.yFloatExponentBits());
        assertEquals(5, profile.yFloatMantissaBits());
        assertEquals(11, profile.yDoubleExponentBits());
        assertEquals(5, profile.yDoubleMantissaBits());
        assertTrue(profile.yFloatValue(3000.25D) != 3000.25D);
        assertTrue(profile.yDoubleValue(3000.25D) != 3000.25D);
        assertEquals(3000.0D, profile.floatValue(3000.25D), 0.0D);
        assertEquals(3000.25D, profile.doubleValue(3000.25D), 0.0D);
    }

    @Test
    public void legacyConstructorsCopyXZFloatingWidthsToY() {
        BetaNumericProfile profile = new BetaNumericProfile(10, 20, 15, 8, 11, 11, 26);
        assertEquals(profile.floatExponentBits(), profile.yFloatExponentBits());
        assertEquals(profile.floatMantissaBits(), profile.yFloatMantissaBits());
        assertEquals(profile.doubleExponentBits(), profile.yDoubleExponentBits());
        assertEquals(profile.doubleMantissaBits(), profile.yDoubleMantissaBits());
    }

    @Test
    public void trueYNoiseUsesYFloatingControlsButTwoDimensionalNoiseUsesXZControls() {
        java.util.Random seed = new java.util.Random(42L);
        BetaNumericProfile normal = new BetaNumericProfile(10, 20, 15, 8, 11, 8, 11, 11, 26, 11, 11);
        NoiseGeneratorPerlin normalNoise = new NoiseGeneratorPerlin(seed, normal);
        seed = new java.util.Random(42L);
        BetaNumericProfile coarseY = new BetaNumericProfile(10, 20, 15, 8, 11, 4, 3, 11, 26, 4, 3);
        NoiseGeneratorPerlin coarseYNoise = new NoiseGeneratorPerlin(seed, coarseY);

        assertEquals(normalNoise.func_801_a(3000.25D, 3000.75D),
                coarseYNoise.func_801_a(3000.25D, 3000.75D), 0.0D);
        assertTrue(normalNoise.generateNoise(0.0D, 3000.25D, 0.0D)
                != coarseYNoise.generateNoise(0.0D, 3000.25D, 0.0D));
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
