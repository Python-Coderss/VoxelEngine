package com.voxel.world.beta;

import java.util.Random;

/**
 * Exact port of Beta 1.7.3's MapGenCaves.
 * Worm-like cave carving using sinusoidal paths through 3D space.
 * Preserves ALL bugs including the exact carving thresholds and branch patterns.
 */
public class BetaMapGenCaves {
    protected int field_1306_a = 8;
    protected Random rand = new Random();
    protected BetaNumericProfile numericProfile = BetaNumericProfile.DEFAULT;

    public void setNumericProfile(BetaNumericProfile numericProfile) {
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
    }

    // Block ID constants (Beta 1.7.3 values) - these are used internally
    // and mapped to VoxelEngine IDs by the caller
    private static final int BETA_STONE = 1;
    private static final int BETA_DIRT = 3;
    private static final int BETA_GRASS = 2;
    private static final int BETA_WATER_MOVING = 8;
    private static final int BETA_WATER_STILL = 9;
    private static final int BETA_LAVA_MOVING = 10;
    private static final int BETA_LAVA_STILL = 11;

    /**
     * Entry point matching Beta 1.7.3's func_867_a.
     * @param chunkProvider source of terrain data
     * @param worldSeed world random seed
     * @param var3 chunk X
     * @param var4 chunk Z
     * @param var5 block data array (Beta 1.7.3 format: 16*128*16 bytes, index = x<<11|z<<7|y)
     */
    public void func_867_a(long worldSeed, int var3, int var4, byte[] var5) {
        int var6 = this.field_1306_a;
        this.rand.setSeed(worldSeed);
        long var7 = this.rand.nextLong() / 2L * 2L + 1L;
        long var9 = this.rand.nextLong() / 2L * 2L + 1L;

        for (int var11 = var3 - var6; var11 <= var3 + var6; ++var11) {
            for (int var12 = var4 - var6; var12 <= var4 + var6; ++var12) {
                this.rand.setSeed((long) var11 * var7 + (long) var12 * var9 ^ worldSeed);
                this.func_868_a(var11, var12, var3, var4, var5);
            }
        }
    }

    /**
     * Selects the precision switch for the dominant world-space axis. Cave
     * angles and radii are derived values rather than absolute coordinates,
     * so their ULP context is the largest relevant coordinate.
     */
    private float floatAtDistance(double value, double x, double y, double z) {
        if (Math.abs(y) > Math.abs(x) && Math.abs(y) > Math.abs(z)) {
            return numericProfile.yFloatValueAtDistance(value, y);
        }
        return numericProfile.xzFloatValueAtDistance(value, x, z);
    }

    private double xCoordinate(double value) {
        return numericProfile.xDoubleCoordinate(numericProfile.xFloatCoordinate(value));
    }

    private double yCoordinate(double value) {
        return numericProfile.yDoubleValue(numericProfile.yFloatValue(value));
    }

    private double zCoordinate(double value) {
        return numericProfile.zDoubleCoordinate(numericProfile.zFloatCoordinate(value));
    }

    private float yFloatAtDistance(double value, double y) {
        return numericProfile.yFloatValueAtDistance(value, y);
    }

    protected void func_868_a(int var1, int var2, int var3, int var4, byte[] var5) {
        int var7 = this.rand.nextInt(this.rand.nextInt(this.rand.nextInt(40) + 1) + 1);
        if (this.rand.nextInt(15) != 0) {
            var7 = 0;
        }

        for (int var8 = 0; var8 < var7; ++var8) {
            double var9 = (double) (var1 * 16 + this.rand.nextInt(16));
            double var11 = (double) this.rand.nextInt(this.rand.nextInt(120) + 8);
            double var13 = (double) (var2 * 16 + this.rand.nextInt(16));
            int var15 = 1;
            if (this.rand.nextInt(4) == 0) {
                this.func_870_a(var3, var4, var5, var9, var11, var13);
                var15 += this.rand.nextInt(4);
            }

            for (int var16 = 0; var16 < var15; ++var16) {
                float var17 = floatAtDistance(this.rand.nextFloat() * (float) Math.PI * 2.0F,
                        var9, var11, var13);
                float var18 = floatAtDistance((this.rand.nextFloat() - 0.5F) * 2.0F / 8.0F,
                        var9, var11, var13);
                float var19 = floatAtDistance(this.rand.nextFloat() * 2.0F + this.rand.nextFloat(),
                        var9, var11, var13);
                this.releaseEntitySkin(var3, var4, var5, var9, var11, var13, var19, var17, var18, 0, 0, 0.5D);
            }
        }
    }

    protected void func_870_a(int var1, int var2, byte[] var3, double var4, double var6, double var8) {
        this.releaseEntitySkin(var1, var2, var3, var4, var6, var8,
                1.0F + this.rand.nextFloat() * 6.0F, 0.0F, 0.0F, -1, -1, 0.5D);
    }

