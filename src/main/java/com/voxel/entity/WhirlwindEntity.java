package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;
import java.util.Random;

/** Whirlwind - a moving tornado. Damages players on contact (Evil variant). */
public class WhirlwindEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/whirlwind.json";

    private float spin = 0.0f;
    private float dirTimer = 0.0f;
    private float yawDir = 0.0f;
    private final Random rng = new Random();

    public WhirlwindEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 10.0f;
        pickWidth = 1.2f;
        pickHeight = 1.8f;
    }

    @Override
    public void update(float dt) {
        snapshotPrev();
        animTime += dt;
        spin += dt * 220.0f;
        rotation.y = spin % 360.0f;
        if (hitFlashTime > 0) hitFlashTime -= dt;

        // Drift across the island tops
        if (world != null) {
            dirTimer -= dt;
            if (dirTimer <= 0) {
                dirTimer = 3.0f + rng.nextFloat() * 4.0f;
                yawDir = rng.nextFloat() * (float) Math.PI * 2.0f;
            }
            float dx = (float) Math.sin(yawDir) * 0.9f * dt;
            float dz = (float) Math.cos(yawDir) * 0.9f * dt;
            int bx = (int) Math.floor(getPosX() + dx);
            int bz = (int) Math.floor(getPosZ() + dz);
            int by = (int) Math.floor(getPosY());
            if (world.getVoxel(bx, by, bz) == 0 && world.getVoxel(bx, by + 1, bz) == 0) {
                addPosition(dx, 0, dz);
                // Stick to ground level
                if (world.getVoxel(bx, by - 1, bz) == 0 && world.getVoxel(bx, by - 2, bz) != 0)
                    addPosition(0, -1.0f * dt, 0);
            } else {
                yawDir += 2.5f;
            }
        }
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        // Contact damage handled by Main's projectile/contact pass; no chase AI.
        if (player != null && playerPos != null
                && getPosition().distance(playerPos) < 1.4f && attackCooldown <= 0) {
            player.takeDamage(2.0f);
            attackCooldown = 1.0f;
            // Toss the player upward like the tornado
            player.setPosition(player.getPosition().x, player.getPosition().y + 1.2f, player.getPosition().z);
        }
        if (attackCooldown > 0) attackCooldown -= dt;
    }

    @Override
    public int xpDropValue() { return 4; }
}
