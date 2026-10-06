package com.voxel.entity;

import com.voxel.Player;
import com.voxel.ai.brain.Brains;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;

/**
 * The Heart of Ender — Minecraft Dungeons' final boss: a segmented, pulsing
 * ender orb crowned by a burning eye, trailing tendrils.
 *
 * <p>Bespoke model/skin under {@code heart_of_ender/}; the behaviour lives in
 * {@link com.voxel.ai.brain.HeartOfEnderBrain} (drift, pulse nova, and disgorge
 * endermites).</p>
 */
public class HeartOfEnderEntity extends EnemyEntity {

    public static final float BOSS_HEALTH = 360.0f;
    public static final float PULSE_DAMAGE = 3.0f;

    private final EntityManager entityManager;
    private ModelPart orbMain, orbUpper, orbLower, eye;
    private ModelPart[] tendrils = new ModelPart[0];
    private boolean dropped;

    public HeartOfEnderEntity(int id, Vector3f position, TextureManager textureManager,
                              Player player, EntityManager entityManager) {
        super(id, position, textureManager, player);
        this.entityManager = entityManager;
        loadModel("src/main/resources/assets/minecraft/models/entity/heart_of_ender.json",
                textureManager);
        this.maxHealth = BOSS_HEALTH;
        this.health = BOSS_HEALTH;
        this.pickWidth = 2.6f;
        this.pickHeight = 2.6f;
        this.dimension = com.voxel.world.DimensionType.OVERWORLD;

        java.util.List<ModelPart> tails = new java.util.ArrayList<ModelPart>();
        for (ModelPart p : parts) {
            if (p.name.equals("orb_main")) orbMain = p;
            else if (p.name.equals("orb_upper")) orbUpper = p;
            else if (p.name.equals("orb_lower")) orbLower = p;
            else if (p.name.equals("eye")) eye = p;
            else if (p.name.startsWith("tendril")) tails.add(p);
        }
        this.tendrils = tails.toArray(new ModelPart[0]);
        this.brain = Brains.newHeartOfEnderBrain(this);
    }

    /** A nova of ender energy scalds anything standing close. */
    public void pulseNova() {
        if (player == null) return;
        if (player.getPosition().distance(getPosition()) <= 5.0f) {
            player.takeDamage(PULSE_DAMAGE);
        }
    }

    /** Disgorge 1–3 endermites from the orb. */
    public int spawnMites(int count) {
        if (entityManager == null) return 0;
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            float ang = (float) (Math.PI * 2.0 * i / Math.max(1, count) + id * 0.9);
            float x = getPosX() + (float) Math.cos(ang) * 2.5f;
            float z = getPosZ() + (float) Math.sin(ang) * 2.5f;
            EndermiteEntity mite = new EndermiteEntity(
                    95_000 + entityManager.getEntityCount() + i,
                    new Vector3f(x, getPosY(), z), modelTextureManager, player);
            mite.setWorld(world);
            mite.dimension = dimension;
            entityManager.addEntity(mite);
            spawned++;
        }
        return spawned;
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;
        float t = animTime;
        // The orb breathes; the segments counter-rotate; tendrils lash.
        float pulse = 1.0f + (float) Math.sin(t * 2.6f) * 0.08f;
        if (orbMain != null) orbMain.size.set(16f * pulse, 16f * pulse, 16f * pulse);
        if (orbUpper != null) orbUpper.rotation.y += dt * 40f;
        if (orbLower != null) orbLower.rotation.y -= dt * 40f;
        if (eye != null) {
            eye.rotation.y += dt * 90f;
            eye.size.set(8f * (2f - pulse), 8f * (2f - pulse), 8f * (2f - pulse));
        }
        for (int i = 0; i < tendrils.length; i++) {
            tendrils[i].rotation.x = (float) Math.sin(t * 3.0f + i * 1.3f) * 20f;
            tendrils[i].rotation.z = (float) Math.cos(t * 2.2f + i) * 16f;
        }
        // Gentle hover.
        rotation.x = (float) Math.sin(t * 1.5f) * 5f;
    }

    public boolean dropLoot(com.voxel.game.DroppedItemManager droppedItemManager) {
        if (droppedItemManager == null) return false;
        int x = (int) Math.floor(getPosX());
        int y = (int) Math.floor(getPosY()) + 1;
        int z = (int) Math.floor(getPosZ());
        droppedItemManager.spawn("power_fragment", 6, x, y, z);
        droppedItemManager.spawn("diamond", 5, x, y, z);
        droppedItemManager.spawn("glowstone", 4, x, y, z);
        return true;
    }

    public boolean markedDropped() { return dropped; }
    public void markDropped() { dropped = true; }
    public int xpDropValue() { return 220; }
}
