package com.voxel;

import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;

/**
 * Represents the player with AABB collision and exact Minecraft 1.12.2 physics.
 * Uses a fixed 20-tick-per-second internal loop so all constants match Minecraft directly.
 */
public class Player {
    private final Vector3f position = new Vector3f();
    private final Vector3f prevPosition = new Vector3f();
    private final Vector3f velocity = new Vector3f();
    private final Vector3f size = new Vector3f(0.6f, 1.8f, 0.6f);

    // --- Interpolation (render thread uses this to smooth between physics ticks) ---
    private volatile long lastTickWallNanos = System.nanoTime();

    // --- Tick accumulator for fixed 20 Hz physics ---
    private float tickAccumulator = 0.0f;
    private static final float TICK_RATE = 0.05f; // 1/20 second
    public static final float TICK_RATE_SECONDS = 0.05f;

    private boolean onGround = false;
    private boolean flying = false;
    private boolean isSwimming = false;
    private boolean isSprinting = false;
    private boolean isSneaking = false;
    private boolean parachuteDeployed = false;
    private String parachuteItemId = null;
    private int parachuteSlotIndex = -1;

    // --- Per-frame input accumulation (consumed each tick) ---
    private float moveStrafing = 0.0f;
    private float moveForward = 0.0f;
    private float moveVertical = 0.0f;
    private boolean jumpRequested = false;

    // ════════════════════════════════════════════════════════════════
    //  Exact Minecraft 1.12.2 constants (from miners source)
    // ════════════════════════════════════════════════════════════════

    /** Per-tick gravity (EntityLivingBase.travel: motionY -= 0.08D) */
    private static final double GRAVITY = 0.08D;

    /** Jump upward motion (EntityLivingBase.getJumpUpwardsMotion: 0.42F) */
    private static final float JUMP_VELOCITY = 0.42F;

    /** Base friction multiplier per tick (EntityLivingBase.travel: 0.91F) */
    private static final float BASE_FRICTION = 0.91F;

    /** Default block slipperiness (Block.getSlipperiness default: 0.6F) */
    private static final float DEFAULT_SLIPPERINESS = 0.6F;

    /** Ice slipperiness (Blocks.ICE: 0.98F) */
    private static final float ICE_SLIPPERINESS = 0.98F;

    /** Movement speed attribute (EntityPlayer: 0.10000000149011612D) */
    private static final float MOVE_SPEED = 0.1F;

    /** Air movement factor (EntityLivingBase.jumpMovementFactor: 0.02F) */
    private static final float AIR_MOVE_FACTOR = 0.02F;

    /** Sprint speed boost modifier (EntityLivingBase: 0.30000001192092896D) */
    private static final float SPRINT_BOOST = 0.3F;

    /** Sprint jump horizontal impulse (EntityLivingBase.jump: sin/cos * 0.2F) */
    private static final float SPRINT_JUMP_IMPULSE = 0.2F;

    /** Creative flight speed (PlayerCapabilities.getFlySpeed: 0.05F) */
    private static final float FLY_SPEED = 0.05F;

    /** Sneaking eye height adjustment (EntityPlayer.getEyeHeight: -0.08F) */
    private static final float SNEAK_HEIGHT = 1.65F;

    /** Step height for auto-step (EntityLivingBase.stepHeight: 0.6F) */
    private static final float STEP_HEIGHT = 0.6F;

    /** Water slow-down multiplier (EntityLivingBase.getWaterSlowDown: 0.8F) */
    private static final float WATER_SLOWDOWN = 0.8F;

    /** Ladder max speed clamp (EntityLivingBase.travel ladder section: 0.15F) */
    private static final float LADDER_MAX_SPEED = 0.15F;

    /** Slipperiness-to-acceleration constant (EntityLivingBase.travel: 0.16277136F) */
    private static final float SLIP_CONSTANT = 0.16277136F;

    // --- Health / death / spawn ---
    private float health = 20.0f;
    private float maxHealth = 20.0f;
    private boolean isDead = false;
    private float fallDistance = 0.0f;
    private Vector3f spawnPoint = new Vector3f();

    private float yaw = -90, pitch = 0;
    private com.voxel.world.DimensionType dimension = com.voxel.world.DimensionType.OVERWORLD;

