package com.voxel.utils;

import org.joml.Vector3i;

public enum Direction {
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    EAST(1, 0, 0),
    WEST(-1, 0, 0),
    UP(0, 1, 0),
    DOWN(0, -1, 0);

    public final int x, y, z;

    Direction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3i offset(Vector3i pos) {
        return new Vector3i(pos.x + x, pos.y + y, pos.z + z);
    }
}
