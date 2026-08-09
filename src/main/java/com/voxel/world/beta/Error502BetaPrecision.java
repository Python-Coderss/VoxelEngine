package com.voxel.world.beta;

/**
 * Experimental coordinate-aware precision policy — backs the OVERWORLD preset
 * after the error502/overworld preset swap (ERROR502 uses
 * {@link OverworldBetaPrecision}).
 *
 * Mirrors the original {@link BetaPrecisionTuning} switch values: integer widths
 * stay at the far-lands baseline (20-bit X/Z int, 15-bit Y int), float
 * mantissas degrade 23→16→12→8→6→4 past 3,500 blocks, X/Z doubles stay fixed
 * at 26 bits, and Y doubles degrade 52→40→18→16→14→11.
 */
public final class Error502BetaPrecision extends BetaPrecisionTuning {

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
            case 0: return 15;
            case 1: return 15;
            case 2: return 15;
            case 3: return 15;
            case 4: return 15;
            default: return 15;
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
            case 1: return 16;
            case 2: return 12;
            case 3: return 8;
            case 4: return 6;
            default: return 4;
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
            case 2: return 12;
            case 3: return 8;
            case 4: return 6;
            default: return 4;
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
            case 2: return 12;
            case 3: return 8;
            case 4: return 6;
            default: return 4;
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
        switch (coordinateBand(x)) {
            default: return 26;
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
            case 2: return 18;
            case 3: return 16;
            case 4: return 14;
            default: return 11;
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
        switch (coordinateBand(z)) {
            default: return 26;
        }
    }
}
