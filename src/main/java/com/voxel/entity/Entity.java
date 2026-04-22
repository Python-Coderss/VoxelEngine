package com.voxel.entity;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

public class Entity {
    public final Vector3f position = new Vector3f();
    public final List<EntityPart> rootParts = new ArrayList<>();
    private final Matrix4f rootTransform = new Matrix4f();

    public void update() {
        rootTransform.identity().translate(position).scale(1.0f / 16.0f);
        for (EntityPart part : rootParts) {
            part.updateTransforms(rootTransform);
        }
    }

    public void getAllParts(List<EntityPart> result) {
        for (EntityPart part : rootParts) {
            collectParts(part, result);
        }
    }

    private void collectParts(EntityPart part, List<EntityPart> result) {
        result.add(part);
        for (EntityPart child : part.children) {
            collectParts(child, result);
        }
    }
}
