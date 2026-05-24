package com.voxel.entity;

import com.voxel.Player;
import com.voxel.World;
import org.joml.Vector3f;

/**
 * Creeper entity — silently approaches player, explodes on death or when close enough.
 */
public class CreeperEntity extends EnemyEntity {
    private float fuseTimer = 1.5f;
    private boolean primed = false;

    public CreeperEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p) {
        super(id, position, textureManager, p);
        loadModel("src/main/resources/assets/minecraft/models/entity/creeper.json", textureManager);
        // Green tint for creeper
        this.tintColor.set(0.5f, 1.0f, 0.3f);
        this.tintAmount = 0.35f;
    }

    @Override
    public void updateAI(Vector3f playerPos, float dt) {
        if (isDead()) return;

        float dist = position.distance(playerPos);

        if (primed) {
            fuseTimer -= dt;
            hitFlashTime = 1.0f; // bright flash while primed
            if (fuseTimer <= 0) {
                explode();
            }
            return;
        }

        // Slowly approach player
        if (dist < 12.0f) {
            moveToward(playerPos, dt, 1.8f);
            // Face the player
            Vector3f toPlayer = new Vector3f(playerPos).sub(position);
            if (toPlayer.length() > 0.01f) {
                rotation.y = (float) Math.toDegrees(Math.atan2(toPlayer.x, toPlayer.z));
            }
        }

        // If within blast radius, start fuse
        if (dist < 2.5f) {
            primed = true;
            fuseTimer = 1.5f;
        }
    }

    private void explode() {
        // Deal heavy damage to nearby entities
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            Entity e = entityManager.getEntity(i);
            if (e == this) continue;
            if (e instanceof EnemyEntity && !((EnemyEntity) e).isDead()) {
                EnemyEntity enemy = (EnemyEntity) e;
                float d = position.distance(enemy.position);
                if (d < 6.0f) {
                    float damage = 15.0f * (1.0f - d / 6.0f);
                    Vector3f kb = new Vector3f(enemy.position).sub(position).normalize().mul(2.0f * (1.0f - d / 6.0f));
                    enemy.takeDamage(damage, kb);
                }
            }
        }
        // Also damage player
        if (player != null) {
            float playerDist = position.distance(player.getPosition());
            if (playerDist < 6.0f) {
                float damage = 20.0f * (1.0f - playerDist / 6.0f);
                player.takeDamage(damage);
            }
        }
        // Self-destruct
        die();
    }

    @Override
    public void performAttack(Vector3f playerPos) {
        // Creepers explode instead of attacking normally
        primed = true;
    }

    @Override
    public void die() {
        super.die();
        rotation.x = 90.0f; // Fall flat
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;

        float time = (float) (animTime % 2) * 2.0f * (float) Math.PI;
        float swing = (float) Math.sin(time) * 15f;

        for (ModelPart p : parts) {
            if (p.name.equals("left_leg")) p.rotation.x = -swing;
            else if (p.name.equals("right_leg")) p.rotation.x = swing;
        }
    }
}
