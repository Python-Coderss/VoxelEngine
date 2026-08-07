package com.voxel.world.beta;

import java.util.Random;

/**
 * Exact port of Beta 1.7.3's NoiseGeneratorPerlin.
 * Preserves ALL bugs including the Far Lands floating-point precision issues
 * and the permutation table wrapping at extreme coordinates.
 */
public class NoiseGeneratorPerlin {
    private int[] permutations;
    /** Cached profile-aware permutation results for the normal 10+ bit path. */
    private volatile int[] permutationCache;
    private volatile BetaNumericProfile numericProfile;
    private volatile boolean permutationCacheEnabled;
    public double xCoord;
    public double yCoord;
    public double zCoord;

    public NoiseGeneratorPerlin() {
        this(new Random(), BetaNumericProfile.DEFAULT);
    }

    public NoiseGeneratorPerlin(Random var1) {
        this(var1, BetaNumericProfile.DEFAULT);
    }

    public NoiseGeneratorPerlin(Random var1, BetaNumericProfile numericProfile) {
        this.permutations = new int[512];
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        this.xCoord = var1.nextDouble() * 256.0D;
        this.yCoord = var1.nextDouble() * 256.0D;
        this.zCoord = var1.nextDouble() * 256.0D;

        int var2;
        for (var2 = 0; var2 < 256; this.permutations[var2] = this.numericProfile.shortValue(var2++)) {
        }

        for (var2 = 0; var2 < 256; ++var2) {
            int var3 = this.numericProfile.intValue(var1.nextInt(256 - var2) + var2) & 255;
            int var4 = numericProfile.shortValue(this.permutations[var2]);
            this.permutations[var2] = this.numericProfile.shortValue(this.permutations[var3]);
            this.permutations[var3] = this.numericProfile.shortValue(var4);
            this.permutations[var2 + 256] = this.numericProfile.shortValue(this.permutations[var2]);
        }
        rebuildPermutationCache();
        permutationCacheEnabled = this.numericProfile.intBits() >= 10;
    }

    public synchronized double generateNoise(double var1, double var3, double var5) {
        return generateNoise(var1, var3, var5, true);
    }

    private double generateNoise(double var1, double var3, double var5, boolean secondCoordinateIsY) {
        double var7 = d(var1 + this.xCoord);
        double var9 = d(var3 + this.yCoord);
        double var11 = d(var5 + this.zCoord);
        int var13 = numericProfile.intValue(var7);
        int var14 = secondCoordinateIsY
                ? numericProfile.yIntValue(var9)
                : numericProfile.intValue(var9);
        int var15 = numericProfile.intValue(var11);
        if (var7 < (double) var13) {
            var13 = numericProfile.intValue((long) var13 - 1L);
        }
        if (var9 < (double) var14) {
            var14 = secondCoordinateIsY
                    ? numericProfile.yIntValue((long) var14 - 1L)
                    : numericProfile.intValue((long) var14 - 1L);
        }
        if (var11 < (double) var15) {
            var15 = numericProfile.intValue((long) var15 - 1L);
        }
        int var16 = var13 & 255;
        int var17 = var14 & 255;
        int var18 = var15 & 255;
        var7 = d(var7 - (double) var13);
        var9 = d(var9 - (double) var14);
        var11 = d(var11 - (double) var15);
        double var19 = d(var7 * var7 * var7 * (var7 * (var7 * 6.0D - 15.0D) + 10.0D));
        double var21 = d(var9 * var9 * var9 * (var9 * (var9 * 6.0D - 15.0D) + 10.0D));
        double var23 = d(var11 * var11 * var11 * (var11 * (var11 * 6.0D - 15.0D) + 10.0D));
        int var25 = numericProfile.intValue(permutation(var16) + var17);
        int var26 = numericProfile.intValue(permutation(var25) + var18);
        int var27 = numericProfile.intValue(permutation(var25 + 1) + var18);
        int var28 = numericProfile.intValue(permutation(var16 + 1) + var17);
        int var29 = numericProfile.intValue(permutation(var28) + var18);
        int var30 = numericProfile.intValue(permutation(var28 + 1) + var18);
        return this.lerp(var23,
                this.lerp(var21,
                        this.lerp(var19, this.grad(permutation(var26), var7, var9, var11),
                                this.grad(permutation(var29), var7 - 1.0D, var9, var11)),
                        this.lerp(var19, this.grad(permutation(var27), var7, var9 - 1.0D, var11),
                                this.grad(permutation(var30), var7 - 1.0D, var9 - 1.0D, var11))),
                this.lerp(var21,
                        this.lerp(var19, this.grad(permutation(var26 + 1), var7, var9, var11 - 1.0D),
                                this.grad(permutation(var29 + 1), var7 - 1.0D, var9, var11 - 1.0D)),
                        this.lerp(var19, this.grad(permutation(var27 + 1), var7, var9 - 1.0D, var11 - 1.0D),
                                this.grad(permutation(var30 + 1), var7 - 1.0D, var9 - 1.0D, var11 - 1.0D))));
    }

