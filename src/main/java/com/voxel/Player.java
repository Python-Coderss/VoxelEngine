package com.voxel;

import com.voxel.utils.BlockDataManager;
import com.voxel.utils.FixedPoint;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Represents the player with AABB collision and exact Minecraft 1.12.2 physics.
 * Uses 64-bit fixed-point position (8 fractional bits, 1/256 resolution) to avoid
 * floating-point precision loss at extreme coordinates (Far Lands, ~13M blocks).
 * Velocity remains as double since it stays small and needs float-multiply precision.
 */
public class Player {
    // Fixed-point position (8 fractional bits: 1/256 = 0.0039 resolution)
    private long posX, posY, posZ;
    private long prevPosX, prevPosY, prevPosZ;
    
    // Velocity remains double (small values, no large-coordinate precision issues)
    private final Vector3d velocity = new Vector3d();
    private final Vector3f size = new Vector3f(0.6f, 1.8f, 0.6f);

    private volatile long lastTickWallNanos = System.nanoTime();

    private float tickAccumulator = 0.0f;
    private static final float TICK_RATE = 0.05f;
    public static final float TICK_RATE_SECONDS = 0.05f;

    private boolean onGround = false;
    private boolean flying = false;
    private boolean isSwimming = false;
    private boolean isSprinting = false;
    private boolean isSneaking = false;
    private boolean parachuteDeployed = false;
    private String parachuteItemId = null;
    private int parachuteSlotIndex = -1;

    private float moveStrafing = 0.0f;
    private float moveForward = 0.0f;
    private float moveVertical = 0.0f;
    private boolean jumpRequested = false;

    // Exact Minecraft 1.12.2 constants
    private static final double GRAVITY = 0.08D;
    private static final float JUMP_VELOCITY = 0.42F;
    private static final float BASE_FRICTION = 0.91F;
    private static final float DEFAULT_SLIPPERINESS = 0.6F;
    private static final float ICE_SLIPPERINESS = 0.98F;
    private static final float MOVE_SPEED = 0.1F;
    private static final float AIR_MOVE_FACTOR = 0.02F;
    private static final float SPRINT_BOOST = 0.3F;
    private static final float SPRINT_JUMP_IMPULSE = 0.2F;
    private static final float FLY_SPEED = 0.05F;
    private static final float SNEAK_HEIGHT = 1.65F;
    private static final float STEP_HEIGHT = 0.6F;
    private static final float WATER_SLOWDOWN = 0.8F;
    private static final float LADDER_MAX_SPEED = 0.15F;
    private static final float SLIP_CONSTANT = 0.16277136F;

    // Health / death / spawn
    private float health = 20.0f;
    private float maxHealth = 20.0f;
    private boolean isDead = false;
    private float fallDistance = 0.0f;
    private long spawnX, spawnY, spawnZ;
    private final java.util.EnumMap<com.voxel.world.DimensionType, Vector3f> spawnPoints =
        new java.util.EnumMap<>(com.voxel.world.DimensionType.class);

    private float yaw = -90, pitch = 0;
    private com.voxel.world.DimensionType dimension = com.voxel.world.DimensionType.OVERWORLD;

    public Player(double x, double y, double z) {
        posX = FixedPoint.fromDouble(x);
        posY = FixedPoint.fromDouble(y);
        posZ = FixedPoint.fromDouble(z);
        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        spawnX = posX;
        spawnY = posY;
        spawnZ = posZ;
        spawnPoints.put(dimension, new Vector3f((float) x, (float) y, (float) z));
    }

    public void update(float dt, World world, BlockDataManager blockDataManager) {
        if (isDead) return;

        isSwimming = checkInLiquid(world, blockDataManager);

        int feetBlock = checkBlockAtFeet(world);
        int waistBlock = checkBlockAtWaist(world);
        String feetName = blockDataManager.getName(feetBlock);
        String waistName = blockDataManager.getName(waistBlock);
        boolean inColdAercloud = feetName.contains("cold_aercloud") || waistName.contains("cold_aercloud");
        boolean inBlueAercloud = feetName.contains("blue_aercloud") || waistName.contains("blue_aercloud");
        boolean onQuicksoil = feetName.contains("quicksoil") || checkBlockBelow(world, blockDataManager).contains("quicksoil");

        tickAccumulator += dt;
        if (tickAccumulator > 2.0f) tickAccumulator = 2.0f;

        while (tickAccumulator >= TICK_RATE) {
            prevPosX = posX;
            prevPosY = posY;
            prevPosZ = posZ;
            tick(TICK_RATE, world, blockDataManager,
                 inColdAercloud, inBlueAercloud, onQuicksoil);
            tickAccumulator -= TICK_RATE;
            lastTickWallNanos = System.nanoTime();
        }
    }

