package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;
import java.util.Random;

/**
 * Base class for the Aether's passive sky mobs.
 * Supports ground wandering, hopping and free flight (hover with drift).
 */
public abstract class AetherPassiveEntity extends Entity {
    protected final Random random = new Random();
    protected ModelPart[] legs;
    protected float animTime = 0.0f;
    protected World world;

    private float wanderTimer = 0.0f;
    private float wanderYaw = 0.0f;
    private float limbSwing = 0.0f;

    /** Movement modes. */
    protected enum MoveMode { WALK, HOP, FLY }
    protected MoveMode moveMode = MoveMode.WALK;
    /** Flight altitude band for FLY mode. */
    protected float flyMinY = 90f, flyMaxY = 120f;
    protected float moveSpeed = 0.9f;

    protected AetherPassiveEntity(int id, Vector3f position,
                                  com.voxel.utils.TextureManager textureManager,
                                  String modelPath) {
        super(id, position);
        loadModel(modelPath, textureManager);
    }

    public void setWorld(World world) { this.world = world; }

    @Override
    public void update(float dt) {
        super.update(dt);
        animTime += dt;
        snapshotPrev();

        wanderTimer -= dt;
        if (wanderTimer <= 0.0f) {
            wanderTimer = 1.5f + random.nextFloat() * 3.5f;
            wanderYaw = (random.nextFloat() < 0.35f)
                    ? Float.NaN
                    : random.nextFloat() * (float) Math.PI * 2.0f;
        }

        boolean moved = false;
        if (!Float.isNaN(wanderYaw)) {
            switch (moveMode) {
                case WALK: moved = walkStep(dt); break;
                case HOP:  moved = hopStep(dt); break;
                case FLY:  moved = flyStep(dt); break;
            }
        }

        animate(moved);
    }

    private boolean walkStep(float dt) {
        return tryGroundMove(
                (float) Math.sin(wanderYaw) * moveSpeed * dt,
                (float) Math.cos(wanderYaw) * moveSpeed * dt);
    }

    private boolean hopStep(float dt) {
        // Bounded hops: short bursts of horizontal motion with a small rise/fall cycle.
        float phase = (animTime * 2.2f) % 1.0f;
        float arc = (float) Math.sin(phase * Math.PI);
        addPosition(0.0f, arc * 0.35f - 0.175f, 0.0f);
        return tryGroundMove(
                (float) Math.sin(wanderYaw) * moveSpeed * 1.6f * dt * arc,
                (float) Math.cos(wanderYaw) * moveSpeed * 1.6f * dt * arc);
    }

    private boolean flyStep(float dt) {
        float dx = (float) Math.sin(wanderYaw) * moveSpeed * dt;
        float dz = (float) Math.cos(wanderYaw) * moveSpeed * dt;
        // Gentle vertical drift within the flight band
        float dy = (float) Math.sin(animTime * 0.7f) * 0.25f * dt;
        if (getPosY() < flyMinY) dy = Math.abs(dy) + 0.05f * dt;
        if (getPosY() > flyMaxY) dy = -Math.abs(dy) - 0.05f * dt;
        int bx = (int) Math.floor(getPosX() + dx);
        int bz = (int) Math.floor(getPosZ() + dz);
        if (world != null && world.getVoxel(bx, (int) Math.floor(getPosY()), bz) != 0) {
            wanderYaw += 1.8f; // turn away from terrain
            return false;
        }
        addPosition(dx, dy, dz);
        rotation.y = (float) Math.toDegrees(wanderYaw);
        return true;
    }

    private boolean tryGroundMove(float dx, float dz) {
        float nx = getPosX() + dx;
        float nz = getPosZ() + dz;
        int bx = (int) Math.floor(nx);
        int by = (int) Math.floor(getPosY());
        int bz = (int) Math.floor(nz);
        if (world == null) { addPosition(dx, 0, dz); return true; }
        if (world.getVoxel(bx, by - 1, bz) != 0
                && world.getVoxel(bx, by, bz) == 0
                && world.getVoxel(bx, by + 1, bz) == 0) {
            addPosition(dx, 0.0f, dz);
            rotation.y = (float) Math.toDegrees(wanderYaw);
            return true;
        }
        return false;
    }

    protected void animate(boolean moved) {
        float amount = moved ? 1.0f : 0.0f;
        if (moved) limbSwing += animTime > 0 ? 0.1f : 0.0f;
        limbSwing += 0.1f * amount;
        float swing = (float) Math.cos(limbSwing * 6.662f) * 1.4f * amount;
        float swingOpposite = -swing;
        for (int i = 0; i < legs.length; i++) {
            if (legs[i] != null) {
                legs[i].rotation.x = (i % 2 == 0 ? swing : swingOpposite)
                        * 180.0f / (float) Math.PI;
            }
        }
        // Wing flap for flyers / gliders
        ModelPart lw = findPart("left_wing");
        ModelPart rw = findPart("right_wing");
        if (lw != null && rw != null && (moveMode == MoveMode.FLY || moveMode == MoveMode.HOP)) {
            float flap = (float) Math.sin(animTime * 6.0f) * 30.0f;
            lw.rotation.z = flap;
            rw.rotation.z = -flap;
        }
    }

    protected void bindLegs(String... names) {
        legs = new ModelPart[names.length];
        for (int i = 0; i < names.length; i++) legs[i] = findPart(names[i]);
    }
}