    public final double lerp(double var1, double var3, double var5) {
        return d(var3 + var1 * (var5 - var3));
    }

    public synchronized void setNumericProfile(BetaNumericProfile numericProfile) {
        // Disable the cache first so a concurrent sampler falls back to the
        // profile-aware path while the new state is being published.
        permutationCacheEnabled = false;
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        rebuildPermutationCache();
        permutationCacheEnabled = this.numericProfile.intBits() >= 10;
    }

    public BetaNumericProfile getNumericProfile() {
        return numericProfile;
    }

    private double d(double value) {
        return numericProfile.doubleValue(value);
    }

    private int permutation(int index) {
        // All normal Beta profiles use at least 10 integer bits, so the
        // profile-aware index is already equivalent to index & 511. Keep the
        // old conversion for narrow custom profiles to preserve their exact
        // wrapping behavior.
        if (permutationCacheEnabled) {
            return permutationCache[index & 511];
        }
        int wrappedIndex = numericProfile.intValue(index) & 511;
        return numericProfile.shortValue(permutations[wrappedIndex]) & 255;
    }

    private void rebuildPermutationCache() {
        int[] cache = new int[512];
        for (int index = 0; index < cache.length; ++index) {
            int wrappedIndex = numericProfile.intValue(index) & 511;
            cache[index] = numericProfile.shortValue(permutations[wrappedIndex]) & 255;
        }
        this.permutationCache = cache;
    }

    public final double func_4110_a(int var1, double var2, double var4) {
        int var6 = var1 & 15;
        double var7 = (double) (1 - ((var6 & 8) >> 3)) * var2;
        double var9 = var6 < 4 ? 0.0D : (var6 != 12 && var6 != 14 ? var4 : var2);
        return d(((var6 & 1) == 0 ? var7 : -var7) + ((var6 & 2) == 0 ? var9 : -var9));
    }

    public final double grad(int var1, double var2, double var4, double var6) {
        int var8 = var1 & 15;
        double var9 = var8 < 8 ? var2 : var4;
        double var11 = var8 < 4 ? var4 : (var8 != 12 && var8 != 14 ? var6 : var2);
        return d(((var8 & 1) == 0 ? var9 : -var9) + ((var8 & 2) == 0 ? var11 : -var11));
    }

    public synchronized double func_801_a(double var1, double var3) {
        // This legacy 2D helper treats its second argument as Z, not Y.
        return this.generateNoise(var1, var3, 0.0D, false);
    }