    public Player(float x, float y, float z) {
        position.set(x, y, z);
        prevPosition.set(x, y, z);
        spawnPoint.set(x, y, z);
    }

    /**
     * Main update: accumulate dt and run physics at exactly 20 ticks/second.
     */
    public void update(float dt, World world, BlockDataManager blockDataManager) {
        if (isDead) return;

        isSwimming = checkInLiquid(world, blockDataManager);

        // Aether / parachute / aercloud mechanics are still dt-based
        int feetBlock = checkBlockAtFeet(world);
        int waistBlock = checkBlockAtWaist(world);
        String feetName = blockDataManager.getName(feetBlock);
        String waistName = blockDataManager.getName(waistBlock);
        boolean inColdAercloud = feetName.contains("cold_aercloud") || waistName.contains("cold_aercloud");
        boolean inBlueAercloud = feetName.contains("blue_aercloud") || waistName.contains("blue_aercloud");
        boolean onQuicksoil = feetName.contains("quicksoil") || checkBlockBelow(world, blockDataManager).contains("quicksoil");

        // Accumulate time and run fixed-rate ticks
        tickAccumulator += dt;

        // Cap accumulator to prevent spiral of death (max ~2 seconds of ticks)
        if (tickAccumulator > 2.0f) tickAccumulator = 2.0f;

        while (tickAccumulator >= TICK_RATE) {
            prevPosition.set(position);  // snapshot for interpolation
            tick(TICK_RATE, world, blockDataManager,
                 inColdAercloud, inBlueAercloud, onQuicksoil);
            tickAccumulator -= TICK_RATE;
            lastTickWallNanos = System.nanoTime();
        }
    }

