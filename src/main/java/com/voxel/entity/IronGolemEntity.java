package com.voxel.entity;

import org.joml.Vector3f;

/** Peaceful Iron Golem utility mob with Minecraft-style walking limbs. */
public class IronGolemEntity extends UtilityMobEntity {
    public IronGolemEntity(int id, Vector3f position,
                           com.voxel.utils.TextureManager textureManager) {
        super(id, position, textureManager,
                "src/main/resources/assets/minecraft/models/entity/iron_golem.json",
                "left_leg", "right_leg", "left_arm", "right_arm");
    }

    @Override
    public void update(float dt) {
        super.update(dt);

        float amount = movedThisTick ? 1.0f : 0.0f;
        float wave = triangleWave(walkTime, 13.0f);
        ModelPart leftLeg = findPart("left_leg");
        ModelPart rightLeg = findPart("right_leg");
        ModelPart leftArm = findPart("left_arm");
        ModelPart rightArm = findPart("right_arm");

        if (leftLeg != null) leftLeg.rotation.x = toDegrees(-1.5f * wave * amount);
        if (rightLeg != null) rightLeg.rotation.x = toDegrees(1.5f * wave * amount);
        if (rightArm != null) rightArm.rotation.x = toDegrees((-0.2f + 1.5f * wave) * amount);
        if (leftArm != null) leftArm.rotation.x = toDegrees((-0.2f - 1.5f * wave) * amount);
    }
}
