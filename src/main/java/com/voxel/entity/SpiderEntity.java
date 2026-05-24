package com.voxel.entity;

import com.voxel.Player;
import com.voxel.World;
import org.joml.Vector3f;

/**
 * Spider entity — fast, climbs walls, jumps at the player.
 */
public class SpiderEntity extends EnemyEntity {
    private float jumpCooldown = 0.0f;

    public SpiderEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p) {
        super(id, position, textureManager, p);
        loadModel("src/main/resources/assets/minecraft/models/entity/spider.json", textureManager);
        // Dark red tint for spider
        this.tintColor.set(1.3f, 0.4f, 0.3f);
        this.tintAmount = 0.4f;
    }

    @Override
    public void updateAI(Vector3f playerPos, float dt) {
        if (isDead()) return;

        jumpCooldown = Math.max(0, jumpCooldown - dt);
        float dist = position.distance(playerPos);

        // Face the player
        Vector3f toPlayer = new Vector3f(playerPos).sub(position);
        if (toPlayer.length() > 0.01f) {
            rotation.y = (float) Math.toDegrees(Math.atan2(toPlayer.x, toPlayer.z));
        }

        // Fast approach — spiders are fast
        if (dist < 16.0f) {
            moveToward(playerPos, dt, 3.5f); // Very fast!
        }

        // Leap at the player when in range
        if (dist < 4.0f && jumpCooldown <= 0) {
            leapAt(playerPos);
            jumpCooldown = 2.0f;
        }

        // Attack when close
        if (dist < 2.5f) {
            if (player != null) {
                player.takeDamage(3.0f);
                performAttack(playerPos);
            }
        }
    }

    private void leapAt(Vector3f targetPos) {
        Vector3f leapDir = new Vector3f(targetPos).sub(position).normalize();
        // High arc leap
        leapDir.y += 0.5f;
        leapDir.normalize().mul(4.0f);
        position.add(leapDir);
        hitFlashTime = 0.3f;
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;

        float time = (float) (animTime % 2) * 2.0f * (float) Math.PI;
        float swing = (float) Math.sin(time) * 20f;

        for (ModelPart p : parts) {
            if (p.name.contains("leg")) {
                if (p.name.contains("left")) p.rotation.z = 8f;
                else p.rotation.z = -8f;
                p.rotation.x = swing * 0.7f;
            }
        }
    }
}
