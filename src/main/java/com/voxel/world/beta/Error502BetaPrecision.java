package com.voxel.world.beta;

/**
 * Aggressive coordinate-aware precision policy backing the ERROR502 preset
 * ({@link BetaNumericProfile#DEFAULT}).
 *
 * Integer widths stay at the far-lands baseline (20-bit X/Z int, 17-bit Y int)
 * while float mantissas degrade 23→16→11→6→4→2→1 past 3,500 blocks and X/Z + Y
 * doubles degrade 52→40→30→18→11→6→1. Edit the switch bodies to tune.
 */
public final class Error502BetaPrecision extends BetaPrecisionTuning {

    @Override
    public int xIntBits(double x) {
        return xzIntBits;
    }

    @Override
    public int yIntBits(double y) {
        return 17;
    }

    @Override
    public int zIntBits(double z) {
        return xzIntBits;
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
            case 1: return 16;
            case 2: return 11;
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
            case 1: return 16;
            case 2: return 11;
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
            case 1: return 16;
            case 2: return 11;
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

    @Override
    public int xDoubleMantissaBits(double x) {
        // Full 52-bit doubles at origin, degrading with distance.
        switch (coordinateBand(x)) {
            case 0: return 52;
            case 1: return 40;
            case 2: return 30;
            case 3: return 18;
            case 4: return 11;
            case 5: return 6;
            default: return 1;
        }
    }

    @Override
    public int yDoubleExponentBits(double y) {
        switch (coordinateBandY(y)) {
            default: return 11;
        }
    }

    @Override
    public int yDoubleMantissaBits(double y) {
        switch (coordinateBandY(y)) {
            case 0: return 52;
            case 1: return 40;
            case 2: return 30;
            case 3: return 18;
            case 4: return 11;
            case 5: return 6;
            default: return 1;
        }
    }

    @Override
    public int zDoubleExponentBits(double z) {
        switch (coordinateBand(z)) {
            default: return 11;
        }
    }

    @Override
    public int zDoubleMantissaBits(double z) {
        // Full 52-bit doubles at origin, degrading with distance.
        switch (coordinateBand(z)) {
            case 0: return 52;
            case 1: return 40;
            case 2: return 30;
            case 3: return 18;
            case 4: return 11;
            case 5: return 6;
            default: return 1;
        }
    }
}
