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
    private boolean flying = false;
    private boolean isSwimming = false;
    private boolean parachuteDeployed = false;
    private String parachuteItemId = null;
    private int parachuteSlotIndex = -1;

    private float health = 20.0f;
    private float maxHealth = 20.0f;
    private boolean isDead = false;
    private float fallDistance = 0.0f;
    private Vector3f spawnPoint = new Vector3f();

    private float yaw = -90, pitch = 0;
    private com.voxel.world.DimensionType dimension = com.voxel.world.DimensionType.OVERWORLD;

    public Player(float x, float y, float z) {
        position.set(x, y, z);
        spawnPoint.set(x, y, z);
    }

    public void update(float dt, World world, BlockDataManager blockDataManager) {
        if (isDead) return;

        // Check for special block properties using data-driven flags
        isSwimming = checkInLiquid(world, blockDataManager);
        
        int feetBlock = checkBlockAtFeet(world);
        int waistBlock = checkBlockAtWaist(world);
        
        // Use block names for specialized Aether mechanics
        String feetName = blockDataManager.getName(feetBlock);
        String waistName = blockDataManager.getName(waistBlock);
        
        boolean inColdAercloud = feetName.contains("cold_aercloud") || waistName.contains("cold_aercloud");
        boolean inBlueAercloud = feetName.contains("blue_aercloud") || waistName.contains("blue_aercloud");
        boolean onQuicksoil = feetName.contains("quicksoil") || checkBlockBelow(world, blockDataManager).contains("quicksoil");

        if (!flying) {
            if (inBlueAercloud && !parachuteDeployed && velocity.y <= 0) {
                // Blue aercloud: launch player upward when landing/falling onto it
                velocity.y = 12.0f;
                fallDistance = 0;
            } else if (parachuteDeployed) {
                // Slow fall with parachute
                velocity.y -= 2.5f * dt;
                if (velocity.y < -3.0f) velocity.y = -3.0f; // Terminal velocity with parachute
                fallDistance = 0; // No fall damage with parachute
            } else if (inColdAercloud) {
                // Cold aercloud: slow descent like a cushion
                velocity.y -= 2.0f * dt;
                if (velocity.y < -1.0f) velocity.y = -1.0f; // Very gentle terminal velocity
                fallDistance = 0; // No fall damage on cold aercloud
            } else if (isSwimming) {
                velocity.y -= 2.0f * dt; // Slow sinking
                if (velocity.y < -1.5f) velocity.y = -1.5f; // Terminal velocity in water
                fallDistance = 0; // Negate fall damage in water
            } else {
                velocity.y -= 22.0f * dt;
                if (velocity.y < 0) {
                    fallDistance += -velocity.y * dt;
                }
            }
        } else {
            fallDistance = 0;
        }

        // Apply friction
        float frictionFactor;
        if (isSwimming) {
            frictionFactor = (float) Math.pow(0.15f, dt); // Thick drag in water
        } else if (onGround && onQuicksoil) {
            frictionFactor = (float) Math.pow(0.4f, dt); // Slippery quicksoil
        } else {
            frictionFactor = (float) Math.pow(onGround ? 0.05f : 0.1f, dt);
        }

        velocity.x *= frictionFactor;
        velocity.z *= frictionFactor;
        
        if (flying || isSwimming) {
            velocity.y *= frictionFactor;
        }

        moveAndCollide(dt, world, blockDataManager);
        
        if (velocity.y > 0) onGround = false;
    }

    private boolean checkInLiquid(World world, BlockDataManager blockDataManager) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y + 0.5f); // Check at waist height
        int z = (int) Math.floor(position.z);
        int voxel = world.getVoxel(x, y, z);
        return voxel > 0 && blockDataManager.isLiquid(voxel);
    }

    private void moveAndCollide(float dt, World world, BlockDataManager blockDataManager) {
        // ... (X and Z movements)
        position.x += velocity.x * dt;
        if (checkCollision(world, blockDataManager)) {
            position.x -= velocity.x * dt;
            velocity.x = 0;
        }

        position.z += velocity.z * dt;
        if (checkCollision(world, blockDataManager)) {
            position.z -= velocity.z * dt;
            velocity.z = 0;
        }

        // Y Movement
        float prevYVel = velocity.y;
        onGround = false;
        position.y += velocity.y * dt;
        if (checkCollision(world, blockDataManager)) {
            if (prevYVel < 0) {
                onGround = true;
                if (parachuteDeployed) {
                    fallDistance = 0; // Parachute negates fall damage
                } else {
                    handleFallDamage();
                }
            }
            position.y -= velocity.y * dt;
            velocity.y = 0;
        }
    }

    private void handleFallDamage() {
        if (fallDistance > 3.0f) { // Safe fall height of 3 blocks (Minecraft standard)
            float damage = (float) Math.ceil(fallDistance - 3.0f);
            takeDamage(damage);
        }
        fallDistance = 0;
    }

    public void takeDamage(float amount) {
        if (isDead || flying) return;
        health = Math.max(0, health - amount);
        if (health <= 0) {
            die();
        }
    }

    public void takeDamage(float amount, boolean invincible) {
        if (invincible) return; // I-frames: ignore damage entirely
        takeDamage(amount);
    }

    private void die() {
        isDead = true;
        velocity.set(0);
    }

    public void respawn() {
        position.set(spawnPoint);
        velocity.set(0);
        health = maxHealth;
        isDead = false;
        fallDistance = 0;
        parachuteDeployed = false;
        parachuteItemId = null;
        parachuteSlotIndex = -1;
    }

    public void setSpawnPoint(Vector3f point) {
        spawnPoint.set(point);
    }

    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public boolean isDead() { return isDead; }

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
        if (flying || isSwimming) {
            // Apply pitch-based vertical movement if looking up/down while moving forward/backward
            float pitchRad = (float) Math.toRadians(pitch);
            float verticalFactor = -(float) Math.sin(pitchRad);
            
            // Assuming dz corresponds to forward/backward movement in the local frame
            // We need to know if the move was intentional forward movement.
            // Since dx/dz are passed as multipliers, we can check their magnitude.
            float horizontalSpeed = (float) Math.sqrt(dx * dx + dz * dz);
            if (horizontalSpeed > 0.1f) {
                velocity.y += verticalFactor * horizontalSpeed * speed;
            }
        }
        velocity.x += dx * speed;
        velocity.y += dy * speed;
        velocity.z += dz * speed;
    }

    public void jump(World world, BlockDataManager blockDataManager) {
        if (onGround) {
            velocity.y = 8.5f;
            onGround = false;
        } else if (isSwimming) {
            // Check if at a pool edge — if so, launch the player out
            if (isAtWaterEdge(world, blockDataManager)) {
                velocity.y = 14.0f; // Huge vertical boost to escape the pool
            } else {
                velocity.y += 0.5f; // Swim up (legacy style)
                if (velocity.y > 4.0f) velocity.y = 4.0f;
            }
        }
    }

    /**
     * Detects if the player is swimming at the edge of a water pool
     * with a climbable solid block adjacent, within 1 block above the water surface.
     */
    private boolean isAtWaterEdge(World world, BlockDataManager blockDataManager) {
        if (!isSwimming) return false;

        int feetY = (int) Math.floor(position.y);
        int px = (int) Math.floor(position.x);
        int pz = (int) Math.floor(position.z);

        // Check 4 horizontal neighbors at feet level and 1 block above
        int[][] offsets = {{-1,0}, {1,0}, {0,-1}, {0,1}};
        for (int checkY = feetY; checkY <= feetY + 1; checkY++) {
            for (int[] off : offsets) {
                int nx = px + off[0];
                int nz = pz + off[1];
                int voxel = world.getVoxel(nx, checkY, nz);
                if (voxel > 0 && blockDataManager.isFullBlock(voxel)) {
                    // Found a solid block adjacent — the player can climb out here
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns the block ID at the player's feet (standing position). */
    private int checkBlockAtFeet(World world) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y);
        int z = (int) Math.floor(position.z);
        return world.getVoxel(x, y, z);
    }

    /** Returns the block ID at the player's waist (mid-body). */
    private int checkBlockAtWaist(World world) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y + 0.9f);
        int z = (int) Math.floor(position.z);
        return world.getVoxel(x, y, z);
    }

    /** Checks if the player is standing on or inside a quicksoil block. */
    private boolean checkOnQuicksoil(World world) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y - 0.1f); // Just below feet
        int z = (int) Math.floor(position.z);
        int blockBelow = world.getVoxel(x, y, z);
        if (blockBelow == 109) return true; // Quicksoil below
        // Also check the block the player is standing in
        int feetBlock = world.getVoxel(x, (int) Math.floor(position.y), z);
        return feetBlock == 109;
    }

    /** Returns the block name below the player's feet. */
    private String checkBlockBelow(World world, BlockDataManager blockDataManager) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y - 0.1f);
        int z = (int) Math.floor(position.z);
        int voxel = world.getVoxel(x, y, z);
        return blockDataManager.getName(voxel);
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
    public boolean isParachuteDeployed() { return parachuteDeployed; }
    public void deployParachute(String itemId, int slotIndex) {
        this.parachuteDeployed = true;
        this.parachuteItemId = itemId;
        this.parachuteSlotIndex = slotIndex;
        this.velocity.y = Math.max(this.velocity.y, -3.0f); // Cap fall speed immediately
    }
    public String getParachuteItemId() { return parachuteItemId; }
    public int getParachuteSlotIndex() { return parachuteSlotIndex; }
    public void resetParachute() {
        this.parachuteDeployed = false;
        this.parachuteItemId = null;
        this.parachuteSlotIndex = -1;
    }

    public com.voxel.world.DimensionType getDimension() { return dimension; }
    public void setDimension(com.voxel.world.DimensionType dimension) { this.dimension = dimension; }
}
