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

public class BlockDataManager {
    private final Map<Integer, BlockData> blockRegistry = new HashMap<>();
    private int tboId;
    private int textureId;
    
    public static class BlockData {
        public int textureIndex;
        public int transparency; // 0 = opaque, 255 = fully transparent
        public int reflectivity; // 0 = none, 255 = fully reflective
        
        public BlockData(int textureIndex, int transparency, int reflectivity) {
            this.textureIndex = textureIndex;
            this.transparency = transparency;
            this.reflectivity = reflectivity;
        }
    }

    public void registerBlock(int id, String name, TextureManager textureManager, String modelsDir) {
        String jsonPath = modelsDir + "/" + name + ".json";
        int texIndex = -1;
        int transparency = 0;
        int reflectivity = 0;

        try {
            if (Files.exists(Paths.get(jsonPath))) {
                String content = new String(Files.readAllBytes(Paths.get(jsonPath)));
                JSONObject json = new JSONObject(content);
                if (json.has("textures")) {
                    JSONObject textures = json.getJSONObject("textures");
                    String texName = textures.optString("all", textures.optString("top", ""));
                    if (texName.contains("/")) {
                        texName = texName.substring(texName.lastIndexOf('/') + 1);
                    }
                    texIndex = textureManager.getTextureIndex(texName);
                }
            } else {
                // Fallback: try to find texture by the block name itself
                texIndex = textureManager.getTextureIndex(name);
            }
        } catch (IOException e) {
            System.err.println("Could not load block JSON for " + name + ", using fallback.");
            texIndex = textureManager.getTextureIndex(name);
        }

        // Hardcoded special properties for demonstration
        if (name.contains("glass")) {
            transparency = 200;
            reflectivity = 50;
        } else if (name.contains("mirror") || name.equals("diamond_block")) {
            reflectivity = 200;
        } else if (name.contains("water")) {
            transparency = 150;
            reflectivity = 100;
        }

        blockRegistry.put(id, new BlockData(texIndex, transparency, reflectivity));
    }

    public void uploadToGPU() {
        int maxId = 0;
        for (int id : blockRegistry.keySet()) {
            if (id > maxId) maxId = id;
        }

        IntBuffer buffer = MemoryUtil.memAllocInt((maxId + 1) * 4);
        for (int i = 0; i <= maxId; i++) {
            BlockData data = blockRegistry.get(i);
            if (data != null) {
                buffer.put(data.textureIndex);
                buffer.put(data.transparency);
                buffer.put(data.reflectivity);
                buffer.put(0); // padding
            } else {
                buffer.put(-1); buffer.put(0); buffer.put(0); buffer.put(0);
            }
        }
        buffer.flip();

        tboId = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, tboId);
        glBufferData(GL_TEXTURE_BUFFER, buffer, GL_STATIC_DRAW);

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, textureId);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32I, tboId);

        MemoryUtil.memFree(buffer);
    }

    public int getTextureId() {
        return textureId;
    }
}
