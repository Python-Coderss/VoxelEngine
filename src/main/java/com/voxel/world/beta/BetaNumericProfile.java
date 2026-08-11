package com.voxel.world.beta;

/** Independent numeric controls for Beta terrain generation. */
public final class BetaNumericProfile {
    public static final int CLASSIC_FAR_LANDS_BLOCKS = BetaPrecisionTuning.CLASSIC_FAR_LANDS_BLOCKS;

    /**
     * Legacy fixed-width Beta profile (no distance tuning): short=10, X/Z
     * int=20, Y int=17, standard float 8/23 and double 11/52 on every axis.
     * Retained for tests and reference — the overworld now uses the
     * coordinate-tuned {@link #OVERWORLD} profile.
     */
    public static final BetaNumericProfile STANDARD_BETA =
            new BetaNumericProfile(10, 20, 17, 8, 23, 11, 52);

    /**
     * Overworld profile: coordinate-tuned via {@link OverworldBetaPrecision}.
     * Integer far lands (~3,060 blocks) with near-full float precision in
     * bands 0–1, then following the tuned X/Z float curve:
     * 23→20→12→6→4→2→1. X/Z doubles stay at 52 bits through 4,000 blocks,
     * then use the historical 26-bit mask. Y follows the same full-through-
     * 4,000 curve so three-axis corners can degrade without affecting spawn.
     */
    public static final BetaNumericProfile OVERWORLD =
            new BetaNumericProfile(
                    10, 20, 17,
                    8, 23, 8, 23,
                    11, 52, 11, 52,
                    new OverworldBetaPrecision());

    /**
     * ERROR502 profile: coordinate-tuned via {@link Error502BetaPrecision}.
     * Aggressive block-space degradation: float 23→16→11→6→4→2→1, X/Z and
     * Y double 52→40→30→18→11→6→1.
     */
    public static final BetaNumericProfile DEFAULT =
            new BetaNumericProfile(
                    BetaPrecisionTuning.SHORT_BITS,
                    BetaPrecisionTuning.XZ_INT_BITS,
                    BetaPrecisionTuning.Y_INT_BITS,
                    BetaPrecisionTuning.XZ_FLOAT_EXPONENT_BITS,
                    BetaPrecisionTuning.XZ_FLOAT_MANTISSA_BITS,
                    BetaPrecisionTuning.Y_FLOAT_EXPONENT_BITS,
                    BetaPrecisionTuning.Y_FLOAT_MANTISSA_BITS,
                    BetaPrecisionTuning.XZ_DOUBLE_EXPONENT_BITS,
                    BetaPrecisionTuning.XZ_DOUBLE_MANTISSA_BITS,
                    BetaPrecisionTuning.Y_DOUBLE_EXPONENT_BITS,
                    BetaPrecisionTuning.Y_DOUBLE_MANTISSA_BITS,
                    new Error502BetaPrecision()
            );

    private final int shortBits;
    private final int intBits;
    private final int yIntBits;
    private final int floatExponentBits;
    private final int floatMantissaBits;
    private final int yFloatExponentBits;
    private final int yFloatMantissaBits;
    private final int doubleExponentBits;
    private final int doubleMantissaBits;
    private final int yDoubleExponentBits;
    private final int yDoubleMantissaBits;
    private final boolean coordinateTuned;
    // Per-dimension coordinate-aware precision policy (null = fixed widths).
    private final BetaPrecisionTuning tuning;
    // Fallback instance for helper-only calls (quantizeAtDistance/worldDistance)
    // on fixed-width profiles. Results are independent of which instance runs them.
    private static final BetaPrecisionTuning HELPER_TUNING = new OverworldBetaPrecision();

    public BetaNumericProfile(int shortBits, int intBits,
                              int floatExponentBits, int floatMantissaBits,
                              int doubleExponentBits, int doubleMantissaBits) {
        this(shortBits, intBits, intBits, floatExponentBits, floatMantissaBits,
                floatExponentBits, floatMantissaBits,
                doubleExponentBits, doubleMantissaBits,
                doubleExponentBits, doubleMantissaBits);
    }

