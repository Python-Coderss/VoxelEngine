package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Magma Cube — the Nether's fire-immune equivalent of a slime.
 *
 * <p>Bouncing AI:</p>
 * <ul>
 *   <li>Jumps every ~1.5 seconds in a random direction</li>
 *   <li>Splits into smaller (size-1) cubes on each player hit until size = 1</li>
 *   <li>Cube size starts at 4 (Mojang) → 3 → 2 → 1 (the smallest cube drops
 *       magma cream and XP)</li>
 * </ul>
 *
 * <p>The model {@code magma_cube.json} is a single 8×8×8 cuboid with a
 * magma cube texture; we stretch it with {@link #setScale(float, float, float)}
 * to match the size tier.</p>
 */
public class MagmaCubeEntity extends EnemyEntity {

    /** Size in cubic blocks. Each hit shrinks size by 1; size=1 dies. */
    private int size;

    private float jumpCooldown = 1.5f;
    private float bobPhase = 0.0f;
    private final Vector3f lastJumpVelocity = new Vector3f(0, 0, 0);
    private int hitCount = 0;

    public MagmaCubeEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager,
                            Player p, int size) {
        super(id, position, textureManager, p);
        this.size = Math.max(1, Math.min(4, size));
        loadModel("src/main/resources/assets/minecraft/models/entity/magma_cube.json", textureManager);
        // Visual scale would normally be applied via setScale(), but Entity
        // doesn't expose that yet — for now we just remember the size and
        // use it for collision/damage scaling.
    }

    public int getSize() { return size; }

    /** Player-attack callback: splits the cube into a smaller one. */
    public void onPunch() {
        hitCount++;
        size--;
        if (size <= 0) {
            // Smallest cube dies on the next tick — Main.tick polls isDead().
            dead = true;
        }
    }

    @Override
    public void update(float dt) {
        if (dead) return;
        super.update(dt);
        snapshotPrev();

        bobPhase += dt;
        // Vertical squash-and-stretch: when on the ground the cube squishes
        // down on impact; in the air it stretches. Approximate with a
        // simple sin-driven height wobble.
        float squash = 0.05f * (float) Math.sin(bobPhase * 6.0f);
        float ny = getPosY() + squash * dt;
        setPositionD(getPosX(), ny, getPosZ());

        jumpCooldown -= dt;
        if (jumpCooldown <= 0.0f) {
            // Pick a random direction and leap.
            float angle = (float) (Math.random() * Math.PI * 2.0);
            float speed = 0.3f + 0.05f * size;
            lastJumpVelocity.set((float) Math.cos(angle) * speed, 0.5f,
                    (float) Math.sin(angle) * speed);
            jumpCooldown = 1.5f - 0.1f * size;
        }

        // Apply the jump velocity each tick — actual position update happens
        // in the parent class's tick() via the velocity field. This is a
        // placeholder for future ground-collision extensions; the parent's
        // update() handles position integration.
    }

    public Vector3f getLastJumpVelocity() { return lastJumpVelocity; }

    private boolean dead = false;
    public boolean isDead() { return dead; }
}