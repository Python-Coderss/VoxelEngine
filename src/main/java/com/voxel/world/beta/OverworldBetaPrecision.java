package com.voxel.world.beta;

/**
 * Aggressive coordinate-aware precision policy — backs the ERROR502 preset
 * after the error502/overworld preset swap (the overworld uses
 * {@link Error502BetaPrecision}).
 *
 * Integer widths stay at the far-lands baseline (20-bit X/Z int, 15-bit Y int)
 * while float mantissas degrade 23→16→11→6→4→2 past 3,500 blocks and X/Z + Y
 * doubles degrade 52→40→30→18→11→6. Edit the switch bodies to tune.
 */
public final class OverworldBetaPrecision extends BetaPrecisionTuning {

    @Override
    public int xIntBits(double x) {
        switch (coordinateBand(x)) {
            default: return 20;
        }
    }

    @Override
    public int yIntBits(double y) {
        switch (coordinateBandY(y)) {
            default: return 15;
        }
    }

    @Override
    public int zIntBits(double z) {
        switch (coordinateBand(z)) {
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
            case 2: return 11;
            case 3: return 6;
            case 4: return 4;
            default: return 2;
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
	        default: return 2;
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
	        default: return 2;
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
        // Full 52-bit doubles at origin, degrading with distance — mirrors the
        // Y branch and the class contract (was 26/2 before the preset swap).
        switch (coordinateBand(x)) {
	        case 0: return 52;
	        case 1: return 40;
	        case 2: return 30;
	        case 3: return 18;
	        case 4: return 11;
	        default: return 6;
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
            default: return 6;
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
        // Full 52-bit doubles at origin, degrading with distance — mirrors the
        // Y branch and the class contract (was 26/6 before the preset swap).
        switch (coordinateBand(z)) {
	        case 0: return 52;
	        case 1: return 40;
	        case 2: return 30;
	        case 3: return 18;
	        case 4: return 11;
	        default: return 6;
        }
    }
}
