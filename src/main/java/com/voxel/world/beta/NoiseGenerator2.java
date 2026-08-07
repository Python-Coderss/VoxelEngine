package com.voxel.world.beta;

import java.util.Random;

/**
 * Exact port of Beta 1.7.3's NoiseGenerator2.
 * Simplex-like 2D noise used for temperature/humidity biome maps.
 * Preserves ALL bugs.
 */
public class NoiseGenerator2 {
    private BetaNumericProfile numericProfile;
    private static int[][] field_4296_d = new int[][]{
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };
    private int[] field_4295_e;
    public double field_4292_a;
    public double field_4291_b;
    public double field_4297_c;
    private static final double field_4294_f = 0.5D * (Math.sqrt(3.0D) - 1.0D);
    private static final double field_4293_g = (3.0D - Math.sqrt(3.0D)) / 6.0D;

    public NoiseGenerator2() {
        this(new Random(), BetaNumericProfile.DEFAULT);
    }

    public NoiseGenerator2(Random var1) {
        this(var1, BetaNumericProfile.DEFAULT);
    }

    public NoiseGenerator2(Random var1, BetaNumericProfile numericProfile) {
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        this.field_4295_e = new int[512];
        this.field_4292_a = var1.nextDouble() * 256.0D;
        this.field_4291_b = var1.nextDouble() * 256.0D;
        this.field_4297_c = var1.nextDouble() * 256.0D;

        int var2;
        for (var2 = 0; var2 < 256; this.field_4295_e[var2] = this.numericProfile.shortValue(var2++)) {
        }

        for (var2 = 0; var2 < 256; ++var2) {
            int var3 = this.numericProfile.intValue(var1.nextInt(256 - var2) + var2) & 255;
            int var4 = this.numericProfile.shortValue(this.field_4295_e[var2]);
            this.field_4295_e[var2] = this.numericProfile.shortValue(this.field_4295_e[var3]);
            this.field_4295_e[var3] = this.numericProfile.shortValue(var4);
            this.field_4295_e[var2 + 256] = this.numericProfile.shortValue(this.field_4295_e[var2]);
        }
    }

    private int wrap(double value) {
        return numericProfile.intValue(value);
    }

    private int permutation(int index) {
        int wrappedIndex = numericProfile.intValue(index) & 511;
        return numericProfile.shortValue(field_4295_e[wrappedIndex]) & 255;
    }

    private double floatAtDistance(double value, double x, double z) {
        return numericProfile.xzFloatValueAtDistance(value, x, z);
    }

    private static double func_4156_a(int[] var0, double var1, double var3) {
        return (double) var0[0] * var1 + (double) var0[1] * var3;
    }

    public void setNumericProfile(BetaNumericProfile numericProfile) {
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
    }

    public void func_4157_a(double[] var1, double var2, double var4, int var6, int var7,
                             double var8, double var10, double var12) {
        int var14 = 0;
        var8 = numericProfile.doubleValue(var8);
        var10 = numericProfile.doubleValue(var10);
        var12 = numericProfile.doubleValue(var12);

        for (int var15 = 0; var15 < var6; ++var15) {                double worldX = var2 + (double) var15;
                double var16 = numericProfile.doubleValue(
                        numericProfile.xFloatCoordinate(worldX * var8 + this.field_4292_a));

            for (int var18 = 0; var18 < var7; ++var18) {
                double worldZ = var4 + (double) var18;
                double var19 = numericProfile.doubleValue(
                        numericProfile.zFloatCoordinate(worldZ * var10 + this.field_4291_b));
                double var27 = floatAtDistance((var16 + var19) * field_4294_f, worldX, worldZ);
                int var29 = wrap(var16 + var27);
                int var30 = wrap(var19 + var27);
                double var31 = (double) (var29 + var30) * field_4293_g;
                double var33 = (double) var29 - var31;
                double var35 = (double) var30 - var31;
                double var37 = floatAtDistance(var16 - var33, worldX, worldZ);
                double var39 = floatAtDistance(var19 - var35, worldX, worldZ);
                byte var41;
                byte var42;
                if (var37 > var39) {
                    var41 = 1;
                    var42 = 0;
                } else {
                    var41 = 0;
                    var42 = 1;
                }

                double var43 = floatAtDistance(var37 - (double) var41 + field_4293_g, worldX, worldZ);
                double var45 = floatAtDistance(var39 - (double) var42 + field_4293_g, worldX, worldZ);
                double var47 = floatAtDistance(var37 - 1.0D + 2.0D * field_4293_g, worldX, worldZ);
                double var49 = floatAtDistance(var39 - 1.0D + 2.0D * field_4293_g, worldX, worldZ);
                int var51 = var29 & 255;
                int var52 = var30 & 255;
                int var53 = permutation(var51 + permutation(var52)) % 12;
                int var54 = permutation(var51 + var41 + permutation(var52 + var42)) % 12;
                int var55 = permutation(var51 + 1 + permutation(var52 + 1)) % 12;
                double var56 = 0.5D - var37 * var37 - var39 * var39;
                double var21;
                if (var56 < 0.0D) {
                    var21 = 0.0D;
                } else {
                    var56 *= var56;
                    var21 = var56 * var56 * func_4156_a(field_4296_d[var53], var37, var39);
                }

                double var58 = 0.5D - var43 * var43 - var45 * var45;
                double var23;
                if (var58 < 0.0D) {
                    var23 = 0.0D;
                } else {
                    var58 *= var58;
                    var23 = var58 * var58 * func_4156_a(field_4296_d[var54], var43, var45);
                }

                double var60 = 0.5D - var47 * var47 - var49 * var49;
                double var25;
                if (var60 < 0.0D) {
                    var25 = 0.0D;
                } else {
                    var60 *= var60;
                    var25 = var60 * var60 * func_4156_a(field_4296_d[var55], var47, var49);
                }

                int var10001 = var14++;
                var1[var10001] += numericProfile.doubleValue(70.0D * (var21 + var23 + var25) * var12);
            }
        }
    }
}
