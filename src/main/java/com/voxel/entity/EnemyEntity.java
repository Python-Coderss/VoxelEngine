package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Basic enemy entity with simple AI and Story Mode style animations.
 * Now uses the same humanoid model as the player.
 */
public class EnemyEntity extends Entity {
    private ModelPart head;
    private ModelPart leftArm, rightArm;
    private ModelPart leftLeg, rightLeg;
    private float animTime = 0.0f;
    private float health = 20.0f;
    private boolean isDead = false;
    private float hitFlashTime = 0.0f;

    public EnemyEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        
        // 1. Load the base humanoid structure
        loadModel("src/main/resources/assets/minecraft/models/entity/player.json", textureManager);
        
        // 2. Override all parts to use zombie_skin and set standard UV origins
        int zombieTexIdx = textureManager.getTextureIndex("zombie_skin");
        for (ModelPart part : parts) {
            if (zombieTexIdx != -1) part.textureIndex = zombieTexIdx;
            
            // Standard Minecraft Skin UV Origins
            if (part.name.equals("head")) part.uvOrigin.set(0, 0);
            else if (part.name.equals("body")) part.uvOrigin.set(16, 16);
            else if (part.name.equals("right_arm")) part.uvOrigin.set(40, 16);
            else if (part.name.equals("left_arm")) part.uvOrigin.set(32, 48); // Classic layout
            else if (part.name.equals("right_leg")) part.uvOrigin.set(0, 16);
            else if (part.name.equals("left_leg")) part.uvOrigin.set(16, 48);
        }

        // Cache parts for animation
        for (ModelPart part : parts) {
            if (part.name.equals("head")) head = part;
            else if (part.name.equals("left_arm")) leftArm = part;
            else if (part.name.equals("right_arm")) rightArm = part;
            else if (part.name.equals("left_leg")) leftLeg = part;
            else if (part.name.equals("right_leg")) rightLeg = part;
        }
    }

    private float lastBob = 0.0f;

    @Override
    public void update(float dt) {
        if (isDead) return;
        
        animTime += dt;
        
        // Zombie-like shuffle animation (Simplified Story Mode gait)
        float swingAngle = (float) Math.sin(animTime * 4.0f) * 0.4f; // ~23 deg
        
        if (leftLeg != null) leftLeg.rotation.x = -swingAngle;
        if (rightLeg != null) rightLeg.rotation.x = swingAngle;
        
        // Zombies keep arms forward (classic pose)
        if (leftArm != null) {
            leftArm.rotation.x = (float) Math.toRadians(-80.0f) + (float) Math.sin(animTime * 2.0f) * 0.1f;
            leftArm.rotation.z = (float) Math.toRadians(-5.0f);
        }
        if (rightArm != null) {
            rightArm.rotation.x = (float) Math.toRadians(-80.0f) + (float) Math.cos(animTime * 2.0f) * 0.1f;
            rightArm.rotation.z = (float) Math.toRadians(5.0f);
        }

        // --- Fix: Non-cumulative Bobbing ---
        float bob = (float) Math.abs(Math.sin(animTime * 4.0f)) * 0.05f;
        position.y -= lastBob; // Remove old offset
        position.y += bob;     // Apply new offset
        lastBob = bob;

        if (hitFlashTime > 0) hitFlashTime -= dt;
    }

    public void takeDamage(float amount, Vector3f knockback) {
        health -= amount;
        position.add(knockback);
        hitFlashTime = 0.2f;
        if (health <= 0) die();
    }

    private void die() {
        isDead = true;
        rotation.x = (float) Math.toRadians(90.0f); // Fall over
        position.y -= 0.3f;
    }

    public boolean isDead() { return isDead; }
}
