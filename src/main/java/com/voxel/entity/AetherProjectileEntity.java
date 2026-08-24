package com.voxel.entity;

import com.voxel.Player;
import com.voxel.World;
import org.joml.Vector3f;

/**
 * Shared straight-line projectile for Aether mobs:
 * Zephyr snowballs, Aechor darts, Sun Spirit fireballs and Valkyrie Queen throws.
 */
public class AetherProjectileEntity extends Entity {
    public enum Type { ZEPHYR_SNOWBALL, POISON_DART, SUN_FIRE }

    private final Type type;
    private final Vector3f velocity;
    private final Player target;
    private float life = 4.0f;
    private boolean expired = false;

    public AetherProjectileEntity(int id, Vector3f position, Vector3f velocity,
                                  Type type, Player target) {
        super(id, position);
        this.velocity = new Vector3f(velocity);
        this.type = type;
        this.target = target;
        // Simple visual: single emissive box part
        int tex = -1;
        switch (type) {
            case ZEPHYR_SNOWBALL: tex = 0; break;
            case POISON_DART: tex = 0; break;
            case SUN_FIRE: tex = 0; break;
        }
        addPart(new ModelPart("orb",
                new org.joml.Vector3f(-0.25f, -0.25f, -0.25f),
                new org.joml.Vector3f(type == Type.SUN_FIRE ? 8.0f : 3.0f,
                                      type == Type.SUN_FIRE ? 8.0f : 3.0f,
                                      type == Type.SUN_FIRE ? 8.0f : 3.0f),
                tex));
        parts.get(parts.size() - 1).emissive = type != Type.ZEPHYR_SNOWBALL;
        switch (type) {
            case ZEPHYR_SNOWBALL: tintColor.set(0.8f, 0.9f, 1.0f); break;
            case POISON_DART: tintColor.set(0.4f, 1.0f, 0.4f); break;
            case SUN_FIRE: tintColor.set(1.0f, 0.55f, 0.1f); break;
        }
        tintAmount = 0.85f;
    }

    @Override
    public void update(float dt) {
        snapshotPrev();
        addPosition(velocity.x * dt, velocity.y * dt, velocity.z * dt);
        life -= dt;
        if (life <= 0) { expired = true; return; }

        if (target != null) {
            float hitRadius = (type == Type.SUN_FIRE) ? 2.0f : 1.4f;
            if (getPosition().distance(target.getPosition()) < hitRadius) {
                switch (type) {
                    case ZEPHYR_SNOWBALL:
                        // Knockback only, like the mod
                        target.setPosition(target.getPosition().x + velocity.x * 0.6f, target.getPosition().y + Math.max(0.35f, velocity.y * 0.4f), target.getPosition().z + velocity.z * 0.6f);
                        target.takeDamage(1.0f);
                        break;
                    case POISON_DART:
                        target.takeDamage(2.0f);
                        break;
                    case SUN_FIRE:
                        target.takeDamage(4.0f);
                        break;
                }
                expired = true;
            }
        }

    }

    /** Called by Main with the active world so we can stop at terrain. */
    public void tickWorld(World world, float dt) {
        if (expired) return;
        int bx = (int) Math.floor(getPosX());
        int by = (int) Math.floor(getPosY());
        int bz = (int) Math.floor(getPosZ());
        if (world != null && world.getVoxel(bx, by, bz) != 0) expired = true;
    }

    public boolean isExpired() { return expired; }
}