    /**
     * One physics tick at the fixed 20 Hz rate.
     * Uses exact Minecraft constants directly — no dt scaling needed.
     */
    private void tick(float tickDt, World world, BlockDataManager blockDataManager,
                       boolean inColdAercloud, boolean inBlueAercloud, boolean onQuicksoil) {
        // --- Gravity (special mechanics override) ---
        if (!flying) {
            if (inBlueAercloud && !parachuteDeployed && velocity.y <= 0) {
                velocity.y = 12.0f;
                fallDistance = 0;
            } else if (parachuteDeployed) {
                velocity.y -= 2.5f * tickDt;  // dt-scaled for parachute smoothness
                if (velocity.y < -3.0f) velocity.y = -3.0f;
                fallDistance = 0;
            } else if (inColdAercloud) {
                velocity.y -= 2.0f * tickDt;
                if (velocity.y < -1.0f) velocity.y = -1.0f;
                fallDistance = 0;
            } else if (isSwimming) {
                velocity.y -= 0.02D;  // Minecraft lava/water slow gravity
                fallDistance = 0;
            } else {
                velocity.y -= GRAVITY;  // Exact: 0.08 per tick
                if (velocity.y < 0) {
                    fallDistance += (float)(-velocity.y);
                }
            }
        } else {
            fallDistance = 0;
        }

        // --- Jump (processed once per tick) ---
        if (jumpRequested) {
            jumpRequested = false;
            if (onGround) {
                velocity.y = JUMP_VELOCITY;  // Exact: 0.42
                onGround = false;
                if (isSprinting) {
                    float yawRad = (float) Math.toRadians(yaw);
                    velocity.x += Math.cos(yawRad) * SPRINT_JUMP_IMPULSE;   // 0.2
                    velocity.z += Math.sin(yawRad) * SPRINT_JUMP_IMPULSE;
                }
            } else if (isSwimming) {
                // Water edge boost: launch out near blocks (original mechanic, scaled to 20 tps)
                if (isAtWaterEdge(world, blockDataManager)) {
                    velocity.y = 0.70F; // ~14.0 * 0.05 — huge vertical boost to escape the pool
                } else {
                    velocity.y += 0.025F; // Swim up (~0.5 * 0.05)
                    if (velocity.y > 0.20F) velocity.y = 0.20F; // Cap (~4.0 * 0.05)
                }
            }
        }

        // --- Compute friction and acceleration (Minecraft travel() logic) ---
        float friction;
        float acceleration;

        if (isSwimming) {
            friction = WATER_SLOWDOWN;  // 0.8
            acceleration = 0.02F;
        } else if (onGround && onQuicksoil) {
            friction = 0.4F * BASE_FRICTION;  // 0.4 * 0.91
            float f6 = friction;
            float f7 = SLIP_CONSTANT / (f6 * f6 * f6);
            acceleration = MOVE_SPEED * f7;
        } else if (onGround) {
            float slipperiness = getBlockSlipperiness(world, blockDataManager);
            friction = slipperiness * BASE_FRICTION;
            float f7 = SLIP_CONSTANT / (friction * friction * friction);
            acceleration = MOVE_SPEED * f7;
        } else {
            friction = BASE_FRICTION;  // 0.91 (air)
            acceleration = AIR_MOVE_FACTOR;  // 0.02
            if (isSprinting) {
                acceleration += AIR_MOVE_FACTOR * SPRINT_BOOST;  // 0.02 + 0.02*0.3 = 0.026
            }
        }

        // --- Apply horizontal movement impulse (Minecraft moveRelative equivalent) ---
        float strafe = moveStrafing;
        float forward = moveForward;

        if (strafe != 0.0f || forward != 0.0f) {
            float d = (float) Math.sqrt(strafe * strafe + forward * forward);
            if (d < 1.0f) d = 1.0f;

            // Diagonal movement normalization (Minecraft: multiply by 0.98 for diagonals)
            float factor = acceleration / d;

            // Sprinting boost
            if (isSprinting && onGround && !isSneaking) {
                factor *= (1.0f + SPRINT_BOOST);  // * 1.3
            }
            // Sneaking speed reduction
            if (isSneaking && onGround) {
                factor *= 0.3f;
            }

            strafe *= factor;
            forward *= factor;

            float yawRad = (float) Math.toRadians(yaw);
            float sin = (float) Math.sin(yawRad);
            float cos = (float) Math.cos(yawRad);

            // VoxelEngine yaw convention: camera forward = (cos, sin)
            // forward key → (cos, sin), left strafe → (-sin, cos)
            velocity.x += forward * cos + strafe * sin;
            velocity.z += forward * sin - strafe * cos;
        }

        // Flying vertical movement
        if (flying && moveVertical != 0.0f) {
            velocity.y += moveVertical * FLY_SPEED;
        }

        // --- Apply friction to horizontal velocity ---
        float frictionFactor;
        if (isSwimming) {
            frictionFactor = WATER_SLOWDOWN;
        } else if (onGround) {
            float slipperiness = onQuicksoil ? 0.4F : getBlockSlipperiness(world, blockDataManager);
            frictionFactor = slipperiness * BASE_FRICTION;
        } else {
            frictionFactor = BASE_FRICTION;
        }

        velocity.x *= frictionFactor;
        velocity.z *= frictionFactor;
        if (flying || isSwimming) {
            velocity.y *= frictionFactor;
        }

        // Clamp tiny velocities to zero (Minecraft behavior)
        if (Math.abs(velocity.x) < 0.003D) velocity.x = 0;
        if (Math.abs(velocity.y) < 0.003D) velocity.y = 0;
        if (Math.abs(velocity.z) < 0.003D) velocity.z = 0;

        // --- Move and collide (position += velocity, no dt scaling) ---
        moveAndCollide(world, blockDataManager);

        // Reset per-tick input accumulators (they're re-set by caller each frame)
        moveStrafing = 0.0f;
        moveForward = 0.0f;
        moveVertical = 0.0f;
    }

