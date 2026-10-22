package com.voxel.modloader;

import com.voxel.api.ModBlock;
import com.voxel.api.ModContext;
import com.voxel.api.ModItem;
import com.voxel.api.VoxelMod;

/** Loaded from a generated jar by {@link ModLoaderTest}; never shipped. */
public final class TestMod implements VoxelMod {

    static int id = -1;
    static int ticks = 0;

    @Override
    public String id() {
        return "testmod";
    }

    @Override
    public String name() {
        return "Test Mod";
    }

    @Override
    public void onLoad(ModContext context) {
        id = context.registerBlock(ModBlock.of("test_block")
                .light(255, 200, 120)
                .hardness(1.5f)
                .build());
        context.registerBlockItem("test_block", "Test Block");
        context.registerItem(ModItem.of("test_gem", "Test Gem"));
        context.events().onTick(event -> ticks++);
        context.log("loaded");
    }
}
