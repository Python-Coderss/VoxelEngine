package com.voxel.entity;

import org.joml.Vector3f;

/**
 * Flying Cow - a cow with wings that can carry the player across the sky
 * (gliding mount in the mod; here a slow-flying passive that flaps while walking).
 */
public class FlyingCowEntity extends AetherPassiveEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/flying_cow.json";

    public FlyingCowEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm) {
        super(id, position, tm, MODEL);
        moveMode = MoveMode.WALK;
        moveSpeed = 0.85f;
        pickWidth = 0.9f;
        pickHeight = 1.4f;
        bindLegs("leg_1", "leg_2", "leg_3", "leg_4");
    }

    @Override
    protected void animate(boolean moved) {
        super.animate(moved);
        ModelPart lw = findPart("left_wing");
        ModelPart rw = findPart("right_wing");
        if (lw != null && rw != null) {
            float flap = (float) Math.sin(animTime * 6.5f) * 28.0f;
            lw.rotation.z = flap;
            rw.rotation.z = -flap;
        }
    }
}
