package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Peaceful wandering pig. */
public class PigEntity extends FarmAnimalEntity {
    public PigEntity(int id, Vector3f position,
                     com.voxel.utils.TextureManager textureManager, Player player) {
        super(id, position, textureManager,
                "src/main/resources/assets/minecraft/models/entity/pig.json",
                "leg_1", "leg_2", "leg_3", "leg_4");
    }
}
