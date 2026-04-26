package com.voxel.jem;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class JemPartDefinition {
    public final String name;
    public final Vector3f origin = new Vector3f();
    public final Quaternionf baseRotation = new Quaternionf();
    public final Vector3f baseScale = new Vector3f(1.0f);
    public final Vector3f min = new Vector3f();
    public final Vector3f max = new Vector3f();
    public final int blockId;

    public JemPartDefinition(String name, Vector3f origin, Quaternionf baseRotation,
            Vector3f baseScale, Vector3f min, Vector3f max, int blockId) {
        this.name = name;
        this.origin.set(origin);
        this.baseRotation.set(baseRotation);
        this.baseScale.set(baseScale);
        this.min.set(min);
        this.max.set(max);
        this.blockId = blockId;
    }
}
