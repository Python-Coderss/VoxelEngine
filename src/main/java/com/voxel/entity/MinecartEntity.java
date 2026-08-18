package com.voxel.entity;

import com.voxel.World;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;

/**
 * A rideable minecart. It follows straight rails (north-south or east-west)
 * under its center and arcs through curved corner rails: it accelerates from
 * the rider's W/S input, coasts with friction, stops at track ends, and falls
 * with gravity when no rail remains beneath it.
 */
public class MinecartEntity extends Entity {
    /** Rail that runs along Z (north-south). */
    public static final int RAIL_NS = 391;
    /** Rail that runs along X (east-west). */
    public static final int RAIL_EW = 392;
    /** Curved corner rails (Beta 1.7.3 metadata 6-9): each connects one N-S and
     *  one E-W neighbour. SE = south & east, SW = south & west, NW = north &
     *  west, NE = north & east. */
    public static final int RAIL_CURVE_SE = 450;
    public static final int RAIL_CURVE_SW = 451;
    public static final int RAIL_CURVE_NW = 452;
    public static final int RAIL_CURVE_NE = 453;
    /** Height of the rail slab above its block's base (1/16 of a block). */
    public static final float RAIL_TOP = 1.0f / 16.0f;
    /** Radius of the quarter-circle a cart follows through a curve cell. */
    private static final double CURVE_RADIUS = 0.5;

    private static final float MAX_SPEED = 4.0f;
    private static final float ACCEL = 3.0f;
    private static final float FRICTION = 1.8f;
    private static final float GRAVITY = 18.0f;
    private static final float MAX_FALL_SPEED = 24.0f;

