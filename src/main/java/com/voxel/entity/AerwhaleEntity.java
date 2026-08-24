package com.voxel.entity;

import org.joml.Vector3f;

/** Aerwhale - giant passive sky whale drifting through the clouds. */
public class AerwhaleEntity extends AetherPassiveEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/aerwhale.json";

    public AerwhaleEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm) {
        super(id, position, tm, MODEL);
        moveMode = MoveMode.FLY;
        moveSpeed = 0.55f;
        flyMinY = 100f;
        flyMaxY = 140f;
        pickWidth = 4.0f;
        pickHeight = 3.0f;
        bindLegs();
    }
}
