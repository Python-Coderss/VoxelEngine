package com.voxel.entity;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;

/**
 * Ender Dragon skeleton.
 *
 * <p>Mirrors the gameplay arc of Mojang's boss without re-implementing the
 * full body-segment AI:</p>
 * <ol>
 *   <li>Phase 1 — circling the bedrock pillar. The dragon preserves a
 *       curved orbit around the End spawn platform at radius ~38, height
 *       ~120 (matching the wide sky-arena feel even without the body
 *       segment chain).</li>
 *   <li>Phase 2 — fireball breath. While circling, the dragon drops a
 *       {@link FireballEntity} every 4 seconds along its tangent heading.</li>
 *   <li>Phase 3 — perch. After enough sun-time the dragon rests on the
 *       fountain pillar at (100, 49, 0). Punching it three times returns it
 *       to circling; this is the player-facing "punch 3 crystals" loop.</li>
 *   <li>Death → drops a {@code dragon_egg} on top of the fountain.</li>
 * </ol>
 *
 * <p>The model {@code assets/minecraft/models/entity/dragon.json} provides
 * the 14-part cuboid truss. We do not animate individual rig boxes here —
 * the whole-body rotation is enough to convey the boss presence and to
 * connect to the eel-like skeleton of the vanilla fight.</p>
 */
public class EnderDragonEntity extends Entity {

    public static final float ORBIT_RADIUS         = 38.0f;
    public static final float ORBIT_HEIGHT         = 120.0f;
    public static final float ORBIT_SPEED_RAD_PER_S = 0.18f;

    private final Vector3f pillarCenter = new Vector3f(100.0f, 64.0f, 0.0f);

    private float phaseTime = 0.0f;
    private float breathCooldown = 3.5f;
    private float orbitAngle = 0.0f;
    private boolean perched = false;
    private boolean dead = false;
    private int cumulativeHits = 0;
    private boolean dropped = false;

    /** Set by external code after the dragon takes damage. */
    public void onPunch() {
        cumulativeHits++;
        if (perched && cumulativeHits >= 3) {
            perched = false;
            orbitAngle = (float) (Math.atan2(getPosZ() - pillarCenter.z, getPosX() - pillarCenter.x));
            phaseTime = 0.0f;
        }
        // Light damage model: each punch drains 5% health. Kill threshold is
        // reached after ~20 punches. Players use the right-click on the body
        // (or stand on it during the perch phase) to trigger this callback.
        takeDamage(0.05f);
    }

    /** Health gate: 0..1, drained by onPunch. Below 0 the dragon dies. */
    public float getHealth() { return Math.max(0f, 1f - cumulativeHits * 0.05f); }

    /** Returns the cumulativeHits counter, used by EndCrystalEntity. */
    public int getCumulativeHits() { return cumulativeHits; }

    /** End Crystal regen: each tick a live crystal adds to the dragon's health
     *  by reducing the cumulativeHits counter (clamped at zero). */
    public void heal(float amount) {
        if (amount <= 0f) return;
        // Healing lowers the cumulative hits. A 0.02/tick regen would offset
        // a single 0.05/punch, so the dragon is meaningfully tougher while
        // crystals are alive.
        cumulativeHits = Math.max(0, cumulativeHits - (int) Math.ceil(amount * 100.0f));
    }

    private void takeDamage(float amount) {
        // Cumulative hits double as the death counter. With amount = 0.05,
        // 20 punches are enough to kill the dragon and drop the egg.
        if (amount <= 0f) return;
        if (getHealth() <= 0f) {
            dead = true;
        }
    }

    public boolean isDead() { return dead; }
    public void markDead() { dead = true; }
    public boolean markedDropped() { return dropped; }
    public boolean markedXpDropped() { return dropped; }
    public void markXpDropped() { dropped = true; }
    public int xpDropValue() { return 12000; }
    public void markDropped() { dropped = true; }

