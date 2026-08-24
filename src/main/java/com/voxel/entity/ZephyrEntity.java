package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Zephyr - flying cloud blob that lobbs knockback snowballs at players. */
public class ZephyrEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/zephyr.json";

    private float shootCooldown = 2.0f;
    private float bobPhase = 0.0f;
    /** Set by Main each tick so the Zephyr knows where to shoot. */
    public Player mainPlayer;

    public ZephyrEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 20.0f;
        pickWidth = 1.6f;
        pickHeight = 1.4f;
    }

    @Override
    public void update(float dt) {
        // Free flight: gentle sine hover, no gravity/walk bob.
        snapshotPrev();
        animTime += dt;
        bobPhase += dt;
        if (hitFlashTime > 0) hitFlashTime -= dt;
        addPosition(0.0f, (float) Math.sin(bobPhase * 1.5) * 0.02f, 0.0f);
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (world == null || isDead() || playerPos == null) return;
        attackCooldown = Math.max(0, attackCooldown - dt);

        float dist = getPosition().distance(playerPos);

        // Face the player and keep mid-range
        rotation.y = (float) Math.toDegrees(Math.atan2(playerPos.x - getPosX(), playerPos.z - getPosZ()));
        if (dist > 18.0f) {
            moveToward(playerPos, dt, 1.1f);
        } else if (dist < 9.0f) {
            Vector3f away = new Vector3f(getPosition()).sub(playerPos).normalize().mul(dt * 1.4f);
            tryFlyMove(away.x, away.y, away.z);
        }

        shootCooldown -= dt;
        if (shootCooldown <= 0 && dist < 24.0f && dist > 3.0f) {
            shootCooldown = 2.8f;
            shootSnowball(playerPos);
        }
    }

    private void shootSnowball(Vector3f playerPos) {
        if (EnemyEntity.entityManager == null || mainPlayer == null) return;
        Vector3f dir = new Vector3f(playerPos).sub(getPosition()).normalize();
        AetherProjectileEntity ball = new AetherProjectileEntity(
                80_000 + EnemyEntity.entityManager.getEntityCount(),
                new Vector3f(getPosX() + dir.x, getPosY() + 1.0f, getPosZ() + dir.z),
                dir.mul(7.0f), AetherProjectileEntity.Type.ZEPHYR_SNOWBALL, mainPlayer);
        ball.dimension = dimension;
        EnemyEntity.entityManager.addEntity(ball);
    }

    /** Flying movement: no ground requirement. */
    protected void tryFlyMove(float dx, float dy, float dz) {
        int bx = (int) Math.floor(getPosX() + dx);
        int by = (int) Math.floor(getPosY() + dy);
        int bz = (int) Math.floor(getPosZ() + dz);
        if (world.getVoxel(bx, by, bz) == 0) addPosition(dx, dy, dz);
    }
}
