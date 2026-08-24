package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;
import java.util.Random;

/**
 * Moa - rideable flightless bird. Hatched from eggs in the mod; here it
 * wanders islands, and can carry the player (see Main's mount logic):
 * while mounted it performs strong jumps and glides between islands.
 */
public class MoaEntity extends AetherPassiveEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/moa.json";
    private static final String[] SKINS = {
            "src/main/resources/assets/aether/models/entity/moa.json"
    };

    private static final Random SKIN_RNG = new Random();
    /** Jump charge available while mounted (regenerates on ground). */
    public int jumpCharges = 3;
    public boolean mounted = false;
    private float glideTimer = 0.0f;

    public MoaEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm) {
        super(id, position, tm, MODEL);
        moveMode = MoveMode.WALK;
        moveSpeed = 1.1f;
        pickWidth = 0.9f;
        pickHeight = 1.9f;
        bindLegs("right_leg", "left_leg");
    }

    @Override
    public void update(float dt) {
        if (mounted && world != null) {
            // Mounted behavior: player controls handled by Main; here we do the
            // moa's own motion: strong hops with mid-air glide.
            snapshotPrev();
            animTime += dt;
            boolean grounded = world.getVoxel(
                    (int) Math.floor(getPosX()),
                    (int) Math.floor(getPosY() - 0.1f),
                    (int) Math.floor(getPosZ())) != 0;
            if (grounded) jumpCharges = Math.min(3, jumpCharges + (glideTimer > 0.6f ? 1 : 0));
            glideTimer += dt;
            animate(true);
            return;
        }
        super.update(dt);
    }
}