    private void tick(float tickDt, World world, BlockDataManager blockDataManager,
                       boolean inColdAercloud, boolean inBlueAercloud, boolean onQuicksoil) {
        // Gravity
        if (!flying) {
            if (inBlueAercloud && !parachuteDeployed && velocity.y <= 0) {
                velocity.y = 12.0f;
                fallDistance = 0;
            } else if (parachuteDeployed) {
                velocity.y -= 2.5f * tickDt;
                if (velocity.y < -3.0f) velocity.y = -3.0f;
                fallDistance = 0;
            } else if (inColdAercloud) {
                velocity.y -= 2.0f * tickDt;
                if (velocity.y < -1.0f) velocity.y = -1.0f;
                fallDistance = 0;
            } else if (isSwimming) {
                velocity.y -= 0.02D;
                fallDistance = 0;
            } else {
                velocity.y -= GRAVITY;
                if (velocity.y < 0) {
                    fallDistance += (float)(-velocity.y);
                }
            }
        } else {
            fallDistance = 0;
        }

        // Jump
        if (jumpRequested) {
            jumpRequested = false;
            if (onGround) {
                velocity.y = JUMP_VELOCITY;
                onGround = false;
                if (isSprinting) {
                    float yawRad = (float) Math.toRadians(yaw);
                    velocity.x += Math.cos(yawRad) * SPRINT_JUMP_IMPULSE;
                    velocity.z += Math.sin(yawRad) * SPRINT_JUMP_IMPULSE;
                }
            } else if (isSwimming) {
                if (isAtWaterEdge(world, blockDataManager)) {
                    velocity.y = 0.70F;
                } else {
                    velocity.y += 0.025F;
                    if (velocity.y > 0.20F) velocity.y = 0.20F;
                }
            }
        }

        // Friction and acceleration
        float friction;
        float acceleration;

        if (isSwimming) {
            friction = WATER_SLOWDOWN;
            acceleration = 0.02F;
        } else if (onGround && onQuicksoil) {
            friction = 0.4F * BASE_FRICTION;
            float f7 = SLIP_CONSTANT / (friction * friction * friction);
            acceleration = MOVE_SPEED * f7;
        } else if (onGround) {
            float slipperiness = getBlockSlipperiness(world, blockDataManager);
            friction = slipperiness * BASE_FRICTION;
            float f7 = SLIP_CONSTANT / (friction * friction * friction);
            acceleration = MOVE_SPEED * f7;
        } else {
            friction = BASE_FRICTION;
            acceleration = AIR_MOVE_FACTOR;
            if (isSprinting) {
                acceleration += AIR_MOVE_FACTOR * SPRINT_BOOST;
            }
        }

        // Horizontal movement
        float strafe = moveStrafing;
        float forward = moveForward;

        if (strafe != 0.0f || forward != 0.0f) {
            float d = (float) Math.sqrt(strafe * strafe + forward * forward);
            if (d < 1.0f) d = 1.0f;
            float factor = acceleration / d;
            if (isSprinting && !isSneaking) {
                if (flying) {
                    factor *= 2.0f;
                } else if (onGround) {
                    factor *= (1.0f + SPRINT_BOOST);
                }
            }
            if (isSneaking && onGround) {
                factor *= 0.3f;
            }
            strafe *= factor;
            forward *= factor;

            float yawRad = (float) Math.toRadians(yaw);
            float sin = (float) Math.sin(yawRad);
            float cos = (float) Math.cos(yawRad);
            velocity.x += forward * cos + strafe * sin;
            velocity.z += forward * sin - strafe * cos;
        }

        if (flying && moveVertical != 0.0f) {
            float flyFactor = isSprinting ? 2.0f : 1.0f;
            velocity.y += moveVertical * flyFactor;
        }

        // Apply friction
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

        if (Math.abs(velocity.x) < 0.003D) velocity.x = 0;
        if (Math.abs(velocity.y) < 0.003D) velocity.y = 0;
        if (Math.abs(velocity.z) < 0.003D) velocity.z = 0;

        // Move and collide — position += velocity in fixed-point
        moveAndCollide(world, blockDataManager);

        moveStrafing = 0.0f;
        moveForward = 0.0f;
        moveVertical = 0.0f;
    }

