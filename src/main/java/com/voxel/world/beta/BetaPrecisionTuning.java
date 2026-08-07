package com.voxel.world.beta;

/**
 * Single edit point for Beta worldgen precision tuning.
 *
 * Edit the switch statements in this file to choose precision independently
 * for X, Y, and Z at different coordinate ranges. The generator classes and
 * {@link BetaNumericProfile} should not need changes for normal tuning.
 *
 * The default cases preserve the current profile:
 * X/Z integer 20, Y integer 15; X/Z float 8/14, Y float 8/11;
 * X/Z double 11/26, Y double 11/11.
 */
public final class BetaPrecisionTuning {
    private BetaPrecisionTuning() {
    }

    public static final int CLASSIC_FAR_LANDS_BLOCKS = 12_550_821;

    // Baseline aliases used when constructing the default profile.
    public static final int SHORT_BITS = 10;
    public static final int XZ_INT_BITS = 20;
    public static final int Y_INT_BITS = 15;
    public static final int XZ_FLOAT_EXPONENT_BITS = 8;
    public static final int XZ_FLOAT_MANTISSA_BITS = 14;
    public static final int Y_FLOAT_EXPONENT_BITS = 8;
    public static final int Y_FLOAT_MANTISSA_BITS = 11;
    public static final int XZ_DOUBLE_EXPONENT_BITS = 11;
    public static final int XZ_DOUBLE_MANTISSA_BITS = 26;
    public static final int Y_DOUBLE_EXPONENT_BITS = 11;
    public static final int Y_DOUBLE_MANTISSA_BITS = 11;


    public enum Axis { X, Y, Z }

    /** Coordinate bands used by the switch functions below. */
    public static int coordinateBand(double coordinate) {
        double distance = Math.abs(coordinate);
        if (distance < 3500.0D) return 0;
        if (distance < 4000.0D) return 1;
        if (distance < 4500.0D) return 2;
        if (distance < 5000.0D) return 3;
        if (distance < 5500.0D) return 4;
        return 5;
    }
    /** Coordinate bands used by the switch functions below. */
    public static int coordinateBandY(double coordinate) {
        double distance = Math.abs(coordinate);
        if (distance < 400.0D) return 0;
        if (distance < 700.0D) return 1;
        if (distance < 1000.0D) return 2;
        if (distance < 1200.0D) return 3;
        if (distance < 1400.0D) return 4;
        return 5;
    }

    // ---------------------------------------------------------------------
    // Integer/lattice precision. Edit the return values in these switches.
    // ---------------------------------------------------------------------

    public static int xIntBits(double x) {
        switch (coordinateBand(x)) {
            case 0: return 20;
            case 1: return 20;
            case 2: return 20;
            case 3: return 20;
            case 4: return 20;
            default: return 20;
        }
    }

    public static int yIntBits(double y) {
        switch (coordinateBandY(y)) {
            case 0: return 15;
            case 1: return 15;
            case 2: return 15;
            case 3: return 15;
            case 4: return 15;
            default: return 15;
        }
    }

    public static int zIntBits(double z) {
        switch (coordinateBand(z)) {
            case 0: return 20;
            case 1: return 20;
            case 2: return 20;
            case 3: return 20;
            case 4: return 20;
            default: return 20;
        }
    }

    // ---------------------------------------------------------------------
    // Float exponent/mantissa precision. Edit these switches independently.
    // ---------------------------------------------------------------------

    public static int xFloatExponentBits(double x) {
        switch (coordinateBand(x)) {
            default: return 8;
        }
    }

    /**
     * Example: to make X lose one bit only after 4,096 blocks, change the
     * {@code case 2} return below from 14 to 13. Make the equivalent edit in
     * {@link #zFloatMantissaBits(double)} if Z should behave the same way.
     */
    public static int xFloatMantissaBits(double x) {
        switch (coordinateBand(x)) {
	        case 0: return 23;
	        case 1: return 16;
	        case 2: return 12;
	        case 3: return 8;
	        case 4: return 6;
	        default: return 4;
        }
    }

    public static int yFloatExponentBits(double y) {
        switch (coordinateBandY(y)) {
            default: return 8;
        }
    }

    public static int yFloatMantissaBits(double y) {
        switch (coordinateBandY(y)) {
	        case 0: return 23;
	        case 1: return 16;
	        case 2: return 12;
	        case 3: return 8;
	        case 4: return 6;
	        default: return 4;
        }
    }

    public static int zFloatExponentBits(double z) {
        switch (coordinateBand(z)) {
            default: return 8;
        }
    }

    public static int zFloatMantissaBits(double z) {
        switch (coordinateBand(z)) {
	        case 0: return 23;
	        case 1: return 16;
	        case 2: return 12;
	        case 3: return 8;
	        case 4: return 6;
	        default: return 4;
        }
    }

    // ---------------------------------------------------------------------
    // Double exponent/mantissa precision. Edit these switches independently.
    // ---------------------------------------------------------------------

    public static int xDoubleExponentBits(double x) {
        switch (coordinateBand(x)) {
            default: return 11;
        }
    }

    public static int xDoubleMantissaBits(double x) {
        switch (coordinateBand(x)) {
            default: return 26;
        }
    }

    public static int yDoubleExponentBits(double y) {
        switch (coordinateBand(y)) {
            default: return 11;
        }
    }

    public static int yDoubleMantissaBits(double y) {
        switch (coordinateBandY(y)) {
            case 0: return 52;
            case 1: return 40;
            case 2: return 18;
            case 3: return 16;
            case 4: return 14;
            default: return 11;
        }
    }

    public static int zDoubleExponentBits(double z) {
        switch (coordinateBand(z)) {
            default: return 11;
        }
    }

    public static int zDoubleMantissaBits(double z) {
        switch (coordinateBand(z)) {
            default: return 26;
        }
    }

    /** Select the signed dominant world coordinate as an ULP context. */
    public static double worldDistance(double x, double y, double z) {
        double dominant = x;
        if (Math.abs(y) > Math.abs(dominant)) dominant = y;
        if (Math.abs(z) > Math.abs(dominant)) dominant = z;
        return dominant;
    }

    public static double worldDistance(double x, double z) {
        return Math.abs(x) >= Math.abs(z) ? x : z;
    }

    /** Returns the selected axis for a 2D X/Z context. */
    public static Axis dominantXZAxis(double x, double z) {
        return Math.abs(x) >= Math.abs(z) ? Axis.X : Axis.Z;
    }

    /** Quantize a local/derived value at a signed world-space distance. */
    public static double quantizeAtDistance(int exponentBits, int mantissaBits,
                                            double value, double distance) {
        if (!Double.isFinite(value) || !Double.isFinite(distance)
                || Math.abs(distance) <= 1.0D || value == 0.0D) {
            return NBitFloat.fromDouble(exponentBits, mantissaBits, value).toDouble();
        }
        double quantizedAnchor = NBitFloat.fromDouble(exponentBits, mantissaBits, distance).toDouble();
        double quantizedSum = NBitFloat.fromDouble(exponentBits, mantissaBits,
                distance + value).toDouble();
        return quantizedSum - quantizedAnchor;
    }
}
