package com.voxel.jem;

import org.joml.Vector4f;

public final class EntityPartMetadata {
    public final Vector4f[] uvs = new Vector4f[6]; // u, v, w, h
    public int textureIdx;

    public EntityPartMetadata() {
        for (int i = 0; i < 6; i++) {
            uvs[i] = new Vector4f();
        }
    }
}
