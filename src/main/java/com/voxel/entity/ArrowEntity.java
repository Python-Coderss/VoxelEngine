package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;

/**
 * Arrow projectile fired by skeletons. Flies in a straight line, rotates to
 * face its direction of travel, and expires on block impact or after its
 * lifetime. Main.tick() checks player proximity and applies damage; expired
 * arrows are pruned by EntityManager.
 */
public class ArrowEntity extends Entity {

    /** Damage dealt to the player on a direct hit. */
    public static final float DAMAGE = 3.0f;

    private final Vector3f velocity;
    private float life = 5.0f;
    private boolean expired = false;

    public World world;

    public ArrowEntity(int id, Vector3f position, Vector3f velocity, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        this.velocity = new Vector3f(velocity);
        loadModel("src/main/resources/assets/minecraft/models/entity/arrow.json", textureManager);
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

        // Point the arrow down its line of travel so the thin shaft leads.
        rotation.y = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));

        float nx = getPosX() + velocity.x * dt;
        float ny = getPosY() + velocity.y * dt;
        float nz = getPosZ() + velocity.z * dt;

        // Block collision: an arrow sticks into (and dies against) any solid block.
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
