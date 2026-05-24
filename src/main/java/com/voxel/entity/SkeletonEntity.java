package com.voxel.entity;

import com.voxel.Player;
import com.voxel.World;
import org.joml.Vector3f;

/**
 * Skeleton entity — keeps distance and shoots arrows at the player.
 */
public class SkeletonEntity extends EnemyEntity {
    private float shootCooldown = 0.0f;
    private float strafeTimer = 0.0f;
    private float strafeDir = 1.0f;

    public SkeletonEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p) {
        super(id, position, textureManager, p);
        loadModel("src/main/resources/assets/minecraft/models/entity/skeleton.json", textureManager);
        // Bone-white tint
        this.tintColor.set(1.2f, 1.15f, 1.0f);
        this.tintAmount = 0.3f;
    }

    @Override
    public void updateAI(Vector3f playerPos, float dt) {
        if (isDead()) return;

        shootCooldown = Math.max(0, shootCooldown - dt);
        float dist = position.distance(playerPos);

        // Face the player
        Vector3f toPlayer = new Vector3f(playerPos).sub(position);
        if (toPlayer.length() > 0.01f) {
            rotation.y = (float) Math.toDegrees(Math.atan2(toPlayer.x, toPlayer.z));
        }

        // Keep distance — retreat if too close, approach if too far
        if (dist < 5.0f) {
            // Back away
            Vector3f away = new Vector3f(position).sub(playerPos).normalize();
            moveToward(new Vector3f(position).add(away.mul(3.0f)), dt, 2.2f);
        } else if (dist > 14.0f) {
            // Approach
            moveToward(playerPos, dt, 2.2f);
        } else {
            // Strafe in the ideal range
            strafeTimer -= dt;
            if (strafeTimer <= 0) {
                strafeTimer = 1.2f + (float) Math.random() * 0.8f;
                strafeDir *= -1;
            }
            // Strafe perpendicular
            Vector3f right = new Vector3f();
            toPlayer.cross(new Vector3f(0, 1, 0), right).normalize();
            right.mul(strafeDir * 2.0f);
            Vector3f strafeTarget = new Vector3f(position).add(right);
            moveToward(strafeTarget, dt, 1.8f);
        }

        // Shoot when in range
        if (dist < 18.0f && shootCooldown <= 0) {
            shootAt(playerPos);
            shootCooldown = 1.8f + (float) Math.random() * 0.6f;
        }
    }

    private void shootAt(Vector3f targetPos) {
        // Calculate arrow trajectory
        Vector3f dir = new Vector3f(targetPos).sub(position);
        float dist = dir.length();
        if (dist < 0.01f) return;
        dir.normalize();

        // Add slight random spread
        float spread = 0.05f;
        dir.x += (float)(Math.random() - 0.5) * spread;
        dir.y += (float)(Math.random() - 0.5) * spread;
        dir.z += (float)(Math.random() - 0.5) * spread;
        dir.normalize();

        // Check line-of-sight to target
        Vector3f checkPos = new Vector3f(position);
        boolean hitBlock = false;
        for (float d = 0; d < dist; d += 0.5f) {
            checkPos.set(position).fma(d, dir);
            int x = (int) Math.floor(checkPos.x);
            int y = (int) Math.floor(checkPos.y);
            int z = (int) Math.floor(checkPos.z);
            if (world != null && world.getVoxel(x, y, z) != 0) {
                hitBlock = true;
                break;
            }
        }

        if (!hitBlock && player != null) {
            // Direct hit on player
            float damage = 4.0f + (float) Math.random() * 4.0f;
            player.takeDamage(damage);
            hitFlashTime = 0.2f;
        }
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;

        float time = (float) (animTime % 2) * 2.0f * (float) Math.PI;
        float swing = (float) Math.sin(time) * 25f;

        for (ModelPart p : parts) {
            if (p.name.equals("left_arm") || p.name.equals("right_arm")) {
                // Draw bow animation when shooting
                if (shootCooldown > 1.0f) {
                    p.rotation.x = -60;
                } else {
                    p.rotation.x = swing * 0.5f;
                }
            } else if (p.name.equals("left_leg")) p.rotation.x = -swing;
            else if (p.name.equals("right_leg")) p.rotation.x = swing;
        }
    }
}
