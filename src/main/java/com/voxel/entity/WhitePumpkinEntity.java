package com.voxel.entity;

import org.joml.Vector3f;

import com.voxel.Player;

/**
 * The White Pumpkin — Minecraft: Story Mode's pumpkin-headed boss (Jack's
 * disguise). A heavy melee bruiser: slow, tough, and hits hard. Defeating it
 * drops the white pumpkin trophy, Formidi-Bombs and gold.
 *
 * <p>Death drops follow the Wither pattern: Main scans for dead bosses and
 * calls {@link #dropLoot} exactly once.</p>
 */
public class WhitePumpkinEntity extends EnemyEntity {

    /** Boss health pool (a geared player takes a real fight). */
    public static final float BOSS_HEALTH = 250.0f;

    private ModelPart leftArm, rightArm, leftLeg, rightLeg;
    private boolean dropped = false;

    public WhitePumpkinEntity(int id, Vector3f position,
                              com.voxel.utils.TextureManager textureManager, Player p2) {
        super(id, position, textureManager, p2);
        loadModel("src/main/resources/assets/minecraft/models/entity/white_pumpkin.json", textureManager);
        this.maxHealth = BOSS_HEALTH;
        this.health = BOSS_HEALTH;

        for (ModelPart p : parts) {
            if (p.name.equals("left_arm")) leftArm = p;
            else if (p.name.equals("right_arm")) rightArm = p;
            else if (p.name.equals("left_leg")) leftLeg = p;
            else if (p.name.equals("right_leg")) rightLeg = p;
        }
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        // Heavy, slow walk cycle.
        float time = (float) (animTime % 3) * (2.0f / 3.0f) * (float) Math.PI;
        float swing = (float) Math.sin(time) * 22f;
        if (leftArm != null) leftArm.rotation.x = swing;
        if (rightArm != null) rightArm.rotation.x = -swing;
        if (leftLeg != null) leftLeg.rotation.x = -swing;
        if (rightLeg != null) rightLeg.rotation.x = swing;
    }

    /** One-shot death drops (Main calls this once, like the Wither star). */
    public boolean dropLoot(com.voxel.game.DroppedItemManager droppedItemManager) {
        if (droppedItemManager == null) return false;
        int x = (int) Math.floor(getPosX());
        int y = (int) Math.floor(getPosY());
        int z = (int) Math.floor(getPosZ());
        droppedItemManager.spawn("white_pumpkin", 1, x, y, z);
        droppedItemManager.spawn("formidi_bomb", 2, x, y, z);
        droppedItemManager.spawn("gold_block", 3, x, y, z);
        droppedItemManager.spawn("diamond", 2, x, y, z);
        return true;
    }

    public boolean markedDropped() {
        return dropped;
    }

    public void markDropped() {
        dropped = true;
    }
}
