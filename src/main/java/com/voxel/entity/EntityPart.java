package com.voxel.entity;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class EntityPart {
    public final String name;
    public final Vector3f position = new Vector3f();
    public final Vector3f pivot = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();
    public final Vector3f scale = new Vector3f(1.0f / 16.0f);
    
    public final Matrix4f localTransform = new Matrix4f();
    public final Matrix4f worldTransform = new Matrix4f();
    public final Matrix4f worldToLocal = new Matrix4f();
    
    public short[] voxelData; // 16x16x16
    public int gpuBufferOffset = -1;
    
    public EntityPart parent;
    public final List<EntityPart> children = new ArrayList<>();

    public EntityPart(String name) {
        this.name = name;
    }

    public void updateTransforms(Matrix4f parentTransform) {
        localTransform.identity()
                .translate(position)
                .translate(pivot)
                .rotate(rotation)
                .translate(new Vector3f(pivot).negate())
                .scale(scale);
        
        worldTransform.set(parentTransform).mul(localTransform);
        worldTransform.invert(worldToLocal);
        
        for (EntityPart child : children) {
            child.updateTransforms(worldTransform);
        }
    }
}
