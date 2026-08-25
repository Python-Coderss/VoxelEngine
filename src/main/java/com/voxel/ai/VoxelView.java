package com.voxel.ai;

/**
 * Read-only voxel access for AI code. {@code com.voxel.World} adapts directly
 * via method reference ({@code world::getVoxel}); tests use lambda grids.
 */
public interface VoxelView {

    /** Block type id at the given world coordinate (0 = air). */
    int getVoxel(int x, int y, int z);

    default boolean isAir(int x, int y, int z) {
        return getVoxel(x, y, z) == 0;
    }
}
