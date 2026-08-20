package com.voxel.entity;

import com.voxel.Player;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;

/**
 * Experience orb — small floating pickup that drifts toward the nearest
 * player when they're within {@link #PICKUP_RADIUS}. On contact it adds
 * its xpValue to the player's XP total (tracked via
 * {@link Player#addExperience(int)}) and self-destructs.
 *
 * <p>Visually the orb is a tiny cube with the same emissive texture as the
 * Minecraft XP orb; we don't ship a separate model so we reuse a 2×2
 * cube_all from the existing block atlas.</p>
 *
 * <p>Simplifications vs. Mojang:</p>
 * <ul>
 *   <li>No orbit / spin animation — straight-line drift only.</li>
 *   <li>No merge when multiple orbs collide.</li>
 *   <li>Doesn't honor absorption levels (no XP /3 special-case).</li>
 * </ul>
 */
public class ExperienceOrbEntity extends Entity {

    /** Horizontal pickup radius in blocks. Mojang uses 0.5-2.5 depending on XP value. */
    private static final float PICKUP_RADIUS = 2.5f;
    /** Drift speed (blocks / sec) toward the player when in pickup range. */
    private static final float DRIFT_SPEED = 4.0f;
    /** Gravity applied each tick to make the orb fall naturally. */
    private static final float GRAVITY = 6.0f;

    public final int xpValue;
    private boolean expired = false;
    private Player nearestPlayer;

    public ExperienceOrbEntity(int id, Vector3f position, int xpValue,
                                TextureManager textureManager) {
        super(id, position);
        this.xpValue = Math.max(1, xpValue);
        loadModel("src/main/resources/assets/minecraft/models/entity/experience_orb.json",
                textureManager);
    }

    public void setNearestPlayer(Player p) { this.nearestPlayer = p; }

    public boolean isExpired() { return expired; }

    @Override
    public void update(float dt) {
        if (expired) return;
        super.update(dt);
        snapshotPrev();

        // Drift toward the nearest player when in range. We don't have a
        // velocity field on Entity so we teleport-step in the player's
        // direction each tick.
        if (nearestPlayer != null) {
            float dx = nearestPlayer.getPosition().x - getPosX();
            float dy = nearestPlayer.getPosition().y - getPosY();
            float dz = nearestPlayer.getPosition().z - getPosZ();
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < PICKUP_RADIUS && dist > 1e-3f) {
                float step = Math.min(DRIFT_SPEED * dt, dist);
                setPositionD(
                        getPosX() + (dx / dist) * step,
                        getPosY() + (dy / dist) * step,
                        getPosZ() + (dz / dist) * step);
                // Pickup: give XP and expire.
                if (dist < 0.6f) {
                    nearestPlayer.addExperience(xpValue);
                    expired = true;
                    return;
                }
                return;
            }
        }
        // Default: gentle gravity so the orb settles on the ground.
        float ny = getPosY() - GRAVITY * dt;
        setPositionD(getPosX(), Math.max(0, ny), getPosZ());
    }
}