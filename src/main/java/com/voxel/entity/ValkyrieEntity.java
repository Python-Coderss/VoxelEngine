package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Valkyrie - winged warrior guarding the Silver Dungeon. Melee duelist. */
public class ValkyrieEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/valkyrie.json";

    public boolean duelStarted = false; // "earned fight" gate set by the dungeon manager
    protected float flapPhase = 0.0f;

    public ValkyrieEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm, Player p) {
        super(id, position, tm, p);
        loadModel(MODEL, tm);
        health = maxHealth = 25.0f;
        pickWidth = 0.7f;
        pickHeight = 1.95f;
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        flapPhase += dt * 4.0f;
        ModelPart lw = findPart("left_wing");
        ModelPart rw = findPart("right_wing");
        if (lw != null && rw != null) {
            float flap = (float) Math.sin(flapPhase) * 20.0f;
            lw.rotation.z = flap;
            rw.rotation.z = -flap;
        }
    }

    /** Stronger than the generic strike. */
    @Override
    public void performAttack(Vector3f playerPos) {
        if (player != null) player.takeDamage(3.5f);
    }

    @Override
    public int xpDropValue() { return 10; }
}