    /**
     * Creates a profile with an independent Y-axis integer width. X and Z use
     * {@code intBits}; Y uses {@code yIntBits}. Y floating-point widths default
     * to the corresponding X/Z widths.
     */
    public BetaNumericProfile(int shortBits, int intBits, int yIntBits,
                              int floatExponentBits, int floatMantissaBits,
                              int doubleExponentBits, int doubleMantissaBits) {
        this(shortBits, intBits, yIntBits,
                floatExponentBits, floatMantissaBits,
                floatExponentBits, floatMantissaBits,
                doubleExponentBits, doubleMantissaBits,
                doubleExponentBits, doubleMantissaBits);
    }

    /**
     * Creates a profile with independent integer, float, and double controls
     * for the Y axis. X/Z controls retain their existing names and behavior.
     */
    public BetaNumericProfile(int shortBits, int intBits, int yIntBits,
                              int floatExponentBits, int floatMantissaBits,
                              int yFloatExponentBits, int yFloatMantissaBits,
                              int doubleExponentBits, int doubleMantissaBits,
                              int yDoubleExponentBits, int yDoubleMantissaBits) {
        this(shortBits, intBits, yIntBits, floatExponentBits, floatMantissaBits,
                yFloatExponentBits, yFloatMantissaBits, doubleExponentBits, doubleMantissaBits,
                yDoubleExponentBits, yDoubleMantissaBits, null);
    }

    BetaNumericProfile(int shortBits, int intBits, int yIntBits,
                       int floatExponentBits, int floatMantissaBits,
                       int yFloatExponentBits, int yFloatMantissaBits,
                       int doubleExponentBits, int doubleMantissaBits,
                       int yDoubleExponentBits, int yDoubleMantissaBits,
                       BetaPrecisionTuning tuning) {
        NBitInteger.of(shortBits, 0L);
        NBitInteger.of(intBits, 0L);
        NBitInteger.of(yIntBits, 0L);
        NBitFloat.fromDouble(floatExponentBits, floatMantissaBits, 0.0);
        NBitFloat.fromDouble(yFloatExponentBits, yFloatMantissaBits, 0.0);
        NBitFloat.fromDouble(doubleExponentBits, doubleMantissaBits, 0.0);
        NBitFloat.fromDouble(yDoubleExponentBits, yDoubleMantissaBits, 0.0);
        this.shortBits = shortBits;
        this.intBits = intBits;
        this.yIntBits = yIntBits;
        this.floatExponentBits = floatExponentBits;
        this.floatMantissaBits = floatMantissaBits;
        this.yFloatExponentBits = yFloatExponentBits;
        this.yFloatMantissaBits = yFloatMantissaBits;
        this.doubleExponentBits = doubleExponentBits;
        this.doubleMantissaBits = doubleMantissaBits;
        this.yDoubleExponentBits = yDoubleExponentBits;
        this.yDoubleMantissaBits = yDoubleMantissaBits;
        this.tuning = tuning;
        this.coordinateTuned = tuning != null;
    }

    /**
     * The active precision policy: the coordinate-tuned subclass, or the
     * Overworld policy as an instance fallback for helper-only calls
     * (quantizeAtDistance/worldDistance) on fixed-width profiles. Their results
     * are independent of which instance handles them.
     */
    private BetaPrecisionTuning activeTuning() {
        return tuning != null ? tuning : HELPER_TUNING;
    }

    /** The coordinate-tuned policy backing this profile (null = fixed widths). */
    BetaPrecisionTuning tuning() { return tuning; }

    public int shortBits() { return shortBits; }
    public int intBits() { return intBits; }
    public int yIntBits() { return yIntBits; }
    public int floatExponentBits() { return floatExponentBits; }
    public int floatMantissaBits() { return floatMantissaBits; }
    public int yFloatExponentBits() { return yFloatExponentBits; }
    public int yFloatMantissaBits() { return yFloatMantissaBits; }
    public int doubleExponentBits() { return doubleExponentBits; }
    public int doubleMantissaBits() { return doubleMantissaBits; }
    public int yDoubleExponentBits() { return yDoubleExponentBits; }
    public int yDoubleMantissaBits() { return yDoubleMantissaBits; }

    public int shortValue(long value) {
        return (int) NBitInteger.of(shortBits, value).signedValue();
    }

    public int intValue(long value) {
        return (int) NBitInteger.of(intBits, value).signedValue();
    }

    public int intValue(double value) {
        return (int) NBitInteger.ofDouble(intBits, value).signedValue();
    }

    public int xIntValue(double value) {
        int bits = coordinateTuned ? tuning.xIntBits(value) : intBits;
        return (int) NBitInteger.ofDouble(bits, value).signedValue();
    }