    /**
     * Minecraft-style move with per-tick velocity (no dt involved).
     * Each tick: position += velocity, detect collision, resolve.
     */
    private void moveAndCollide(World world, BlockDataManager blockDataManager) {
        // --- Step-assist (exact Minecraft stepHeight: 0.6) ---
        // Only step up if the player WOULD collide at current Y but the space
        // at Y+STEP_HEIGHT is clear. Prevents bouncing on flat ground.
        if (onGround && !isSneaking) {
            float stepX = velocity.x;
            float stepZ = velocity.z;
            float horDist = (float) Math.sqrt(stepX * stepX + stepZ * stepZ);
            if (horDist > 0.001f
                    && wouldCollideAtOffset(stepX, 0, stepZ, world, blockDataManager)
                    && !wouldCollideAtOffset(stepX, STEP_HEIGHT, stepZ, world, blockDataManager)) {
                float origX = position.x, origY = position.y, origZ = position.z;
                position.x += stepX;
                position.y += STEP_HEIGHT;
                position.z += stepZ;
                if (!checkCollision(world, blockDataManager)) {
                    velocity.x -= stepX;
                    velocity.z -= stepZ;
                } else {
                    position.x = origX;
                    position.y = origY;
                    position.z = origZ;
                }
            }
        }

        // X movement
        position.x += velocity.x;
        if (checkCollision(world, blockDataManager)) {
            position.x -= velocity.x;
            velocity.x = 0;
        }

        // Z movement
        position.z += velocity.z;
        if (checkCollision(world, blockDataManager)) {
            position.z -= velocity.z;
            velocity.z = 0;
        }

        // Y movement
        float prevYVel = velocity.y;
        onGround = false;
        position.y += velocity.y;
        if (checkCollision(world, blockDataManager)) {
            if (prevYVel < 0) {
                onGround = true;
                if (!parachuteDeployed) {
                    handleFallDamage();
                } else {
                    fallDistance = 0;
                }
            }
            position.y -= velocity.y;
            velocity.y = 0;
        }
    }

    /** Tests collision at an offset position (used by step-assist). */
    private boolean wouldCollideAtOffset(float dx, float dy, float dz, World world, BlockDataManager blockDataManager) {
        position.x += dx;
        position.y += dy;
        position.z += dz;
        boolean result = checkCollision(world, blockDataManager);
        position.x -= dx;
        position.y -= dy;
        position.z -= dz;
        return result;
    }

    private void handleFallDamage() {
        if (fallDistance > 3.0f) {
            float damage = (float) Math.ceil(fallDistance - 3.0f);
            takeDamage(damage);
        }
        fallDistance = 0;
    }

    // ════════════════════════════════════════════════════════════════
    //  Public movement API (called each frame by input handler)
    //  Sets per-tick strafe/forward/vertical values consumed by tick()
    // ════════════════════════════════════════════════════════════════

    /**
     * Applies movement impulse for this frame. Values are accumulated
     * and consumed at 20 Hz by the tick loop.
     *
     * @param dx    Forward/backward component (already normalized by caller)
     * @param dy    Vertical component (flying up/down)
     * @param dz    Strafe component (already normalized by caller)
     * @param speed Per-frame speed multiplier
     */
    public void move(float dx, float dy, float dz, float speed) {
        if (flying || isSwimming) {
            float pitchRad = (float) Math.toRadians(pitch);
            float verticalFactor = -(float) Math.sin(pitchRad);
            float horizontalMag = (float) Math.sqrt(dx * dx + dz * dz);
            if (horizontalMag > 0.1f) {
                moveVertical += verticalFactor * horizontalMag * speed;
            }
        }
        // Accumulate strafe/forward for consumption in tick()
        moveStrafing += dx;
        moveForward += dz;
        moveVertical += dy * speed;
    }

    public void jump(World world, BlockDataManager blockDataManager) {
        jumpRequested = true;
    }

    // ════════════════════════════════════════════════════════════════
    //  Sprint / Sneak
    // ════════════════════════════════════════════════════════════════

    public boolean isSprinting() { return isSprinting; }
    public void setSprinting(boolean sprinting) { this.isSprinting = sprinting; }
    public boolean isSneaking() { return isSneaking; }
    public void setSneaking(boolean sneaking) {
        this.isSneaking = sneaking;
        size.y = sneaking ? SNEAK_HEIGHT : 1.8F;
    }

    // ════════════════════════════════════════════════════════════════
    //  Block queries
    // ════════════════════════════════════════════════════════════════

