package com.voxel.entity;

import com.voxel.Player;
import com.voxel.ai.brain.Brains;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;

/**
 * The Arch-Illager — Minecraft Dungeons' antagonist: a crowned, robed caster
 * who hovers behind a crackling staff and blinks out of danger.
 *
 * <p>Bespoke model/skin under {@code arch_illager/}; the behaviour lives in
 * {@link com.voxel.ai.brain.ArchIllagerBrain} (keep the distance, call vexes
 * and blink away).</p>
 */
public class ArchIllagerEntity extends EnemyEntity {

    public static final float BOSS_HEALTH = 280.0f;
    public static final float FANG_DAMAGE = 3.0f;

    private final EntityManager entityManager;
    private ModelPart hood, head, staffOrb, crown;
    private boolean dropped;

    public ArchIllagerEntity(int id, Vector3f position, TextureManager textureManager,
                             Player player, EntityManager entityManager) {
        super(id, position, textureManager, player);
        this.entityManager = entityManager;
        loadModel("src/main/resources/assets/minecraft/models/entity/arch_illager.json",
                textureManager);
        this.maxHealth = BOSS_HEALTH;
        this.health = BOSS_HEALTH;
        this.pickWidth = 2.4f;
        this.pickHeight = 2.8f;
        this.dimension = com.voxel.world.DimensionType.OVERWORLD;

        for (ModelPart p : parts) {
            if (p.name.equals("hood")) hood = p;
            else if (p.name.equals("head")) head = p;
            else if (p.name.equals("staff_orb")) staffOrb = p;
            else if (p.name.equals("crown")) crown = p;
        }
        this.brain = Brains.newArchIllagerBrain(this);
    }

    /** Evoker-style fang line erupts at the target: ranged magic damage. */
    public void callFangs(Vector3f target) {
        if (player == null) return;
        player.takeDamage(FANG_DAMAGE);
    }

    /** Summon 1–2 vexes (GenericMob vex skin) to swarm the player. */
    public int summonVexes(int count) {
        if (entityManager == null) return 0;
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            float ang = (float) (Math.PI * 2.0 * i / Math.max(1, count) + id * 2.1);
            float x = getPosX() + (float) Math.cos(ang) * 2.5f;
            float z = getPosZ() + (float) Math.sin(ang) * 2.5f;
            GenericMobEntity vex = new GenericMobEntity(
                    94_000 + entityManager.getEntityCount() + i,
                    new Vector3f(x, getPosY() + 1f, z), modelTextureManager, player,
                    "src/main/resources/assets/minecraft/models/entity/vex.json");
            vex.setWorld(world);
            vex.dimension = dimension;
            entityManager.addEntity(vex);
            spawned++;
        }
        return spawned;
    }

    /** Blink a short step away from the given threat (stays in loaded ground). */
    public void blinkAwayFrom(Vector3f threat, float distance) {
        Vector3f away = new Vector3f(getPosition()).sub(threat);
        away.y = 0f;
        if (away.lengthSquared() < 1e-4f) away.set(1f, 0f, 0f);
        away.normalize().mul(distance);
        addPosition(away.x, 0f, away.z);
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;
        // Robes sway; the staff orb pulses; the whole caster hovers.
        float t = animTime;
        if (hood != null) hood.rotation.x = (float) Math.sin(t * 1.4f) * 3f;
        if (crown != null) crown.rotation.y += dt * 18f;
        if (staffOrb != null) {
            float pulse = 1.0f + (float) Math.sin(t * 4f) * 0.12f;
            staffOrb.size.set(6f * pulse, 6f * pulse, 6f * pulse);
        }
        rotation.x = (float) Math.sin(t * 1.2f) * 3f;
    }

    public boolean dropLoot(com.voxel.game.DroppedItemManager droppedItemManager) {
        if (droppedItemManager == null) return false;
        int x = (int) Math.floor(getPosX());
        int y = (int) Math.floor(getPosY()) + 1;
        int z = (int) Math.floor(getPosZ());
        droppedItemManager.spawn("power_fragment", 4, x, y, z);
        droppedItemManager.spawn("gold_block", 2, x, y, z);
        droppedItemManager.spawn("diamond", 3, x, y, z);
        return true;
    }

    public boolean markedDropped() { return dropped; }
    public void markDropped() { dropped = true; }
    public int xpDropValue() { return 160; }
}
