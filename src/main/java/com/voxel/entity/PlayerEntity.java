package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Render-only player model that mirrors the physics Player.
 * Enhanced with Story Mode style animations (Rolling, Combat).
 */
public class PlayerEntity extends Entity {
    private static final Vector3f HIDDEN_POSITION = new Vector3f(-10000.0f, -10000.0f, -10000.0f);
    private ModelPart head;
    private ModelPart leftArm, rightArm;
    private ModelPart leftLeg, rightLeg;
    private float walkAnimTime = 0.0f;
    private float rollAnimTime = 0.0f;
    private float attackAnimTime = 0.0f;
    private boolean isRolling = false;

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

    public void startRoll() {
        if (!isRolling) {
            isRolling = true;
            rollAnimTime = 0.0f;
        }
    }

    public void startAttack() {
        attackAnimTime = 1.0f;
    }

    public void syncFromPlayer(Player player, float yaw, float pitch, boolean visible, float dt) {
        if (!visible) {
            position.set(HIDDEN_POSITION);
            return;
        }

        position.set(player.getPosition());

        // --- Cinematic Story Mode Animation ---
        Vector3f vel = player.getVelocity();
        float horizontalSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // 1. Orient body towards movement direction
        if (horizontalSpeed > 0.1f && !isRolling) {
            float moveYaw = (float) Math.toDegrees(Math.atan2(vel.x, vel.z));
            float turnLerp = 10.0f * dt;
            rotation.y = lerpAngle(rotation.y, moveYaw, turnLerp);
        }

        // 2. Procedural Roll
        if (isRolling) {
            rollAnimTime += dt * 3.5f;
            if (rollAnimTime >= 1.0f) {
                isRolling = false;
                rotation.x = 0;
            } else {
                // Spin 360 degrees
                rotation.x = (float) (rollAnimTime * Math.PI * 2.0);
                // Add a small hop
                position.y += (float) Math.sin(rollAnimTime * Math.PI) * 0.4f;
            }
        }

        // 3. Attack Animation (Arm Swing)
        float attackSwing = 0;
        if (attackAnimTime > 0) {
            attackAnimTime -= dt * 5.0f;
            attackSwing = (float) Math.sin(Math.max(0, attackAnimTime) * Math.PI) * 85.0f; // Degrees
        }

        if (head != null) {
            head.rotation.set(pitch, 0.0f, 0.0f);
        }

        if (horizontalSpeed > 0.1f && player.isOnGround() && !isRolling) {
            walkAnimTime += dt * horizontalSpeed * 0.2f;

            // 1. Exaggerated Limb Swing (degrees)
            float swingAngle = (float) Math.sin(walkAnimTime * 12.0f) * 35.0f; 
            if (leftLeg != null) leftLeg.rotation.x = -swingAngle;
            if (rightLeg != null) rightLeg.rotation.x = swingAngle;
            
            if (leftArm != null) {
                leftArm.rotation.x = swingAngle * 0.7f;
                leftArm.rotation.z = -5.0f;
            }
            if (rightArm != null) {
                rightArm.rotation.x = -swingAngle * 0.7f + attackSwing;
                rightArm.rotation.z = 5.0f;
            }

            // 2. Body Bob (Y-oscillation) - Apply relative to base position
            float bob = (float) Math.abs(Math.sin(walkAnimTime * 10.0f)) * 0.2f;
            position.y += bob;

            // 3. Body Tilt (Leaning)
            rotation.z = (float) Math.sin(walkAnimTime * 10.0f) * 0.08f;
        } else {
            // Neutral Pose
            walkAnimTime = 0;
            float lerpFactor = 8.0f * dt;
            if (leftLeg != null) leftLeg.rotation.x = lerp(leftLeg.rotation.x, 0, lerpFactor);
            if (rightLeg != null) rightLeg.rotation.x = lerp(rightLeg.rotation.x, 0, lerpFactor);
            
            if (leftArm != null) {
                leftArm.rotation.x = lerp(leftArm.rotation.x, 0, lerpFactor);
                leftArm.rotation.z = lerp(leftArm.rotation.z, 0, lerpFactor);
            }
            if (rightArm != null) {
                if (attackAnimTime > 0) {
                    rightArm.rotation.x = attackSwing;
                } else {
                    rightArm.rotation.x = lerp(rightArm.rotation.x, 0, lerpFactor);
                }
                rightArm.rotation.z = lerp(rightArm.rotation.z, 0, lerpFactor);
            }
            rotation.z = lerp(rotation.z, 0, lerpFactor);
            if (!isRolling) rotation.x = lerp(rotation.x, 0, lerpFactor);
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