    public EnderDragonEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager,
                             com.voxel.entity.EntityManager entityManager) {
        super(id, position);
        this.entityManager = entityManager;
        this.dimension = com.voxel.world.DimensionType.END;
        // Huge flying boss: generous pick box so clicks/punches connect.
        this.pickWidth = 4.0f;
        this.pickHeight = 5.0f;
        loadModel("src/main/resources/assets/minecraft/models/entity/dragon.json", textureManager);
    }

    private com.voxel.entity.EntityManager entityManager;

    public void setEntityManager(com.voxel.entity.EntityManager em) { this.entityManager = em; }

    @Override
    public void update(float dt) {
        if (dead) return;
        super.update(dt);
        snapshotPrev();

        phaseTime += dt;
        breathCooldown -= dt;

        if (perched) {
            // Sit on the pillar and let punches knock it back into the air.
            setPositionD(pillarCenter.x, pillarCenter.y + 8.0f, pillarCenter.z);
            rotation.x = -25.0f;
            rotation.y += 12.0f * dt;
            return;
        }

        orbitAngle += ORBIT_SPEED_RAD_PER_S * dt;
        float cx = pillarCenter.x + (float) Math.cos(orbitAngle) * ORBIT_RADIUS;
        float cz = pillarCenter.z + (float) Math.sin(orbitAngle) * ORBIT_RADIUS;
        float cy = ORBIT_HEIGHT + (float) Math.sin(phaseTime * 0.5f) * 6.0f;

        // Lerp toward orbit position so transitions feel smooth rather than
        // teleporter-stiff when the dragon rises from a perch.
        float lerpK = Math.min(1.0f, dt * 2.0f);
        float px = getPosX() + (cx - getPosX()) * lerpK;
        float py = getPosY() + (cy - getPosY()) * lerpK;
        float pz = getPosZ() + (cz - getPosZ()) * lerpK;
        setPositionD(px, py, pz);

        // Head leads along the tangent; the body is level with the horizon.
        float tangent = (float) (orbitAngle + Math.PI / 2.0);
        rotation.y = (float) Math.toDegrees(tangent);
        rotation.x = 8.0f + (float) Math.sin(phaseTime * 1.3f) * 4.0f;

        // Perch on the pillar every ~45 seconds. Standing on the bedrock
        // pillar is the dragon's "I'm vulnerable" window.
        if (phaseTime > 45.0f && phaseTime < 45.5f) {
            perched = true;
            cumulativeHits = 0;
        }

        // Shoot breath at the tangent every breath-cooldown seconds.
        if (breathCooldown <= 0f && entityManager != null) {
            breathCooldown = 4.0f;
            float yawRad = (float) Math.toRadians(rotation.y);
            Vector3f mouth = new Vector3f(getPosX(), getPosY() - 6.0f, getPosZ());
            Vector3f heading = new Vector3f((float) Math.cos(yawRad), -0.15f,
                    (float) Math.sin(yawRad));
            FireballEntity fire = new FireballEntity(70000 + entityManager.getEntityCount(),
                    mouth, heading, null);
            fire.world = this.world;
            entityManager.addEntity(fire);
        }
    }

    public World world;

    public World getWorld() { return world; }
    public void setWorld(World w) { this.world = w; }

    /**
     * Drops a dragon_egg block at the player's feet on death. The dragon egg
     * is a single-tick teleport-and-place; physics + gravity will pick it up
     * from the world state.
     */
    public void dropLoot(World world, BlockDataManager blockDataManager) {
        if (world == null || blockDataManager == null) return;
        int eggId = blockDataManager.findBlockId("dragon_egg");
        if (eggId <= 0) return;
        int x = (int) Math.floor(pillarCenter.x);
        int y = (int) Math.floor(pillarCenter.y);
        int z = (int) Math.floor(pillarCenter.z);
        // The bedrock pillar is solid; place atop it.
        world.setVoxel(x, y + 12, z, eggId);
    }
}