    /**
     * Move with per-tick velocity. Position updates use fixed-point arithmetic
     * to avoid double precision loss at large coordinates.
     */
    private void moveAndCollide(World world, BlockDataManager blockDataManager) {
        long stepVelX = FixedPoint.fromDouble(velocity.x);
        long stepVelZ = FixedPoint.fromDouble(velocity.z);

        // Step-assist
        if (onGround && !isSneaking) {
            double horDist = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            if (horDist > 0.001
                    && wouldCollideAtOffset(velocity.x, 0, velocity.z, world, blockDataManager)
                    && !wouldCollideAtOffset(velocity.x, STEP_HEIGHT, velocity.z, world, blockDataManager)) {
                long origX = posX, origY = posY, origZ = posZ;
                posX += stepVelX;
                posY += FixedPoint.fromDouble(STEP_HEIGHT);
                posZ += stepVelZ;
                if (!checkCollision(world, blockDataManager)) {
                    posX = origX;
                    posY = origY;
                    posZ = origZ;
                }
            }
        }

        // X movement
        posX += stepVelX;
        if (checkCollision(world, blockDataManager)) {
            posX -= stepVelX;
            velocity.x = 0;
        }

        // Z movement
        posZ += stepVelZ;
        if (checkCollision(world, blockDataManager)) {
            posZ -= stepVelZ;
            velocity.z = 0;
        }

        // Y movement
        double prevYVel = velocity.y;
        onGround = false;
        long stepVelY = FixedPoint.fromDouble(velocity.y);
        posY += stepVelY;
        if (checkCollision(world, blockDataManager)) {
            if (prevYVel < 0) {
                onGround = true;
                if (!parachuteDeployed) {
                    handleFallDamage();
                } else {
                    fallDistance = 0;
                }
            }
            posY -= stepVelY;
            velocity.y = 0;
        }
    }

