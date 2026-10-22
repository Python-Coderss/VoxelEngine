package com.voxel.modloader;

import com.voxel.api.ModContext;
import com.voxel.api.VoxelMod;

/** Throws from onLoad so {@link ModLoaderTest} can prove failures are isolated. */
public final class BrokenTestMod implements VoxelMod {

    @Override
    public String id() {
        return "brokenmod";
    }

    @Override
    public String name() {
        return "Broken Mod";
    }

    @Override
    public void onLoad(ModContext context) {
        throw new IllegalStateException("intentional test failure");
    }
}
