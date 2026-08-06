package com.voxel.world.beta;

/** Independent numeric controls for Beta terrain generation. */
public final class BetaNumericProfile {
    public static final int CLASSIC_FAR_LANDS_BLOCKS = 12_550_821;

    /** Defaults mirror Java's primitive widths. */
    public static final BetaNumericProfile DEFAULT =
            new BetaNumericProfile(
                    10, // shortBits
                    20, // intBits
                    8,  // float exponent bits
                    11, // float mantissa bits
                    11, // double exponent bits
                    26  // double mantissa bits
            );

    private final int shortBits;
    private final int intBits;
    private final int floatExponentBits;
    private final int floatMantissaBits;
    private final int doubleExponentBits;
    private final int doubleMantissaBits;

    public BetaNumericProfile(int shortBits, int intBits,
                              int floatExponentBits, int floatMantissaBits,
                              int doubleExponentBits, int doubleMantissaBits) {
        NBitInteger.of(shortBits, 0L);
        NBitInteger.of(intBits, 0L);
        NBitFloat.fromDouble(floatExponentBits, floatMantissaBits, 0.0);
        NBitFloat.fromDouble(doubleExponentBits, doubleMantissaBits, 0.0);
        this.shortBits = shortBits;
        this.intBits = intBits;
        this.floatExponentBits = floatExponentBits;
        this.floatMantissaBits = floatMantissaBits;
        this.doubleExponentBits = doubleExponentBits;
        this.doubleMantissaBits = doubleMantissaBits;
    }

    public int shortBits() { return shortBits; }
    public int intBits() { return intBits; }
    public int floatExponentBits() { return floatExponentBits; }
    public int floatMantissaBits() { return floatMantissaBits; }
    public int doubleExponentBits() { return doubleExponentBits; }
    public int doubleMantissaBits() { return doubleMantissaBits; }

    public int shortValue(long value) {
        return (int) NBitInteger.of(shortBits, value).signedValue();
    }

    public int intValue(long value) {
        return (int) NBitInteger.of(intBits, value).signedValue();
    }

    public int intValue(double value) {
        return (int) NBitInteger.ofDouble(intBits, value).signedValue();
    }

    public float floatValue(double value) {
        return (float) NBitFloat.fromDouble(floatExponentBits, floatMantissaBits, value).toDouble();
    }

    public short shortPrimitive(long value) {
        return (short) shortValue(value);
    }

    public double doubleValue(double value) {
        return NBitFloat.fromDouble(doubleExponentBits, doubleMantissaBits, value).toDouble();
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
