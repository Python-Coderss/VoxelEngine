package com.voxel.entity;

import org.joml.Vector3f;

/**
 * Represents a single box part of an entity's model.
 */
public class ModelPart {
    public String name;
    public Vector3f offset; // Relative to 0,0,0 in 32x32x32 space
    public Vector3f size;   // Dimensions in 32x32x32 space
    public Vector3f rotation; // Euler angles
    public int textureIndex;

    public ModelPart(String name, Vector3f offset, Vector3f size, int textureIndex) {
        this.name = name;
        this.offset = new Vector3f(offset);
        this.size = new Vector3f(size);
        this.rotation = new Vector3f(0, 0, 0);
        this.textureIndex = textureIndex;
    }
}
