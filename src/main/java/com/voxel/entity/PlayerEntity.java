package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Render-only player model that mirrors the physics Player.
 */
public class PlayerEntity extends Entity {
    private static final Vector3f HIDDEN_POSITION = new Vector3f(-10000.0f, -10000.0f, -10000.0f);
    private ModelPart head;
    private ModelPart leftArm, rightArm;
    private ModelPart leftLeg, rightLeg;
    private float walkAnimTime = 0.0f;

    public PlayerEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        loadModel("src/main/resources/assets/minecraft/models/entity/player.json", textureManager);

        for (ModelPart part : parts) {
            if (part.name.equals("head")) head = part;
            else if (part.name.equals("left_arm")) leftArm = part;
            else if (part.name.equals("right_arm")) rightArm = part;
            else if (part.name.equals("left_leg")) leftLeg = part;
            else if (part.name.equals("right_leg")) rightLeg = part;
        }
    }

    public void syncFromPlayer(Player player, float yaw, float pitch, boolean visible, float dt) {
        if (!visible) {
            position.set(HIDDEN_POSITION);
            return;
        }

        position.set(player.getPosition());
        rotation.set(0.0f, yaw, 0.0f);
        if (head != null) {
            head.rotation.set(pitch, 0.0f, 0.0f);
        }

        // Walking Animation
        Vector3f vel = player.getVelocity();
        float horizontalSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z) / 5;
        
        if (horizontalSpeed > 0.1f && player.isOnGround()) {
            walkAnimTime += dt * horizontalSpeed * 1.5f; // Speed adjusts frequency
            float swingAngle = (float) Math.sin(walkAnimTime * 8.0f) * 45.0f; // 45 deg max swing

            if (leftLeg != null) leftLeg.rotation.x = -swingAngle;
            if (rightLeg != null) rightLeg.rotation.x = swingAngle;
            if (leftArm != null) leftArm.rotation.x = swingAngle;
            if (rightArm != null) rightArm.rotation.x = -swingAngle;
        } else {
            // Smoothly return to neutral pose
            walkAnimTime = 0;
            float lerpFactor = 10.0f * dt;
            if (leftLeg != null) leftLeg.rotation.x = lerp(leftLeg.rotation.x, 0, lerpFactor);
            if (rightLeg != null) rightLeg.rotation.x = lerp(rightLeg.rotation.x, 0, lerpFactor);
            if (leftArm != null) leftArm.rotation.x = lerp(leftArm.rotation.x, 0, lerpFactor);
            if (rightArm != null) rightArm.rotation.x = lerp(rightArm.rotation.x, 0, lerpFactor);
        }
    }

    private float lerp(float a, float b, float f) {
        return a + f * (b - a);
    }
}
