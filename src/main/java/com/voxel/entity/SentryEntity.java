package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Sentry - floating guardian of the Bronze Dungeon. Charges and slams. */
public class SentryEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/sentry.json";

    private float bobPhase = 0.0f;
    private float chargeCooldown = 0.0f;
    private boolean charging = false;
    private Vector3f chargeDir = new Vector3f();

    public SentryEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 14.0f;
        pickWidth = 1.0f;
        pickHeight = 1.1f;
    }

    @Override
    public void update(float dt) {
        snapshotPrev();
        animTime += dt;
        bobPhase += dt;
        if (hitFlashTime > 0) hitFlashTime -= dt;
        // Idle hover bob
        if (!charging) addPosition(0.0f, (float) Math.sin(bobPhase * 2.5) * 0.015f, 0.0f);
        // Eye glow pulse
        ModelPart eyes = findPart("eyes");
        if (eyes != null) eyes.emissive = true;
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (world == null || isDead() || playerPos == null) return;
        attackCooldown = Math.max(0, attackCooldown - dt);
        chargeCooldown -= dt;

        float dist = getPosition().distance(playerPos);

        if (charging) {
            // Slide rapidly toward the stored charge direction; slam on contact.
            addPosition(chargeDir.x * dt * 9.0f, 0.0f, chargeDir.z * dt * 9.0f);
            rotation.y = (float) Math.toDegrees(Math.atan2(chargeDir.x, chargeDir.z));
            if (player != null && getPosition().distance(player.getPosition()) < 1.6f && attackCooldown <= 0) {
                player.takeDamage(3.0f);
                attackCooldown = 1.0f;
            }
            if (chargeCooldown < 1.4f || dist < 1.2f) charging = false;
            return;
        }

        if (dist < 16.0f) {
            rotation.y = (float) Math.toDegrees(Math.atan2(playerPos.x - getPosX(), playerPos.z - getPosZ()));
            if (dist > 10.0f) moveToward(playerPos, dt, 1.2f); // slow float approach
        }

        if (dist < 8.0f && dist > 2.0f && chargeCooldown <= 0) {
            charging = true;
            hitFlashTime = 0.5f;
            chargeCooldown = 3.0f;
            chargeDir.set(new Vector3f(playerPos).sub(getPosition()).normalize());
        }
    }

    @Override
    public int xpDropValue() { return 6; }
}