    public synchronized void func_805_a(double[] var1, double var2, double var4, double var6, int var8, int var9, int var10,
                           double var11, double var13, double var15, double var17) {
        int var10001;
        int var19;
        int var22;
        double var31;
        double var35;
        int var37;
        double var38;
        int var40;
        int var41;
        double var42;
        int var75;
        if (var9 == 1) {
            boolean var64 = false;
            boolean var65 = false;
            boolean var21 = false;
            boolean var68 = false;
            double var70 = 0.0D;
            double var73 = 0.0D;
            var75 = 0;
            double var77 = 1.0D / var17;

            for (int var30 = 0; var30 < var8; ++var30) {
                var31 = d((var2 + (double) var30) * var11 + this.xCoord);
                int var78 = numericProfile.intValue(var31);
                if (var31 < (double) var78) {
                    var78 = numericProfile.intValue((long) var78 - 1L);
                }
                int var34 = var78 & 255;
                var31 -= (double) var78;
                var35 = d(var31 * var31 * var31 * (var31 * (var31 * 6.0D - 15.0D) + 10.0D));

                for (var37 = 0; var37 < var10; ++var37) {
                    var38 = d((var6 + (double) var37) * var15 + this.zCoord);
                    var40 = numericProfile.intValue(var38);
                    if (var38 < (double) var40) {
                        var40 = numericProfile.intValue((long) var40 - 1L);
                    }
                    var41 = var40 & 255;
                    var38 -= (double) var40;
                    var42 = d(var38 * var38 * var38 * (var38 * (var38 * 6.0D - 15.0D) + 10.0D));
                    var19 = permutation(var34) + 0;
                    int var66 = numericProfile.intValue(permutation(var19) + var41);
                    int var67 = numericProfile.intValue(permutation(var34 + 1) + 0);
                    var22 = numericProfile.intValue(permutation(var67) + var41);
                    var70 = this.lerp(var35, this.func_4110_a(permutation(var66), var31, var38),
                            this.grad(permutation(var22), var31 - 1.0D, 0.0D, var38));
                    var73 = this.lerp(var35, this.grad(permutation(var66 + 1), var31, 0.0D, var38 - 1.0D),
                            this.grad(permutation(var22 + 1), var31 - 1.0D, 0.0D, var38 - 1.0D));
                    double var79 = this.lerp(var42, var70, var73);
                    var10001 = var75++;
                    var1[var10001] += var79 * var77;
                }
            }
        } else {
            var19 = 0;
            double var20 = 1.0D / var17;
            var22 = -1;
            boolean var23 = false;
            boolean var24 = false;
            boolean var25 = false;
            boolean var26 = false;
            boolean var27 = false;
            boolean var28 = false;
            double var29 = 0.0D;
            var31 = 0.0D;
            double var33 = 0.0D;
            var35 = 0.0D;

            for (var37 = 0; var37 < var8; ++var37) {
                var38 = d((var2 + (double) var37) * var11 + this.xCoord);
                var40 = numericProfile.intValue(var38);
                if (var38 < (double) var40) {
                    var40 = numericProfile.intValue((long) var40 - 1L);
                }
                var41 = var40 & 255;
                var38 -= (double) var40;
                var42 = d(var38 * var38 * var38 * (var38 * (var38 * 6.0D - 15.0D) + 10.0D));

                for (int var44 = 0; var44 < var10; ++var44) {
                    double var45 = d((var6 + (double) var44) * var15 + this.zCoord);
                    int var47 = numericProfile.intValue(var45);
                    if (var45 < (double) var47) {
                        var47 = numericProfile.intValue((long) var47 - 1L);
                    }
                    int var48 = var47 & 255;
                    var45 -= (double) var47;
                    double var49 = d(var45 * var45 * var45 * (var45 * (var45 * 6.0D - 15.0D) + 10.0D));

                    for (int var51 = 0; var51 < var9; ++var51) {
                        double var52 = d((var4 + (double) var51) * var13 + this.yCoord);
                        int var54 = numericProfile.yIntValue(var52);
                        if (var52 < (double) var54) {
                            var54 = numericProfile.yIntValue((long) var54 - 1L);
                        }
                        int var55 = var54 & 255;
                        var52 -= (double) var54;
                        double var56 = d(var52 * var52 * var52 * (var52 * (var52 * 6.0D - 15.0D) + 10.0D));
                        if (var51 == 0 || var55 != var22) {
                            var22 = var55;
                            int var69 = numericProfile.intValue(permutation(var41) + var55);
                            int var71 = numericProfile.intValue(permutation(var69) + var48);
                            int var72 = numericProfile.intValue(permutation(var69 + 1) + var48);
                            int var74 = numericProfile.intValue(permutation(var41 + 1) + var55);
                            var75 = numericProfile.intValue(permutation(var74) + var48);
                            int var76 = numericProfile.intValue(permutation(var74 + 1) + var48);
                            var29 = this.lerp(var42, this.grad(permutation(var71), var38, var52, var45),
                                    this.grad(permutation(var75), var38 - 1.0D, var52, var45));
                            var31 = this.lerp(var42, this.grad(permutation(var72), var38, var52 - 1.0D, var45),
                                    this.grad(permutation(var76), var38 - 1.0D, var52 - 1.0D, var45));
                            var33 = this.lerp(var42,
                                    this.grad(permutation(var71 + 1), var38, var52, var45 - 1.0D),
                                    this.grad(permutation(var75 + 1), var38 - 1.0D, var52, var45 - 1.0D));
                            var35 = this.lerp(var42,
                                    this.grad(permutation(var72 + 1), var38, var52 - 1.0D, var45 - 1.0D),
                                    this.grad(permutation(var76 + 1), var38 - 1.0D, var52 - 1.0D,
                                            var45 - 1.0D));
                        }
                        double var58 = this.lerp(var56, var29, var31);
                        double var60 = this.lerp(var56, var33, var35);
                        double var62 = this.lerp(var49, var58, var60);
                        var10001 = var19++;
                        var1[var10001] += var62 * var20;
                    }
                }
            }
        }
    }
}