    public int yIntValue(long value) {
        return yIntValue((double) value);
    }

    public int yIntValue(double value) {
        int bits = coordinateTuned ? tuning.yIntBits(value) : yIntBits;
        return (int) NBitInteger.ofDouble(bits, value).signedValue();
    }

    public int zIntValue(double value) {
        int bits = coordinateTuned ? tuning.zIntBits(value) : intBits;
        return (int) NBitInteger.ofDouble(bits, value).signedValue();
    }

    /**
     * Rounds a block coordinate to the corner of its 16-block chunk closest
     * to 0,0,0 (see {@link BetaPrecisionTuning#chunkOffset(double)}).
     */
    public static double chunkOffset(double coordinate) {
        return BetaPrecisionTuning.chunkOffset(coordinate);
    }

    /** Quantizes an absolute X/Z coordinate through the X/Z float stage. */
    public float floatCoordinate(double value) {
        return floatValue(value);
    }

    /** X float stage with precision selected by an explicit chunk-aligned block offset. */
    public float xFloatCoordinate(double value, double contextX) {
        if (!coordinateTuned) return floatValue(value);
        return (float) NBitFloat.fromDouble(
                tuning.xFloatExponentBits(contextX),
                tuning.xFloatMantissaBits(contextX), value).toDouble();
    }

    public float xFloatCoordinate(double value) {
        return xFloatCoordinate(value, value);
    }

    /** Z float stage with precision selected by an explicit chunk-aligned block offset. */
    public float zFloatCoordinate(double value, double contextZ) {
        if (!coordinateTuned) return floatValue(value);
        return (float) NBitFloat.fromDouble(
                tuning.zFloatExponentBits(contextZ),
                tuning.zFloatMantissaBits(contextZ), value).toDouble();
    }

    public float zFloatCoordinate(double value) {
        return zFloatCoordinate(value, value);
    }

    public float floatValue(double value) {
        return (float) NBitFloat.fromDouble(floatExponentBits, floatMantissaBits, value).toDouble();
    }

    /**
     * Quantizes a local/derived float value at a signed world-space distance.
     * This models the usual float operation
     * {@code (float) (distance + value) - (float) distance}: the value keeps
     * its local meaning, while its ULP is determined by the axis-aligned world
     * distance. Use {@link #floatValue(double)} for absolute coordinates.
     */
    public float floatValueAtDistance(double value, double distance) {
        return (float) activeTuning().quantizeAtDistance(
                floatExponentBits, floatMantissaBits, value, distance);
    }

    public float xFloatValueAtDistance(double value, double x) {
        int exponent = coordinateTuned ? tuning.xFloatExponentBits(x) : floatExponentBits;
        int mantissa = coordinateTuned ? tuning.xFloatMantissaBits(x) : floatMantissaBits;
        return (float) activeTuning().quantizeAtDistance(exponent, mantissa, value, x);
    }

    public float zFloatValueAtDistance(double value, double z) {
        int exponent = coordinateTuned ? tuning.zFloatExponentBits(z) : floatExponentBits;
        int mantissa = coordinateTuned ? tuning.zFloatMantissaBits(z) : floatMantissaBits;
        return (float) activeTuning().quantizeAtDistance(exponent, mantissa, value, z);
    }

    /** Select X or Z precision for a derived value using the dominant context axis. */
    public float xzFloatValueAtDistance(double value, double x, double z) {
        return Math.abs(x) >= Math.abs(z)
                ? xFloatValueAtDistance(value, x)
                : zFloatValueAtDistance(value, z);
    }

    /** Y float stage with precision selected by an explicit chunk-aligned block offset. */
    public float yFloatValue(double value, double contextY) {
        int exponent = coordinateTuned ? tuning.yFloatExponentBits(contextY) : yFloatExponentBits;
        int mantissa = coordinateTuned ? tuning.yFloatMantissaBits(contextY) : yFloatMantissaBits;
        return (float) NBitFloat.fromDouble(exponent, mantissa, value).toDouble();
    }

    public float yFloatValue(double value) {
        return yFloatValue(value, value);
    }

