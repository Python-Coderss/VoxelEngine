package com.example.lantern;

import com.voxel.api.ModBlock;
import com.voxel.api.ModContext;
import com.voxel.api.VoxelMod;

/**
 * Example mod: adds a warm glowing lantern block and an item for it.
 *
 * <p>Build it with {@code build.bat} and drop the resulting jar into
 * {@code mods/}. See {@code docs/MODDING.md}.</p>
 */
public final class LanternMod implements VoxelMod {

    @Override
    public String id() {
        return "example";
    }

    @Override
    public String name() {
        return "Example Lantern";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public void onLoad(ModContext ctx) {
        int id = ctx.registerBlock(ModBlock.of("glow_lantern")
                .emissive(220)
                .light(255, 214, 140)
                .hardness(0.6f)
                .build());
        ctx.registerBlockItem("glow_lantern", "Glow Lantern");
        ctx.log("registered glow_lantern as block id " + id);

        // Events are optional; this one welcomes the player and shows how to
        // read the world through the event payload.
        ctx.events().onWorldLoad(event ->
                ctx.log("world ready: " + event.saveName
                        + (event.freshWorld ? " (new)" : " (loaded)")));
    }
}
