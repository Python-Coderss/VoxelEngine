package com.voxel.entity;

import org.joml.Vector3f;

/** Aerbunny - tiny bouncing rabbit. Hops around; boosts the player's jump when ridden/touched. */
public class AerbunnyEntity extends AetherPassiveEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/aerbunny.json";

    /** Set by Main each frame while the physics player is close (engine keeps the player out of EntityManager). */
    public boolean playerNear = false;
    private float boostCooldown = 0.0f;

    public AerbunnyEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm) {
        super(id, position, tm, MODEL);
        moveMode = MoveMode.HOP;
        moveSpeed = 0.7f;
        pickWidth = 0.45f;
        pickHeight = 0.6f;
        bindLegs("right_front_leg", "left_front_leg", "right_back_leg", "left_back_leg");
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (boostCooldown > 0) boostCooldown -= dt;
        if (playerNear && boostCooldown <= 0 && world != null) {
            // Hop-boost: nudge the player upward like the mod's jump boost
            com.voxel.Player p = getMainPlayer();
            if (p != null) {
                p.setPosition(p.getPosition().x, p.getPosition().y + 0.9f, p.getPosition().z);
                boostCooldown = 1.5f;
            }
        }
    }

    private static com.voxel.Player mainPlayer;
    public static void setMainPlayer(com.voxel.Player p) { mainPlayer = p; }
    private com.voxel.Player getMainPlayer() { return mainPlayer; }
}
