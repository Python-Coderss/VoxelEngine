package com.voxel.world.beta;

/**
 * Coordinate-aware precision policy backing the OVERWORLD preset
 * ({@link BetaNumericProfile#OVERWORLD}).
 *
 * Integer widths stay at the far-lands baseline (20-bit X/Z int, 17-bit Y int).
 * X/Z and Y float/double precision remain at full standard precision through
 * the chunk-aligned 4,000-block boundary, keeping vertical quantization out of
 * near-spawn horizontal behavior. Beyond that first band, each axis follows
 * the tuned degradation stages; X/Z and Y doubles use the historical 26-bit
 * mask after the full-precision band.
 * Edit the switch bodies or band override to tune.
 */
public final class OverworldBetaPrecision extends BetaPrecisionTuning {

    /** Keep horizontal float precision at the full stage through X/Z 4000. */
    private static final double FULL_XZ_PRECISION_BLOCKS = 4000.0D;

    @Override
    public int coordinateBand(double coordinate) {
        double distance = Math.abs(chunkOffset(coordinate));
        if (distance <= FULL_XZ_PRECISION_BLOCKS) return 0;
        // Preserve the width of each later tuning band while moving the whole
        // horizontal degradation curve back by 500 blocks.
        if (distance < 4500.0D) return 1;
        if (distance < 5000.0D) return 2;
        if (distance < 5500.0D) return 3;
        if (distance < 6000.0D) return 4;
        if (distance < 6500.0D) return 5;
        return 6;
    }

    /** Keep Y on the same chunk-aligned 4,000+ bands as X/Z. */
    @Override
    public int coordinateBandY(double coordinate) {
        return coordinateBand(coordinate);
    }

    @Override
    public int xIntBits(double x) {
        switch (coordinateBand(x)) {
            case 0: return 20;
            case 1: return 20;
            case 2: return 20;
            case 3: return 20;
            case 4: return 20;
            default: return 20;
        }
    }

    @Override
    public int yIntBits(double y) {
        switch (coordinateBandY(y)) {
            case 0: return 17;
            case 1: return 17;
            case 2: return 17;
            case 3: return 17;
            case 4: return 17;
            default: return 17;
        }
    }

    @Override
    public int zIntBits(double z) {
        switch (coordinateBand(z)) {
            case 0: return 20;
            case 1: return 20;
            case 2: return 20;
            case 3: return 20;
            case 4: return 20;
            default: return 20;
        }
    }

    @Override
    public int xFloatExponentBits(double x) {
        switch (coordinateBand(x)) {
            default: return 8;
        }
    }

    @Override
    public int xFloatMantissaBits(double x) {
        switch (coordinateBand(x)) {
            case 0: return 23;
            case 1: return 20;
            case 2: return 12;
            case 3: return 6;
            case 4: return 4;
            case 5: return 2;
            default: return 1;
        }
    }

    @Override
    public int yFloatExponentBits(double y) {
        switch (coordinateBandY(y)) {
            default: return 8;
        }
    }

    @Override
    public int yFloatMantissaBits(double y) {
        switch (coordinateBandY(y)) {
            case 0: return 23;
            case 1: return 20;
            case 2: return 12;
            case 3: return 6;
            case 4: return 4;
            case 5: return 2;
            default: return 1;
        }
    }

    @Override
    public int zFloatExponentBits(double z) {
        switch (coordinateBand(z)) {
            default: return 8;
        }
    }

    @Override
    public int zFloatMantissaBits(double z) {
        switch (coordinateBand(z)) {
            case 0: return 23;
            case 1: return 20;
            case 2: return 12;
            case 3: return 6;
            case 4: return 4;
            case 5: return 2;
            default: return 1;
        }
    }

    @Override
    public int xDoubleExponentBits(double x) {
        switch (coordinateBand(x)) {
            default: return 11;
        }
    }

    /** Keep full X double precision through the chunk-aligned 4000 boundary, then use the historical mask. */
    @Override
    public int xDoubleMantissaBits(double x) {
        return coordinateBand(x) == 0 ? 52 : 26;
    }

    @Override
    public int yDoubleExponentBits(double y) {
        switch (coordinateBandY(y)) {
            default: return 11;
        }
    }

    @Override
    public int yDoubleMantissaBits(double y) {
        return coordinateBandY(y) == 0 ? 52 : 26;
    }

    @Override
    public int zDoubleExponentBits(double z) {
        switch (coordinateBand(z)) {
            default: return 11;
        }
    }

    /** Keep full Z double precision through the chunk-aligned 4000 boundary, then use the historical mask. */
    @Override
    public int zDoubleMantissaBits(double z) {
        return coordinateBand(z) == 0 ? 52 : 26;
    }
}
