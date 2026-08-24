package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Sun Spirit - Gold Dungeon boss. An immutable fire spirit that hovers in
 * its boss room, rains sunfire, and scorches anyone who comes close.
 * Melee weapons deal heavily reduced damage (in the mod only ice balls hurt it).
 */
public class SunSpiritEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/sun_spirit.json";

    private float shootCooldown = 2.0f;
    private float burnTick = 0.0f;
    private float spin = 0.0f;
    public Player mainPlayer;

    public SunSpiritEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 120.0f;
        pickWidth = 1.6f;
        pickHeight = 1.8f;
    }

    @Override
    public void update(float dt) {
        snapshotPrev();
        animTime += dt;
        if (hitFlashTime > 0) hitFlashTime -= dt;
        addPosition(0.0f, (float) Math.sin(animTime * 1.2) * 0.02f, 0.0f);

        spin += dt * 60.0f;
        ModelPart r1 = findPart("ring_1");
        ModelPart r2 = findPart("ring_2");
        if (r1 != null) r1.rotation.y = spin % 360.0f;
        if (r2 != null) r2.rotation.z = spin % 360.0f;
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (world == null || isDead() || playerPos == null) return;
        attackCooldown = Math.max(0, attackCooldown - dt);

        float dist = getPosition().distance(playerPos);
        rotation.y = (float) Math.toDegrees(Math.atan2(playerPos.x - getPosX(), playerPos.z - getPosZ()));

        // Heat aura: standing close burns the player
        if (dist < 4.0f && player != null) {
            burnTick -= dt;
            if (burnTick <= 0) {
                burnTick = 0.8f;
                player.takeDamage(2.0f);
            }
        }

        shootCooldown -= dt;
        if (shootCooldown <= 0 && dist < 26.0f && EnemyEntity.entityManager != null && mainPlayer != null) {
            shootCooldown = 1.8f;
            Vector3f dir = new Vector3f(playerPos).sub(getPosition()).normalize();
            AetherProjectileEntity fire = new AetherProjectileEntity(
                    82_000 + EnemyEntity.entityManager.getEntityCount(),
                    new Vector3f(getPosX(), getPosY() + 1.0f, getPosZ()),
                    dir.mul(8.0f), AetherProjectileEntity.Type.SUN_FIRE, mainPlayer);
            fire.dimension = dimension;
            EnemyEntity.entityManager.addEntity(fire);
        }
    }

    /** Fire spirit: melee weapons barely scratch it (10% damage). */
    @Override
    public void takeDamage(float amount, Vector3f knockback) {
        super.takeDamage(amount * 0.1f, knockback);
    }

    @Override
    public int xpDropValue() { return 150; }
}
