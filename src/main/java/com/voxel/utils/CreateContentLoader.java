package com.voxel.utils;

import com.voxel.game.ItemDefinitions;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Registers the ported Create mod components from the manifest written by
 * {@code tools/port_create_models.py} ({@code src/main/resources/create_content.json}).
 *
 * The manifest carries stable block IDs (5000+), display names, icon textures
 * and full-block flags, so the block IDs never depend on directory-scan order
 * and saved worlds keep their block mapping across content updates. Models and
 * textures live alongside the vanilla pack resources; the item icon is the
 * model's first texture.
 */
public final class CreateContentLoader {
    private CreateContentLoader() { }

    private static final String MANIFEST = "src/main/resources/create_content.json";
    private static final String MODELS_DIR = "src/main/resources/assets/minecraft/models/block";

    private static JSONArray readManifest() {
        Path manifestPath = Paths.get(MANIFEST);
        if (!Files.isRegularFile(manifestPath)) return new JSONArray();
        try {
            return new JSONArray(new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to read Create content manifest: " + MANIFEST, ex);
        }
    }

    /**
     * Registers the block half during setupResources (after textures are
     * loaded). Items are registered separately because ItemDefinitions is
     * constructed later in init().
     *
     * @return the number of blocks registered.
     */
    public static int registerBlocks(BlockDataManager blockDataManager,
                                     BlockRegistry blockRegistry,
                                     ShaderBlockRegistry shaderBlockRegistry,
                                     TextureManager textureManager) {
        JSONArray entries = readManifest();
        int registered = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String name = e.getString("name");
            int id = e.getInt("id");
            if (blockRegistry.hasName(name)) continue;

            blockRegistry.register(name, id);
            shaderBlockRegistry.register(id, id);
            blockDataManager.registerBlock(id, name, textureManager, MODELS_DIR);
            if (!e.optBoolean("fullBlock", true)) {
                blockDataManager.setFullBlock(id, false);
            }
            blockDataManager.setHardness(id, (float) e.optDouble("hardness", 1.5));
            registered++;
        }

        System.out.println("[Create content] Registered " + registered + " Create mod blocks");
        return registered;
    }

    /** Registers the inventory items once ItemDefinitions exists (init phase). */
    public static int registerItems(ItemDefinitions itemDefinitions,
                                    TextureManager textureManager) {
        JSONArray entries = readManifest();
        int registered = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String name = e.getString("name");
            int id = e.getInt("id");
            String icon = e.optString("icon", "");
            int iconLayer = icon.isEmpty() ? -1 : textureManager.getTextureIndex(icon);
            itemDefinitions.registerGeneratedItem(name, e.optString("displayName", name), id, iconLayer);
            registered++;
        }
        return registered;
    }
}
