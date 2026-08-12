package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Blaze: a floating Nether mob. Hovers toward the player (no ground pathing),
 * bobs up and down, and launches fireballs from range. Fireballs are spawned
 * directly into the EntityManager on the logic thread.
 */
public class BlazeEntity extends EnemyEntity {

    /** How often a blaze fires a fireball (seconds). */
    private static final float SHOOT_INTERVAL = 2.2f;
    /** Preferred combat range. */
    private static final float PREFERRED_RANGE = 8.0f;
    /** Fireball muzzle speed (blocks/sec). */
    private static final float FIREBALL_SPEED = 9.0f;

    private final com.voxel.utils.TextureManager textureManager;
    private float shootCooldown = 1.5f;
    private float bobPhase = 0.0f;
    private int fireballCounter = 0;

    public BlazeEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p2) {
        super(id, position, textureManager, p2);
        this.textureManager = textureManager;
        loadModel("src/main/resources/assets/minecraft/models/entity/blaze.json", textureManager);
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;

        // Floating bob: gentle vertical sine motion.
        bobPhase += dt * 2.6f;
        float bob = (float) Math.sin(bobPhase) * 0.14f;
        long bobFp = com.voxel.utils.FixedPoint.fromFloat(bob);
        // super.update() already applied walk-bob on posY; add the hover bob on top.
        addFixed(0, bobFp, 0);

        // Slow idle spin of the whole body.
        rotation.y += dt * 18.0f;
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

        if (dist < 2.0f) {
            // Too close: back away (direct position manipulation via addFixed).
            Vector3f away = new Vector3f(toPlayer).normalize().mul(-dt * 2.6f);
            addFixed(com.voxel.utils.FixedPoint.fromFloat(away.x), 0, com.voxel.utils.FixedPoint.fromFloat(away.z));
        } else if (dist > PREFERRED_RANGE + 6.0f) {
            // Too far: drift closer (use moveToward from base class).
            Vector3f target = new Vector3f(playerPos);
            target.y = here.y + (playerPos.y - here.y) * 0.5f;
            moveToward(target, dt, 3.2f);
        } else {
            // In range: strafe around the player while staying airborne.
            float strafeDir = (float) Math.signum(Math.sin(bobPhase * 0.8f + id));
            Vector3f right = new Vector3f(toPlayer).normalize()
                    .cross(new Vector3f(0, 1, 0)).normalize();
            float sx = right.x * strafeDir * dt * 2.0f;
            float sz = right.z * strafeDir * dt * 2.0f;
            addFixed(com.voxel.utils.FixedPoint.fromFloat(sx), 0, com.voxel.utils.FixedPoint.fromFloat(sz));

            // Keep roughly at the player's eye height.
            float dy = (playerPos.y + 1.0f) - here.y;
            addFixed(0, com.voxel.utils.FixedPoint.fromFloat(dy * 1.2f * dt), 0);
        }

        // Fire!
        if (shootCooldown <= 0.0f && dist < 18.0f) {
            shootCooldown = SHOOT_INTERVAL + (float) (Math.random() * 0.8f);
            shootFireball(playerPos);
        }
    }

    @Override
    public void updateAI(Vector3f playerPos, float dt) {
        updateAI(playerPos, player.getVelocity(), dt);
    }

    private void shootFireball(Vector3f target) {
        Vector3f here = getPosition();
        Vector3f dir = new Vector3f(target).add(0, 1.2f, 0).sub(here).normalize();
        Vector3f vel = new Vector3f(dir).mul(FIREBALL_SPEED);

        if (entityManager == null) return;
        FireballEntity fireball = new FireballEntity(90000 + (fireballCounter++ % 1000),
                new Vector3f(here.x, here.y + 0.9f, here.z), vel, textureManager);
        fireball.dimension = dimension;
        fireball.world = world;
        entityManager.addEntity(fireball);
    }
}
