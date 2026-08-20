package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Skeleton: a ranged overworld mob. Keeps its distance from the player,
 * strafes to avoid melee, and looses arrows from a bow. Arrows are spawned
 * directly into the EntityManager on the logic thread (see ArrowEntity).
 */
public class SkeletonEntity extends EnemyEntity {

    /** Preferred shooting range. */
    private static final float PREFERRED_RANGE = 9.0f;
    /** How often a skeleton looses an arrow (seconds). */
    private static final float SHOOT_INTERVAL = 2.0f;
    /** Arrow muzzle speed (blocks/sec). */
    private static final float ARROW_SPEED = 16.0f;

    private final com.voxel.utils.TextureManager textureManager;
    private float shootCooldown = 1.2f;
    private float strafePhase = 0.0f;
    private int arrowCounter = 0;

    private ModelPart leftArm, rightArm;

    public SkeletonEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p2) {
        super(id, position, textureManager, p2);
        this.textureManager = textureManager;
        loadModel("src/main/resources/assets/minecraft/models/entity/skeleton.json", textureManager);
        for (ModelPart p : parts) {
            if (p.name.equals("left_arm")) leftArm = p;
            else if (p.name.equals("right_arm")) rightArm = p;
        }
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;

        // Aim the bow arms toward the player by keeping them held forward.
        // A subtle sine sway reads as a drawn bow even without a bow model.
        float sway = (float) Math.sin(animTime * 3.2f) * 8.0f;
        if (leftArm != null) leftArm.rotation.x = -80.0f + sway;
        if (rightArm != null) rightArm.rotation.x = -80.0f - sway;
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

        shootCooldown -= dt;
        strafePhase += dt;

        if (dist < 4.0f) {
            // Too close: back away so the bow stays useful.
            Vector3f away = new Vector3f(toPlayer).normalize().mul(-1.0f);
            Vector3f target = new Vector3f(here).add(away.x * 6.0f, 0, away.z * 6.0f);
            moveToward(target, dt, 3.0f);
        } else if (dist > PREFERRED_RANGE + 4.0f) {
            // Too far: close the gap.
            moveToward(playerPos, dt, 3.4f);
        } else {
            // In range: strafe sideways to be a moving target.
            float strafeDir = (float) Math.signum(Math.sin(strafePhase * 1.4f + id));
            Vector3f right = new Vector3f(toPlayer).normalize()
                    .cross(new Vector3f(0, 1, 0)).normalize();
            Vector3f target = new Vector3f(here)
                    .add(right.x * strafeDir * 6.0f, 0, right.z * strafeDir * 6.0f);
            moveToward(target, dt, 2.2f);
        }

        // Loose an arrow when in range and off cooldown.
        if (shootCooldown <= 0.0f && dist < 20.0f) {
            shootCooldown = SHOOT_INTERVAL + (float) (Math.random() * 0.8f);
            shootArrow(playerPos);
        }
    }

    @Override
    public void updateAI(Vector3f playerPos, float dt) {
        updateAI(playerPos, player.getVelocity(), dt);
    }

    private void shootArrow(Vector3f target) {
        Vector3f here = getPosition();
        Vector3f dir = new Vector3f(target).add(0, 1.2f, 0).sub(here).normalize();
        Vector3f vel = new Vector3f(dir).mul(ARROW_SPEED);

        if (entityManager == null) return;
        ArrowEntity arrow = new ArrowEntity(80000 + (arrowCounter++ % 1000),
                new Vector3f(here.x, here.y + 1.1f, here.z), vel, textureManager);
        arrow.dimension = dimension;
        arrow.world = world;
        entityManager.addEntity(arrow);
    }
}
