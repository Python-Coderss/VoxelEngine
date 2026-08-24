package com.voxel.entity;

import org.joml.Vector3f;

/** Sheepuff - a puffy sheep whose wool lets it float gently downward. */
public class SheepuffEntity extends AetherPassiveEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/sheepuff.json";

    public SheepuffEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm) {
        super(id, position, tm, MODEL);
        moveMode = MoveMode.WALK;
        moveSpeed = 0.8f;
        pickWidth = 0.9f;
        pickHeight = 1.3f;
        bindLegs("leg_1", "leg_2", "leg_3", "leg_4");
    }
}
