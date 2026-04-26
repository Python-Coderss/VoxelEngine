package com.voxel.jem;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JemEntityInstance {
    private final JemModel model;
    private final List<JemPartInstance> parts = new ArrayList<>();
    private final Map<String, Quaternionf> poseRotations = new HashMap<>();
    private final Matrix4f rootTransform = new Matrix4f();

    public final Vector3f position = new Vector3f();

    public JemEntityInstance(JemModel model) {
        this.model = model;
        for (JemPartDefinition part : model.getParts()) {
            Quaternionf pose = poseRotations.get(part.name);
            if (pose == null) {
                pose = new Quaternionf();
                poseRotations.put(part.name, pose);
            }
            parts.add(new JemPartInstance(part, pose));
        }
    }

    public JemModel getModel() {
        return model;
    }

    public List<JemPartInstance> getParts() {
        return Collections.unmodifiableList(parts);
    }

    public JemPartInstance findPart(String name) {
        for (JemPartInstance part : parts) {
            if (name.equals(part.getName())) {
                return part;
            }
        }
        return null;
    }

    public Quaternionf getPoseRotation(String name) {
        Quaternionf pose = poseRotations.get(name);
        if (pose == null) {
            pose = new Quaternionf();
            poseRotations.put(name, pose);
        }
        return pose;
    }

    public void update() {
        rootTransform.identity().translate(position).scale(1.0f / 16.0f);
        for (JemPartInstance part : parts) {
            part.update(rootTransform);
        }
    }
}
