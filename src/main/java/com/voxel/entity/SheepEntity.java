package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Peaceful wandering sheep. */
public class SheepEntity extends FarmAnimalEntity {
    public SheepEntity(int id, Vector3f position,
                       com.voxel.utils.TextureManager textureManager, Player player) {
        super(id, position, textureManager,
                "src/main/resources/assets/minecraft/models/entity/sheep.json",
                "leg_1", "leg_2", "leg_3", "leg_4");
    }
}
