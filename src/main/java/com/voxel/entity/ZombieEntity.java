package com.voxel.entity;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * A simple implementation of a Minecraft-like Zombie entity.
 */
public class ZombieEntity extends Entity {
    public ZombieEntity(int id, Vector3f position, int headTex, int bodyTex, int armTex, int legTex) {
        super(id, position);
        
        // Head
        addPart(new ModelPart(new Vector3f(-0.25f, 1.5f, -0.25f), new Vector3f(0.5f, 0.5f, 0.5f), headTex));
        // Body
        addPart(new ModelPart(new Vector3f(-0.25f, 0.75f, -0.125f), new Vector3f(0.5f, 0.75f, 0.25f), bodyTex));
        // Left Arm
        addPart(new ModelPart(new Vector3f(-0.5f, 0.75f, -0.125f), new Vector3f(0.25f, 0.75f, 0.25f), armTex));
        // Right Arm
        addPart(new ModelPart(new Vector3f(0.25f, 0.75f, -0.125f), new Vector3f(0.25f, 0.75f, 0.25f), armTex));
        // Left Leg
        addPart(new ModelPart(new Vector3f(-0.25f, 0.0f, -0.125f), new Vector3f(0.25f, 0.75f, 0.25f), legTex));
        // Right Leg
        addPart(new ModelPart(new Vector3f(0.0f, 0.0f, -0.125f), new Vector3f(0.25f, 0.75f, 0.25f), legTex));
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        // Simple animation: swing arms and legs
        float time = (float) (System.currentTimeMillis() % 1000) / 1000.0f * 2.0f * (float) Math.PI;
        float swing = (float) Math.sin(time) * 0.5f;

        // Left Arm (part 2)
        parts.get(2).rotation.x = swing;
        // Right Arm (part 3)
        parts.get(3).rotation.x = -swing;
        // Left Leg (part 4)
        parts.get(4).rotation.x = -swing;
        // Right Leg (part 5)
        parts.get(5).rotation.x = swing;
    }
}
