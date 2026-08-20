package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Small hostile Silverfish using the segmented Minecraft model. */
public class SilverfishEntity extends EnemyEntity {
    public SilverfishEntity(int id, Vector3f position,
                            com.voxel.utils.TextureManager textureManager, Player player) {
        super(id, position, textureManager, player);
        loadModel("src/main/resources/assets/minecraft/models/entity/silverfish.json", textureManager);
    }
}