    private boolean checkInLiquid(World world, BlockDataManager blockDataManager) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y + 0.5f);
        int z = (int) Math.floor(position.z);
        int voxel = world.getVoxel(x, y, z);
        return voxel > 0 && blockDataManager.isLiquid(voxel);
    }

    private float getBlockSlipperiness(World world, BlockDataManager blockDataManager) {
        int bx = (int) Math.floor(position.x);
        int by = (int) Math.floor(position.y - 0.1f);
        int bz = (int) Math.floor(position.z);
        int voxel = world.getVoxel(bx, by, bz);
        if (voxel <= 0) return DEFAULT_SLIPPERINESS;
        String name = blockDataManager.getName(voxel);
        if (name != null && name.contains("ice")) return ICE_SLIPPERINESS;
        return DEFAULT_SLIPPERINESS;
    }

    private int checkBlockAtFeet(World world) {
        return world.getVoxel(
            (int) Math.floor(position.x),
            (int) Math.floor(position.y),
            (int) Math.floor(position.z));
    }

    private int checkBlockAtWaist(World world) {
        return world.getVoxel(
            (int) Math.floor(position.x),
            (int) Math.floor(position.y + 0.9f),
            (int) Math.floor(position.z));
    }

    private String checkBlockBelow(World world, BlockDataManager blockDataManager) {
        int voxel = world.getVoxel(
            (int) Math.floor(position.x),
            (int) Math.floor(position.y - 0.1f),
            (int) Math.floor(position.z));
        return blockDataManager.getName(voxel);
    }

    private boolean isAtWaterEdge(World world, BlockDataManager blockDataManager) {
        if (!isSwimming) return false;
        int feetY = (int) Math.floor(position.y);
        int px = (int) Math.floor(position.x);
        int pz = (int) Math.floor(position.z);
        int[][] offsets = {{-1,0}, {1,0}, {0,-1}, {0,1}};
        for (int checkY = feetY; checkY <= feetY + 1; checkY++) {
            for (int[] off : offsets) {
                int voxel = world.getVoxel(px + off[0], checkY, pz + off[1]);
                if (voxel > 0 && blockDataManager.isFullBlock(voxel)) return true;
            }
        }
        return false;
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
                    if (voxel > 0 && blockDataManager.isFullBlock(voxel)) return true;
                }
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  Damage / Health / Death
    // ════════════════════════════════════════════════════════════════

    public void takeDamage(float amount) {
        if (isDead || flying) return;
        health = Math.max(0, health - amount);
        if (health <= 0) die();
    }

    public void takeDamage(float amount, boolean invincible) {
        if (invincible) return;
        takeDamage(amount);
    }

    private void die() {
        isDead = true;
        velocity.set(0);
    }

    public void respawn() {
        position.set(spawnPoint);
        prevPosition.set(spawnPoint);
        velocity.set(0);
        health = maxHealth;
        isDead = false;
        fallDistance = 0;
        tickAccumulator = 0;
        lastTickWallNanos = System.nanoTime();
        parachuteDeployed = false;
        parachuteItemId = null;
        parachuteSlotIndex = -1;
    }

    public void setSpawnPoint(Vector3f point) { spawnPoint.set(point); }

    // ════════════════════════════════════════════════════════════════
    //  Getters / Setters
    // ════════════════════════════════════════════════════════════════

    public Vector3f getPosition() { return position; }

    /** Interpolated position for smooth rendering between physics ticks. */
    public Vector3f getInterpolatedPosition(float partialTicks) {
        return new Vector3f(
            prevPosition.x + (position.x - prevPosition.x) * partialTicks,
            prevPosition.y + (position.y - prevPosition.y) * partialTicks,
            prevPosition.z + (position.z - prevPosition.z) * partialTicks
        );
    }

    /**
     * Interpolated position using wall-clock time since last physics tick.
     * Self-contained — used by EntityManager for PlayerEntity rendering.
     */
    public Vector3f getInterpolatedPosition() {
        float pt = (System.nanoTime() - lastTickWallNanos) / 1e9f / TICK_RATE_SECONDS;
        if (pt < 0f) pt = 0f;
        if (pt > 1f) pt = 1f;
        return getInterpolatedPosition(pt);
    }

    /** Wall-clock nanos of the last completed physics tick (for computing partialTicks). */
    public long getLastTickWallNanos() { return lastTickWallNanos; }

    public Vector3f getVelocity() { return velocity; }
    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; }
    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { this.pitch = pitch; }
    public boolean isFlying() { return flying; }
    public void setFlying(boolean flying) { this.flying = flying; }
    public boolean isOnGround() { return onGround; }
    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public boolean isDead() { return isDead; }

    public boolean isParachuteDeployed() { return parachuteDeployed; }
    public void deployParachute(String itemId, int slotIndex) {
        this.parachuteDeployed = true;
        this.parachuteItemId = itemId;
        this.parachuteSlotIndex = slotIndex;
        this.velocity.y = Math.max(this.velocity.y, -3.0f);
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
