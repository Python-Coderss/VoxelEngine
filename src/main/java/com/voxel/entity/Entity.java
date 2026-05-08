package com.voxel.entity;

import org.joml.Vector3f;
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
        try {
            String content = new String(Files.readAllBytes(Paths.get(path)));
            JSONObject json = new JSONObject(content);
            JSONArray partsArray = json.getJSONArray("parts");
            
            for (int i = 0; i < partsArray.length(); i++) {
                JSONObject p = partsArray.getJSONObject(i);
                String name = p.getString("name");
                JSONArray from = p.getJSONArray("from");
                JSONArray size = p.getJSONArray("size");
                String texture = p.getString("texture");
                
                int texIdx = textureManager.getTextureIndex(texture);
                ModelPart part = new ModelPart(
                    name,
                    new Vector3f((float)from.getDouble(0), (float)from.getDouble(1), (float)from.getDouble(2)),
                    new Vector3f((float)size.getDouble(0), (float)size.getDouble(1), (float)size.getDouble(2)),
                    texIdx
                );

                if (p.has("absolute_offset")) {
                    JSONArray absOff = p.getJSONArray("absolute_offset");
                    part.absoluteOffset.set((float)absOff.getDouble(0), (float)absOff.getDouble(1), (float)absOff.getDouble(2));
                }

                addPart(part);
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
