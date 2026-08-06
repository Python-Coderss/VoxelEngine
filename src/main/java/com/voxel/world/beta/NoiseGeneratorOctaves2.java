package com.voxel.world.beta;

import java.util.Random;

/**
 * Exact port of Beta 1.7.3's NoiseGeneratorOctaves2.
 * Stacks multiple NoiseGenerator2 instances for 2D octave noise
 * (used for temperature/humidity biome maps).
 * Preserves ALL bugs including Far Lands precision issues.
 */
public class NoiseGeneratorOctaves2 {
    private NoiseGenerator2[] field_4234_a;
    private int field_4233_b;
    private BetaNumericProfile numericProfile;

    public NoiseGeneratorOctaves2(Random var1, int var2) {
        this(var1, var2, BetaNumericProfile.DEFAULT);
    }

    public NoiseGeneratorOctaves2(Random var1, int var2, BetaNumericProfile numericProfile) {
        this.field_4233_b = var2;
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        this.field_4234_a = new NoiseGenerator2[var2];

        for (int var3 = 0; var3 < var2; ++var3) {
            this.field_4234_a[var3] = new NoiseGenerator2(var1, this.numericProfile);
        }
    }

    public void setNumericProfile(BetaNumericProfile numericProfile) {
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        for (NoiseGenerator2 generator : field_4234_a) generator.setNumericProfile(this.numericProfile);
    }

    public double[] func_4112_a(double[] var1, double var2, double var4, int var6, int var7,
                                 double var8, double var10, double var12) {
        return this.func_4111_a(var1, var2, var4, var6, var7, var8, var10, var12, 0.5D);
    }

    public double[] func_4111_a(double[] var1, double var2, double var4, int var6, int var7,
                                 double var8, double var10, double var12, double var14) {
        var8 = numericProfile.doubleValue(var8 / 1.5D);
        var10 = numericProfile.doubleValue(var10 / 1.5D);
        if (var1 != null && var1.length >= var6 * var7) {
            for (int var16 = 0; var16 < var1.length; ++var16) {
                var1[var16] = 0.0D;
            }
        } else {
            var1 = new double[var6 * var7];
        }

        double var21 = numericProfile.doubleValue(1.0D);
        double var18 = numericProfile.doubleValue(1.0D);

        for (int var20 = 0; var20 < this.field_4233_b; ++var20) {
            // BUG: var18 doubles each octave, causing Far Lands precision loss
            this.field_4234_a[var20].func_4157_a(var1, var2, var4, var6, var7,
                    numericProfile.doubleValue(var8 * var18), numericProfile.doubleValue(var10 * var18),
                    numericProfile.doubleValue(0.55D / var21));
            var18 = numericProfile.doubleValue(var18 * var12);
            var21 = numericProfile.doubleValue(var21 * var14);
        }

        return var1;
    }
}