    protected void releaseEntitySkin(int var1, int var2, byte[] var3,
                                      double var4, double var6, double var8,
                                      float var10, float var11, float var12,
                                      int var13, int var14, double var15) {
        double var17 = (double) (var1 * 16 + 8);
        double var19 = (double) (var2 * 16 + 8);
        var4 = xCoordinate(var4);
        var8 = zCoordinate(var8);
        float var21 = floatAtDistance(0.0F, var4, var6, var8);
        float var22 = floatAtDistance(0.0F, var4, var6, var8);
        Random var23 = new Random(this.rand.nextLong());
        if (var14 <= 0) {
            int var24 = this.field_1306_a * 16 - 16;
            var14 = var24 - var23.nextInt(var24 / 4);
        }

        boolean var52 = false;
        if (var13 == -1) {
            var13 = var14 / 2;
            var52 = true;
        }

        int var25 = var23.nextInt(var14 / 2) + var14 / 4;
        boolean var26 = var23.nextInt(6) == 0;

        for (; var13 < var14; ++var13) {
            double var27 = numericProfile.xzDoubleValueAtDistance(1.5D
                    + (double) floatAtDistance(Math.sin((float) var13 * (float) Math.PI / (float) var14)
                    * var10 * 1.0F, var4, var6, var8), var4, var8);
            double var29 = var27 * var15;
            float var31 = floatAtDistance(Math.cos(var12), var4, var6, var8);
            float var32 = floatAtDistance(Math.sin(var12), var4, var6, var8);
            var4 = xCoordinate(var4
                    + (double) floatAtDistance(Math.cos(var11) * var31, var4, var6, var8));
            var6 = yCoordinate(var6
                    + (double) yFloatAtDistance(var32, var6));
            var8 = zCoordinate(var8
                    + (double) floatAtDistance(Math.sin(var11) * var31, var4, var6, var8));
            if (var26) {
                var12 = floatAtDistance(var12 * 0.92F, var4, var6, var8);
            } else {
                var12 = floatAtDistance(var12 * 0.7F, var4, var6, var8);
            }
            var12 = floatAtDistance(var12 + var22 * 0.1F, var4, var6, var8);
            var11 = floatAtDistance(var11 + var21 * 0.1F, var4, var6, var8);
            var22 = floatAtDistance(var22 * 0.9F, var4, var6, var8);
            var21 = floatAtDistance(var21 * (12.0F / 16.0F), var4, var6, var8);
            var22 = floatAtDistance(var22
                    + (var23.nextFloat() - var23.nextFloat()) * var23.nextFloat() * 2.0F,
                    var4, var6, var8);
            var21 = floatAtDistance(var21
                    + (var23.nextFloat() - var23.nextFloat()) * var23.nextFloat() * 4.0F,
                    var4, var6, var8);

            if (!var52 && var13 == var25 && var10 > 1.0F) {
                this.releaseEntitySkin(var1, var2, var3, var4, var6, var8,
                        floatAtDistance(var23.nextFloat() * 0.5F + 0.5F, var4, var6, var8),
                        floatAtDistance(var11 - (float) Math.PI * 0.5F, var4, var6, var8),
                        floatAtDistance(var12 / 3.0F, var4, var6, var8), var13, var14, 1.0D);
                this.releaseEntitySkin(var1, var2, var3, var4, var6, var8,
                        floatAtDistance(var23.nextFloat() * 0.5F + 0.5F, var4, var6, var8),
                        floatAtDistance(var11 + (float) Math.PI * 0.5F, var4, var6, var8),
                        floatAtDistance(var12 / 3.0F, var4, var6, var8), var13, var14, 1.0D);
                return;
            }

            if (var52 || var23.nextInt(4) != 0) {
                double var33 = var4 - var17;
                double var35 = var8 - var19;
                double var37 = (double) (var14 - var13);
                double var39 = (double) (var10 + 2.0F + 16.0F);
                if (var33 * var33 + var35 * var35 - var37 * var37 > var39 * var39) {
                    return;
                }

                if (var4 >= var17 - 16.0D - var27 * 2.0D && var8 >= var19 - 16.0D - var27 * 2.0D
                        && var4 <= var17 + 16.0D + var27 * 2.0D && var8 <= var19 + 16.0D + var27 * 2.0D) {
                    int var53 = (int) Math.floor(var4 - var27) - var1 * 16 - 1;
                    int var34 = (int) Math.floor(var4 + var27) - var1 * 16 + 1;
                    int var54 = (int) Math.floor(var6 - var29) - 1;
                    int var36 = (int) Math.floor(var6 + var29) + 1;
                    int var55 = (int) Math.floor(var8 - var27) - var2 * 16 - 1;
                    int var38 = (int) Math.floor(var8 + var27) - var2 * 16 + 1;

                    if (var53 < 0) var53 = 0;
                    if (var34 > 16) var34 = 16;
                    if (var54 < 1) var54 = 1;
                    if (var36 > 120) var36 = 120;
                    if (var55 < 0) var55 = 0;
                    if (var38 > 16) var38 = 16;

                    boolean var56 = false;

                    // Check for water
                    for (int var40 = var53; !var56 && var40 < var34; ++var40) {
                        for (int var41 = var55; !var56 && var41 < var38; ++var41) {
                            for (int var42 = var36 + 1; !var56 && var42 >= var54 - 1; --var42) {
                                int var43 = (var40 * 16 + var41) * 128 + var42;
                                if (var42 >= 0 && var42 < 128) {
                                    if (var3[var43] == BETA_WATER_MOVING || var3[var43] == BETA_WATER_STILL) {
                                        var56 = true;
                                    }
                                    if (var42 != var54 - 1 && var40 != var53 && var40 != var34 - 1
                                            && var41 != var55 && var41 != var38 - 1) {
                                        var42 = var54;
                                    }
                                }
                            }
                        }
                    }

                    if (!var56) {
                        for (int var40 = var53; var40 < var34; ++var40) {
                            double var57 = ((double) (var40 + var1 * 16) + 0.5D - var4) / var27;

                            for (int var43 = var55; var43 < var38; ++var43) {
                                double var44 = ((double) (var43 + var2 * 16) + 0.5D - var8) / var27;
                                int var46 = (var40 * 16 + var43) * 128 + var36;
                                boolean var47 = false;

                                if (var57 * var57 + var44 * var44 < 1.0D) {
                                    for (int var48 = var36 - 1; var48 >= var54; --var48) {
                                        double var49 = ((double) var48 + 0.5D - var6) / var29;
                                        if (var49 > -0.7D && var57 * var57 + var49 * var49 + var44 * var44 < 1.0D) {
                                            byte var51 = var3[var46];
                                            if (var51 == BETA_GRASS) {
                                                var47 = true;
                                            }
                                            if (var51 == BETA_STONE || var51 == BETA_DIRT || var51 == BETA_GRASS) {
                                                if (var48 < 10) {
                                                    var3[var46] = (byte) BETA_LAVA_MOVING;
                                                } else {
                                                    var3[var46] = 0; // air
                                                    if (var47 && var3[var46 - 1] == BETA_DIRT) {
                                                        var3[var46 - 1] = (byte) BETA_GRASS;
                                                    }
                                                }
                                            }
                                        }
                                        --var46;
                                    }
                                }
                            }
                        }

                        if (var52) {
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Surface-cave pass: extra worm caves biased toward the upper terrain band
     * (y ≈ 16-71) with wide radii and a flatter pitch so they punch through
     * hillsides and cliff faces, giving the surface visible cave mouths.
     * Runs in addition to the faithful Beta 1.7.3 pass (func_867_a) and writes
     * into the same Beta-format block array.
     */
    public void generateSurfaceCaves(long worldSeed, int cx, int cz, byte[] blocks) {
        int range = this.field_1306_a;
        this.rand.setSeed(worldSeed);
        long a = this.rand.nextLong() / 2L * 2L + 1L;
        long b = this.rand.nextLong() / 2L * 2L + 1L;

        for (int x = cx - range; x <= cx + range; ++x) {
            for (int z = cz - range; z <= cz + range; ++z) {
                this.rand.setSeed((long) x * a + (long) z * b ^ worldSeed);
                // ~1/100 of columns get surface caves (classic pass gives
                // 1/15 underground tunnels; surface mouths should be rare).
                if (this.rand.nextInt(100) != 0) continue;
                int count = 1 + this.rand.nextInt(2);
                for (int i = 0; i < count; ++i) {
                    double sx = x * 16 + this.rand.nextInt(16);
                    double sy = 16 + this.rand.nextInt(56);
                    double sz = z * 16 + this.rand.nextInt(16);
                    float yaw = floatAtDistance(this.rand.nextFloat() * (float) Math.PI * 2.0F, sx, sy, sz);
                    float pitch = floatAtDistance((this.rand.nextFloat() - 0.5F) * 2.0F / 6.0F, sx, sy, sz);
                    float radius = floatAtDistance(2.0F + this.rand.nextFloat() * 3.0F, sx, sy, sz);
                    this.releaseEntitySkin(cx, cz, blocks, sx, sy, sz, radius, yaw, pitch, 0, 0, 0.6D);
                }
            }
        }
    }

    /** Accessor for the noise field range. */
    public int getField1306a() {
        return field_1306_a;
    }
}
