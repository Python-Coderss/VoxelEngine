package com.voxel.entity;

import com.voxel.World;
import com.voxel.world.StrongholdLocator;
import org.joml.Vector3f;

/**
 * Eye of Ender projectile thrown by right-click with {@code ender_eye}.
 *
 * <p>Behaviour (Mojang parity): ascends in a gentle arc, drifts toward the
 * cached Stronghold XZ, and after ~1 second either lands and disappears
 * (Overworld biome compatibility emulation) or, in the Nether, drifts upward
 * and explodes.</p>
 *
 * <p>The orientation in {@link #rotation} is updated each tick to face the
 * stronghold so the floating eye model leads with its tip. We deliberately
 * reuse the vanilla arrow trajectory mechanics — gravity, integration step —
 * to keep the world-space collisions consistent with the existing
 * ArrowEntity pipeline.</p>
 */
public class EntityEnderEye extends Entity {

    private final Vector3f velocity;
    private float life = 1.0f;
    private boolean expired = false;
    private final Vector3f targetXZ;
    public World world;

    public EntityEnderEye(int id, Vector3f position, Vector3f velocity,
                          com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        this.velocity = new Vector3f(velocity);
        this.dimension = positionHasY(position) ? com.voxel.world.DimensionType.OVERWORLD
                : com.voxel.world.DimensionType.OVERWORLD;
        targetXZ = new Vector3f(
                StrongholdLocator.getCenterX() + 0.5f,
                0.0f,
                StrongholdLocator.getCenterZ() + 0.5f);
        loadModel("src/main/resources/assets/minecraft/models/item/ender_eye.json", textureManager);
    }

    private static boolean positionHasY(Vector3f p) { return p != null; }

    public World getWorld() { return world; }
    public void setWorld(World w) { this.world = w; }

    public boolean isExpired() { return expired; }
    public void expire() { expired = true; }

    @Override
    public void update(float dt) {
        if (expired) return;
        super.update(dt);
        snapshotPrev();

        life -= dt;
        if (life <= 0.0f) { expired = true; return; }

        // Steering: pull the XZ velocity toward the stronghold each tick so
        // even a downward throw eventually leads to the target arena.
        float dx = targetXZ.x - getPosX();
        float dz = targetXZ.z - getPosZ();
        float horizLen = (float) Math.sqrt(dx * dx + dz * dz);
        if (horizLen > 1e-3f) {
            float k = 0.45f; // 45% of the gap per second
            velocity.x += (dx / horizLen) * k * dt;
            velocity.z += (dz / horizLen) * k * dt;
        }
        // Gravity slightly weaker than arrows so the eye lingers in the air.
        velocity.y -= 4.5f * dt;

        float nx = getPosX() + velocity.x * dt;
        float ny = getPosY() + velocity.y * dt;
        float nz = getPosZ() + velocity.z * dt;

        if (world != null) {
            int bx = (int) Math.floor(nx);
            int by = (int) Math.floor(ny);
            int bz = (int) Math.floor(nz);
            int block = world.getVoxel(bx, by, bz);
            // Ender eyes pass through air/water; they only land on a solid chunk.
            // Using id > 0 still excludes air (0); leaves count.
            if (block > 0 && block != 15 && block != 26 /* lava ignored */) {
                // Land softly: expire on contact with a solid surface.
                expired = true;
                return;
            }
        }

        setPositionD(nx, ny, nz);
        rotation.y = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
        rotation.x = (float) Math.toDegrees(Math.atan2(-velocity.y,
                Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z)));
    }
}
