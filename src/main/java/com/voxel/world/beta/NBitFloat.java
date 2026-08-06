package com.voxel.world.beta;

/**
 * A configurable IEEE-754 binary format represented in a Java long.
 *
 * The encoding is one sign bit, {@code exponentBits} exponent bits, and
 * {@code mantissaBits} fraction bits.  Values are rounded to nearest,
 * ties-to-even when encoded.  A zero-fraction format is valid: it can still
 * represent signed zero, signed powers of two, and infinities when its
 * exponent field has an all-ones value.
 */
public final class NBitFloat {
    private final int exponentBits;
    private final int mantissaBits;
    private final long rawBits;

    private NBitFloat(int exponentBits, int mantissaBits, long rawBits) {
        validate(exponentBits, mantissaBits);
        this.exponentBits = exponentBits;
        this.mantissaBits = mantissaBits;
        this.rawBits = rawBits;
    }

    /** Encode a Java double using the configured IEEE-style format. */
    public static NBitFloat fromDouble(int exponentBits, int mantissaBits, double value) {
        validate(exponentBits, mantissaBits);
        int totalBits = totalBits(exponentBits, mantissaBits);
        long sign = Double.doubleToRawLongBits(value) < 0L
                ? (1L << (totalBits - 1)) : 0L;
        long exponentMask = mask(exponentBits);
        long fractionMask = mask(mantissaBits);
        long specialExponent = exponentMask;

        if (Double.isNaN(value)) {
            // A format without fraction bits cannot encode NaN distinctly;
            // use its infinity representation in that case.
            long fraction = mantissaBits == 0 ? 0L : 1L;
            return new NBitFloat(exponentBits, mantissaBits,
                    sign | (specialExponent << mantissaBits) | fraction);
        }
        if (Double.isInfinite(value)) {
            return new NBitFloat(exponentBits, mantissaBits,
                    sign | (specialExponent << mantissaBits));
        }
        if (value == 0.0D) {
            return new NBitFloat(exponentBits, mantissaBits, sign);
        }

        long bias = bias(exponentBits);
        long maxUnbiasedExponent = (exponentMask - 1L) - bias;
        long minUnbiasedExponent = 1L - bias;
        double magnitude = Math.abs(value);
        int sourceExponent = Math.getExponent(magnitude);

        if ((long) sourceExponent > maxUnbiasedExponent) {
            return new NBitFloat(exponentBits, mantissaBits,
                    sign | (specialExponent << mantissaBits));
        }

        long fraction;
        long encodedExponent;
        if ((long) sourceExponent >= minUnbiasedExponent) {
            // Normalize to [2^mantissaBits, 2^(mantissaBits+1)).
            double scaled = Math.scalb(magnitude, mantissaBits - sourceExponent);
            long significand = roundToEven(scaled);
            long hiddenBit = 1L << mantissaBits;
            long carryBit = hiddenBit << 1;
            long exponent = sourceExponent;
            if (significand >= carryBit) {
                significand = hiddenBit;
                exponent++;
            }
            if (exponent > maxUnbiasedExponent) {
                return new NBitFloat(exponentBits, mantissaBits,
                        sign | (specialExponent << mantissaBits));
            }
            encodedExponent = exponent + bias;
            fraction = significand - hiddenBit;
        } else {
            // Subnormal quantum is 2^(minExponent - mantissaBits).
            double quantum = Math.scalb(1.0D,
                    (int) (minUnbiasedExponent - mantissaBits));
            long subnormal = roundToEven(magnitude / quantum);
            long hiddenBit = 1L << mantissaBits;
            if (subnormal <= 0L) {
                return new NBitFloat(exponentBits, mantissaBits, sign);
            }
            if (subnormal >= hiddenBit) {
                // Rounding a subnormal up crosses into the minimum normal.
                encodedExponent = 1L;
                fraction = subnormal - hiddenBit;
            } else {
                encodedExponent = 0L;
                fraction = subnormal;
            }
        }

        long raw = sign | ((encodedExponent & exponentMask) << mantissaBits)
                | (fraction & fractionMask);
        return new NBitFloat(exponentBits, mantissaBits, raw);
    }

    /** Decode raw bits in this configurable IEEE-style format. */
    public static NBitFloat fromRawBits(int exponentBits, int mantissaBits, long rawBits) {
        validate(exponentBits, mantissaBits);
        return new NBitFloat(exponentBits, mantissaBits,
                rawBits & mask(totalBits(exponentBits, mantissaBits)));
    }

    /** Return the encoded sign/exponent/fraction bit pattern. */
    public long rawBits() {
        return rawBits;
    }

    /** Decode this value to the nearest Java double representation. */
    public double toDouble() {
        long fractionMask = mask(mantissaBits);
        long exponent = (rawBits >>> mantissaBits) & mask(exponentBits);
        boolean negative = (rawBits & (1L << (totalBits(exponentBits, mantissaBits) - 1))) != 0L;
        double magnitude;
        long allOnes = mask(exponentBits);
        if (exponent == allOnes) {
            magnitude = fractionMask != 0L && (rawBits & fractionMask) != 0L
                    ? Double.NaN : Double.POSITIVE_INFINITY;
        } else if (exponent == 0L) {
            if (mantissaBits == 0 || (rawBits & fractionMask) == 0L) {
                magnitude = 0.0D;
            } else {
                long fraction = rawBits & fractionMask;
                magnitude = Math.scalb((double) fraction,
                        (int) (1L - bias(exponentBits) - mantissaBits));
            }
        } else {
            long fraction = rawBits & fractionMask;
            double significand = 1.0D + Math.scalb((double) fraction, -mantissaBits);
            magnitude = Math.scalb(significand,
                    (int) (exponent - bias(exponentBits)));
        }
        return negative ? -magnitude : magnitude;
    }

    public int exponentBits() { return exponentBits; }
    public int mantissaBits() { return mantissaBits; }

    private static long roundToEven(double value) {
        // All significands encountered here are at most 2^53, so rint is
        // exact enough for the configurable formats supported by this class.
        return (long) Math.rint(value);
    }

    private static long bias(int exponentBits) {
        return (1L << (exponentBits - 1)) - 1L;
    }

    private static int totalBits(int exponentBits, int mantissaBits) {
        return 1 + exponentBits + mantissaBits;
    }

    private static long mask(int bits) {
        if (bits == 0) return 0L;
        if (bits == 64) return -1L;
        return (1L << bits) - 1L;
    }

    private static void validate(int exponentBits, int mantissaBits) {
        if (exponentBits < 2 || exponentBits > 30) {
            throw new IllegalArgumentException("exponentBits must be in [2, 30]");
        }
        if (mantissaBits < 0 || mantissaBits > 52) {
            throw new IllegalArgumentException("mantissaBits must be in [0, 52]");
        }
        if (totalBits(exponentBits, mantissaBits) > 64) {
            throw new IllegalArgumentException("sign, exponent, and mantissa must fit in 64 bits");
        }
    }
}
