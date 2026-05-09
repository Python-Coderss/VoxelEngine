package com.voxel;

import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;

/**
 * Represents the player with AABB collision and physics.
 */
public class Player {
    private final Vector3f position = new Vector3f();
    private final Vector3f velocity = new Vector3f();
    private final Vector3f size = new Vector3f(0.6f, 1.8f, 0.6f);

    private boolean onGround = false;
    private boolean flying = true;

    private float yaw = -90, pitch = 0;

    public Player(float x, float y, float z) {
        position.set(x, y, z);
    }

    public void update(float dt, World world, BlockDataManager blockDataManager) {
        if (!flying) {
            // Adjust gravity to be per-second (0.001f might be too small for dt in seconds)
            velocity.y -= 25.0f * dt; 
        }

        // 1. Calculate the factor (how much velocity REMAINS)
        // Ground friction: 0.01f (stops very fast) | Air friction: 0.9f (drifts a bit)
        float frictionFactor = (float) Math.pow(onGround ? 0.01f : 0.9f, dt);

        // 2. Multiply directly
        velocity.x *= frictionFactor;
        velocity.z *= frictionFactor;
        
        if (flying) {
            velocity.y *= frictionFactor;
        }

        // Resolve collision axis-by-axis
        moveAndCollide(dt, world, blockDataManager);
        
        if (velocity.y > 0) onGround = false;
    }

    private void moveAndCollide(float dt, World world, BlockDataManager blockDataManager) {
        // X Movement
        position.x += velocity.x * dt;
        if (checkCollision(world, blockDataManager)) {
            position.x -= velocity.x * dt;
            velocity.x = 0;
        }

        // Z Movement
        position.z += velocity.z * dt;
        if (checkCollision(world, blockDataManager)) {
            position.z -= velocity.z * dt;
            velocity.z = 0;
        }

        // Y Movement
        onGround = false;
        position.y += velocity.y * dt;
        if (checkCollision(world, blockDataManager)) {
            if (velocity.y < 0) onGround = true;
            position.y -= velocity.y * dt;
            velocity.y = 0;
        }
    }

    private boolean checkCollision(World world, BlockDataManager blockDataManager) {
        int minX = (int) Math.floor(position.x - size.x / 2);
        int maxX = (int) Math.floor(position.x + size.x / 2);
        int minY = (int) Math.floor(position.y);
        int maxY = (int) Math.floor(position.y + size.y);
        int minZ = (int) Math.floor(position.z - size.z / 2);
        int maxZ = (int) Math.floor(position.z + size.z / 2);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int voxel = world.getVoxel(x, y, z);
                    if (voxel > 0 && blockDataManager.isFullBlock(voxel)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void move(float dx, float dy, float dz, float speed) {
        velocity.x += dx * speed;
        velocity.y += dy * speed;
        velocity.z += dz * speed;
    }

    public void jump() {
        if (onGround) {
            velocity.y = 8.5f;
            onGround = false;
        }
    }

    // Getters and Setters
    public Vector3f getPosition() { return position; }
    public Vector3f getVelocity() { return velocity; }
    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; }
    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { this.pitch = pitch; }
    public boolean isFlying() { return flying; }
    public void setFlying(boolean flying) { this.flying = flying; }
    public boolean isOnGround() { return onGround; }
}
