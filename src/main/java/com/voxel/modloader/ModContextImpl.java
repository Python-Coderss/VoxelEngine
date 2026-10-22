package com.voxel.modloader;

import com.voxel.api.ModBlock;
import com.voxel.api.ModContext;
import com.voxel.api.ModItem;
import com.voxel.api.VoxelMod;
import com.voxel.api.events.ModEvents;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.BlockRegistry;
import com.voxel.utils.ShaderBlockRegistry;
import com.voxel.utils.TextureManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-mod registration handle. Allocates block ids from the reserved mod range
 * and queues items until the engine's item table is ready.
 */
final class ModContextImpl implements ModContext {

    private final VoxelMod mod;
    private final File assetsDir;
    private final File modelDir;
    private final BlockRegistry blockRegistry;
    private final ShaderBlockRegistry shaderRegistry;
    private final BlockDataManager blockData;
    private final TextureManager textures;
    private final EventBus events;
    private final Map<String, Integer> blockIds = new HashMap<>();
    private final List<ModLoader.QueuedItem> queued = new ArrayList<>();
    private int nextBlockId = ModLoader.FIRST_MOD_BLOCK_ID;

    ModContextImpl(VoxelMod mod, File assetsDir, File modelDir,
                   BlockRegistry blockRegistry, ShaderBlockRegistry shaderRegistry,
                   BlockDataManager blockData, TextureManager textures) {
        this.mod = mod;
        this.assetsDir = assetsDir;
        this.modelDir = modelDir;
        this.blockRegistry = blockRegistry;
        this.shaderRegistry = shaderRegistry;
        this.blockData = blockData;
        this.textures = textures;
        this.events = new EventBus(mod.id());
    }

    @Override
    public String modId() {
        return mod.id();
    }

    @Override
    public File assetsDir() {
        return assetsDir;
    }

    @Override
    public int registerBlock(ModBlock block) {
        if (block == null) throw new IllegalArgumentException("block is required");
        File model = new File(modelDir, block.model + ".json");
        if (!model.isFile()) {
            throw new IllegalStateException("missing model " + model.getPath()
                    + " (expected assets/" + mod.id() + "/models/block/" + block.model + ".json)");
        }
        // Model override: the engine keys block data by the model file name, so
        // copy the chosen model to <block name>.json and register under the
        // block's own name. Names must stay stable for save files.
        if (!block.model.equals(block.name)) {
            File alias = new File(modelDir, block.name + ".json");
            try {
                java.nio.file.Files.copy(model.toPath(), alias.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("could not stage model alias "
                        + alias.getPath() + ": " + e.getMessage(), e);
            }
        }
        int id = allocateId();
        blockRegistry.register(block.name, id);
        shaderRegistry.register(id, id);
        blockData.registerBlock(id, block.name, textures, modelDir.getPath(),
                block.transparency, block.reflectivity, block.diffuse, block.emissive);
        if (!block.fullBlock) blockData.setFullBlock(id, false);
        if (block.hardness >= 0f) blockData.setHardness(id, block.hardness);
        blockData.setMiningTier(id, block.miningTier);
        if (block.emitsLight) blockData.setLightColor(id, block.lightR, block.lightG, block.lightB);
        blockIds.put(block.name, id);
        return id;
    }

    @Override
    public void registerItem(ModItem item) {
        if (item == null) throw new IllegalArgumentException("item is required");
        int layer = textures.getTextureIndex(item.iconTexture);
        queue(new ModLoader.QueuedItem(item.id, item.displayName, 0, layer));
        if (layer < 0) {
            log("item '" + item.id + "' icon texture '" + item.iconTexture + "' was not found");
        }
    }

    @Override
    public void registerBlockItem(String blockName, String displayName) {
        Integer id = blockIds.get(blockName);
        if (id == null) {
            throw new IllegalStateException("registerBlockItem('" + blockName
                    + "') must be called after registerBlock for that block");
        }
        int layer = -1;
        BlockDataManager.BlockData data = blockData.blockRegistry.get(id);
        if (data != null) layer = data.tex[1];
        queue(new ModLoader.QueuedItem(blockName, displayName, id, layer));
    }

    @Override
    public ModEvents events() {
        return events;
    }

    @Override
    public void log(String message) {
        System.out.println("[Mods/" + mod.id() + "] " + message);
    }

    int blockCount() {
        return blockIds.size();
    }

    int itemCount() {
        return queued.size();
    }

    List<ModLoader.QueuedItem> takeQueuedItems() {
        List<ModLoader.QueuedItem> copy = new ArrayList<>(queued);
        queued.clear();
        return copy;
    }

    private void queue(ModLoader.QueuedItem item) {
        if (item.iconLayer < 0) {
            // Keep the item usable with the atlas' first layer rather than
            // handing the renderer an out-of-range index.
            item.iconLayer = 0;
        }
        queued.add(item);
        ModLoader.queuedItems().add(item);
    }

    private int allocateId() {
        while (nextBlockId < ModLoader.MOD_BLOCK_ID_LIMIT) {
            int candidate = nextBlockId++;
            if (!blockData.blockRegistry.containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("mod block-id range exhausted ("
                + ModLoader.FIRST_MOD_BLOCK_ID + ".." + (ModLoader.MOD_BLOCK_ID_LIMIT - 1) + ")");
    }
}
