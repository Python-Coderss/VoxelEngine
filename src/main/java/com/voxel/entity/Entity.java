package com.voxel.entity;

import org.joml.Vector3f;
import org.joml.Vector2f;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all entities in the voxel engine.
 * Entities are composed of multiple ModelParts, similar to Minecraft.
 */
public class Entity {
    public int id;
    public Vector3f position;
    public Vector3f rotation; // x=pitch, y=yaw, z=roll
    public List<ModelPart> parts;

    public Entity(int id, Vector3f position) {
        this.id = id;
        this.position = new Vector3f(position);
        this.parts = new ArrayList<>();
        this.rotation = new Vector3f(0, 0, 0);
    }

    public void loadModel(String path, com.voxel.utils.TextureManager textureManager) {
        java.util.Map<String, ModelPart> loadedParts = new java.util.LinkedHashMap<>();
        loadModelRecursive(path, textureManager, loadedParts);
        this.parts.clear();
        this.parts.addAll(loadedParts.values());
    }

    private int resolveTextureIndex(String texName, com.voxel.utils.TextureManager textureManager) {
        return textureManager.getTextureIndex(texName);
    }

    private void loadModelRecursive(String path, com.voxel.utils.TextureManager textureManager, java.util.Map<String, ModelPart> loadedParts) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(path)));
            JSONObject json = new JSONObject(content);

            // 1. Handle Parent Inheritance
            if (json.has("parent")) {
                String parentPath = json.getString("parent");
                // Resolve relative path if necessary
                if (!parentPath.startsWith("src/")) {
                    java.nio.file.Path currentPath = Paths.get(path);
                    parentPath = currentPath.getParent().resolve(parentPath).toString();
                }
                loadModelRecursive(parentPath, textureManager, loadedParts);
            }

            // 2. Load/Override Parts
            if (json.has("parts")) {
                JSONArray partsArray = json.getJSONArray("parts");
                for (int i = 0; i < partsArray.length(); i++) {
                    JSONObject p = partsArray.getJSONObject(i);
                    String name = p.getString("name");
                    
                    ModelPart part = loadedParts.get(name);
                    
                    if (part == null) {
                        // New part - requires basic fields
                        JSONArray from = p.getJSONArray("from");
                        JSONArray size = p.getJSONArray("size");
                        String texName = p.getString("texture");
                        int texIdx = resolveTextureIndex(texName, textureManager);
                        
                        part = new ModelPart(
                            name,
                            new Vector3f((float)from.getDouble(0), (float)from.getDouble(1), (float)from.getDouble(2)),
                            new Vector3f((float)size.getDouble(0), (float)size.getDouble(1), (float)size.getDouble(2)),
                            texIdx
                        );
                        
                        if (p.has("uv")) {
                            JSONArray uv = p.getJSONArray("uv");
                            part.uvOrigin.set((float)uv.getDouble(0), (float)uv.getDouble(1));
                        }
                        
                        loadedParts.put(name, part);
                    } else {
                        // Override existing part properties
                        if (p.has("from")) {
                            JSONArray from = p.getJSONArray("from");
                            part.offset.set((float)from.getDouble(0), (float)from.getDouble(1), (float)from.getDouble(2));
                        }
                        if (p.has("size")) {
                            JSONArray size = p.getJSONArray("size");
                            part.size.set((float)size.getDouble(0), (float)size.getDouble(1), (float)size.getDouble(2));
                        }
                        if (p.has("texture")) {
                            part.textureIndex = resolveTextureIndex(p.getString("texture"), textureManager);
                        }
                        if (p.has("uv")) {
                            JSONArray uv = p.getJSONArray("uv");
                            part.uvOrigin.set((float)uv.getDouble(0), (float)uv.getDouble(1));
                        }
                    }

                    // Optional/Overrideable fields
                    if (p.has("absolute_offset")) {
                        JSONArray absOff = p.getJSONArray("absolute_offset");
                        part.absoluteOffset.set((float)absOff.getDouble(0), (float)absOff.getDouble(1), (float)absOff.getDouble(2));
                    }
                    
                    if (p.has("rotation")) {
                        JSONArray rot = p.getJSONArray("rotation");
                        part.rotation.set((float)rot.getDouble(0), (float)rot.getDouble(1), (float)rot.getDouble(2));
                    }

                    if (p.has("texture_mapping")) {
                        String mapping = p.getString("texture_mapping");
                        if ("cuboid_atlas".equals(mapping)) {
                            part.textureMapping = ModelPart.TEXTURE_MAPPING_CUBOID_ATLAS;
                        } else {
                            part.textureMapping = ModelPart.TEXTURE_MAPPING_PLANAR;
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load entity model: " + path);
        }
    }

    public void addPart(ModelPart part) {
        parts.add(part);
    }

    public void update(float dt) {
        // Basic update logic, can be overridden
    }
}
