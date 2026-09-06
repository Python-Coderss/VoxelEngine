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

    // ── Health / damage (Aether wildlife is huntable for loot) ──
    protected float health = 10.0f;
    protected float maxHealth = 10.0f;
    private boolean dead = false;
    public float hitFlashTime = 0.0f;
    /** Frames left in the panic run; 0 = calm wandering. */
    private int fleeTimer = 0;
    private float fleeYaw = 0.0f;

    /** Runtime hook so Main can route drops into DroppedItemManager. */
    public interface DropSpawner {
        void spawn(String itemId, int count, float x, float y, float z);
    }
    private static volatile DropSpawner dropSpawner;

    public static void setDropSpawner(DropSpawner spawner) { dropSpawner = spawner; }

    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public boolean isDead() { return dead; }

    /** Passive wildlife: hurt flash, panic flee, death with item drops. */
    public void damage(float amount, float dirX, float dirZ) {
        if (dead) return;
        health -= amount;
        hitFlashTime = 0.35f;
        if ((dirX != 0.0f || dirZ != 0.0f) && !dead) {
            fleeYaw = (float) Math.atan2(dirX, dirZ);
            fleeTimer = 120; // ~2 s of panic
        }
        if (health <= 0.0f) die();
    }

    /** Item drops on death: {itemId, maxRoll}, engine drops 1..maxRoll. */
    protected String[][] deathLoot() { return null; }

    private void die() {
        if (dead) return;
        dead = true;
        rotation.x = 78.0f;
        DropSpawner spawner = dropSpawner;
        if (spawner == null) return;
        String[][] loot = deathLoot();
        if (loot == null) return;
        float x = getPosX(), y = getPosY() + 0.4f, z = getPosZ();
        for (String[] entry : loot) {
            if (entry == null || entry.length < 2) continue;
            int max = Math.max(1, Integer.parseInt(entry[1]));
            int count = 1 + random.nextInt(max);
            spawner.spawn(entry[0], count, x, y, z);
        }
    }

    /** Persistence hook: restore saved health. */
    public void restoreHealth(float h) { health = Math.max(1.0f, Math.min(h, maxHealth)); }

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
        if (hitFlashTime > 0.0f) hitFlashTime -= dt;
        if (dead) return;

        wanderTimer -= dt;
        if (wanderTimer <= 0.0f) {
            if (fleeTimer > 0) {
                fleeTimer--;
                wanderYaw = fleeYaw;
                wanderTimer = 0.2f; // keep the panic heading
                moveSpeed = 2.2f;
            } else {
                wanderTimer = 1.5f + random.nextFloat() * 3.5f;
                wanderYaw = (random.nextFloat() < 0.35f)
                        ? Float.NaN
                        : random.nextFloat() * (float) Math.PI * 2.0f;
                moveSpeed = 0.9f;
            }
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