    private boolean wouldCollideAtOffset(double dx, double dy, double dz, World world, BlockDataManager blockDataManager) {
        posX += FixedPoint.fromDouble(dx);
        posY += FixedPoint.fromDouble(dy);
        posZ += FixedPoint.fromDouble(dz);
        boolean result = checkCollision(world, blockDataManager);
        posX -= FixedPoint.fromDouble(dx);
        posY -= FixedPoint.fromDouble(dy);
        posZ -= FixedPoint.fromDouble(dz);
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
    //  Public movement API
    // ════════════════════════════════════════════════════════════════

    public void move(float dx, float dy, float dz, float speed) {
        if (flying) {
            float pitchRad = (float) Math.toRadians(pitch);
            float verticalFactor = (float) Math.sin(pitchRad);
            float horizontalMag = (float) Math.sqrt(dx * dx + dz * dz);
            if (horizontalMag > 0.1f) {
                moveVertical += verticalFactor * horizontalMag * FLY_SPEED * 0.2f;
            }
        }
        moveStrafing += dx;
        moveForward += dz;
        moveVertical += dy;
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
    //  Block queries (use fixed-point shifts instead of Math.floor)
    // ════════════════════════════════════════════════════════════════

    private boolean checkInLiquid(World world, BlockDataManager blockDataManager) {
        int x = FixedPoint.blockX(posX);
        int y = FixedPoint.blockX(posY + FixedPoint.fromDouble(0.5));
        int z = FixedPoint.blockX(posZ);
        int voxel = world.getVoxel(x, y, z);
        return voxel > 0 && blockDataManager.isLiquid(voxel);
    }

    private float getBlockSlipperiness(World world, BlockDataManager blockDataManager) {
        int bx = FixedPoint.blockX(posX);
        int by = FixedPoint.blockX(posY - FixedPoint.fromDouble(0.1));
        int bz = FixedPoint.blockX(posZ);
        int voxel = world.getVoxel(bx, by, bz);
        if (voxel <= 0) return DEFAULT_SLIPPERINESS;
        String name = blockDataManager.getName(voxel);
        if (name != null && name.contains("ice")) return ICE_SLIPPERINESS;
        return DEFAULT_SLIPPERINESS;
    }

    private int checkBlockAtFeet(World world) {
        return world.getVoxel(
            FixedPoint.blockX(posX), FixedPoint.blockX(posY), FixedPoint.blockX(posZ));
    }

    private int checkBlockAtWaist(World world) {
        return world.getVoxel(
            FixedPoint.blockX(posX), FixedPoint.blockX(posY + FixedPoint.fromDouble(0.9)), FixedPoint.blockX(posZ));
    }

    private String checkBlockBelow(World world, BlockDataManager blockDataManager) {
        int voxel = world.getVoxel(
            FixedPoint.blockX(posX), FixedPoint.blockX(posY - FixedPoint.fromDouble(0.1)), FixedPoint.blockX(posZ));
        return blockDataManager.getName(voxel);
    }

    private boolean isAtWaterEdge(World world, BlockDataManager blockDataManager) {
        if (!isSwimming) return false;
        int feetY = FixedPoint.blockX(posY);
        int px = FixedPoint.blockX(posX);
        int pz = FixedPoint.blockX(posZ);
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
        // Pure fixed-point AABB collision — no double/float conversions.
        long halfW = FixedPoint.fromFloat(size.x * 0.5f);
        long halfD = FixedPoint.fromFloat(size.z * 0.5f);
        long heightFP = FixedPoint.fromFloat(size.y);
        int minX = FixedPoint.blockX(posX - halfW);
        int maxX = FixedPoint.blockX(posX + halfW);
        int minY = FixedPoint.blockX(posY);
        int maxY = FixedPoint.blockX(posY + heightFP);
        int minZ = FixedPoint.blockX(posZ - halfD);
        int maxZ = FixedPoint.blockX(posZ + halfD);
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
        Vector3f dimensionSpawn = spawnPoints.get(dimension);
        if (dimensionSpawn != null) {
            posX = FixedPoint.fromFloat(dimensionSpawn.x);
            posY = FixedPoint.fromFloat(dimensionSpawn.y);
            posZ = FixedPoint.fromFloat(dimensionSpawn.z);
            prevPosX = posX;
            prevPosY = posY;
            prevPosZ = posZ;
        } else {
            posX = spawnX;
            posY = spawnY;
            posZ = spawnZ;
            prevPosX = spawnX;
            prevPosY = spawnY;
            prevPosZ = spawnZ;
        }
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

    public void teleport(double x, double y, double z) {
        posX = FixedPoint.fromDouble(x);
        posY = FixedPoint.fromDouble(y);
        posZ = FixedPoint.fromDouble(z);
        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        velocity.set(0);
        fallDistance = 0;
        tickAccumulator = 0;
        lastTickWallNanos = System.nanoTime();
        parachuteDeployed = false;
        parachuteItemId = null;
        parachuteSlotIndex = -1;
    }

    public void setSpawnPoint(Vector3f point) {
        spawnX = FixedPoint.fromFloat(point.x);
        spawnY = FixedPoint.fromFloat(point.y);
        spawnZ = FixedPoint.fromFloat(point.z);
        spawnPoints.put(dimension, new Vector3f(point));
    }

    // ════════════════════════════════════════════════════════════════
    //  Getters / Setters — public API returns Vector3f (compatible)
    // ════════════════════════════════════════════════════════════════

    public Vector3f getPosition() {
        return new Vector3f(FixedPoint.toFloat(posX), FixedPoint.toFloat(posY), FixedPoint.toFloat(posZ));
    }

    public Vector3d getPositionD() {
        return new Vector3d(FixedPoint.toDouble(posX), FixedPoint.toDouble(posY), FixedPoint.toDouble(posZ));
    }

    public void setPosition(double x, double y, double z) {
        posX = FixedPoint.fromDouble(x);
        posY = FixedPoint.fromDouble(y);
        posZ = FixedPoint.fromDouble(z);
    }

    /** Raw fixed-point X coordinate (for callers that need maximum precision). */
    public long getFixedX() { return posX; }
    public long getFixedY() { return posY; }
    public long getFixedZ() { return posZ; }

    /** Raw fixed-point access for velocity-critical paths. */
    public long getFixedPrevX() { return prevPosX; }
    public long getFixedPrevY() { return prevPosY; }
    public long getFixedPrevZ() { return prevPosZ; }

    public Vector3f getInterpolatedPosition(float partialTicks) {
        return new Vector3f(
            FixedPoint.lerpToFloat(prevPosX, posX, partialTicks),
            FixedPoint.lerpToFloat(prevPosY, posY, partialTicks),
            FixedPoint.lerpToFloat(prevPosZ, posZ, partialTicks)
        );
    }

    public Vector3f getInterpolatedPosition() {
        float pt = (System.nanoTime() - lastTickWallNanos) / 1e9f / TICK_RATE_SECONDS;
        if (pt < 0f) pt = 0f;
        if (pt > 1f) pt = 1f;
        return getInterpolatedPosition(pt);
    }

    public long getLastTickWallNanos() { return lastTickWallNanos; }

    public Vector3f getVelocity() {
        return new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z);
    }

    public Vector3d getVelocityD() { return velocity; }

    public void resetVelocity() { velocity.set(0); }
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
