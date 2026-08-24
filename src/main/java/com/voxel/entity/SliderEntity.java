package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Slider - Bronze Dungeon boss. A giant stone cube that slides in bursts
 * to crush intruders. Only damaged by pickaxes (vanilla-Aether rule).
 */
public class SliderEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/slider.json";

    private float slideCooldown = 2.0f;
    private boolean sliding = false;
    private Vector3f slideDir = new Vector3f();
    private boolean awakened = false;
    public Player mainPlayer;

    public SliderEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 100.0f;
        pickWidth = 1.9f;
        pickHeight = 1.5f;
    }

    @Override
    public void update(float dt) {
        snapshotPrev();
        animTime += dt;
        if (hitFlashTime > 0) hitFlashTime -= dt;
        if (!sliding && awakened) {
            // Face texture glows when awake
            setPartTexture("face",
                    modelTextureManager.getEntityTextureIndex("slider/slider_awake_critical"));
        }
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (world == null || isDead() || playerPos == null) return;
        attackCooldown = Math.max(0, attackCooldown - dt);

        if (!awakened) {
            if (getPosition().distance(playerPos) < 8.0f) {
                awakened = true;
                hitFlashTime = 0.6f;
            }
            return;
        }

        float dist = getPosition().distance(playerPos);

        if (sliding) {
            addPosition(slideDir.x * dt * 8.0f, 0.0f, slideDir.z * dt * 8.0f);
            rotation.y = (float) Math.toDegrees(Math.atan2(slideDir.x, slideDir.z));
            // Crush anything we slide into
            if (player != null && getPosition().distance(player.getPosition()) < 2.2f && attackCooldown <= 0) {
                player.takeDamage(6.0f);
                attackCooldown = 1.5f;
            }
            if (dist < 1.5f) sliding = false;   // reached target
            if (slideCooldown < 2.4f) sliding = false; // burst over (~0.6s)
            return;
        }

        rotation.y = (float) Math.toDegrees(Math.atan2(playerPos.x - getPosX(), playerPos.z - getPosZ()));
        slideCooldown -= dt;
        if (slideCooldown <= 0 && dist > 1.6f) {
            slideCooldown = 3.0f;
            sliding = true;
            hitFlashTime = 0.25f;
            slideDir.set(new Vector3f(playerPos).sub(getPosition()).normalize());
        }
    }

    /**
     * The Slider is immune to everything but pickaxes.
     */
    @Override
    public void takeDamage(float amount, Vector3f knockback) {
        if (isDead()) return;
        if (!lastHitWasPickaxe) {
            // Clang! No damage - flash briefly to show immunity.
            hitFlashTime = 0.15f;
            return;
        }
        super.takeDamage(amount, knockback);
    }

    @Override
    public int xpDropValue() { return 100; }
}
