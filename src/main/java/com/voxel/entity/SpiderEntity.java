package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Spider: a fast melee overworld mob. No pathfinding — it charges the player
 * in a straight line (auto-stepping over terrain via the base tryMove logic)
 * and bites on contact, dealing more damage than a zombie but with less reach.
 */
public class SpiderEntity extends EnemyEntity {

    /** Spider bite damage per hit. */
    private static final float BITE_DAMAGE = 3.0f;
    /** Chase speed (faster than the humanoid mobs). */
    private static final float CHASE_SPEED = 4.4f;
    /** Bite cooldown (seconds). */
    private static final float BITE_COOLDOWN = 1.1f;

    private float biteCooldown = 0.6f;
    private final ModelPart[] legs = new ModelPart[8];
    private Vector3f lastAnimationPosition;
    private float limbSwing = 0.0f;

    private static final float[] BASE_YAW = {
        45.0f, -45.0f, 22.5f, -22.5f,
        -22.5f, 22.5f, -45.0f, 45.0f
    };
    private static final float[] BASE_ROLL = {
        -45.0f, 45.0f, -33.3f, 33.3f,
        -33.3f, 33.3f, -45.0f, 45.0f
    };

    public SpiderEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p2) {
        super(id, position, textureManager, p2);
        loadModel("src/main/resources/assets/minecraft/models/entity/spider.json", textureManager);
        for (ModelPart p : parts) {
            if (p.name.startsWith("left_leg_")) {
                legs[Integer.parseInt(p.name.substring("left_leg_".length())) * 2 - 2] = p;
            } else if (p.name.startsWith("right_leg_")) {
                legs[Integer.parseInt(p.name.substring("right_leg_".length())) * 2 - 1] = p;
            }
        }
    }

    @Override
    public void update(float dt) {
        super.update(dt);

        // ModelSpider is driven by limbSwing, not by an idle sine wave. Measure
        // the horizontal movement since the previous entity update so the legs
        // stop at the base pose when the spider stops and scale with real speed.
        Vector3f current = getPosition();
        float horizontalDistance = 0.0f;
        if (lastAnimationPosition != null) {
            float dx = current.x - lastAnimationPosition.x;
            float dz = current.z - lastAnimationPosition.z;
            horizontalDistance = (float) Math.sqrt(dx * dx + dz * dz);
        }
        lastAnimationPosition = current;

        limbSwing += horizontalDistance * 6.0f;
        float limbSwingAmount = dt > 0.0001f
                ? Math.min(1.0f, horizontalDistance / dt * 0.24f)
                : 0.0f;
        float swing = limbSwing * 0.6662f * 2.0f;

        // Exact vanilla ModelSpider phase relationships, in radians.
        float f3 = -(float) Math.cos(swing) * 0.4f * limbSwingAmount;
        float f4 = -(float) Math.cos(swing + (float) Math.PI) * 0.4f * limbSwingAmount;
        float f5 = -(float) Math.cos(swing + (float) Math.PI / 2.0f) * 0.4f * limbSwingAmount;
        float f6 = -(float) Math.cos(swing + (float) Math.PI * 1.5f) * 0.4f * limbSwingAmount;
        float f7 = Math.abs((float) Math.sin(limbSwing * 0.6662f) * 0.4f) * limbSwingAmount;
        float f8 = Math.abs((float) Math.sin(limbSwing * 0.6662f + (float) Math.PI) * 0.4f) * limbSwingAmount;
        float f9 = Math.abs((float) Math.sin(limbSwing * 0.6662f + (float) Math.PI / 2.0f) * 0.4f) * limbSwingAmount;
        float f10 = Math.abs((float) Math.sin(limbSwing * 0.6662f + (float) Math.PI * 1.5f) * 0.4f) * limbSwingAmount;

        float[] yawSwing = { f3, -f3, f4, -f4, f5, -f5, f6, -f6 };
        float[] rollSwing = { f7, -f7, f8, -f8, f9, -f9, f10, -f10 };
        for (int i = 0; i < legs.length; i++) {
            if (legs[i] == null) continue;
            legs[i].rotation.y = BASE_YAW[i] + (float) Math.toDegrees(yawSwing[i]);
            legs[i].rotation.z = BASE_ROLL[i] + (float) Math.toDegrees(rollSwing[i]);
        }
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (isDead() || world == null) return;

        Vector3f here = getPosition();
        Vector3f toPlayer = new Vector3f(playerPos).sub(here);
        float dist = toPlayer.length();

        // Face the player.
        if (dist > 0.01f) {
            rotation.y = (float) Math.toDegrees(Math.atan2(toPlayer.x, toPlayer.z));
        }

        biteCooldown -= dt;

        if (dist < 2.4f) {
            // In bite range: strike on cooldown.
            if (biteCooldown <= 0.0f) {
                performAttack(playerPos);
                biteCooldown = BITE_COOLDOWN;
            }
        } else {
            // Charge headlong at the player.
            moveToward(playerPos, dt, CHASE_SPEED);
        }
    }

    @Override
    public void updateAI(Vector3f playerPos, float dt) {
        updateAI(playerPos, player.getVelocity(), dt);
    }

    /** Spider bite: faster, lighter hits than a zombie's wind-up strike. */
    @Override
    public void performAttack(Vector3f playerPos) {
        if (player != null) {
            player.takeDamage(BITE_DAMAGE);
        }
    }
}
