package com.voxel.world.beta;

/** Independent numeric controls for Beta terrain generation. */
public final class BetaNumericProfile {
    public static final int CLASSIC_FAR_LANDS_BLOCKS = BetaPrecisionTuning.CLASSIC_FAR_LANDS_BLOCKS;

    /**
     * Standard Beta numeric behavior used by the normal Overworld Far Lands.
     * Only the integer widths are reduced: short=10, X/Z int=20, Y int=15.
     * Java-standard float and double widths are retained on every axis.
     */
    public static final BetaNumericProfile STANDARD_BETA =
            new BetaNumericProfile(10, 20, 15, 8, 23, 11, 52);

    /** Default profile backed by the editable {@link BetaPrecisionTuning} file. */
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
                    true
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
                yDoubleExponentBits, yDoubleMantissaBits, false);
    }

    BetaNumericProfile(int shortBits, int intBits, int yIntBits,
                       int floatExponentBits, int floatMantissaBits,
                       int yFloatExponentBits, int yFloatMantissaBits,
                       int doubleExponentBits, int doubleMantissaBits,
                       int yDoubleExponentBits, int yDoubleMantissaBits,
                       boolean coordinateTuned) {
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
        this.coordinateTuned = coordinateTuned;
    }

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
        int bits = coordinateTuned ? BetaPrecisionTuning.xIntBits(value) : intBits;
        return (int) NBitInteger.ofDouble(bits, value).signedValue();
    }

    public int yIntValue(long value) {
        return yIntValue((double) value);
    }

    public int yIntValue(double value) {
        int bits = coordinateTuned ? BetaPrecisionTuning.yIntBits(value) : yIntBits;
        return (int) NBitInteger.ofDouble(bits, value).signedValue();
    }

    public int zIntValue(double value) {
        int bits = coordinateTuned ? BetaPrecisionTuning.zIntBits(value) : intBits;
        return (int) NBitInteger.ofDouble(bits, value).signedValue();
    }

    /** Quantizes an absolute X/Z coordinate through the X/Z float stage. */
    public float floatCoordinate(double value) {
        return floatValue(value);
    }

    public float xFloatCoordinate(double value) {
        if (!coordinateTuned) return floatValue(value);
        return (float) NBitFloat.fromDouble(
                BetaPrecisionTuning.xFloatExponentBits(value),
                BetaPrecisionTuning.xFloatMantissaBits(value), value).toDouble();
    }

    public float zFloatCoordinate(double value) {
        if (!coordinateTuned) return floatValue(value);
        return (float) NBitFloat.fromDouble(
                BetaPrecisionTuning.zFloatExponentBits(value),
                BetaPrecisionTuning.zFloatMantissaBits(value), value).toDouble();
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
        return (float) BetaPrecisionTuning.quantizeAtDistance(
                floatExponentBits, floatMantissaBits, value, distance);
    }

    public float xFloatValueAtDistance(double value, double x) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.xFloatExponentBits(x) : floatExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.xFloatMantissaBits(x) : floatMantissaBits;
        return (float) BetaPrecisionTuning.quantizeAtDistance(exponent, mantissa, value, x);
    }

    public float zFloatValueAtDistance(double value, double z) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.zFloatExponentBits(z) : floatExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.zFloatMantissaBits(z) : floatMantissaBits;
        return (float) BetaPrecisionTuning.quantizeAtDistance(exponent, mantissa, value, z);
    }

    /** Select X or Z precision for a derived value using the dominant context axis. */
    public float xzFloatValueAtDistance(double value, double x, double z) {
        return Math.abs(x) >= Math.abs(z)
                ? xFloatValueAtDistance(value, x)
                : zFloatValueAtDistance(value, z);
    }

    public float yFloatValue(double value) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.yFloatExponentBits(value) : yFloatExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.yFloatMantissaBits(value) : yFloatMantissaBits;
        return (float) NBitFloat.fromDouble(exponent, mantissa, value).toDouble();
    }

    /** Y-axis equivalent of {@link #floatValueAtDistance(double, double)}. */
    public float yFloatValueAtDistance(double value, double distance) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.yFloatExponentBits(distance) : yFloatExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.yFloatMantissaBits(distance) : yFloatMantissaBits;
        return (float) BetaPrecisionTuning.quantizeAtDistance(exponent, mantissa, value, distance);
    }

    public short shortPrimitive(long value) {
        return (short) shortValue(value);
    }

    public double doubleValue(double value) {
        return NBitFloat.fromDouble(doubleExponentBits, doubleMantissaBits, value).toDouble();
    }

    public double xDoubleCoordinate(double value) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.xDoubleExponentBits(value) : doubleExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.xDoubleMantissaBits(value) : doubleMantissaBits;
        return NBitFloat.fromDouble(exponent, mantissa, value).toDouble();
    }

    public double zDoubleCoordinate(double value) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.zDoubleExponentBits(value) : doubleExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.zDoubleMantissaBits(value) : doubleMantissaBits;
        return NBitFloat.fromDouble(exponent, mantissa, value).toDouble();
    }

    public double yDoubleValue(double value) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.yDoubleExponentBits(value) : yDoubleExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.yDoubleMantissaBits(value) : yDoubleMantissaBits;
        return NBitFloat.fromDouble(exponent, mantissa, value).toDouble();
    }

    /** Y-axis double equivalent for distance-aware derived calculations. */
    public double yDoubleValueAtDistance(double value, double distance) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.yDoubleExponentBits(distance) : yDoubleExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.yDoubleMantissaBits(distance) : yDoubleMantissaBits;
        return BetaPrecisionTuning.quantizeAtDistance(exponent, mantissa, value, distance);
    }

    public double xDoubleValueAtDistance(double value, double distance) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.xDoubleExponentBits(distance) : doubleExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.xDoubleMantissaBits(distance) : doubleMantissaBits;
        return BetaPrecisionTuning.quantizeAtDistance(exponent, mantissa, value, distance);
    }

    public double zDoubleValueAtDistance(double value, double distance) {
        int exponent = coordinateTuned ? BetaPrecisionTuning.zDoubleExponentBits(distance) : doubleExponentBits;
        int mantissa = coordinateTuned ? BetaPrecisionTuning.zDoubleMantissaBits(distance) : doubleMantissaBits;
        return BetaPrecisionTuning.quantizeAtDistance(exponent, mantissa, value, distance);
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
        return BetaPrecisionTuning.worldDistance(x, y, z);
    }

    public double worldDistance(double x, double z) {
        return BetaPrecisionTuning.worldDistance(x, z);
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
