package com.voxel.utils;

import org.joml.Vector3i;

/**
 * Represents the 6 cardinal directions in 3D space.
 * Used for navigating the voxel grid (e.g., finding neighbors).
 */
public enum Direction {
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    EAST(1, 0, 0),
    WEST(-1, 0, 0),
    UP(0, 1, 0),
    DOWN(0, -1, 0);

    /** The unit vector components for this direction. */
    public final int x, y, z;

    /**
     * Constructor for the direction.
     * @param x X offset.
     * @param y Y offset.
     * @param z Z offset.
     */
    Direction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Applies this direction's offset to a given position.
     * @param pos The base position.
     * @return A new Vector3i representing the neighbor position.
     */
    public Vector3i offset(Vector3i pos) {
        return new Vector3i(pos.x + x, pos.y + y, pos.z + z);
    }
}
