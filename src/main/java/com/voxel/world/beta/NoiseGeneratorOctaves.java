package com.voxel.world.beta;

import java.util.Random;

/** Faithful port of Beta 1.8.1's NoiseGeneratorOctaves. */
public class NoiseGeneratorOctaves {
    private NoiseGeneratorPerlin[] generatorCollection;
    private int octaves;

    public NoiseGeneratorOctaves(Random rand, int octaves) {
        this.octaves = octaves;
        this.generatorCollection = new NoiseGeneratorPerlin[octaves];
        for (int i = 0; i < octaves; ++i) {
            this.generatorCollection[i] = new NoiseGeneratorPerlin(rand);
        }
    }

    /** 2D noise (func_806_a). */
    public double func_806_a(double x, double y) {
        double out = 0.0D;
        double amp = 1.0D;
        for (int i = 0; i < this.octaves; ++i) {
            out += this.generatorCollection[i].func_801_a(x * amp, y * amp) / amp;
            amp /= 2.0D;
        }
        return out;
    }

    /** 3D noise array (generateNoiseOctaves). */
    public double[] generateNoiseOctaves(double[] out, int x, int y, int z,
                                         int xSize, int ySize, int zSize,
                                         double xScale, double yScale, double zScale) {
        if (out == null) {
            out = new double[xSize * ySize * zSize];
        } else {
            for (int i = 0; i < out.length; ++i) out[i] = 0.0D;
        }

        double amp = 1.0D;
        for (int i = 0; i < this.octaves; ++i) {
            double px = (double) x * amp * xScale;
            double py = (double) y * amp * yScale;
            double pz = (double) z * amp * zScale;
            long ix = BetaMathHelper.func_35599_c(px);
            long iz = BetaMathHelper.func_35599_c(pz);
            px -= (double) ix;
            pz -= (double) iz;
            ix %= 16777216L;
            iz %= 16777216L;
            px += (double) ix;
            pz += (double) iz;
            this.generatorCollection[i].func_805_a(out, px, py, pz, xSize, ySize, zSize,
                    xScale * amp, yScale * amp, zScale * amp, amp);
            amp /= 2.0D;
        }
        return out;
    }

    /** 2D noise array (func_4109_a) — y is fixed at 10. */
    public double[] func_4109_a(double[] out, int x, int z, int xSize, int zSize,
                                double xScale, double zScale, double amp) {
        return this.generateNoiseOctaves(out, x, 10, z, xSize, 1, zSize, xScale, 1.0D, zScale);
    }
}
