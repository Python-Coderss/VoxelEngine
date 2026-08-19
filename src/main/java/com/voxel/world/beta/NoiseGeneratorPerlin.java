package com.voxel.world.beta;

import java.util.Random;

/** Faithful port of Beta 1.8.1's NoiseGeneratorPerlin. */
public class NoiseGeneratorPerlin {
    private final int[] permutations;
    public final double xCoord;
    public final double yCoord;
    public final double zCoord;

    public NoiseGeneratorPerlin(Random rand) {
        this.permutations = new int[512];
        this.xCoord = rand.nextDouble() * 256.0D;
        this.yCoord = rand.nextDouble() * 256.0D;
        this.zCoord = rand.nextDouble() * 256.0D;

        int i;
        for (i = 0; i < 256; this.permutations[i] = i++) {
        }

        for (i = 0; i < 256; ++i) {
            int j = rand.nextInt(256 - i) + i;
            int k = this.permutations[i];
            this.permutations[i] = this.permutations[j];
            this.permutations[j] = k;
            this.permutations[i + 256] = this.permutations[i];
        }
    }

    public double generateNoise(double x, double y, double z) {
        double x0 = x + this.xCoord;
        double y0 = y + this.yCoord;
        double z0 = z + this.zCoord;
        int ix = (int) x0;
        int iy = (int) y0;
        int iz = (int) z0;
        if (x0 < (double) ix) --ix;
        if (y0 < (double) iy) --iy;
        if (z0 < (double) iz) --iz;

        int a = ix & 255;
        int b = iy & 255;
        int c = iz & 255;
        x0 -= (double) ix;
        y0 -= (double) iy;
        z0 -= (double) iz;
        double u = x0 * x0 * x0 * (x0 * (x0 * 6.0D - 15.0D) + 10.0D);
        double v = y0 * y0 * y0 * (y0 * (y0 * 6.0D - 15.0D) + 10.0D);
        double w = z0 * z0 * z0 * (z0 * (z0 * 6.0D - 15.0D) + 10.0D);
        int i1 = this.permutations[a] + b;
        int i2 = this.permutations[i1] + c;
        int i3 = this.permutations[i1 + 1] + c;
        int i4 = this.permutations[a + 1] + b;
        int i5 = this.permutations[i4] + c;
        int i6 = this.permutations[i4 + 1] + c;
        return this.lerp(w,
                this.lerp(v,
                        this.lerp(u, this.grad(this.permutations[i2], x0, y0, z0),
                                this.grad(this.permutations[i5], x0 - 1.0D, y0, z0)),
                        this.lerp(u, this.grad(this.permutations[i3], x0, y0 - 1.0D, z0),
                                this.grad(this.permutations[i6], x0 - 1.0D, y0 - 1.0D, z0))),
                this.lerp(v,
                        this.lerp(u, this.grad(this.permutations[i2 + 1], x0, y0, z0 - 1.0D),
                                this.grad(this.permutations[i5 + 1], x0 - 1.0D, y0, z0 - 1.0D)),
                        this.lerp(u, this.grad(this.permutations[i3 + 1], x0, y0 - 1.0D, z0 - 1.0D),
                                this.grad(this.permutations[i6 + 1], x0 - 1.0D, y0 - 1.0D, z0 - 1.0D))));
    }

    public final double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    public final double func_4110_a(int i, double a, double b) {
        int j = i & 15;
        double c = (double) (1 - ((j & 8) >> 3)) * a;
        double d = j < 4 ? 0.0D : (j != 12 && j != 14 ? b : a);
        return ((j & 1) == 0 ? c : -c) + ((j & 2) == 0 ? d : -d);
    }

    public final double grad(int i, double a, double b, double c) {
        int j = i & 15;
        double d = j < 8 ? a : b;
        double e = j < 4 ? b : (j != 12 && j != 14 ? c : a);
        return ((j & 1) == 0 ? d : -d) + ((j & 2) == 0 ? e : -e);
    }

    /** 2D helper: second argument is treated as the Y coordinate (as Beta does). */
    public double func_801_a(double x, double y) {
        return this.generateNoise(x, y, 0.0D);
    }

