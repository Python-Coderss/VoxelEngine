package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Valkyrie Queen - Silver Dungeon boss. Duels the player: teleports behind
 * her target, strikes hard in melee, and periodically retreats to recover.
 */
public class ValkyrieQueenEntity extends ValkyrieEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/valkyrie_queen.json";

    private float teleportCooldown = 6.0f;
    private float comboTimer = 0.0f;
    private int comboHits = 0;

    public ValkyrieQueenEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 80.0f;
        pickWidth = 0.8f;
        pickHeight = 2.2f;
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (world == null || isDead() || playerPos == null) return;
        super.updateAI(playerPos, playerVelocity, dt);
        attackCooldown = Math.max(0.0f, attackCooldown);

        float dist = getPosition().distance(playerPos);

        // Phase 2 (below half health): faster teleports and combos
        boolean enraged = health < maxHealth * 0.5f;
        teleportCooldown -= dt * (enraged ? 1.8f : 1.0f);

        if (teleportCooldown <= 0 && dist < 24.0f && dist > 3.0f && world != null) {
            tryTeleportBehind(playerPos);
            teleportCooldown = enraged ? 4.0f : 7.0f;
        }

        // Three-hit combo: extra quick strike after landing a hit
        if (comboHits > 0) {
            comboTimer -= dt;
            if (comboTimer <= 0) {
                if (player != null && dist < 3.2f) {
                    player.takeDamage(2.5f);
                    comboHits--;
                    comboTimer = 0.45f;
                } else {
                    comboHits = 0;
                }
            }
        }
    }

    @Override
    public void performAttack(Vector3f playerPos) {
        super.performAttack(playerPos);
        comboHits = 2;
        comboTimer = 0.45f;
    }

    /** Teleport to a free cell roughly 2 blocks behind the player. */
    private void tryTeleportBehind(Vector3f playerPos) {
        Vector3f away = new Vector3f(getPosition()).sub(playerPos);
        away.y = 0;
        if (away.lengthSquared() < 0.01f) return;
        away.normalize();
        for (int d = 2; d <= 5; d++) {
            float nx = playerPos.x + away.x * d;
            float nz = playerPos.z + away.z * d;
            int bx = (int) Math.floor(nx), bz = (int) Math.floor(nz);
            int by = (int) Math.floor(playerPos.y);
            if (world.getVoxel(bx, by, bz) == 0
                    && world.getVoxel(bx, by + 1, bz) == 0
                    && world.getVoxel(bx, by - 1, bz) != 0) {
                setPositionD(nx, playerPos.y, nz);
                hitFlashTime = 0.35f; // teleport flash
                return;
            }
        }
    }

    @Override
    public int xpDropValue() { return 60; }
}
