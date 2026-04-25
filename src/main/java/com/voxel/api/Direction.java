package com.voxel.api;

public enum Direction {
    UP(0), DOWN(1), NORTH(2), SOUTH(3), EAST(4), WEST(5);

    public final int id;
    Direction(int id) { this.id = id; }
    
    public static Direction fromSide(int side) {
        // Mapping from shader 'side' (0=X, 1=Y, 2=Z) and sign
        // This needs to match the shader's normal/side logic.
        // For now, we'll use a simpler mapping or rely on a different scheme if needed.
        return values()[side % 6];
    }
}
