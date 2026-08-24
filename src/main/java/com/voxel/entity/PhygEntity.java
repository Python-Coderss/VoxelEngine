package com.voxel.entity;

import org.joml.Vector3f;

/** Phyg - a winged pig that glides between islands. */
public class PhygEntity extends AetherPassiveEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/phyg.json";

    public PhygEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm) {
        super(id, position, tm, MODEL);
        moveMode = MoveMode.WALK;
        moveSpeed = 0.85f;
        pickWidth = 0.9f;
        pickHeight = 1.2f;
        bindLegs("leg_1", "leg_2", "leg_3", "leg_4");
    }

    @Override
    protected void animate(boolean moved) {
        super.animate(moved);
        ModelPart lw = findPart("left_wing");
        ModelPart rw = findPart("right_wing");
        if (lw != null && rw != null) {
            float flap = (float) Math.sin(animTime * 7.0f) * 30.0f;
            lw.rotation.z = flap;
            rw.rotation.z = -flap;
        }
    }
}
