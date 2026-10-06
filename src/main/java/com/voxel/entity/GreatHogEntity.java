package com.voxel.entity;

import com.voxel.Player;
import com.voxel.ai.brain.Brains;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;

/**
 * The Great Hog — Minecraft Legends' horde warlord. A hoglin brute in bronze
 * wargear with a two-handed hammer.
 *
 * <p>Bespoke model/skin under {@code great_hog/}; the behaviour lives in
 * {@link com.voxel.ai.brain.GreatHogBrain} (charge, ground pound, and calling
 * the horde).</p>
 */
public class GreatHogEntity extends EnemyEntity {

    public static final float BOSS_HEALTH = 320.0f;
    /** Melee damage applied by the base performAttack is 2 HP; a boss swing
     *  telegraphs through the brain's wind-up and hits harder via this value. */
    public static final float HAMMER_DAMAGE = 4.0f;

    private final EntityManager entityManager;
    private ModelPart head, rightArm, leftLeg, rightLeg;
    private ModelPart hammer;
    private boolean dropped;

    public GreatHogEntity(int id, Vector3f position, TextureManager textureManager,
                          Player player, EntityManager entityManager) {
        super(id, position, textureManager, player);
        this.entityManager = entityManager;
        loadModel("src/main/resources/assets/minecraft/models/entity/great_hog.json",
                textureManager);
        this.maxHealth = BOSS_HEALTH;
        this.health = BOSS_HEALTH;
        this.pickWidth = 3.0f;
        this.pickHeight = 3.2f;
        this.dimension = com.voxel.world.DimensionType.OVERWORLD;

        for (ModelPart p : parts) {
            if (p.name.equals("head")) head = p;
            else if (p.name.equals("hammer_head")) hammer = p;
            else if (p.name.equals("leg_fl") || p.name.equals("leg_bl")) leftLeg = p;
            else if (p.name.equals("leg_fr") || p.name.equals("leg_br")) rightLeg = p;
        }
        this.brain = Brains.newGreatHogBrain(this);
    }

    /** Call 2–4 piglins of the horde to the warlord's side. */
    public int summonHorde(int count) {
        if (entityManager == null) return 0;
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            float ang = (float) (Math.PI * 2.0 * i / Math.max(1, count) + id * 1.7);
            float x = getPosX() + (float) Math.cos(ang) * 5f;
            float z = getPosZ() + (float) Math.sin(ang) * 5f;
            GenericMobEntity piglin = new GenericMobEntity(
                    93_000 + entityManager.getEntityCount() + i,
                    new Vector3f(x, getPosY(), z), modelTextureManager, player,
                    "src/main/resources/assets/minecraft/models/entity/pigman.json");
            piglin.setWorld(world);
            piglin.dimension = dimension;
            entityManager.addEntity(piglin);
            spawned++;
        }
        return spawned;
    }

    /** The hammer comes down: heavy damage plus knockback. */
    public void pound(Vector3f playerPos) {
        if (player == null) return;
        Vector3f dir = new Vector3f(playerPos).sub(getPosition());
        float len = dir.length();
        Vector3f knock = len > 0.001f
                ? dir.normalize().mul(1.4f).add(0f, 0.5f, 0f)
                : new Vector3f(0f, 0.5f, 0f);
        player.takeDamage(HAMMER_DAMAGE);
        // EnemyEntity.takeDamage is the mob-side API; the player has no
        // knockback hook here, so the shove is applied to the boss itself as a
        // recoil to sell the impact.
        addPosition(-knock.x, 0f, -knock.z);
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;
        float swing = (float) Math.sin(animTime * 5.0f) * 20f;
        if (leftLeg != null) leftLeg.rotation.x = swing;
        if (rightLeg != null) rightLeg.rotation.x = -swing;
        if (rightArm != null) rightArm.rotation.x = -swing * 0.6f;
        if (head != null) head.rotation.x = (float) Math.sin(animTime * 2.0f) * 5f;
        if (hammer != null) hammer.rotation.x = (float) Math.sin(animTime * 5.0f) * 12f;
    }

    public boolean dropLoot(com.voxel.game.DroppedItemManager droppedItemManager) {
        if (droppedItemManager == null) return false;
        int x = (int) Math.floor(getPosX());
        int y = (int) Math.floor(getPosY()) + 1;
        int z = (int) Math.floor(getPosZ());
        droppedItemManager.spawn("gold_block", 4, x, y, z);
        droppedItemManager.spawn("iron_ingot", 8, x, y, z);
        droppedItemManager.spawn("power_fragment", 3, x, y, z);
        return true;
    }

    public boolean markedDropped() { return dropped; }
    public void markDropped() { dropped = true; }
    public int xpDropValue() { return 180; }
}
