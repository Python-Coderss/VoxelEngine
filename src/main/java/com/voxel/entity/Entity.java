package com.voxel.entity;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all entities in the voxel engine.
 * Entities are composed of multiple ModelParts, similar to Minecraft.
 */
public class Entity {
    public int id;
    public Vector3f position;
    public float yaw, pitch;
    public List<ModelPart> parts;

    public Entity(int id, Vector3f position) {
        this.id = id;
        this.position = new Vector3f(position);
        this.parts = new ArrayList<>();
        this.yaw = 0;
        this.pitch = 0;
    }

    public void addPart(ModelPart part) {
        parts.add(part);
    }

    public void update(float dt) {
        // Basic update logic, can be overridden
    }
}
