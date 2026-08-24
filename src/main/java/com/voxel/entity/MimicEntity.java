package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Mimic - treasure chest that comes alive and attacks when approached. */
public class MimicEntity extends EnemyEntity {
    public static final String MODEL_CLOSED = "src/main/resources/assets/aether/models/entity/chest_mimic_closed.json";
    public static final String MODEL_OPEN   = "src/main/resources/assets/aether/models/entity/mimic.json";

    private boolean awakened = false;
    private float awakenDist = 3.5f;

    public MimicEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL_CLOSED, tm);
        health = maxHealth = 20.0f;
        pickWidth = 1.2f;
        pickHeight = 1.1f;
    }

    @Override
    public void update(float dt) {
        if (!awakened) {
            snapshotPrev();
            animTime += dt;
            return; // Sleeps: no bobbing, looks like a chest
        }
        super.update(dt);
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (world == null || isDead() || playerPos == null) return;
        if (!awakened) {
            if (getPosition().distance(playerPos) < awakenDist) {
                awakened = true;
                loadModel(MODEL_OPEN, textureManagerRef());
                hitFlashTime = 0.5f; // flash to telegraph the awakening
            }
            return;
        }
        // Once awake: fast lunging melee
        attackCooldown = Math.max(0, attackCooldown - dt);
        float dist = getPosition().distance(playerPos);
        rotation.y = (float) Math.toDegrees(Math.atan2(playerPos.x - getPosX(), playerPos.z - getPosZ()));
        if (dist > 1.2f) moveToward(playerPos, dt, 3.2f);
        else if (attackCooldown <= 0 && player != null) {
            player.takeDamage(3.0f);
            attackCooldown = 1.2f;
        }
    }

    private com.voxel.utils.TextureManager textureManagerRef() {
        return this.modelTextureManager;
    }

    public boolean isAwake() { return awakened; }

    @Override
    public int xpDropValue() { return 8; }
}
