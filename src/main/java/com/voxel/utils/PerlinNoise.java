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

    /**
     * 3D Perlin noise for volumetric density fields (used by Aether terrain).
     * @param x The X coordinate.
     * @param y The Y coordinate.
     * @param z The Z coordinate.
     * @param scale A multiplier for all input coordinates.
     * @return A noise value, typically in the range [-1.0, 1.0].
     */
    public float noise3D(float x, float y, float z, float scale) {
        x *= scale;
        y *= scale;
        z *= scale;

        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        int Z = (int) Math.floor(z) & 255;

        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);

        float u = fade(x);
        float v = fade(y);
        float w = fade(z);

        // Hash the 8 cube corners
        int a = p[X] + Y, aa = p[a] + Z, ab = p[a + 1] + Z;
        int b = p[X + 1] + Y, ba = p[b] + Z, bb = p[b + 1] + Z;

        // Trilinear interpolation of the 8 corner gradients
        return lerp(w,
            lerp(v, lerp(u, grad3D(p[aa], x, y, z), grad3D(p[ba], x - 1, y, z)),
                    lerp(u, grad3D(p[ab], x, y - 1, z), grad3D(p[bb], x - 1, y - 1, z))),
            lerp(v, lerp(u, grad3D(p[aa + 1], x, y, z - 1), grad3D(p[ba + 1], x - 1, y, z - 1)),
                    lerp(u, grad3D(p[ab + 1], x, y - 1, z - 1), grad3D(p[bb + 1], x - 1, y - 1, z - 1))));
    }

    /** 3D gradient direction based on hash value (16 possible directions). */
    private float grad3D(int hash, float x, float y, float z) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** Calculates the dot product between a pseudo-random gradient vector and the distance vector. */
    private float grad(int hash, float x, float y) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : h == 12 || h == 14 ? x : 0;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
