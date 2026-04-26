package com.voxel.utils;

import java.util.Random;

/**
 * A simple implementation of Perlin Noise.
 * Perlin Noise is used to generate natural-looking procedural patterns,
 * like terrain height, temperature variations, or clouds.
 */
public class PerlinNoise {
    // Permutation table used to provide pseudo-random gradients.
    private final int[] p = new int[512];

    /**
     * Initializes the noise generator with a specific seed.
     * @param seed The random seed to ensure consistent noise generation.
     */
    public PerlinNoise(long seed) {
        Random rand = new Random(seed);
        // Fill the first 256 entries with values 0-255.
        for (int i = 0; i < 256; i++) p[i] = i;
        // Shuffle the values to create a random permutation.
        for (int i = 0; i < 256; i++) {
            int j = rand.nextInt(256);
            int temp = p[i];
            p[i] = p[j];
            p[j] = temp;
        }
        // Duplicate the table to avoid index overflow during lookup.
        for (int i = 0; i < 256; i++) p[256 + i] = p[i];
    }

    /**
     * Calculates the noise value at a 2D coordinate (X, Y).
     * @param x The X coordinate.
     * @param y The Y coordinate.
     * @param scale A multiplier for the input coordinates (smaller = smoother/larger features).
     * @return A noise value, typically in the range [-1.0, 1.0].
     */
    public float noise(float x, float y, float scale) {
        // Apply the scale to the input coordinates.
        x *= scale;
        y *= scale;

        // Find the unit square that contains the point.
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;

        // Calculate the relative X, Y of the point within that square.
        x -= Math.floor(x);
        y -= Math.floor(y);

        // Compute fade curves for X and Y to smooth the transitions.
        float u = fade(x);
        float v = fade(y);

        // Hash coordinates of the 4 square corners.
        int a = p[X] + Y, aa = p[a], ab = p[a + 1], b = p[X + 1] + Y, ba = p[b], bb = p[b + 1];

        // Linearly interpolate between the gradient results from the 4 corners.
        return lerp(v, lerp(u, grad(p[aa], x, y), grad(p[ba], x - 1, y)),
                       lerp(u, grad(p[ab], x, y - 1), grad(p[bb], x - 1, y - 1)));
    }

    /** Smoothing function (Ken Perlin's Improved Noise). */
    private float fade(float t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    /** standard linear interpolation. */
    private float lerp(float t, float a, float b) { return a + t * (b - a); }

    /** Calculates the dot product between a pseudo-random gradient vector and the distance vector. */
    private float grad(int hash, float x, float y) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : h == 12 || h == 14 ? x : 0;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
