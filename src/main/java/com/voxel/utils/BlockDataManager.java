package com.voxel.utils;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;

/**
 * Manages properties for each type of block (voxel) in the engine.
 * It parses Minecraft-style JSON models to determine which texture goes on which face,
 * and handles uploading this property data to the GPU in a TBO (Texture Buffer Object).
 */
public class BlockDataManager {
    // Stores block data indexed by their integer ID.
    private final Map<Integer, BlockData> blockRegistry = new HashMap<>();

    // OpenGL IDs for the Buffer and the Texture Buffer Object (TBO).
    private int tboId;
    private int textureId;
    
    /**
     * Inner class representing the properties of a single block type.
     */
    public static class BlockData {
        // Texture indices for each of the 6 faces:
        // 0: -Y (Bottom), 1: +Y (Top), 2: -Z (North), 3: +Z (South), 4: -X (West), 5: +X (East)
        public int[] tex = new int[6];

        // Opacity level (0 = fully opaque, 255 = fully transparent).
        public int transparency;

        // Reflection intensity (0 = matte, 255 = perfect mirror).
        public int reflectivity;
        
        // Whether the block color should be modified by the biome (e.g., grass).
        public int isTintable;

        /** Initializes a block with no textures. */
        public BlockData() {
            for(int i=0; i<6; i++) tex[i] = -1;
        }
    }

    /**
     * Registers a new block by parsing its JSON model and mapping textures.
     * @param id The integer ID to assign to this block (must be > 0).
     * @param name The name of the block (matches the model and texture names).
     * @param textureManager The manager containing loaded textures.
     * @param modelsDir Directory where model JSON files are located.
     */
    public void registerBlock(int id, String name, TextureManager textureManager, String modelsDir) {
        BlockData data = new BlockData();
        Map<String, String> textureMap = new HashMap<>();

        // Recursively resolve the block's model hierarchy to find all textures.
        resolveModelRecursive(name, modelsDir, textureMap, data);

        // Map collected textures from the model to the 6 physical faces.
        String all = textureMap.get("all");
        String side = textureMap.get("side");
        String end = textureMap.get("end");
        String top = textureMap.get("top");
        String bottom = textureMap.get("bottom");

        // Map common Minecraft JSON keys to the 6 face indices.
        assignFace(data, 0, textureMap.getOrDefault("down",  bottom != null ? bottom : (end != null ? end : all)), textureManager);
        assignFace(data, 1, textureMap.getOrDefault("up",    top != null ? top : (end != null ? end : all)), textureManager);
        assignFace(data, 2, textureMap.getOrDefault("north", side != null ? side : all), textureManager);
        assignFace(data, 3, textureMap.getOrDefault("south", side != null ? side : all), textureManager);
        assignFace(data, 4, textureMap.getOrDefault("west",  side != null ? side : all), textureManager);
        assignFace(data, 5, textureMap.getOrDefault("east",  side != null ? side : all), textureManager);

        // Set demonstration properties based on block name.
        if (name.contains("glass")) {
            data.transparency = 150;
            data.reflectivity = 50;
        } else if (name.contains("mirror") || name.equals("diamond_block")) {
            data.reflectivity = 200;
        } else if (name.contains("water")) {
            data.transparency = 150;
            data.reflectivity = 100;
        }

        // Validate that we found a texture for every face.
        for (int i = 0; i < 6; i++) {
            if (data.tex[i] == -1) {
                throw new RuntimeException("Texture for block " + name + " (face " + i + ") could not be resolved!");
            }
        }

        blockRegistry.put(id, data);
    }

    /** Helper to resolve a texture key to its index in the TextureManager. */
    private void assignFace(BlockData data, int face, String texName, TextureManager textureManager) {
        if (texName == null) return;
        if (texName.startsWith("#")) return; // Unresolved variable reference
        
        // Strip path to get the texture name
        if (texName.contains("/")) {
            texName = texName.substring(texName.lastIndexOf('/') + 1);
        }
        data.tex[face] = textureManager.getTextureIndex(texName);
    }

    /**
     * Recursively parses JSON files to handle model inheritance (e.g., 'cube_all' inherits from 'cube').
     */
    private void resolveModelRecursive(String modelName, String modelsDir, Map<String, String> textureMap, BlockData data) {
        if (modelName.startsWith("block/")) modelName = modelName.substring(6);
        String jsonPath = modelsDir + "/" + modelName + ".json";
        
        if (!Files.exists(Paths.get(jsonPath))) return;

        try {
            String content = new String(Files.readAllBytes(Paths.get(jsonPath)));
            JSONObject json = new JSONObject(content);

            // Check if this model is marked as tintable (e.g., grass).
            if (content.contains("\"tintindex\"")) {
                data.isTintable = 1;
            }

            // Extract textures defined in this model.
            if (json.has("textures")) {
                JSONObject texJson = json.getJSONObject("textures");
                for (String key : texJson.keySet()) {
                    String val = texJson.getString(key);
                    // Child models override parents, so only put if key is missing.
                    if (!textureMap.containsKey(key)) {
                        textureMap.put(key, val);
                    }
                }
            }

            // Recurse into the parent model if one is specified.
            if (json.has("parent")) {
                String parent = json.getString("parent");
                if (!parent.equals("block/block") && !parent.equals("block/cube") && !parent.equals("block/cube_all") && !parent.equals("block/cube_column")) {
                     resolveModelRecursive(parent, modelsDir, textureMap, data);
                }
            }

        } catch (IOException e) {
            // Error reading file, stop recursion.
        }
    }

    /**
     * Packs the block property data into a compact format and uploads it to the GPU.
     * Each block uses 8 integers (2 ivec4 in GLSL).
     */
    public void uploadToGPU() {
        int maxId = 0;
        for (int id : blockRegistry.keySet()) {
            if (id > maxId) maxId = id;
        }

        // Allocate memory for the property buffer.
        IntBuffer buffer = MemoryUtil.memAllocInt((maxId + 1) * 8);
        for (int i = 0; i <= maxId; i++) {
            BlockData data = blockRegistry.get(i);
            if (data != null) {
                // Layout for Shader:
                // ivec4(tex-Y, tex+Y, tex-Z, tex+Z)
                buffer.put(data.tex[0]);
                buffer.put(data.tex[1]);
                buffer.put(data.tex[2]);
                buffer.put(data.tex[3]);
                
                // ivec4(tex-X, tex+X, transparency, packed_refl_tint)
                buffer.put(data.tex[4]);
                buffer.put(data.tex[5]);
                buffer.put(data.transparency);
                int packed = (data.reflectivity & 0xFF) | ((data.isTintable & 1) << 8);
                buffer.put(packed);
            } else {
                // Fill with -1 for unused IDs.
                for(int j = 0; j < 8; j++) buffer.put(-1);
            }
        }
        buffer.flip();

        // 1. Create a Generic Buffer to store the data.
        tboId = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, tboId);
        glBufferData(GL_TEXTURE_BUFFER, buffer, GL_STATIC_DRAW);

        // 2. Create a Texture Buffer Object (TBO) which interprets the buffer as a texture.
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, textureId);
        // Link the texture to the buffer with an integer format.
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32I, tboId);

        MemoryUtil.memFree(buffer); // Clean up CPU memory.
    }

    /** @return The ID of the Texture Buffer Object. */
    public int getTextureId() {
        return textureId;
    }
}