    /** Y-axis equivalent of {@link #floatValueAtDistance(double, double)}. */
    public float yFloatValueAtDistance(double value, double distance) {
        int exponent = coordinateTuned ? tuning.yFloatExponentBits(distance) : yFloatExponentBits;
        int mantissa = coordinateTuned ? tuning.yFloatMantissaBits(distance) : yFloatMantissaBits;
        return (float) activeTuning().quantizeAtDistance(exponent, mantissa, value, distance);
    }

    public short shortPrimitive(long value) {
        return (short) shortValue(value);
    }

    public double doubleValue(double value) {
        return NBitFloat.fromDouble(doubleExponentBits, doubleMantissaBits, value).toDouble();
    }

    /** X double stage with precision selected by an explicit chunk-aligned block offset. */
    public double xDoubleCoordinate(double value, double contextX) {
        int exponent = coordinateTuned ? tuning.xDoubleExponentBits(contextX) : doubleExponentBits;
        int mantissa = coordinateTuned ? tuning.xDoubleMantissaBits(contextX) : doubleMantissaBits;
        return NBitFloat.fromDouble(exponent, mantissa, value).toDouble();
    }

    public double xDoubleCoordinate(double value) {
        return xDoubleCoordinate(value, value);
    }

    /** Z double stage with precision selected by an explicit chunk-aligned block offset. */
    public double zDoubleCoordinate(double value, double contextZ) {
        int exponent = coordinateTuned ? tuning.zDoubleExponentBits(contextZ) : doubleExponentBits;
        int mantissa = coordinateTuned ? tuning.zDoubleMantissaBits(contextZ) : doubleMantissaBits;
        return NBitFloat.fromDouble(exponent, mantissa, value).toDouble();
    }

    public double zDoubleCoordinate(double value) {
        return zDoubleCoordinate(value, value);
    }

    /** Y double stage with precision selected by an explicit chunk-aligned block offset. */
    public double yDoubleValue(double value, double contextY) {
        int exponent = coordinateTuned ? tuning.yDoubleExponentBits(contextY) : yDoubleExponentBits;
        int mantissa = coordinateTuned ? tuning.yDoubleMantissaBits(contextY) : yDoubleMantissaBits;
        return NBitFloat.fromDouble(exponent, mantissa, value).toDouble();
    }

    public double yDoubleValue(double value) {
        return yDoubleValue(value, value);
    }

    /** Y-axis double equivalent for distance-aware derived calculations. */
    public double yDoubleValueAtDistance(double value, double distance) {
        int exponent = coordinateTuned ? tuning.yDoubleExponentBits(distance) : yDoubleExponentBits;
        int mantissa = coordinateTuned ? tuning.yDoubleMantissaBits(distance) : yDoubleMantissaBits;
        return activeTuning().quantizeAtDistance(exponent, mantissa, value, distance);
    }

    public double xDoubleValueAtDistance(double value, double distance) {
        int exponent = coordinateTuned ? tuning.xDoubleExponentBits(distance) : doubleExponentBits;
        int mantissa = coordinateTuned ? tuning.xDoubleMantissaBits(distance) : doubleMantissaBits;
        return activeTuning().quantizeAtDistance(exponent, mantissa, value, distance);
    }

    public double zDoubleValueAtDistance(double value, double distance) {
        int exponent = coordinateTuned ? tuning.zDoubleExponentBits(distance) : doubleExponentBits;
        int mantissa = coordinateTuned ? tuning.zDoubleMantissaBits(distance) : doubleMantissaBits;
        return activeTuning().quantizeAtDistance(exponent, mantissa, value, distance);
    }

    /** Select X or Z double precision for a derived value using the dominant horizontal axis. */
    public double xzDoubleValueAtDistance(double value, double x, double z) {
        return Math.abs(x) >= Math.abs(z)
                ? xDoubleValueAtDistance(value, x)
                : zDoubleValueAtDistance(value, z);
    }

    /**
     * Returns the signed coordinate of the dominant axis. Keeping the sign
     * makes quantization symmetric when a generator crosses the origin.
     */
    public double worldDistance(double x, double y, double z) {
        return activeTuning().worldDistance(x, y, z);
    }

    public double worldDistance(double x, double z) {
        return activeTuning().worldDistance(x, z);
    }

    public double distanceIndependent(double value) {
        return doubleValue(value);
    }

    /** Round a Java int through the configured int width. */
    public int intPrimitive(int value) {
        return intValue((long) value);
    }

    public double quantizeDouble(double value) {
        return doubleValue(value);
    }
}
