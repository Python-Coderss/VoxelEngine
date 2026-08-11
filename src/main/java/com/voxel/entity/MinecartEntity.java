package com.voxel.entity;

import com.voxel.World;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;

/**
 * A rideable minecart. It follows straight rails (north-south or east-west)
 * under its center: it accelerates from the rider's W/S input, coasts with
 * friction, stops at track ends, and falls with gravity when no rail remains
 * beneath it.
 */
public class MinecartEntity extends Entity {
    /** Rail that runs along Z (north-south). */
    public static final int RAIL_NS = 391;
    /** Rail that runs along X (east-west). */
    public static final int RAIL_EW = 392;
    /** Height of the rail slab above its block's base (1/16 of a block). */
    public static final float RAIL_TOP = 1.0f / 16.0f;

    private static final float MAX_SPEED = 4.0f;
    private static final float ACCEL = 3.0f;
    private static final float FRICTION = 1.8f;
    private static final float STOP_EPS = 0.02f;
    private static final float GRAVITY = 18.0f;
    private static final float MAX_FALL_SPEED = 24.0f;

    /** Along-track speed in blocks/second (positive = +Z on N-S rails, +X on E-W rails). */
    public float speed = 0;
    private double vy = 0;
    private boolean onRails = false;

    /** Physics-only constructor (no model) — used by tests. */
    public MinecartEntity(int id, Vector3f position) {
        super(id, position);
    }

    /** Full constructor: loads the cart model from the given texture manager. */
    public MinecartEntity(int id, Vector3f position, TextureManager textureManager) {
        this(id, position);
        loadModel("src/main/resources/assets/minecraft/models/entity/minecart.json", textureManager);
    }

    public boolean isOnRails() { return onRails; }
    public float getSpeed() { return speed; }

    public static boolean isRail(int blockId) {
        return blockId == RAIL_NS || blockId == RAIL_EW;
    }

    /**
     * Advances the cart by dt seconds.
     *
     * @param control riding input in [-1, 1] (1 = forward along the rail axis,
     *                -1 = reverse); a cart with no rider coasts with friction.
     */
    public void updateCart(World world, float dt, float control) {
        if (world == null) return;

        int bx = (int) Math.floor(getPosX());
        int by = (int) Math.floor(getPosY() - 0.05);
        int bz = (int) Math.floor(getPosZ());
        int rail = world.getVoxel(bx, by, bz);

        if (isRail(rail)) {
            boolean ew = rail == RAIL_EW;
            rotation.y = ew ? 90 : 0;

            // Accelerate from rider input; otherwise coast with friction.
            speed += control * ACCEL * dt;
            if (control == 0) {
                if (speed > STOP_EPS) speed = Math.max(0, speed - FRICTION * dt);
                else if (speed < -STOP_EPS) speed = Math.min(0, speed + FRICTION * dt);
            }
            speed = Math.max(-MAX_SPEED, Math.min(MAX_SPEED, speed));

            // Move along the rail's axis, keeping centered on the track.
            float nx = ew ? getPosX() + speed * dt : bx + 0.5f;
            float nz = ew ? bz + 0.5f : getPosZ() + speed * dt;
            int nbx = (int) Math.floor(nx);
            int nbz = (int) Math.floor(nz);
            if (isRail(world.getVoxel(nbx, by, nbz))) {
                setPositionD(nx, by + RAIL_TOP, nz);
            } else {
                // Track ends: park at the far edge of the current rail cell so
                // the cart rests against the end of the track.
                speed = 0;
                float ex = ew ? (getPosX() >= bx + 0.5f ? bx + 0.99f : bx + 0.01f) : bx + 0.5f;
                float ez = ew ? bz + 0.5f : (getPosZ() >= bz + 0.5f ? bz + 0.99f : bz + 0.01f);
                setPositionD(ex, by + RAIL_TOP, ez);
            }
            vy = 0;
            onRails = true;
            return;
        }

        // No rail under the cart: free fall until something is below.
        // The per-tick step is clamped to 0.5 blocks so a 1-block floor can
        // never be tunneled through by a fast-falling cart.
        onRails = false;
        vy = Math.max(vy - GRAVITY * dt, -MAX_FALL_SPEED);
        double step = vy * dt;
        if (step < -0.5) {
            vy = -0.5 / dt;
            step = -0.5;
        }
        double ny = getPosY() + step;
        int fy = (int) Math.floor(ny);
        if (world.getVoxel(bx, fy, bz) != 0) {
            vy = 0;
            setPositionD(getPosX(), fy + 1.0, getPosZ());
        } else {
            setPositionD(getPosX(), ny, getPosZ());
        }
    }
}
