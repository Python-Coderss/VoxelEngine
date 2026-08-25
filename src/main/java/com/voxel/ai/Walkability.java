package com.voxel.ai;

/**
 * Traversal rules for a body occupying voxel cells. The default matches the
 * existing humanoid mobs: solid ground below, two air blocks of clearance.
 */
public interface Walkability {

    boolean isWalkable(VoxelView view, int x, int y, int z);

    Walkability HUMANOID = new Walkability() {
        @Override
        public boolean isWalkable(VoxelView view, int x, int y, int z) {
            if (view.getVoxel(x, y - 1, z) == 0) return false;
            if (view.getVoxel(x, y, z) != 0) return false;
            if (view.getVoxel(x, y + 1, z) != 0) return false;
            return true;
        }
    };
}
