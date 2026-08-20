package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Small hostile Endermite using the segmented Minecraft model. */
public class EndermiteEntity extends EnemyEntity {
    public EndermiteEntity(int id, Vector3f position,
                           com.voxel.utils.TextureManager textureManager, Player player) {
        super(id, position, textureManager, player);
        loadModel("src/main/resources/assets/minecraft/models/entity/endermite.json", textureManager);
    }
}
