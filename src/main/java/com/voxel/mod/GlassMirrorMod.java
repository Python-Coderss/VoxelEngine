package com.voxel.mod;

import com.voxel.api.Direction;
import com.voxel.api.IMod;
import com.voxel.api.BlockRegistry;
import com.voxel.api.StandardBlock;

public class GlassMirrorMod implements IMod {
    @Override
    public String getModId() {
        return "glass_mirror_mod";
    }

    @Override
    public void onInitialize() {
        System.out.println("GlassMirrorMod initializing...");
        StandardBlock superGlass = new StandardBlock("super_glass")
            .setTransparency(180)
            .setReflectivity(220)
            .setSideTexture(Direction.UP, "diamond_block")
            .setSideTexture(Direction.DOWN, "diamond_block");
        
        BlockRegistry.register(superGlass);
    }
}
