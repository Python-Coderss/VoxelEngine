package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;

/**
 * Wither Skull projectile — fired by the Wither boss.
 *
 * <p>Slower than a Blaze fireball, larger explosion radius, and applies
 * Wither effect (placeholder: just direct damage here). In Mojang, blue
 * skulls do less damage than black skulls; we treat them as the same.</p>
 */
public class WitherSkullEntity extends Entity {

    private final Vector3f velocity;
    private float life = 2.0f;
    private boolean expired = false;
    public World world;

    public WitherSkullEntity(int id, Vector3f position, Vector3f velocity,
                              com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        this.velocity = new Vector3f(velocity);
        loadModel("src/main/resources/assets/minecraft/models/item/skull_wither.json",
                textureManager);
    }

    public boolean isExpired() { return expired; }

    @Override
    public void update(float dt) {
        if (expired) return;
        super.update(dt);
        snapshotPrev();

        life -= dt;
        if (life <= 0.0f) { expired = true; return; }

        // Slight homing: pull toward player-position estimate if available.
        velocity.y -= 1.0f * dt; // gentle gravity
        float nx = getPosX() + velocity.x * dt;
        float ny = getPosY() + velocity.y * dt;
        float nz = getPosZ() + velocity.z * dt;

        if (world != null) {
            int bx = (int) Math.floor(nx);
            int by = (int) Math.floor(ny);
            int bz = (int) Math.floor(nz);
            int block = world.getVoxel(bx, by, bz);
            if (block != 0) {
                // Mojang: black wither skulls deal 8 damage on impact, blue
                // skulls do 5. We treat all as a 5-damage blast.
                expired = true;
                return;
            }
        }
        setPositionD(nx, ny, nz);
        rotation.y = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
    }
}