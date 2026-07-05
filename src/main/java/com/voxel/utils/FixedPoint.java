package com.voxel.utils;

/**
 * 64-bit fixed-point arithmetic with 8 fractional bits.
 * Lower 8 bits = fractional part. Resolution: 1/256 ≈ 0.0039 blocks.
 * 
 * Integer part uses 56 bits (signed), giving a range of ±2^55 ≈ ±3.6×10^16 blocks.
 * More than sufficient for the Far Lands (~12.5M blocks).
 * 
 * This avoids the floating-point precision loss that `double` experiences
 * at extreme coordinates (where double only has ~13 bits of fractional precision
 * at 13M blocks, causing visible jitter in player position).
 */
public final class FixedPoint {
    
    /** Number of fractional bits. */
    public static final int FRAC_BITS = 8;
    
    /** The scale factor: 1 << FRAC_BITS = 256. */
    public static final long SCALE = 1L << FRAC_BITS;
    
    /** Mask for extracting the fractional part: 0xFF. */
    public static final long FRAC_MASK = SCALE - 1;
    
    // Prevent instantiation
    private FixedPoint() {}
    
    // ── Conversion ───────────────────────────────────────────────────
    
    /** Convert a double to fixed-point (nearest). */
    public static long fromDouble(double value) {
        return (long) Math.round(value * SCALE);
    }
    
    /** Convert a float to fixed-point (nearest). */
    public static long fromFloat(float value) {
        return (long) Math.round(value * SCALE);
    }
    
    /** Convert fixed-point to double. */
    public static double toDouble(long fp) {
        return (double) fp / SCALE;
    }
    
    /** Convert fixed-point to float. */
    public static float toFloat(long fp) {
        return (float) fp / SCALE;
    }
    
    // ── Integer-part extraction (block coordinates) ──────────────────
    
    /** Get the block X/Z/Y coordinate (integer part, truncates toward -inf). */
    public static int blockX(long fp) {
        return (int) (fp >> FRAC_BITS);
    }
    
    /** Floor to nearest block coordinate (same as blockX for now). */
    public static int floorBlock(long fp) {
        // Arithmetic right shift handles negatives correctly for floor behavior
        return (int) (fp >> FRAC_BITS);
    }
    
    // ── Arithmetic ──────────────────────────────────────────────────
    
    /** Add a double delta to a fixed-point value. */
    public static long add(long fp, double delta) {
        return fp + fromDouble(delta);
    }
    
    /** Scale a fixed-point value by a double factor (multiplication). */
    public static long multiply(long fp, double factor) {
        return (long) (toDouble(fp) * factor);
    }
    
    // ── Camera decomposition (integer block + fractional) ─────────────
    
    /** Extract integer block coordinate (floor toward -inf). */
    public static int camBlock(long fp) {
        return (int) (fp >> FRAC_BITS);
    }
    
    /** Extract fractional part as float [0.0, 1.0). */
    public static float camFrac(long fp) {
        return (float) (fp & FRAC_MASK) / (float) SCALE;
    }
    
    // ── Arithmetic ──────────────────────────────────────────────────
    
    /** Linear interpolation between two fixed-point values.
     *  Converts t to fixed-point first, then does all math in 64-bit integers.
     *  No float→long cast of a scaled product — preserves full 56-bit integer precision. */
    public static long lerp(long a, long b, float t) {
        long delta = b - a;
        // Convert t (float in [0,1]) to fixed-point fraction: tFixed in [0, SCALE]
        long tFixed = (long)(t * SCALE + 0.5f);
        // delta * tFixed / SCALE = interpolated delta, all in integer arithmetic
        return a + ((delta * tFixed) >> FRAC_BITS);
    }
    
    /** Interpolate and convert to float in one step. */
    public static float lerpToFloat(long a, long b, float t) {
        return toFloat(lerp(a, b, t));
    }
}
