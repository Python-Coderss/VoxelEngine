package com.voxel.entity;

import com.voxel.Player;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;

/** Peaceful wandering sheep with Minecraft sheared and woolly states. */
public class SheepEntity extends FarmAnimalEntity {
    private static final String SHEARED_MODEL =
            "src/main/resources/assets/minecraft/models/entity/sheep.json";
    private static final String UNSHEARED_MODEL =
            "src/main/resources/assets/minecraft/models/entity/sheep_unsheared.json";

    private final TextureManager textureManager;
    private boolean sheared;

    public SheepEntity(int id, Vector3f position,
                       TextureManager textureManager, Player player) {
        this(id, position, textureManager, player, false);
    }

    public SheepEntity(int id, Vector3f position,
                       TextureManager textureManager, Player player,
                       boolean sheared) {
        super(id, position, textureManager, SHEARED_MODEL,
                "leg_1", "leg_2", "leg_3", "leg_4",
                "wool_leg_1", "wool_leg_2", "wool_leg_3", "wool_leg_4");
        this.textureManager = textureManager;
        // The base model is the sheared MC model. Switch to the wool layer only
        // when the requested state is unsheared.
        this.sheared = true;
        setSheared(sheared);
    }

    public boolean isSheared() {
        return sheared;
    }

    /** Change the sheep's appearance between the MC sheared and woolly states. */
    public void setSheared(boolean sheared) {
        if (this.sheared == sheared) return;
        this.sheared = sheared;
        swapModel(sheared ? SHEARED_MODEL : UNSHEARED_MODEL, textureManager);
        refreshLegParts("leg_1", "leg_2", "leg_3", "leg_4",
                "wool_leg_1", "wool_leg_2", "wool_leg_3", "wool_leg_4");
    }

    public void toggleSheared() {
        setSheared(!sheared);
    }
}
