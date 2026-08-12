package com.voxel.world.beta;

/**
 * Single edit point for Beta worldgen precision tuning.
 *
 * Abstract base for per-dimension precision policies. Precision is chosen
 * independently for X, Y, and Z at different coordinate ranges by overriding
 * the switch methods in a subclass; the shared band/quantization helpers are
 * concrete instance methods on this base (override them too if a dimension
 * needs different band boundaries).
 *
 * Existing policies:
 *   {@link OverworldBetaPrecision} — backs the OVERWORLD preset
 *       ({@link BetaNumericProfile#OVERWORLD}): full float/double precision
 *       through the chunk-aligned 4,000-block boundary, then
 *       23→20→12→6→4→2→1; X/Z and Y doubles use the historical fixed
 *       26-bit mask after the full-precision band.
 *   {@link Error502BetaPrecision}  — backs the ERROR502 preset
 *       ({@link BetaNumericProfile#DEFAULT}): aggressive switches, float
 *       23→16→11→6→4→2→1, X/Z + Y double 52→40→30→18→11→6→1 past 3,500
 *       blocks.
 *
 * Add a new subclass to give another dimension its own far-lands behavior
 * without touching the generators or {@link BetaNumericProfile}.
 */
public abstract class BetaPrecisionTuning {
    public static final int CLASSIC_FAR_LANDS_BLOCKS = 12_550_821;

    /** Chunk size in blocks — the granularity of the precision context. */
    public static final int CHUNK_BLOCKS = 16;

    /**
     * Configurable X/Z integer bit width. Both {@link OverworldBetaPrecision}
     * and {@link Error502BetaPrecision} read this field instead of hardcoding
     * 20. Default is 20 (classic Beta Far Lands). Higher values push the Far
     * Lands further out; the hard world border tracks this value.
     */
    protected int xzIntBits = XZ_INT_BITS;

    /** Sets the X/Z integer bit width for both axes. */
    public void setXzIntBits(int bits) {
        this.xzIntBits = Math.max(16, Math.min(32, bits));
    }

    /** Returns the currently configured X/Z int bits. */
    public int getXzIntBits() { return xzIntBits; }

    /**
     * Rounds a block coordinate to the corner of its 16-block chunk that lies
     * closest to 0,0,0: positive coordinates round down to the chunk start,
     * negative coordinates round up to the chunk end. Every block of a chunk
     * therefore shares one offset, so degradation is identical across the
     * whole chunk instead of shifting per block.
     */
    public static double chunkOffset(double coordinate) {
        double a = Math.floor(coordinate / CHUNK_BLOCKS) * CHUNK_BLOCKS;
        return coordinate < 0.0D ? a + CHUNK_BLOCKS : a;
    }

    // Baseline aliases used when constructing the default (ERROR502) profile.
    public static final int SHORT_BITS = 10;
    public static final int XZ_INT_BITS = 20;
    /** Independent Y lattice width; 17 bits moves the vertical wrap to ±65,536. */
    public static final int Y_INT_BITS = 17;
    public static final int XZ_FLOAT_EXPONENT_BITS = 8;
    public static final int XZ_FLOAT_MANTISSA_BITS = 14;
    public static final int Y_FLOAT_EXPONENT_BITS = 8;
    public static final int Y_FLOAT_MANTISSA_BITS = 11;
    public static final int XZ_DOUBLE_EXPONENT_BITS = 11;
    public static final int XZ_DOUBLE_MANTISSA_BITS = 26;
    public static final int Y_DOUBLE_EXPONENT_BITS = 11;
    public static final int Y_DOUBLE_MANTISSA_BITS = 11;

    public enum Axis { X, Y, Z }

    // ---------------------------------------------------------------------
    // Shared helpers — concrete instance methods (subclasses may override).
    // ---------------------------------------------------------------------

    /** Coordinate bands used by the X/Z switch functions. */
    public int coordinateBand(double coordinate) {
        double distance = Math.abs(chunkOffset(coordinate));
        if (distance < 3500.0D) return 0;
        if (distance < 4000.0D) return 1;
        if (distance < 4500.0D) return 2;
        if (distance < 5000.0D) return 3;
        if (distance < 5500.0D) return 4;
        if (distance < 6000.0D) return 5;
        return 6;
    }

    /** Coordinate bands used by the Y switch functions. */
    public int coordinateBandY(double coordinate) {
        double distance = Math.abs(chunkOffset(coordinate));
        if (distance < 400.0D) return 0;
        if (distance < 700.0D) return 1;
        if (distance < 1000.0D) return 2;
        if (distance < 1200.0D) return 3;
        if (distance < 1400.0D) return 4;
        if (distance < 1600.0D) return 5;
        return 6;
    }

    /**
     * Select the signed dominant world coordinate as an ULP context, rounded
     * to the chunk corner closest to 0,0,0 so the whole chain shares it.
     */
    public double worldDistance(double x, double y, double z) {
        double dominant = x;
        if (Math.abs(y) > Math.abs(dominant)) dominant = y;
        if (Math.abs(z) > Math.abs(dominant)) dominant = z;
        return chunkOffset(dominant);
    }

    public double worldDistance(double x, double z) {
        return chunkOffset(Math.abs(x) >= Math.abs(z) ? x : z);
    }

    /** Returns the selected axis for a 2D X/Z context. */
    public Axis dominantXZAxis(double x, double z) {
        return Math.abs(x) >= Math.abs(z) ? Axis.X : Axis.Z;
    }

    /**
     * Quantize a local/derived value at a signed world-space distance. The
     * distance is first rounded to the chunk corner closest to 0,0,0, so the
     * quantization anchor (and therefore the derived value's ULP) is constant
     * for every block of the same 16-block chunk.
     */
    public double quantizeAtDistance(int exponentBits, int mantissaBits,
                                     double value, double distance) {
        distance = chunkOffset(distance);
        if (!Double.isFinite(value) || !Double.isFinite(distance)
                || Math.abs(distance) <= 1.0D || value == 0.0D) {
            return NBitFloat.fromDouble(exponentBits, mantissaBits, value).toDouble();
        }
        double quantizedAnchor = NBitFloat.fromDouble(exponentBits, mantissaBits, distance).toDouble();
        double quantizedSum = NBitFloat.fromDouble(exponentBits, mantissaBits,
                distance + value).toDouble();
        return quantizedSum - quantizedAnchor;
    }

    // ---------------------------------------------------------------------
    // Precision switches — abstract, override per dimension.
    // ---------------------------------------------------------------------

    public abstract int xIntBits(double x);

    public abstract int yIntBits(double y);

    public abstract int zIntBits(double z);

    public abstract int xFloatExponentBits(double x);

    public abstract int xFloatMantissaBits(double x);

    public abstract int yFloatExponentBits(double y);

    public abstract int yFloatMantissaBits(double y);

    public abstract int zFloatExponentBits(double z);

    public abstract int zFloatMantissaBits(double z);

    public abstract int xDoubleExponentBits(double x);

    public abstract int xDoubleMantissaBits(double x);

    public abstract int yDoubleExponentBits(double y);

    public abstract int yDoubleMantissaBits(double y);

    public abstract int zDoubleExponentBits(double z);

    public abstract int zDoubleMantissaBits(double z);
}
