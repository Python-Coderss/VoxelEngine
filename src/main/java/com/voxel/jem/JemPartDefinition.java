package com.voxel.jem;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class JemPartDefinition {
    public final String name;
    public final Vector3f origin = new Vector3f();
    public final Quaternionf baseRotation = new Quaternionf();
    public final Vector3f baseScale = new Vector3f(1.0f);
    public final Vector3f gridOffset = new Vector3f();
    public final Vector3f voxelScale = new Vector3f(1.0f);
    public final int[] voxelData;

    public JemPartDefinition(String name, Vector3f origin, Quaternionf baseRotation,
            Vector3f baseScale, Vector3f gridOffset, Vector3f voxelScale, int[] voxelData) {
        this.name = name;
        this.origin.set(origin);
        this.baseRotation.set(baseRotation);
        this.baseScale.set(baseScale);
        this.gridOffset.set(gridOffset);
        this.voxelScale.set(voxelScale);
        this.voxelData = voxelData.clone();
    }
}
