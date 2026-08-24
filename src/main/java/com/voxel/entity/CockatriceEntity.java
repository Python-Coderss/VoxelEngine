package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Cockatrice - aggressive bird-like creature; charges and pecks. */
public class CockatriceEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/cockatrice.json";

    public CockatriceEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 20.0f;
        pickWidth = 0.9f;
        pickHeight = 1.6f;
    }

    /** Faster than the generic enemy: aggressive chase with a strong peck. */
    @Override
    public void performAttack(Vector3f playerPos) {
        if (player != null) player.takeDamage(3.0f);
    }

    @Override
    public int xpDropValue() { return 7; }
}
