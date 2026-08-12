package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Pre-1.16 Zombie Pigman: neutral until provoked. Hitting one angers it AND
 * every other pigman within 14 blocks (the classic horde mechanic). Calm
 * pigmen wander aimlessly and never pathfind toward the player.
 */
public class ZombiePigmanEntity extends EnemyEntity {

    /** Range at which a provoked pigman enrages its brethren. */
    private static final float HORDE_AGGRO_RANGE = 14.0f;
    /** How long a pigman stays angry after being provoked. */
    private static final float ANGER_DURATION = 45.0f;

    private boolean angry = false;
    private float angryTimer = 0.0f;

    private final Random wanderRand = new Random();
    private Vector3f wanderTarget = null;
    private float wanderTimer = 0.0f;

    private ModelPart leftArm, rightArm;

    public ZombiePigmanEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p2) {
        super(id, position, textureManager, p2);
        loadModel("src/main/resources/assets/minecraft/models/entity/zombie_pigman.json", textureManager);

        for (ModelPart p : parts) {
            if (p.name.equals("left_arm")) leftArm = p;
            else if (p.name.equals("right_arm")) rightArm = p;
        }
    }

    public boolean isAngry() { return angry; }

    /** Provoke this pigman and every other pigman in the horde radius. */
    public void provoke() {
        angry = true;
        angryTimer = ANGER_DURATION;
        aggroNearbyPigmen();
    }

    private void aggroNearbyPigmen() {
        if (entityManager == null) return;
        Vector3f here = getPosition();
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            Entity other = entityManager.getEntity(i);
            if (other == null || other == this) continue;
            if (!(other instanceof ZombiePigmanEntity)) continue;
            ZombiePigmanEntity pigman = (ZombiePigmanEntity) other;
            if (pigman.isDead() || pigman.isAngry()) continue;
            if (here.distanceSquared(pigman.getPosition()) < HORDE_AGGRO_RANGE * HORDE_AGGRO_RANGE) {
                pigman.provoke();
            }
        }
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;

        if (angry) {
            angryTimer -= dt;
            if (angryTimer <= 0.0f) {
                angry = false;
                wanderTarget = null;
            }
        }

        // Pre-1.16 idle stance: arms held out with the sword, slight sway.
        // (Leg rotation is left to the base EnemyEntity walk cycle.)
        float time = (float) (animTime % 2) * 2.0f * (float) Math.PI;
        float swing = angry ? (float) Math.sin(time) * 30f : (float) Math.sin(time) * 6f;
        if (leftArm != null) leftArm.rotation.x = angry ? -swing : swing * 0.4f;
        if (rightArm != null) rightArm.rotation.x = angry ? swing : -swing * 0.4f;
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (isDead() || world == null) return;

        if (!angry) {
            wander(dt);
            return;
        }
        // Angry: full hunt/attack AI from the base class.
        super.updateAI(playerPos, playerVelocity, dt);
    }

    @Override
    public void updateAI(Vector3f playerPos, float dt) {
        updateAI(playerPos, player.getVelocity(), dt);
    }

    /** Calm behaviour: pick a nearby wander point every few seconds and amble to it. */
    private void wander(float dt) {
        wanderTimer -= dt;
        if (wanderTarget == null || wanderTimer <= 0.0f) {
            Vector3f here = getPosition();
            wanderTarget = new Vector3f(
                here.x + (wanderRand.nextFloat() - 0.5f) * 10.0f,
                here.y,
                here.z + (wanderRand.nextFloat() - 0.5f) * 10.0f
            );
            wanderTimer = 3.0f + wanderRand.nextFloat() * 3.0f;
        }

        Vector3f to = new Vector3f(wanderTarget).sub(getPosition());
        to.y = 0.0f;
        if (to.length() < 0.6f) {
            wanderTarget = null;
            return;
        }
        moveToward(wanderTarget, dt, 1.05f);
    }

    @Override
    public void takeDamage(float amount, Vector3f knockback) {
        if (isDead()) return;
        super.takeDamage(amount, knockback);
        if (!isDead() && !angry) {
            provoke();
        }
    }
}
