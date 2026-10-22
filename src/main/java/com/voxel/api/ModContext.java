package com.voxel.api;

import com.voxel.api.events.ModEvents;

import java.io.File;

/**
 * The per-mod handle handed to {@link VoxelMod#onLoad(ModContext)}.
 *
 * <p>Everything a mod registers goes through this object so the loader can
 * scope it to the mod (id allocation, asset directory, logging) and keep
 * loading when one mod fails.</p>
 */
public interface ModContext {

    /** The owning mod's {@link VoxelMod#id()}. */
    String modId();

    /**
     * Extracted asset root for this mod ({@code mods/.cache/<jar>/assets/<modid>}).
     * Useful for reading extra data files shipped in the jar. May not exist.
     */
    File assetsDir();

    /**
     * Registers a block and allocates its numeric id.
     *
     * <p>The block's model is read from
     * {@code assets/<modid>/models/block/<model>.json} and its textures are
     * loaded from {@code assets/<modid>/textures/blocks} (already scanned during
     * resource setup).</p>
     *
     * @return the allocated block id
     * @throws IllegalStateException when the id space is exhausted or the mod's
     *                               model file cannot be found
     */
    int registerBlock(ModBlock block);

    /**
     * Registers an item for this mod. Block items are normally registered with
     * {@link #registerBlockItem(String, String)}; this entry point is for
     * standalone items whose icon is a 16x16 PNG in the mod's texture folder.
     */
    void registerItem(ModItem item);

    /**
     * Convenience for the common case: a placeable block that also exists as an
     * item in the creative inventory. The item id is the block name.
     *
     * @param blockName  name passed to {@link #registerBlock(ModBlock)}
     * @param displayName name shown in the hotbar/inventory
     */
    void registerBlockItem(String blockName, String displayName);

    /** Event listeners (block break/place, tick, world load). */
    ModEvents events();

    /** Writes a line to the game log, prefixed with the mod id. */
    void log(String message);
}
