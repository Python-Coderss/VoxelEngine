package com.voxel.jem;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

public final class JemPartInstance {
    public final JemPartDefinition definition;
    public final Quaternionf poseRotation;
    public final Matrix4f localTransform = new Matrix4f();
    public final Matrix4f worldTransform = new Matrix4f();
    public final Matrix4f worldToLocal = new Matrix4f();

    public JemPartInstance(JemPartDefinition definition, Quaternionf poseRotation) {
        this.definition = definition;
        this.poseRotation = poseRotation;
    }

    public String getName() {
        return definition.name;
    }

    public int getBlockId() {
        return definition.blockId;
    }

    public void update(Matrix4f rootTransform) {
        localTransform.identity()
                .translate(definition.origin)
                .rotate(definition.baseRotation)
                .rotate(poseRotation)
                .scale(definition.baseScale);

        worldTransform.set(rootTransform).mul(localTransform);
        worldTransform.invert(worldToLocal);
    }
}
