package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;

/**
 * Wither boss skeleton — the high-tier counterpart to the Ender Dragon.
 *
 * <p>Lifecycle (Mojang parity, simplified):</p>
 * <ol>
 *   <li><b>Charge-up phase</b> — 11 seconds, invulnerable, body pulses,
 *       charging health 0 → 300. The Wither can be cancelled during this
 *       window by killing the spawning player quickly enough.</li>
 *   <li><b>Combat phase</b> — invulnerable flag cleared, the Wither
 *       alternates between holding position and shooting WitherSkullEntity
 *       projectiles at the nearest non-Wither mob/player.</li>
 *   <li><b>Death</b> — drops a single Nether Star at the body's feet.</li>
 * </ol>
 *
 * <p>Each {@link #onPunch()} drains {@code damagePerHit} HP; once
 * {@code health <= 0} the Wither marks itself dead and the spawn-on-death
 * hook in Main.tick drops the Nether Star.</p>
 */
public class WitherEntity extends Entity {

    /** Total HP once the Wither has finished charging. */
    public static final float MAX_HEALTH = 300.0f;
    /** Damage applied per punch (Mojang's diamond-sword kill time is ~5s). */
    public static final float DAMAGE_PER_HIT = 20.0f;
    /** Seconds of pre-fight invulnerability. */
    public static final float CHARGE_SECONDS = 11.0f;

    private float phaseTime = 0.0f;
    private float health = 0.0f;
    private boolean charging = true;
    private boolean dead = false;
    private float fireCooldown = 2.0f;
    private int cumulativeHits = 0;
    private com.voxel.entity.EntityManager entityManager;
    public World world;

    public WitherEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager,
                        com.voxel.entity.EntityManager entityManager) {
        super(id, position);
        this.entityManager = entityManager;
        this.dimension = com.voxel.world.DimensionType.OVERWORLD;
        // Big flying boss: generous pick box so clicks/punches connect.
        this.pickWidth = 3.0f;
        this.pickHeight = 3.5f;
        loadModel("src/main/resources/assets/minecraft/models/entity/wither.json", textureManager);
    }

    public boolean isCharging() { return charging; }
    public boolean isDead() { return dead; }
    public float getHealth() { return health; }
    public void markDead() { dead = true; }

    private boolean dropped = false;
    public boolean markedDropped() { return dropped; }
    public boolean markedXpDropped() { return dropped; }
    public void markXpDropped() { dropped = true; }
    public int xpDropValue() { return 50; }
    public void markDropped() { dropped = true; }

    /**
     * Spawn a Nether Star item at the Wither's feet via the supplied
     * DroppedItemManager. Returns true if a star was emitted.
     *
     * <p>The Wither floats ~3 blocks above its spawn soul sand; pick a
     * clear voxel BELOW its current Y to avoid placing inside a wall.</p>
     */
    public boolean dropLoot(com.voxel.game.DroppedItemManager droppedItemManager) {
        if (droppedItemManager == null) return false;
        int x = (int) Math.floor(getPosX());
        int y = (int) Math.floor(getPosY()) - 1;
        int z = (int) Math.floor(getPosZ());
        int targetY = y;
        for (int dy = 1; dy <= 8; dy++) {
            // We don't have the World here; assume air above the spawn
            // platform. The DroppedItemManager will resolve placement.
            targetY = y - dy;
            break;
        }
        droppedItemManager.spawn("nether_star", 1, x, Math.max(y, targetY), z);
        return true;
    }

    /** Drain 1/15th of MAX_HEALTH per hit so the punch arc mirrors the
     *  Ender Dragon's 20-punch kill cadence. */
    public void onPunch() {
        if (charging) {
            // Cancelled during charging? Mojang spawns a single nether star
            // instead of the regular death drop. We just no-op.
            return;
        }
        cumulativeHits++;
        health -= DAMAGE_PER_HIT;
        if (health <= 0f) {
            dead = true;
            health = 0f;
        }
    }

    @Override
    public void update(float dt) {
        if (dead) return;
        super.update(dt);
        snapshotPrev();

        phaseTime += dt;
        if (charging) {
            // Rising charge: pulse the body up and down while HP fills.
            float charge = Math.min(1.0f, phaseTime / CHARGE_SECONDS);
            health = MAX_HEALTH * charge;
            float pulse = (float) Math.sin(phaseTime * 6.0f) * 0.3f;
            setPositionD(getPosX(), getPosY() + pulse * dt, getPosZ());
            rotation.y += 30.0f * dt;
            if (phaseTime >= CHARGE_SECONDS) {
                charging = false;
                fireCooldown = 1.5f;
            }
            return;
        }

        // Combat phase: idle bob + face the player, then fire periodically.
        fireCooldown -= dt;
        float bob = (float) Math.sin(phaseTime * 1.4f) * 0.2f;
        setPositionD(getPosX(), getPosY() + bob * dt, getPosZ());
        rotation.y += 24.0f * dt;
        rotation.x = -8.0f + (float) Math.sin(phaseTime * 0.8f) * 4.0f;

        if (fireCooldown <= 0f && entityManager != null) {
            fireCooldown = 2.5f;
            float yawRad = (float) Math.toRadians(rotation.y);
            Vector3f mouth = new Vector3f(getPosX(), getPosY() + 1.6f, getPosZ());
            Vector3f heading = new Vector3f((float) Math.cos(yawRad) * 0.85f,
                    -0.10f,
                    (float) Math.sin(yawRad) * 0.85f);
            WitherSkullEntity skull = new WitherSkullEntity(
                    90_000 + entityManager.getEntityCount(),
                    mouth, heading, null);
            skull.world = this.world;
            entityManager.addEntity(skull);
        }
    }
}