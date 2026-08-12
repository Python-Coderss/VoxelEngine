package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;

/**
 * Blaze fireball projectile. Flies in a straight line (slight downward arc),
 * expires on block impact or after its lifetime. Main.tick() checks player
 * proximity and applies damage; expired fireballs are pruned by EntityManager.
 */
public class FireballEntity extends Entity {

    private final Vector3f velocity;
    private float life = 4.0f;
    private boolean expired = false;

    public World world;

    public FireballEntity(int id, Vector3f position, Vector3f velocity, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        this.velocity = new Vector3f(velocity);
        loadModel("src/main/resources/assets/minecraft/models/entity/fireball.json", textureManager);
    }

    public boolean isExpired() { return expired; }

    public void expire() { expired = true; }

    @Override
    public void update(float dt) {
        if (expired) return;
        super.update(dt);
        snapshotPrev();

        life -= dt;
        if (life <= 0.0f) { expired = true; return; }

        float nx = getPosX() + velocity.x * dt;
        float ny = getPosY() + velocity.y * dt;
        float nz = getPosZ() + velocity.z * dt;

        // Block collision: a fireball dies against any solid (non-liquid) block.
        if (world != null) {
            int bx = (int) Math.floor(nx);
            int by = (int) Math.floor(ny);
            int bz = (int) Math.floor(nz);
            int block = world.getVoxel(bx, by, bz);
            if (block != 0) {
                expired = true;
                return;
            }
        }

        setPositionD(nx, ny, nz);
    }
}
