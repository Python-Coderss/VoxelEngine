package com.voxel.entity;

import org.joml.Vector3f;

/** Peaceful Snow Golem utility mob with Minecraft pumpkin states. */
public class SnowGolemEntity extends UtilityMobEntity {
    private static final String BARE_MODEL =
            "src/main/resources/assets/minecraft/models/entity/snowman.json";
    private static final String PUMPKIN_MODEL =
            "src/main/resources/assets/minecraft/models/entity/snowman_pumpkin.json";

    private final com.voxel.utils.TextureManager textureManager;
    private boolean pumpkin;

    public SnowGolemEntity(int id, Vector3f position,
                           com.voxel.utils.TextureManager textureManager) {
        this(id, position, textureManager, true);
    }

    public SnowGolemEntity(int id, Vector3f position,
                           com.voxel.utils.TextureManager textureManager,
                           boolean pumpkin) {
        super(id, position, textureManager, BARE_MODEL);
        this.textureManager = textureManager;
        this.pumpkin = false;
        setPumpkin(pumpkin);
    }

    public boolean hasPumpkin() {
        return pumpkin;
    }

    /** Toggle the Minecraft pumpkin head layer. */
    public void setPumpkin(boolean pumpkin) {
        if (this.pumpkin == pumpkin) return;
        this.pumpkin = pumpkin;
        swapModel(pumpkin ? PUMPKIN_MODEL : BARE_MODEL, textureManager);
    }

    public void togglePumpkin() {
        setPumpkin(!pumpkin);
    }
}
