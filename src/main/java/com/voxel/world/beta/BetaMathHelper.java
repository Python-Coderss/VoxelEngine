package com.voxel.world.beta;

/**
 * Faithful port of Beta 1.8.1's MathHelper. The 16-bit sin/cos lookup table is
 * load-bearing: the cave, ravine, ore-vein and big-tree paths depend on its
 * exact quantization to match the vanilla world for a given seed.
 */
public final class BetaMathHelper {
    private BetaMathHelper() {}

    private static final float[] SIN_TABLE = new float[65536];

    public static float sin(float v) {
        return SIN_TABLE[(int) (v * 10430.378F) & 0xFFFF];
    }

    public static float cos(float v) {
        return SIN_TABLE[(int) (v * 10430.378F + 16384.0F) & 0xFFFF];
    }

    public static float sqrt_float(float v) {
        return (float) Math.sqrt((double) v);
    }

    public static float sqrt_double(double v) {
        return (float) Math.sqrt(v);
    }

    public static int floor_float(float v) {
        int i = (int) v;
        return v < (float) i ? i - 1 : i;
    }

    public static int floor_double(double v) {
        int i = (int) v;
        return v < (double) i ? i - 1 : i;
    }

    public static long floorLong(double v) {
        long l = (long) v;
        return v < (double) l ? l - 1L : l;
    }

    /** Beta 1.8.1's MathHelper.func_35599_c — floor to long (used by NoiseGeneratorOctaves). */
    public static long func_35599_c(double v) {
        long l = (long) v;
        return v < (double) l ? l - 1L : l;
    }

    public static float abs(float v) {
        return v >= 0.0F ? v : -v;
    }

    static {
        for (int i = 0; i < 65536; ++i) {
            SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0D / 65536.0D);
        }
    }
}
