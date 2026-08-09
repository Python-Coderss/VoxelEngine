package com.voxel.world.beta;

import java.util.Random;

/**
 * Exact port of Beta 1.7.3's NoiseGeneratorOctaves.
 * Stacks multiple NoiseGeneratorPerlin instances at decreasing frequencies.
 * Preserves ALL bugs including Far Lands floating-point precision issues.
 */
public class NoiseGeneratorOctaves {
    private NoiseGeneratorPerlin[] generatorCollection;
    private int field_1191_b;
    private BetaNumericProfile numericProfile;

    public NoiseGeneratorOctaves(Random var1, int var2) {
        this(var1, var2, BetaNumericProfile.DEFAULT);
    }

    public NoiseGeneratorOctaves(Random var1, int var2, BetaNumericProfile numericProfile) {
        this.field_1191_b = var2;
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        this.generatorCollection = new NoiseGeneratorPerlin[var2];

        for (int var3 = 0; var3 < var2; ++var3) {
            this.generatorCollection[var3] = new NoiseGeneratorPerlin(var1, this.numericProfile);
        }
    }

    public void setNumericProfile(BetaNumericProfile numericProfile) {
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        for (NoiseGeneratorPerlin generator : generatorCollection) generator.setNumericProfile(this.numericProfile);
    }

    /**
     * Pushes the chunk-aligned block offsets (corner closest to 0,0,0) into
     * every Perlin octave so the whole chain anchors precision to the chunk.
     */
    public void setChunkOffset(double blockX, double blockY, double blockZ) {
        for (NoiseGeneratorPerlin generator : generatorCollection) {
            generator.setChunkOffset(blockX, blockY, blockZ);
        }
    }

    public double func_806_a(double var1, double var3) {
        double var5 = numericProfile.doubleValue(0.0D);
        double var7 = numericProfile.doubleValue(1.0D);

        for (int var9 = 0; var9 < this.field_1191_b; ++var9) {
            var5 = numericProfile.doubleValue(var5
                    + this.generatorCollection[var9].func_801_a(var1 * var7, var3 * var7) / var7);
            var7 = numericProfile.doubleValue(var7 / 2.0D);
        }

        return var5;
    }

    public double[] generateNoiseOctaves(double[] var1, double var2, double var4, double var6,
                                          int var8, int var9, int var10,
                                          double var11, double var13, double var15) {
        if (var1 == null) {
            var1 = new double[var8 * var9 * var10];
        } else {
            for (int var17 = 0; var17 < var1.length; ++var17) {
                var1[var17] = 0.0D;
            }
        }

        double var20 = numericProfile.doubleValue(1.0D);

        for (int var19 = 0; var19 < this.field_1191_b; ++var19) {
            // BUG: var20 doubles each octave, causing floating-point precision loss
            // at extreme coordinates (the Far Lands)
            this.generatorCollection[var19].func_805_a(var1, var2, var4, var6, var8, var9, var10,
                    numericProfile.doubleValue(var11 * var20), numericProfile.doubleValue(var13 * var20),
                    numericProfile.doubleValue(var15 * var20), var20);
            var20 = numericProfile.doubleValue(var20 / 2.0D);
        }

        return var1;
    }

    public double[] func_4109_a(double[] var1, int var2, int var3, int var4, int var5,
                                 double var6, double var8, double var10) {
        return this.generateNoiseOctaves(var1, (double) var2, 10.0D, (double) var3, var4, 1, var5, var6, 1.0D, var8);
    }
}
