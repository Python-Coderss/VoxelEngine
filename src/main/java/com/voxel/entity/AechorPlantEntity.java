package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Aechor Plant - stationary poison-dart-shooting plant. */
public class AechorPlantEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/aechor_plant.json";

    private float shootCooldown = 2.0f;

    public AechorPlantEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 15.0f;
        pickWidth = 0.9f;
        pickHeight = 1.0f;
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (world == null || isDead() || playerPos == null) return;
        attackCooldown = Math.max(0, attackCooldown - dt);
        shootCooldown -= dt;

        float dist = getPosition().distance(playerPos);
        if (dist < 12.0f) {
            rotation.y = (float) Math.toDegrees(Math.atan2(playerPos.x - getPosX(), playerPos.z - getPosZ()));
            if (shootCooldown <= 0 && EnemyEntity.entityManager != null && player != null) {
                shootCooldown = 2.5f;
                Vector3f dir = new Vector3f(playerPos).sub(getPosition()).normalize();
                AetherProjectileEntity dart = new AetherProjectileEntity(
                        81_000 + EnemyEntity.entityManager.getEntityCount(),
                        new Vector3f(getPosX() + dir.x * 0.5f, getPosY() + 0.9f, getPosZ() + dir.z * 0.5f),
                        dir.mul(9.0f), AetherProjectileEntity.Type.POISON_DART, player);
                dart.dimension = dimension;
                EnemyEntity.entityManager.addEntity(dart);
            }
        }
        animatePetals();
    }

    private void animatePetals() {
        ModelPart[] petals = { findPart("petal_1"), findPart("petal_2"),
                               findPart("petal_3"), findPart("petal_4") };
        for (int i = 0; i < petals.length; i++) {
            if (petals[i] != null) {
                petals[i].rotation.y = i * 90.0f
                        + (float) Math.sin(animTime * 2.0f + i) * 8.0f;
            }
        }
    }

    @Override
    public int xpDropValue() { return 6; }
}
