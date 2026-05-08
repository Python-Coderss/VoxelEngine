package com.voxel.entity;

import org.joml.Vector3f;
import java.util.ArrayList;
import org.joml.Vector3f;

/**
 * A simple implementation of a Minecraft-like Zombie entity.
 */
public class ZombieEntity extends Entity {
    private ModelPart leftArm, rightArm, leftLeg, rightLeg;

    public ZombieEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        loadModel("src/main/resources/assets/minecraft/models/entity/zombie.json", textureManager);

        for (ModelPart p : parts) {
            if (p.name.equals("left_arm")) leftArm = p;
            else if (p.name.equals("right_arm")) rightArm = p;
            else if (p.name.equals("left_leg")) leftLeg = p;
            else if (p.name.equals("right_leg")) rightLeg = p;
        }
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        // Simple animation: swing arms and legs
        float time = (float) (System.currentTimeMillis() % 2000) / 2000.0f * 2.0f * (float) Math.PI;
        float swing = (float) Math.sin(time) * 0.5f;

        if (leftArm != null) leftArm.rotation.x = swing;
        if (rightArm != null) rightArm.rotation.x = -swing;
        if (leftLeg != null) leftLeg.rotation.x = -swing;
        if (rightLeg != null) rightLeg.rotation.x = swing;
    }
}
