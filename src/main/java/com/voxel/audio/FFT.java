package com.voxel.audio;

/** Iterative radix-2 complex FFT (Cooley–Tukey), power-of-two sizes only. */
public class FFT {
    private final int n;
    private final double[] cosTable;
    private final double[] sinTable;

    public FFT(int n) {
        if (n <= 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a power of two: " + n);
        }
        this.n = n;
        cosTable = new double[n / 2];
        sinTable = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = 2 * Math.PI * i / n;
            cosTable[i] = Math.cos(a);
            sinTable[i] = Math.sin(a);
        }
    }

    public int size() {
        return n;
    }

    /** In-place FFT of interleaved re/im arrays. Inverse normalizes by 1/n. */
    public void transform(double[] re, double[] im, boolean inverse) {
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                double ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    double c = cosTable[k * step];
                    double s = inverse ? -sinTable[k * step] : sinTable[k * step];
                    int a = i + k;
                    int b = i + k + half;
                    double ar = re[a], ai = im[a];
                    double br = re[b], bi = im[b];
                    double xr = c * br - s * bi;
                    double xi = c * bi + s * br;
                    re[a] = ar + xr;
                    im[a] = ai + xi;
                    re[b] = ar - xr;
                    im[b] = ai - xi;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    /** Magnitude spectrum of a real signal, zero-padded to power-of-two. */
    public double[] magnitudeSpectrum(double[] x) {
        int len = Math.min(n, x.length);
        double[] re = new double[n];
        double[] im = new double[n];
        System.arraycopy(x, 0, re, 0, len);
        transform(re, im, false);
        double[] mag = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            mag[i] = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
        }
        return mag;
    }
}
