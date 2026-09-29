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
        // Hovering fire spirit: slow arm drift, a breathing torso and a jaw
        // that murmurs. (The old ring_1/ring_2 targets never existed in the
        // SunSpiritModel — those spins were silently doing nothing.)
        float t = animTime;
        ModelPart head = findPart("head");
        if (head != null) {
            head.rotation.y = (float) Math.sin(t * 0.6) * 12.0f;
            head.rotation.x = (float) Math.sin(t * 0.9) * 4.0f;
        }
        ModelPart mouth = findPart("head_mouth");
        if (mouth != null) {
            mouth.rotation.x = 6.0f + (float) Math.abs(Math.sin(t * 3.1)) * 10.0f;
        }
        ModelPart la = findPart("left_arm_upper");
        if (la != null) la.rotation.z = (float) Math.sin(t * 0.8) * 14.0f - 8.0f;
        ModelPart ra = findPart("right_arm_upper");
        if (ra != null) ra.rotation.z = (float) Math.sin(t * 0.8 + Math.PI) * 14.0f + 8.0f;
        ModelPart mid = findPart("torso_middle");
        if (mid != null) {
            if (Float.isNaN(midOffsetY)) midOffsetY = mid.offset.y;
            mid.offset.y = midOffsetY + (float) Math.sin(t * 1.4) * 0.12f;
        }
    }

    /** Resting y of the middle torso segment (offset animates around it). */
    private float midOffsetY = Float.NaN;

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