    public void func_805_a(double[] out, double x, double y, double z,
                           int xSize, int ySize, int zSize,
                           double xScale, double yScale, double zScale, double amp) {
        if (ySize == 1) {
            int idx = 0;
            double invAmp = 1.0D / amp;

            for (int i = 0; i < xSize; ++i) {
                double px = x + (double) i * xScale + this.xCoord;
                int ix = (int) px;
                if (px < (double) ix) --ix;
                int a = ix & 255;
                px -= (double) ix;
                double u = px * px * px * (px * (px * 6.0D - 15.0D) + 10.0D);

                for (int k = 0; k < zSize; ++k) {
                    double pz = z + (double) k * zScale + this.zCoord;
                    int iz = (int) pz;
                    if (pz < (double) iz) --iz;
                    int c = iz & 255;
                    pz -= (double) iz;
                    double w = pz * pz * pz * (pz * (pz * 6.0D - 15.0D) + 10.0D);
                    int i1 = this.permutations[a] + 0;
                    int i2 = this.permutations[i1] + c;
                    int i3 = this.permutations[a + 1] + 0;
                    int i4 = this.permutations[i3] + c;
                    double n1 = this.lerp(u, this.func_4110_a(this.permutations[i2], px, pz),
                            this.grad(this.permutations[i4], px - 1.0D, 0.0D, pz));
                    double n2 = this.lerp(u, this.grad(this.permutations[i2 + 1], px, 0.0D, pz - 1.0D),
                            this.grad(this.permutations[i4 + 1], px - 1.0D, 0.0D, pz - 1.0D));
                    out[idx++] += this.lerp(w, n1, n2) * invAmp;
                }
            }
        } else {
            int idx = 0;
            double invAmp = 1.0D / amp;
            int lastY = -1;
            double n00 = 0.0D, n10 = 0.0D, n01 = 0.0D, n11 = 0.0D;

            for (int i = 0; i < xSize; ++i) {
                double px = x + (double) i * xScale + this.xCoord;
                int ix = (int) px;
                if (px < (double) ix) --ix;
                int a = ix & 255;
                px -= (double) ix;
                double u = px * px * px * (px * (px * 6.0D - 15.0D) + 10.0D);

                for (int k = 0; k < zSize; ++k) {
                    double pz = z + (double) k * zScale + this.zCoord;
                    int iz = (int) pz;
                    if (pz < (double) iz) --iz;
                    int c = iz & 255;
                    pz -= (double) iz;
                    double w = pz * pz * pz * (pz * (pz * 6.0D - 15.0D) + 10.0D);

                    for (int j = 0; j < ySize; ++j) {
                        double py = y + (double) j * yScale + this.yCoord;
                        int iy = (int) py;
                        if (py < (double) iy) --iy;
                        int b = iy & 255;
                        py -= (double) iy;
                        double v = py * py * py * (py * (py * 6.0D - 15.0D) + 10.0D);
                        if (j == 0 || b != lastY) {
                            lastY = b;
                            int j1 = this.permutations[a] + b;
                            int j2 = this.permutations[j1] + c;
                            int j3 = this.permutations[j1 + 1] + c;
                            int j4 = this.permutations[a + 1] + b;
                            int j5 = this.permutations[j4] + c;
                            int j6 = this.permutations[j4 + 1] + c;
                            n00 = this.lerp(u, this.grad(this.permutations[j2], px, py, pz),
                                    this.grad(this.permutations[j5], px - 1.0D, py, pz));
                            n10 = this.lerp(u, this.grad(this.permutations[j3], px, py - 1.0D, pz),
                                    this.grad(this.permutations[j6], px - 1.0D, py - 1.0D, pz));
                            n01 = this.lerp(u,
                                    this.grad(this.permutations[j2 + 1], px, py, pz - 1.0D),
                                    this.grad(this.permutations[j5 + 1], px - 1.0D, py, pz - 1.0D));
                            n11 = this.lerp(u,
                                    this.grad(this.permutations[j3 + 1], px, py - 1.0D, pz - 1.0D),
                                    this.grad(this.permutations[j6 + 1], px - 1.0D, py - 1.0D, pz - 1.0D));
                        }
                        double na = this.lerp(v, n00, n10);
                        double nb = this.lerp(v, n01, n11);
                        out[idx++] += this.lerp(w, na, nb) * invAmp;
                    }
                }
            }
        }
    }
}