    /** Along-track speed in blocks/second (magnitude, always >= 0). */
    public float speed = 0;
    /** Unit direction of travel (one component 0, the other ±1). Follows the
     *  rail axis on straights; the arc entry direction on curves. */
    private int headX = 0, headZ = 0;
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
        applyFillLevel();
    }

    /** How full of dirt the cart is, 0 (empty) to 1 (full). */
    private float fillLevel = 0;

    public boolean isOnRails() { return onRails; }
    public float getSpeed() { return speed; }
    public float getFillLevel() { return fillLevel; }

    /**
     * Sets how full of dirt the cart is and repositions the dirt part in the
     * model: the dirt slab's bottom tracks the fill level, from just below the
     * floor (empty, hidden) up to the deck underside (full).
     */
    public void setFillLevel(float level) {
        fillLevel = Math.max(0f, Math.min(1f, level));
        applyFillLevel();
    }

    private void applyFillLevel() {
        ModelPart dirt = findPart("dirt");
        if (dirt != null) {
            // Dirt slab is 1 unit (1/16 block) thick. Its bottom (offset.y in
            // 1/16 units) rides the cart's interior: just below the deck floor
            // (y = 2) when empty so it's hidden, up to the wall tops (y = 10)
            // when full.
            dirt.offset.y = 1f + fillLevel * 8f;
        }
    }

    public static boolean isCurve(int blockId) {
        return blockId >= RAIL_CURVE_SE && blockId <= RAIL_CURVE_NE;
    }

    public static boolean isRail(int blockId) {
        return blockId == RAIL_NS || blockId == RAIL_EW || isCurve(blockId);
    }

    /** The two directions a curve connects, as {dx1, dz1, dx2, dz2}. */
    private static int[] curveDirs(int blockId) {
        switch (blockId) {
            case RAIL_CURVE_SE: return new int[]{ 0,  1,  1,  0}; // south & east
            case RAIL_CURVE_SW: return new int[]{ 0,  1, -1,  0}; // south & west
            case RAIL_CURVE_NW: return new int[]{ 0, -1, -1,  0}; // north & west
            default:            return new int[]{ 0, -1,  1,  0}; // north & east
        }
    }

    /**
     * Advances the cart by dt seconds.
     *
     * @param control riding input in [-1, 1] (1 = forward along the rail axis,
     *                -1 = reverse); a cart with no rider coasts with friction.
     */
    public void updateCart(World world, float dt, float control) {
        if (world == null) return;
        // Snapshot the pre-move position so the render thread's interpolation
        // (lerp(prev, pos, partialTicks)) slides smoothly instead of swinging
        // between the spawn point and the current position every frame.
        snapshotPrev();

        int bx = (int) Math.floor(getPosX());
        int by = (int) Math.floor(getPosY() - 0.05);
        int bz = (int) Math.floor(getPosZ());
        int rail = world.getVoxel(bx, by, bz);

        if (isRail(rail)) {
            boolean curve = isCurve(rail);
            // speed is a magnitude (>= 0) and headX/headZ is the actual direction
            // of travel. W accelerates along the heading; S decelerates and, once
            // stopped, flips the heading to reverse.
            if (control > 0) {
                speed = Math.min(MAX_SPEED, speed + ACCEL * dt);
            } else if (control < 0) {
                if (curve) {
                    // Backing up through a curve isn't modelled — just brake.
                    speed = Math.max(0, speed - ACCEL * dt);
                } else {
                    speed -= ACCEL * dt;
                    if (speed < 0) {
                        speed = -speed;
                        headX = -headX;
                        headZ = -headZ;
                    }
                }
            } else {
                speed = Math.max(0, speed - FRICTION * dt);
            }

            if (curve) {
                moveAlongCurve(world, bx, by, bz, rail, dt);
            } else {
                moveAlongStraight(world, bx, by, bz, rail, dt);
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

    /** Straight rail: move along the heading, keeping centered on the track. */
    private void moveAlongStraight(World world, int bx, int by, int bz, int rail, float dt) {
        boolean ew = rail == RAIL_EW;
        // Establish a direction of travel the first time the cart touches a rail.
        if (headX == 0 && headZ == 0) {
            headX = ew ? 1 : 0;
            headZ = ew ? 0 : 1;
        }
        // Model's long (20-unit) axis runs along X: on an E-W rail keep it
        // aligned, on a N-S rail rotate 90° so the cart lies along the track.
        rotation.y = ew ? 0 : 90;

        float nx = getPosX() + headX * speed * dt;
        float nz = getPosZ() + headZ * speed * dt;
        if (ew) nz = bz + 0.5f; else nx = bx + 0.5f;
        int nbx = (int) Math.floor(nx);
        int nbz = (int) Math.floor(nz);
        if (isRail(world.getVoxel(nbx, by, nbz))) {
            setPositionD(nx, by + RAIL_TOP, nz);
        } else {
            // Track ends: park at the far edge of the current rail cell so
            // the cart rests against the end of the track.
            speed = 0;
            float ex = ew ? (headX > 0 ? bx + 0.99f : bx + 0.01f) : bx + 0.5f;
            float ez = ew ? bz + 0.5f : (headZ > 0 ? bz + 0.99f : bz + 0.01f);
            setPositionD(ex, by + RAIL_TOP, ez);
        }
    }

    /**
     * Curved rail: follow the quarter-circle arc from the entry edge (the edge
     * the cart crossed while travelling along its heading) to the exit edge
     * (the other direction the curve connects). The arc is centered on the
     * cell corner between the two edges, radius 0.5, so the cart enters and
     * leaves tangent to the straight rails — no snapping.
     *
     * The curve's connection directions point OUTWARD from the cell, so the
     * entry edge (which the cart crosses moving along its heading) is the
     * neighbor in direction -heading; the arc then sweeps to the edge facing
     * the other connection.
     */
    private void moveAlongCurve(World world, int bx, int by, int bz, int rail, float dt) {
        int[] dirs = curveDirs(rail);
        int ex = headX, ez = headZ;
        if (ex == 0 && ez == 0) { // cart spawned directly on a curve
            ex = -dirs[0];
            ez = -dirs[1];
            headX = ex;
            headZ = ez;
        }
        int xx, xz;
        if (dirs[0] == -ex && dirs[1] == -ez) {
            xx = dirs[2];
            xz = dirs[3];
        } else if (dirs[2] == -ex && dirs[3] == -ez) {
            xx = dirs[0];
            xz = dirs[1];
        } else {
            // Approach direction isn't connected by this curve — dead end.
            speed = 0;
            return;
        }

        // Arc centre: the cell corner between the entry and exit edges.
        double ccx = bx + 0.5 + 0.5 * (xx - ex);
        double ccz = bz + 0.5 + 0.5 * (xz - ez);
        double px = getPosX() - ccx, pz = getPosZ() - ccz;
        // Theta sweeps 0 (entry edge) -> PI/2 (exit edge).
        double theta = Math.atan2(px * ex + pz * ez, -(px * xx + pz * xz));
        theta = Math.max(0, Math.min(Math.PI * 0.5, theta));
        double nextTheta = theta + speed * dt / CURVE_RADIUS;
        boolean exited = false;
        if (nextTheta >= Math.PI * 0.5) {
            int nbx = bx + xx, nbz = bz + xz;
            if (!isRail(world.getVoxel(nbx, by, nbz))) {
                // Track ends at the curve: rest just inside the cell so the cart
                // doesn't roll off the end.
                speed = 0;
                nextTheta = Math.PI * 0.5 - 0.0001;
            } else {
                nextTheta = Math.PI * 0.5;
                headX = xx;
                headZ = xz;
                exited = true;
            }
        }
        double nx = ccx + 0.5 * (-xx * Math.cos(nextTheta) + ex * Math.sin(nextTheta));
        double nz = ccz + 0.5 * (-xz * Math.cos(nextTheta) + ez * Math.sin(nextTheta));
        if (exited) {
            // The arc exit sits exactly on the shared cell boundary; nudge into
            // the exit cell so the next frame reads the straight rail (floor()
            // would otherwise round a west/north exit back into the curve cell).
            // Must exceed one fixed-point step (1/256 ≈ 0.0039) or it rounds away.
            double eps = 0.01;
            nx += xx * eps;
            nz += xz * eps;
        }
        setPositionD(nx, by + RAIL_TOP, nz);
        rotation.y = Math.abs(headX) == 1 ? 0 : 90;
    }
}
