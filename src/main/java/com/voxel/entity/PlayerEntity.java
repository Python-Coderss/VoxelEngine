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

        // Cinematic Story Mode Animation
        Vector3f vel = player.getVelocity();
        float horizontalSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // Orient body towards movement direction
        if (horizontalSpeed > 0.1f) {
            float moveYaw = (float) Math.toDegrees(Math.atan2(vel.x, vel.z));
            float turnLerp = 10.0f * dt;
            rotation.y = lerpAngle(rotation.y, moveYaw, turnLerp);
        }

        if (head != null) {
            head.rotation.set(pitch, 0.0f, 0.0f);
        }

        if (horizontalSpeed > 0.1f && player.isOnGround()) {
            walkAnimTime += dt * horizontalSpeed * 0.2f; // User requested 0.2 multiplier

            // 1. Exaggerated Limb Swing
            float swingAngle = (float) Math.sin(walkAnimTime * 10.0f) * 55.0f; 
            if (leftLeg != null) leftLeg.rotation.x = -swingAngle;
            if (rightLeg != null) rightLeg.rotation.x = swingAngle;
            if (leftArm != null) {
                leftArm.rotation.x = swingAngle;
                leftArm.rotation.z = (float) Math.toRadians(-5.0f); // Keep arms slightly out
            }
            if (rightArm != null) {
                rightArm.rotation.x = -swingAngle;
                rightArm.rotation.z = (float) Math.toRadians(5.0f);
            }

            // 2. Body Bob (Y-oscillation)
            float bob = (float) Math.abs(Math.sin(walkAnimTime * 10.0f)) * 0.15f;
            position.y += bob;

            // 3. Body Tilt (Leaning into movement)
            rotation.z = (float) Math.sin(walkAnimTime * 10.0f) * 0.08f;
        } else {
            // Smoothly return to neutral pose
            walkAnimTime = 0;
            float lerpFactor = 8.0f * dt;
            if (leftLeg != null) leftLeg.rotation.x = lerp(leftLeg.rotation.x, 0, lerpFactor);
            if (rightLeg != null) rightLeg.rotation.x = lerp(rightLeg.rotation.x, 0, lerpFactor);
            if (leftArm != null) {
                leftArm.rotation.x = lerp(leftArm.rotation.x, 0, lerpFactor);
                leftArm.rotation.z = lerp(leftArm.rotation.z, 0, lerpFactor);
            }
            if (rightArm != null) {
                rightArm.rotation.x = lerp(rightArm.rotation.x, 0, lerpFactor);
                rightArm.rotation.z = lerp(rightArm.rotation.z, 0, lerpFactor);
            }
            rotation.z = lerp(rotation.z, 0, lerpFactor);
        }
    }

    private float lerpAngle(float start, float end, float f) {
        float diff = ((end - start) + 180) % 360 - 180;
        return start + diff * f;
    }

    private float lerp(float a, float b, float f) {
        return a + f * (b - a);
    }
}
