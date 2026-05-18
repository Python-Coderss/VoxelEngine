package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/**
 * Render-only player model that mirrors the physics Player.
 */
public class PlayerEntity extends Entity {
    private static final Vector3f HIDDEN_POSITION = new Vector3f(-10000.0f, -10000.0f, -10000.0f);
    private ModelPart head;

    public PlayerEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        loadModel("src/main/resources/assets/minecraft/models/entity/player.json", textureManager);

        for (ModelPart part : parts) {
            if (part.name.equals("head")) {
                head = part;
                break;
            }
        }
    }

    public void syncFromPlayer(Player player, float yaw, float pitch, boolean visible, float dt) {
        if (!visible) {
            position.set(HIDDEN_POSITION);
            return;
        }

        position.set(player.getPosition());
        rotation.set(0.0f, yaw, 0.0f);
        if (head != null) {
            head.rotation.set(pitch, 0.0f, 0.0f);
        }
    }
}
