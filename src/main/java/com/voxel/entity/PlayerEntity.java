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
    private ModelPart cape;
    private float walkAnimTime = 0.0f;
    private float rollAnimTime = 0.0f;
    private float attackAnimTime = 0.0f;
    private boolean isRolling = false;

    // Cape physics state
    private float capeAngle = 0.0f;
    private float capeAngularVel = 0.0f;
    private float prevHorizontalSpeed = 0.0f;

    public PlayerEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        loadModel("src/main/resources/assets/minecraft/models/entity/player.json", textureManager);

        for (ModelPart part : parts) {
            if (part.name.equals("head")) head = part;
            else if (part.name.equals("left_arm")) leftArm = part;
            else if (part.name.equals("right_arm")) rightArm = part;
            else if (part.name.equals("left_leg")) leftLeg = part;
            else if (part.name.equals("right_leg")) rightLeg = part;
            else if (part.name.equals("cape")) cape = part;
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

        // --- Cape Physics ---
        // Cape swings based on acceleration and vertical velocity
        float verticalVel = vel.y;
        float acceleration = (horizontalSpeed - prevHorizontalSpeed) / Math.max(dt, 0.001f);
        prevHorizontalSpeed = horizontalSpeed;

        // Target angle: when moving forward, cape flows behind (positive rotation = lean back)
        // When falling, cape flaps upward
        float targetAngle;
        if (horizontalSpeed > 0.1f) {
            targetAngle = horizontalSpeed * 30.0f; // Flows back proportional to speed
        } else {
            targetAngle = 0.0f;
        }
        // Add upward fling when falling fast
        if (verticalVel < -2.0f) {
            targetAngle += Math.min(-verticalVel * 15.0f, 90.0f);
        }

        // Spring physics for the cape
        float stiffness = 8.0f;
        float damping = 3.0f;
        float force = (targetAngle - capeAngle) * stiffness - capeAngularVel * damping;
        capeAngularVel += force * dt;
        capeAngularVel *= Math.max(0.0f, 1.0f - dt * 0.5f); // Friction
        capeAngle += capeAngularVel * dt;
        capeAngle = Math.max(-90.0f, Math.min(90.0f, capeAngle));

        if (cape != null) {
            cape.rotation.x = capeAngle;
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
