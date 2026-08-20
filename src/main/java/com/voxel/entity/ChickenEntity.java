package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Peaceful wandering chicken. */
public class ChickenEntity extends FarmAnimalEntity {
    private final ModelPart rightWing;
    private final ModelPart leftWing;

    public ChickenEntity(int id, Vector3f position,
                         com.voxel.utils.TextureManager textureManager, Player player) {
        super(id, position, textureManager,
                "src/main/resources/assets/minecraft/models/entity/chicken.json",
                "right_leg", "left_leg");
        rightWing = findPart("right_wing");
        leftWing = findPart("left_wing");
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        float flap = (float) Math.sin(animTime * 8.0f) * 12.0f;
        if (rightWing != null) rightWing.rotation.z = flap;
        if (leftWing != null) leftWing.rotation.z = -flap;
    }
}
