package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;
import java.util.Random;

/** Shared passive behavior for the small farm-animal mobs. */
public class FarmAnimalEntity extends Entity {
    protected final Random random = new Random();
    protected ModelPart[] legs;
    protected float animTime = 0.0f;
    private float wanderTimer = 0.0f;
    private float wanderYaw = 0.0f;
    private float limbSwing = 0.0f;

    // ── Health / damage (passive livestock are killable for food) ──
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

    protected World world;

    protected FarmAnimalEntity(int id, Vector3f position,
                               com.voxel.utils.TextureManager textureManager,
                               String modelPath, String... legNames) {
        super(id, position);
        loadModel(modelPath, textureManager);
        refreshLegParts(legNames);
    }

    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public boolean isDead() { return dead; }

    /**
     * Passive livestock take damage like vanilla animals: hurt flash, a panic
     * run away from the attacker, and death with item drops when health runs out.
     * dirX/dirZ is the direction away from the attacker (0,0 for no panic).
     */
    public void damage(float amount, float dirX, float dirZ) {
        if (dead) return;
        health -= amount;
        hitFlashTime = 0.35f;
        if ((dirX != 0.0f || dirZ != 0.0f) && !dead) {
            fleeYaw = (float) Math.atan2(dirX, dirZ);
            fleeTimer = 120; // ~2 s at 60 fps
        }
        if (health <= 0.0f) die();
    }

    /**
     * Item drops on death. Each entry is {itemId, maxRoll} and the engine
     * drops 1..maxRoll items, approximating vanilla's randomized animal loot.
     */
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

    /** Rebind animated limbs after a state/model swap. */
    protected final void refreshLegParts(String... legNames) {
        legs = new ModelPart[legNames.length];
        for (int i = 0; i < legNames.length; i++) {
            legs[i] = findPart(legNames[i]);
        }
    }

    public void setWorld(World world) {
        this.world = world;
    }

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
                wanderTimer = 0.2f; // keep the panic heading
            } else {
                wanderTimer = 1.5f + random.nextFloat() * 3.0f;
                if (random.nextFloat() < 0.35f) {
                    wanderYaw = Float.NaN; // idle for this interval
                } else {
                    wanderYaw = random.nextFloat() * (float) Math.PI * 2.0f;
                }
            }
        }

        boolean moved = false;
        if (!Float.isNaN(wanderYaw) && world != null) {
            float speed = fleeTimer > 0 ? 1.7f : 0.9f;
            if (fleeTimer > 0) wanderYaw = fleeYaw;
            float dx = (float) Math.sin(wanderYaw) * speed * dt;
            float dz = (float) Math.cos(wanderYaw) * speed * dt;
            float nx = getPosX() + dx;
            float nz = getPosZ() + dz;
            int bx = (int) Math.floor(nx);
            int by = (int) Math.floor(getPosY());
            int bz = (int) Math.floor(nz);
            if (world.getVoxel(bx, by - 1, bz) != 0
                    && world.getVoxel(bx, by, bz) == 0
                    && world.getVoxel(bx, by + 1, bz) == 0) {
                addPosition(dx, 0.0f, dz);
                rotation.y = (float) Math.toDegrees(wanderYaw);
                moved = true;
            }
        }

        float amount = moved ? 1.0f : 0.0f;
        if (moved) limbSwing += dt * 6.0f;
        float swing = (float) Math.cos(limbSwing * 0.6662f) * 1.4f * amount;
        float swingOpposite = (float) Math.cos(limbSwing * 0.6662f + Math.PI) * 1.4f * amount;
        for (int i = 0; i < legs.length; i++) {
            if (legs[i] != null) {
                legs[i].rotation.x = (i % 2 == 0 ? swing : swingOpposite)
                        * 180.0f / (float) Math.PI;
            }
        }
    }
}
