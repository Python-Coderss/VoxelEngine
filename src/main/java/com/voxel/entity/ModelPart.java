package com.voxel.entity;

import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Represents a single box part of an entity's model.
 */
public class ModelPart {
    public static final int TEXTURE_MAPPING_PLANAR = 0;
    public static final int TEXTURE_MAPPING_CUBOID_ATLAS = 1;

    public String name;
    public Vector3f offset; // Relative to 0,0,0 in 32x32x32 space
    public Vector3f absoluteOffset; // Relative to the entity xyz
    public Vector3f size;   // Dimensions in 32x32x32 space
    public Vector3f rotation; // Euler angles
    public int textureIndex;
    public int textureMapping;
    public Vector2f uvOrigin; // Top-left of the part's texture in the source (e.g. 64x64 skin)

    public ModelPart(String name, Vector3f offset, Vector3f size, int textureIndex) {
        this.name = name;
        this.offset = new Vector3f(offset);
        this.absoluteOffset = new Vector3f(0, 0, 0);
        this.size = new Vector3f(size);
        this.rotation = new Vector3f(0, 0, 0);
        this.textureIndex = textureIndex;
        this.textureMapping = TEXTURE_MAPPING_PLANAR;
        this.uvOrigin = new Vector2f(0, 0);
    }
}
