package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Peaceful wandering cow. */
public class CowEntity extends FarmAnimalEntity {
    public CowEntity(int id, Vector3f position,
                     com.voxel.utils.TextureManager textureManager, Player player) {
        super(id, position, textureManager,
                "src/main/resources/assets/minecraft/models/entity/cow.json",
                "leg_1", "leg_2", "leg_3", "leg_4");
    }
}
