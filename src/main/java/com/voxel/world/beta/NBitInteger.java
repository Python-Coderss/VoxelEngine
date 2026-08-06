package com.voxel.world.beta;

/** Wrapping signed two's-complement integer with a configurable width. */
public final class NBitInteger {
    private final int bits;
    private final long raw;

    private NBitInteger(int bits, long raw) {
        if (bits < 1 || bits > 64) {
            throw new IllegalArgumentException("bits must be in [1, 64]");
        }
        this.bits = bits;
        this.raw = raw & mask(bits);
    }

    public static NBitInteger of(int bits, long value) {
        return new NBitInteger(bits, value);
    }

    /** Convert a finite numeric value by truncating toward zero, then wrap. */
    public static NBitInteger ofDouble(int bits, double value) {
        if (Double.isNaN(value)) return new NBitInteger(bits, 0L);
        if (value >= Long.MAX_VALUE) return new NBitInteger(bits, Long.MAX_VALUE);
        if (value <= Long.MIN_VALUE) return new NBitInteger(bits, Long.MIN_VALUE);
        return new NBitInteger(bits, (long) value);
    }

    public static long mask(int bits) {
        if (bits < 1 || bits > 64) {
            throw new IllegalArgumentException("bits must be in [1, 64]");
        }
        return bits == 64 ? -1L : (1L << bits) - 1L;
    }

    public long signedValue() {
        long sign = 1L << (bits - 1);
        return (raw & sign) == 0L ? raw : raw | ~mask(bits);
    }

    public long unsignedValue() {
        return raw;
    }

    public int bits() {
        return bits;
    }
}
