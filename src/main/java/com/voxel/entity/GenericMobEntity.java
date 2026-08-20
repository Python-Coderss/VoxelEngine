package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Generic Minecraft-model mob used for creatures without bespoke gameplay yet. */
public class GenericMobEntity extends EnemyEntity {
    private final ModelPart[] animatedParts;

    public GenericMobEntity(int id, Vector3f position,
                            com.voxel.utils.TextureManager textureManager,
                            Player player, String modelPath) {
        super(id, position, textureManager, player);
        loadModel(modelPath, textureManager);
        java.util.List<ModelPart> animated = new java.util.ArrayList<>();
        for (ModelPart part : parts) {
            String n = part.name.toLowerCase(java.util.Locale.ROOT);
            if (n.contains("leg") || n.contains("wing") || n.contains("tentacle")
                    || n.contains("tail") || n.contains("arm")) {
                animated.add(part);
            }
        }
        animatedParts = animated.toArray(new ModelPart[0]);
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        float swing = (float) Math.sin(animTime * 6.0f) * 18.0f;
        for (int i = 0; i < animatedParts.length; i++) {
            ModelPart part = animatedParts[i];
            String n = part.name.toLowerCase(java.util.Locale.ROOT);
            if (n.contains("wing")) {
                part.rotation.y = (float) Math.sin(animTime * 10.0f + i) * 22.0f;
            } else if (n.contains("tentacle")) {
                part.rotation.x = 18.0f + (float) Math.sin(animTime * 4.0f + i) * 10.0f;
            } else if (n.contains("tail")) {
                part.rotation.y = (float) Math.sin(animTime * 4.0f + i) * 12.0f;
            } else {
                part.rotation.x = (i & 1) == 0 ? swing : -swing;
            }
        }
    }
}
