package com.voxel.entity;

import org.joml.Vector3f;

/**
 * Represents a single box part of an entity's model.
 */
public class ModelPart {
    public Vector3f offset; // Position relative to entity origin
    public Vector3f size;
    public Vector3f rotation; // Euler angles (pitch, yaw, roll)
    public int textureIndex;

    public ModelPart(Vector3f offset, Vector3f size, int textureIndex) {
        this.offset = new Vector3f(offset);
        this.size = new Vector3f(size);
        this.rotation = new Vector3f(0, 0, 0);
        this.textureIndex = textureIndex;
    }
}
