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
        public int[] tex = new int[6]; // 0: -Y, 1: +Y, 2: -Z, 3: +Z, 4: -X, 5: +X
        public int transparency; // 0 = opaque, 255 = fully transparent
        public int reflectivity; // 0 = none, 255 = fully reflective
        public int isTintable; // 0 = false, 1 = true
        
        public BlockData() {
            for(int i=0; i<6; i++) tex[i] = -1;
        }
    }

    public void registerBlock(int id, String name, TextureManager textureManager, String modelsDir) {
        BlockData data = new BlockData();
        Map<String, String> textureMap = new HashMap<>();
        resolveModelRecursive(name, modelsDir, textureMap, data);

        // Map collected textures to the 6 faces
        // 1.12.2 common keys: all, side, end, top, bottom, up, down, north, south, west, east
        String all = textureMap.get("all");
        String side = textureMap.get("side");
        String end = textureMap.get("end");
        String top = textureMap.get("top");
        String bottom = textureMap.get("bottom");

        // Order: 0:-Y, 1:+Y, 2:-Z, 3:+Z, 4:-X, 5:+X
        assignFace(data, 0, textureMap.getOrDefault("down",  bottom != null ? bottom : (end != null ? end : all)), textureManager);
        assignFace(data, 1, textureMap.getOrDefault("up",    top != null ? top : (end != null ? end : all)), textureManager);
        assignFace(data, 2, textureMap.getOrDefault("north", side != null ? side : all), textureManager);
        assignFace(data, 3, textureMap.getOrDefault("south", side != null ? side : all), textureManager);
        assignFace(data, 4, textureMap.getOrDefault("west",  side != null ? side : all), textureManager);
        assignFace(data, 5, textureMap.getOrDefault("east",  side != null ? side : all), textureManager);

        // Hardcoded special properties for demonstration
        if (name.contains("glass")) {
            data.transparency = 150;
            data.reflectivity = 50;
        } else if (name.contains("mirror") || name.equals("diamond_block")) {
            data.reflectivity = 200;
        } else if (name.contains("water")) {
            data.transparency = 150;
            data.reflectivity = 100;
        }

        // Final verification: Ensure all faces have a texture
        for (int i = 0; i < 6; i++) {
            if (data.tex[i] == -1) {
                throw new RuntimeException("Texture for block " + name + " (face " + i + ") could not be resolved!");
            }
        }

        blockRegistry.put(id, data);
    }

    private void assignFace(BlockData data, int face, String texName, TextureManager textureManager) {
        if (texName == null) return;
        if (texName.startsWith("#")) return; // Skip unresolved references (handled by recursion)
        
        if (texName.contains("/")) {
            texName = texName.substring(texName.lastIndexOf('/') + 1);
        }
        data.tex[face] = textureManager.getTextureIndex(texName);
    }

    private void resolveModelRecursive(String modelName, String modelsDir, Map<String, String> textureMap, BlockData data) {
        if (modelName.startsWith("block/")) modelName = modelName.substring(6);
        String jsonPath = modelsDir + "/" + modelName + ".json";
        
        if (!Files.exists(Paths.get(jsonPath))) return;

        try {
            String content = new String(Files.readAllBytes(Paths.get(jsonPath)));
            JSONObject json = new JSONObject(content);

            if (content.contains("\"tintindex\"")) {
                data.isTintable = 1;
            }

            if (json.has("textures")) {
                JSONObject texJson = json.getJSONObject("textures");
                for (String key : texJson.keySet()) {
                    String val = texJson.getString(key);
                    // Only put if we don't have it (child overrides parent)
                    if (!textureMap.containsKey(key)) {
                        textureMap.put(key, val);
                    }
                }
            }

            if (json.has("parent")) {
                String parent = json.getString("parent");
                // Stop recursion at vanilla base parents
                if (!parent.equals("block/block") && !parent.equals("block/cube") && !parent.equals("block/cube_all") && !parent.equals("block/cube_column")) {
                     resolveModelRecursive(parent, modelsDir, textureMap, data);
                }
            }

        } catch (IOException e) {
            // Stop recursion on error
        }
    }

    public void uploadToGPU() {
        int maxId = 0;
        for (int id : blockRegistry.keySet()) {
            if (id > maxId) maxId = id;
        }

        // Pack each block into 2 ivec4 (8 ints)
        // [tex-Y, tex+Y, tex-Z, tex+Z]
        // [tex-X, tex+X, transparency, packed_refl_tint]
        IntBuffer buffer = MemoryUtil.memAllocInt((maxId + 1) * 8);
        for (int i = 0; i <= maxId; i++) {
            BlockData data = blockRegistry.get(i);
            if (data != null) {
                buffer.put(data.tex[0]);
                buffer.put(data.tex[1]);
                buffer.put(data.tex[2]);
                buffer.put(data.tex[3]);
                
                buffer.put(data.tex[4]);
                buffer.put(data.tex[5]);
                buffer.put(data.transparency);
                int packed = (data.reflectivity & 0xFF) | ((data.isTintable & 1) << 8);
                buffer.put(packed);
            } else {
                for(int j = 0; j < 8; j++) buffer.put(-1);
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
