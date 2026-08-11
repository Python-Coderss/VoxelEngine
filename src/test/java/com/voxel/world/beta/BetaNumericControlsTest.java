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
        assertEquals(17, profile.yIntBits());
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
    public void defaultPresetUsesIndependentTwentyBitXZAndSeventeenBitYIntegers() {
        assertEquals(BetaPrecisionTuning.XZ_INT_BITS, BetaNumericProfile.DEFAULT.intBits());
        assertEquals(BetaPrecisionTuning.Y_INT_BITS, BetaNumericProfile.DEFAULT.yIntBits());
        assertEquals(BetaPrecisionTuning.XZ_FLOAT_MANTISSA_BITS,
                BetaNumericProfile.DEFAULT.floatMantissaBits());
        assertEquals(20, BetaNumericProfile.DEFAULT.intBits());
        assertEquals(17, BetaNumericProfile.DEFAULT.yIntBits());
        assertEquals(14, BetaNumericProfile.DEFAULT.floatMantissaBits());
        assertEquals(8, BetaNumericProfile.DEFAULT.yFloatExponentBits());
        assertEquals(11, BetaNumericProfile.DEFAULT.yFloatMantissaBits());
        assertEquals(11, BetaNumericProfile.DEFAULT.yDoubleExponentBits());
        assertEquals(11, BetaNumericProfile.DEFAULT.yDoubleMantissaBits());
    }

    @Test
    public void coordinateBandsSelectIndependentAxisPrecision() {
        // Error502BetaPrecision backs the ERROR502 preset: aggressive switches
        // that degrade float mantissas 23→16→11→6→4→2→1 and doubles
        // 52→40→30→18→11→6→1.
        BetaPrecisionTuning error502 = new Error502BetaPrecision();
        assertEquals(0, error502.coordinateBand(128.0D));
        assertEquals(0, error502.coordinateBand(-2048.0D));
        assertEquals(23, error502.xFloatMantissaBits(2048.0D));
        assertEquals(1, error502.yFloatMantissaBits(2048.0D));
        assertEquals(1, error502.zDoubleMantissaBits(20_000.0D));
        assertEquals(BetaPrecisionTuning.Axis.X,
                error502.dominantXZAxis(3000.0D, 100.0D));
        assertEquals(BetaPrecisionTuning.Axis.Z,
                error502.dominantXZAxis(100.0D, -3000.0D));
    }

    @Test
    public void overworldPolicyClassDegradesWithDistance() {
        // OverworldBetaPrecision backs the OVERWORLD preset: integer widths
        // stay at the far-lands baseline, the first two degrading float bands
        // retain the current Overworld values, then the sequence follows
        // ERROR502 and reaches the same endpoint.
        OverworldBetaPrecision ow = new OverworldBetaPrecision();
        assertEquals(20, ow.xIntBits(100_000.0D));
        assertEquals(20, ow.zIntBits(100_000.0D));
        assertEquals(17, ow.yIntBits(10_000.0D));
        assertEquals(23, ow.xFloatMantissaBits(0.0D));
        assertEquals(23, ow.xFloatMantissaBits(4000.0D));
        assertEquals(20, ow.xFloatMantissaBits(4500.0D));
        assertEquals(12, ow.xFloatMantissaBits(5000.0D));
        assertEquals(6, ow.xFloatMantissaBits(5500.0D));
        assertEquals(1, ow.xFloatMantissaBits(100_000.0D));
        // X/Z doubles remain at full precision through the first 4,000-block
        // band, then use the historical Error502 mask.
        assertEquals(52, ow.xDoubleMantissaBits(0.0D));
        assertEquals(52, ow.xDoubleMantissaBits(4000.0D));
        // Precision contexts are chunk-aligned: the 4000–4015 chunk stays
        // full precision, and degradation begins in the next chunk at 4016.
        assertEquals(52, ow.xDoubleMantissaBits(4015.0D));
        assertEquals(26, ow.xDoubleMantissaBits(4016.0D));
        assertEquals(26, ow.xDoubleMantissaBits(100_000.0D));
        assertEquals(52, ow.zDoubleMantissaBits(4000.0D));
        assertEquals(52, ow.zDoubleMantissaBits(4015.0D));
        assertEquals(26, ow.zDoubleMantissaBits(4016.0D));
        assertEquals(26, ow.zDoubleMantissaBits(100_000.0D));
        // Negative chunks mirror the boundary with the shared chunk-offset
        // rounding: -4016 remains in the full-precision chunk, while -4017
        // enters the first degraded chunk.
        assertEquals(52, ow.xDoubleMantissaBits(-4000.0D));
        assertEquals(52, ow.xDoubleMantissaBits(-4016.0D));
        assertEquals(26, ow.xDoubleMantissaBits(-4017.0D));
        assertEquals(52, ow.zDoubleMantissaBits(-4000.0D));
        assertEquals(52, ow.zDoubleMantissaBits(-4016.0D));
        assertEquals(26, ow.zDoubleMantissaBits(-4017.0D));
        assertEquals(23, ow.yFloatMantissaBits(400.0D));
        assertEquals(52, ow.yDoubleMantissaBits(400.0D));
        assertEquals(23, ow.yFloatMantissaBits(4000.0D));
        assertEquals(52, ow.yDoubleMantissaBits(4000.0D));
        assertEquals(23, ow.yFloatMantissaBits(4015.0D));
        assertEquals(52, ow.yDoubleMantissaBits(4015.0D));
        assertEquals(20, ow.yFloatMantissaBits(4016.0D));
        assertEquals(26, ow.yDoubleMantissaBits(4016.0D));
        assertEquals(1, ow.yFloatMantissaBits(10_000.0D));
        assertEquals(26, ow.yDoubleMantissaBits(10_000.0D));
        Error502BetaPrecision error502 = new Error502BetaPrecision();
        // Overworld stays full through X/Z=4000, then follows its tuned
        // degradation curve; its early later bands intentionally remain
        // gentler than ERROR502's corresponding bands.
        assertEquals(23, ow.xFloatMantissaBits(4000.0D));
        assertEquals(20, ow.xFloatMantissaBits(4500.0D));
        assertEquals(12, ow.xFloatMantissaBits(5000.0D));
        assertEquals(6, ow.xFloatMantissaBits(5500.0D));
        assertEquals(23, ow.yFloatMantissaBits(1500.0D));
        assertEquals(52, ow.yDoubleMantissaBits(1500.0D));
        assertEquals(1, ow.yFloatMantissaBits(10_000.0D));
        assertEquals(26, ow.yDoubleMantissaBits(10_000.0D));
    }

    @Test
    public void tunedPresetsUseNamedPoliciesAndDegradeAtDistance() {
        // Each preset is backed by the policy class named after its dimension;
        // both are coordinate-tuned (STANDARD_BETA is the fixed-width
        // reference and has no tuning policy).
        assertTrue(BetaNumericProfile.OVERWORLD.tuning() instanceof OverworldBetaPrecision);
        assertTrue(BetaNumericProfile.DEFAULT.tuning() instanceof Error502BetaPrecision);
        assertTrue(BetaNumericProfile.STANDARD_BETA.tuning() == null);

        // At 3,600 blocks (still full precision in Overworld, but degraded in
        // ERROR502), Overworld retains more precision.
        double ow = BetaNumericProfile.OVERWORLD.xFloatValueAtDistance(0.1D, 3600.0D);
        double ew = BetaNumericProfile.DEFAULT.xFloatValueAtDistance(0.1D, 3600.0D);
        assertTrue(Math.abs(ow - 0.1D) < Math.abs(ew - 0.1D));

        // The shifted Overworld stages are verified directly; their values are
        // intentionally gentler than ERROR502 in the early later bands.
        OverworldBetaPrecision overworld = new OverworldBetaPrecision();
        assertEquals(23, overworld.xFloatMantissaBits(4000.0D));
        assertEquals(20, overworld.xFloatMantissaBits(4500.0D));
        assertEquals(12, overworld.xFloatMantissaBits(5000.0D));
        assertEquals(6, overworld.xFloatMantissaBits(5500.0D));

        Error502BetaPrecision error502 = new Error502BetaPrecision();
        assertEquals(error502.xFloatMantissaBits(100_000.0D),
                overworld.xFloatMantissaBits(100_000.0D));
        assertEquals(52, overworld.xDoubleMantissaBits(4000.0D));
        assertEquals(52, overworld.zDoubleMantissaBits(4000.0D));
        assertEquals(26, overworld.xDoubleMantissaBits(100_000.0D));
        assertEquals(26, overworld.zDoubleMantissaBits(100_000.0D));
        assertEquals(1, overworld.yFloatMantissaBits(10_000.0D));
        assertEquals(26, overworld.yDoubleMantissaBits(10_000.0D));
    }

    @Test
    public void overworldProfileTracksStandardBetaTerrainNearSpawn() {
        // The Overworld keeps full X/Z/Y double precision through 4,000
        // blocks, then uses the tuned horizontal/vertical degradation bands.
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
        BetaNumericProfile profile = new BetaNumericProfile(8, 20, 17, 8, 23, 11, 52);
        assertEquals(20, profile.intBits());
        assertEquals(32768, profile.intValue(32768));
        assertEquals(32768, profile.yIntValue(32768));
        assertEquals(-65536, profile.yIntValue(65536));
        assertEquals(17, profile.yIntBits());

        assertEquals(17, BetaNumericProfile.STANDARD_BETA.yIntBits());
        assertEquals(17, BetaNumericProfile.OVERWORLD.yIntBits());
        assertEquals(17, BetaNumericProfile.DEFAULT.yIntBits());
        assertEquals(-65536, BetaNumericProfile.DEFAULT.yIntValue(65536));
        assertEquals(17, new OverworldBetaPrecision().yIntBits(100_000.0D));
        assertEquals(17, new Error502BetaPrecision().yIntBits(100_000.0D));
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
        BetaNumericProfile profile = new BetaNumericProfile(10, 20, 17, 8, 11, 8, 11, 11, 26, 11, 11);
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
                new BetaNumericProfile(10, 20, 17, 8, 11, 8, 11, 11, 26, 11, 11));
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
                10, 20, 17,
                8, 11,
                8, 5,
                11, 26,
                11, 5);
        assertEquals(17, profile.yIntBits());
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
