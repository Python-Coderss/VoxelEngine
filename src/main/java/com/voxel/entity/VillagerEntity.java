package com.voxel.entity;

import com.voxel.World;
import com.voxel.ai.body.Emote;
import com.voxel.ai.body.EmotePlayer;
import com.voxel.ai.body.GazeController;
import com.voxel.ai.brain.Brains;
import com.voxel.utils.FixedPoint;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import java.util.*;

/**
 * VillagerEntity — Peaceful villager NPC with priority-based AI.
 * Model adapted from Minecraft 1.12 ModelVillager.
 * AI inspired by Minecraft's EntityAITasks priority system.
 */
public class VillagerEntity extends Entity {

    // ── Model parts (from villager.json) ──
    private ModelPart head, nose, body, robe, leftArm, rightArm, armConnector;
    private ModelPart leftLeg, rightLeg, hat;

    // ── AI Priority levels ──
    private static final int PRI_SWIM        = 0;
    private static final int PRI_FLEE        = 1;
    private static final int PRI_MOVE_INDOORS = 3;
    private static final int PRI_RETURN_HOME = 5;
    private static final int PRI_MATE        = 6;
    private static final int PRI_SOCIALIZE   = 9;
    private static final int PRI_LOOK_AROUND = 10;
    private static final int PRI_WANDER      = 11;
    private static final int PRI_WATCH_TV    = 12;
    private static final int PRI_BUILD       = 13;
    private static final int PRI_IDLE        = 14;

    // ── Movement ──
    private float animTime = 0.0f;
    private Vector3f wanderTarget = new Vector3f();
    private Vector3f prevPosition = new Vector3f();
    private float bobAccum = 0.0f;
    private float moveSpeed = 1.2f;
    private float fleeSpeed = 2.2f;
    private boolean isMoving = false;

    // ── Building ──
    private Queue<BuildTask> buildQueue = new LinkedList<>();
    private int buildProgress = 0;
    private static final int BUILD_STEPS = 20;
    private float autonomousBuildTimer = 0.0f;
    private static final float AUTONOMOUS_BUILD_INTERVAL = 30.0f; // Check every 30s
    private boolean hasBuiltHouse = false;
    private boolean hasFortified = false;

    // ── Village reference ──
    private Vector3i villageCenter;
    private int villageRadius = 48;
    private boolean isInVillage = false;
    public World world;

    // ── Profession ──
    public enum Profession { FARMER, BUILDER, NEWS_ANCHOR, SHOPKEEPER, NITWIT }
    private Profession profession = Profession.NITWIT;
    private int careerLevel = 1;

    // ── TV watching ──
    private boolean watchingTV = false;
    private Vector3i tvPosition;
    private int tvChannel = 0;
    private float watchTimer = 0.0f;

    // ── Social / Schedule ──
    private float socialTimer = 0.0f;
    private float lookTimer = 0.0f;
    private int randomTickDivider = 60;
    private VillagerEntity socialTarget = null;
    private float indoorCheckTimer = 0.0f;
    private boolean isNightOrRaining = false;

    // ── World time provider (set by Main or GameContext) ──
    private static float globalWorldTime = 720.0f; // default: noon
    public static void setGlobalWorldTime(float time) { globalWorldTime = time; }
    /** Current world time in minutes (0-1440); exposed for AI perception. */
    public static float getGlobalWorldTime() { return globalWorldTime; }

    // ── Mating ──
    private boolean isWillingToMate = false;
    private boolean isMating = false;
    private float mateTimer = 0.0f;
    private int mateCooldown = 0;
    private VillagerEntity matePartner = null;

    // ── Baby / Child ──
    private boolean isBaby = false;
    private float babyGrowTimer = 0.0f;
    private static final float BABY_GROW_TIME = 600.0f; // 10 minutes to grow up

    // ── Work schedule ──
    private boolean isWorking = false;
    private float workTimer = 0.0f;
    private Entity fearedEntity = null;
    private float fearTimer = 0.0f;

    // ── Build task ──
    public static class BuildTask {
        public Vector3i position;
        public int blockType;
        public boolean isPlace;
        public BuildTask(Vector3i pos, int block, boolean place) {
            this.position = pos; this.blockType = block; this.isPlace = place;
        }
    }

    // ── Body language layer (driven by the brain, layered over base anims) ──
    private final EmotePlayer emotePlayer = new EmotePlayer();
    private final GazeController gaze = new GazeController();

    // Static mirror for collision avoidance
    public static com.voxel.entity.EntityManager entityManager;
    public static void setEntityManager(com.voxel.entity.EntityManager em) { entityManager = em; }

    public VillagerEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        loadModel("src/main/resources/assets/minecraft/models/entity/villager.json", textureManager);

        for (ModelPart part : parts) {
            switch (part.name) {
                case "head": head = part; break;
                case "nose": nose = part; break;
                case "body": body = part; break;
                case "robe": robe = part; break;
                case "left_arm": leftArm = part; break;
                case "right_arm": rightArm = part; break;
                case "arm_connector": armConnector = part; break;
                case "left_leg": leftLeg = part; break;
                case "right_leg": rightLeg = part; break;
                case "hat": hat = part; break;
            }
        }

        Profession[] profs = Profession.values();
        profession = profs[new Random().nextInt(profs.length)];

        // Crossed-arms rest pose from ModelVillager.setRotationAngles (-0.75 rad
        // ≈ 43° forward about the shared pivot (0,21,1)); negative X tilts
        // forward in this engine's convention.
        if (armConnector != null) { armConnector.rotation.set(-43, 0, 0); }
        if (leftArm != null) { leftArm.rotation.set(-43, 0, 0); }
        if (rightArm != null) { rightArm.rotation.set(-43, 0, 0); }

        wanderTarget.set(position);
        prevPosition.set(position);
        randomTickDivider = 60 + new Random().nextInt(50);

        this.brain = Brains.newVillagerBrain(this);
    }

    public void setVillage(Vector3i center, int radius) {
        this.villageCenter = new Vector3i(center);
        this.villageRadius = radius;
        this.isInVillage = true;
    }

    public boolean isInVillage() { return isInVillage; }
    public Vector3i getVillageCenter() { return villageCenter; }
    public Profession getProfession() { return profession; }
    public void setProfession(Profession p) { this.profession = p; }

    /** Whether this villager is available for TV watching or other interruptions. */
    public boolean isAvailable() {
        return !watchingTV && fearedEntity == null && !isMating;
    }

    public void startWatchingTV(Vector3i tvPos, int channel) {
        this.tvPosition = new Vector3i(tvPos);
        this.tvChannel = channel;
        this.watchingTV = true;
        this.watchTimer = 0.0f;
    }

    public void stopWatchingTV() {
        this.watchingTV = false;
        socialTimer = 0.0f;
    }

    public boolean isWatchingTV() { return watchingTV; }
    public int getTVChannel() { return tvChannel; }
    public Vector3i getTVPosition() { return tvPosition; }
    public boolean isBaby() { return isBaby; }
    public void setBaby(boolean baby) {
        this.isBaby = baby;
        if (baby) {
            // Scale down model parts for baby size (60% of adult)
            for (ModelPart part : parts) {
                part.size.mul(0.6f);
                part.offset.mul(0.6f);
            }
            moveSpeed = 0.7f;
            fleeSpeed = 1.5f;
        }
    }

    public void queueBuild(Vector3i pos, int blockType, boolean place) {
        buildQueue.add(new BuildTask(pos, blockType, place));
    }

    public void queueBuildHouse(Vector3i origin, int width, int depth, int height) {
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                queueBuild(new Vector3i(origin.x + dx, origin.y, origin.z + dz), 72, true);
        for (int h = 1; h <= height; h++) {
            for (int dx = 0; dx < width; dx++) {
                queueBuild(new Vector3i(origin.x + dx, origin.y + h, origin.z), 71, true);
                queueBuild(new Vector3i(origin.x + dx, origin.y + h, origin.z + depth - 1), 71, true);
            }
            for (int dz = 0; dz < depth; dz++) {
                queueBuild(new Vector3i(origin.x, origin.y + h, origin.z + dz), 71, true);
                queueBuild(new Vector3i(origin.x + width - 1, origin.y + h, origin.z + dz), 71, true);
            }
        }
        int doorX = origin.x + width / 2;
        queueBuild(new Vector3i(doorX, origin.y + 1, origin.z), 0, true);
        queueBuild(new Vector3i(doorX, origin.y + 2, origin.z), 0, true);
        for (int dx = -1; dx < width + 1; dx++)
            for (int dz = -1; dz < depth + 1; dz++)
                queueBuild(new Vector3i(origin.x + dx, origin.y + height + 1, origin.z + dz), 72, true);
    }

    public void queueBuildWall(Vector3i start, int length, int height, int dir) {
        int dx = (dir == 1 ? 1 : (dir == 3 ? -1 : 0));
        int dz = (dir == 2 ? 1 : (dir == 0 ? -1 : 0));
        for (int i = 0; i < length; i++)
            for (int h = 0; h < height; h++)
                queueBuild(new Vector3i(start.x + dx * i, start.y + h, start.z + dz * i), 71, true);
    }

    // ════════════════════════════════════════════════════════════════
    //  UPDATE — Priority-based AI task system
    // ════════════════════════════════════════════════════════════════

    @Override
    public void update(float dt) {
        super.update(dt);
        if (world == null) return;

        snapshotPrev();

        // Process build queue (happens regardless of AI — continues in background)
        processBuildQueue();

        // Apply gravity every frame
        applyGravity(dt);

        // Tick cooldowns
        mateCooldown = Math.max(0, mateCooldown - (int)(dt * 20));
        if (mateTimer > 0) mateTimer = Math.max(0, mateTimer - dt);
        if (socialTimer > 0) socialTimer = Math.max(0, socialTimer - dt);
        if (lookTimer > 0) lookTimer = Math.max(0, lookTimer - dt);
        if (fearTimer > 0) fearTimer = Math.max(0, fearTimer - dt);
        indoorCheckTimer += dt;

        // Baby growth
        if (isBaby) {
            babyGrowTimer += dt;
            if (babyGrowTimer >= BABY_GROW_TIME) {
                isBaby = false;
                // Restore adult size
                for (ModelPart part : parts) {
                    part.size.div(0.6f);
                    part.offset.div(0.6f);
                }
                moveSpeed = 1.2f;
                fleeSpeed = 2.2f;
            }
        }

        // Autonomous building check (every ~30 seconds, only for adult builder villagers)
        autonomousBuildTimer += dt;
        if (autonomousBuildTimer >= AUTONOMOUS_BUILD_INTERVAL && !isBaby) {
            autonomousBuildTimer = 0.0f;
            tryAutonomousBuild();
        }

        // Periodic village and schedule checks
        if (--randomTickDivider <= 0) {
            randomTickDivider = 60 + new Random().nextInt(50);
            isNightOrRaining = isNightTime();
            // Update work state based on time of day
            isWorking = !isNightOrRaining && profession == Profession.BUILDER;
            if (!isWillingToMate && mateCooldown <= 0 && !isMating && new Random().nextFloat() < 0.08f) {
                isWillingToMate = true;
            }
            // Occasional profession-based behavior
            if (profession == Profession.BUILDER && isWorking && buildQueue.isEmpty() && new Random().nextFloat() < 0.15f) {
                tryAutonomousBuild();
            }
        }

        // ── Priority-based task execution ──
        boolean brainDrove = false;
        if (brain != null && world != null) {
            brain.perceive(com.voxel.ai.Senses.scan(this, world, entityManager, 24f, globalWorldTime));
            brainDrove = brain.update(dt);
        }
        if (brainDrove)          { /* fall through to post-AI animation */ }
        else if (trySwim(dt))          { /* fall through to post-AI animation */ }
        else if (tryFlee(dt))          { /* fall through */ }
        else if (watchingTV && tryWatchTV(dt)) { /* fall through */ }
        else if (tryMoveIndoors(dt))   { /* fall through */ }
        else if (tryReturnHome(dt))    { /* fall through */ }
        else if (tryMate(dt))          { /* fall through */ }
        else if (trySocialize(dt))     { /* fall through */ }
        else if (tryLookAround(dt))    { /* fall through */ }
        else if (tryWander(dt))        { /* fall through */ }
        else if (tryBuild(dt))         { /* fall through */ }
        else tryIdle(dt);

        // Compute velocity/animation AFTER all AI movement has run
        Vector3f velocity = new Vector3f(getPosition()).sub(prevPosition);
        float speed = velocity.length() / Math.max(dt, 0.00001f);
        prevPosition.set(getPosition());
        animTime += dt * Math.max(0.9f, speed);
        isMoving = speed > 0.05f;
    }

    // ════════════════════════════════════════════════════════════════
    //  PRIORITY TASKS
    // ════════════════════════════════════════════════════════════════

    /** Priority 0: Swim to surface if in water. */
    private boolean trySwim(float dt) {
        int fx = (int)Math.floor(getPosX());
        int fy = (int)Math.floor(getPosY() + 0.5f);
        int fz = (int)Math.floor(getPosZ());
        if (world.getVoxel(fx, fy, fz) == 15) { // water
            tryMove(0, 1.5f * dt, 0);
            updateSwimAnimation(dt);
            return true;
        }
        return false;
    }

    /** Priority 1: Flee from nearby hostile mobs. */
    private boolean tryFlee(float dt) {
        if (entityManager == null) return false;

        // Find closest feared entity
        Entity closest = null;
        float closestDist = 64.0f;
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            Entity e = entityManager.getEntity(i);
            if (e instanceof EnemyEntity && !((EnemyEntity)e).isDead()) {
                float d = getPosition().distanceSquared(e.getPosition());
                if (d < closestDist) {
                    closestDist = d;
                    closest = e;
                }
            }
        }

        if (closest != null) {
            fearedEntity = closest;
            fearTimer = 3.0f;
        }

        // Flee from the last known feared entity (if still valid)
        if (fearedEntity != null && fearTimer > 0) {
            // Verify feared entity is still alive
            if (fearedEntity instanceof EnemyEntity && ((EnemyEntity)fearedEntity).isDead()) {
                fearedEntity = null;
                fearTimer = 0;
                return false;
            }
            Vector3f away = new Vector3f(getPosition()).sub(fearedEntity.getPosition()).normalize();
            tryMove(away.x * fleeSpeed * dt, 0, away.z * fleeSpeed * dt);
            rotation.y = (float)Math.toDegrees(Math.atan2(away.x, away.z));
            updateWalkAnimation(dt);
            return true;
        }

        fearedEntity = null;
        return false;
    }

    /** Priority 12: Watch TV if assigned. */
    private boolean tryWatchTV(float dt) {
        watchTimer += dt;
        if (tvPosition != null) {
            float dx = tvPosition.x + 0.5f - getPosX();
            float dz = tvPosition.z + 0.5f - getPosZ();
            rotation.y = (float)Math.toDegrees(Math.atan2(dx, dz));

            float dist = (float)Math.sqrt(dx * dx + dz * dz);
            if (dist > 4.0f) {
                Vector3f dir = new Vector3f(dx, 0, dz).normalize();
                tryMove(dir.x * moveSpeed * 0.5f * dt, 0, dir.z * moveSpeed * 0.5f * dt);
            }
        }
        updateWatchingAnimation(dt);
        return true;
    }

    /** Priority 3: Move indoors at night or during rain. */
    private boolean tryMoveIndoors(float dt) {
        if (indoorCheckTimer < 5.0f) return false;
        indoorCheckTimer = 0.0f;

        if (!isNightOrRaining) return false;

        // Already indoors if a solid block is overhead
        int fx = (int)Math.floor(getPosX());
        int fy = (int)Math.floor(getPosY() + 2.0f);
        int fz = (int)Math.floor(getPosZ());
        if (world.getVoxel(fx, fy, fz) != 0) return false;

        if (isInVillage && villageCenter != null) {
            moveToward(new Vector3f(villageCenter.x, getPosY(), villageCenter.z), dt, moveSpeed * 0.8f);
            updateWalkAnimation(dt);
            return true;
        }
        return false;
    }

    /** Priority 5: Return toward village center if too far. */
    private boolean tryReturnHome(float dt) {
        if (!isInVillage || villageCenter == null) return false;
        float dx = getPosX() - villageCenter.x;
        float dz = getPosZ() - villageCenter.z;
        float distSq = dx * dx + dz * dz;
        float maxDist = villageRadius * 0.85f;

        if (distSq > maxDist * maxDist) {
            moveToward(new Vector3f(villageCenter.x, getPosY(), villageCenter.z), dt, moveSpeed);
            updateWalkAnimation(dt);
            return true;
        }
        return false;
    }

    /** Priority 6: Mate with another willing villager. Spawns baby villager. */
    private boolean tryMate(float dt) {
        if (mateCooldown > 0 || !isWillingToMate || isBaby) return false;
        if (entityManager == null) return false;

        // If currently in a mating pair and close enough, spawn baby
        if (isMating && matePartner != null) {
            float dist = getPosition().distance(matePartner.getPosition());
            if (dist < 2.0f && mateTimer < 3.0f) {
                // Spawn baby villager!
                spawnBaby(matePartner);
                isWillingToMate = false;
                matePartner.isWillingToMate = false;
                mateCooldown = 400;
                matePartner.mateCooldown = 400;
                isMating = false;
                matePartner.isMating = false;
                matePartner = null;
            }
            mateTimer = Math.max(0, mateTimer - dt);
            return true;
        }

        VillagerEntity partner = null;
        float closest = 8.0f;
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            Entity e = entityManager.getEntity(i);
            if (e instanceof VillagerEntity && e != this) {
                VillagerEntity v = (VillagerEntity)e;
                if (v.isWillingToMate && v.mateCooldown <= 0 && !v.isBaby) {
                    float d = getPosition().distance(e.getPosition());
                    if (d < closest) {
                        closest = d;
                        partner = v;
                    }
                }
            }
        }

        if (partner != null) {
            mateTimer = 5.0f;
            isMating = true;
            matePartner = partner;
            partner.isMating = true;
            partner.matePartner = this;
            moveToward(partner.getPosition(), dt, moveSpeed * 0.6f);
            updateWalkAnimation(dt);
            return true;
        }

        if (mateTimer <= 0) {
            isMating = false;
            matePartner = null;
            // Stay willing for a few more cycles then reset
            if (new Random().nextFloat() < 0.02f) {
                isWillingToMate = false;
                mateCooldown = 200;
            }
        }
        return false;
    }

    /** Spawn a baby villager between this villager and the partner. */
    private void spawnBaby(VillagerEntity partner) {
        if (entityManager == null || world == null) return;
        Vector3f midPoint = new Vector3f(getPosition()).add(partner.getPosition()).mul(0.5f);
        int babyId = 50000 + (int)(Math.random() * 10000);
        com.voxel.utils.TextureManager tm = com.voxel.world.structure.MapGenVillage.textureManager;
        if (tm == null) return;
        VillagerEntity baby = new VillagerEntity(babyId, midPoint, tm);
        baby.setWorld(world);
        baby.setBaby(true);
        baby.dimension = this.dimension;
        if (isInVillage && villageCenter != null) {
            baby.setVillage(villageCenter, villageRadius);
        }
        // Random profession, but inherit from parents with bias
        if (new Random().nextFloat() < 0.5f) {
            baby.setProfession(this.profession);
        } else {
            baby.setProfession(partner.profession);
        }
        entityManager.addEntity(baby);
    }

    /** Autonomous building: decide what to build based on village needs. */
    private void tryAutonomousBuild() {
        if (!isInVillage || villageCenter == null || world == null || isBaby) return;
        if (profession != Profession.BUILDER && new Random().nextFloat() < 0.7f) return; // Non-builders rarely build

        // Check if village already has enough houses (don't overbuild)
        if (!hasBuiltHouse) {
            // Build a new small house at a random position within the village
            Random r = new Random();
            double angle = r.nextDouble() * Math.PI * 2;
            int dist = 10 + r.nextInt(25);
            int bx = villageCenter.x + (int)(Math.cos(angle) * dist);
            int bz = villageCenter.z + (int)(Math.sin(angle) * dist);
            int by = findSurfaceY(bx, bz);
            if (by > 0) {
                int size = 5 + r.nextInt(2);
                queueBuildHouse(new Vector3i(bx, by + 1, bz), size, size, 3);
                hasBuiltHouse = true;
                return;
            }
        }

        // Build walls for fortification (once per villager)
        if (!hasFortified && new Random().nextFloat() < 0.3f) {
            int wallLength = 8 + new Random().nextInt(12);
            int dir = new Random().nextInt(4);
            double angle = new Random().nextDouble() * Math.PI * 2;
            int wx = villageCenter.x + (int)(Math.cos(angle) * villageRadius * 0.7f);
            int wz = villageCenter.z + (int)(Math.sin(angle) * villageRadius * 0.7f);
            int wy = findSurfaceY(wx, wz);
            if (wy > 0) {
                queueBuildWall(new Vector3i(wx, wy + 1, wz), wallLength, 3, dir);
                hasFortified = true;
            }
        }
    }

    private int findSurfaceY(int x, int z) {
        for (int y = 127; y >= 0; y--) {
            if (world.getVoxel(x, y, z) > 0) return y;
        }
        return -1;
    }

    /** Priority 9: Socialize with nearby villagers. */
    private boolean trySocialize(float dt) {
        if (socialTimer > 0 && socialTarget != null) {
            // Check if social target is still valid
            float dist = getPosition().distance(socialTarget.getPosition());
            if (dist > 12.0f) {
                // Target moved too far — give up
                socialTarget = null;
                socialTimer = 0;
                return false;
            }
            if (dist > 1.5f) {
                moveToward(socialTarget.getPosition(), dt, moveSpeed * 0.5f);
                updateWalkAnimation(dt);
            } else {
                float dx = socialTarget.getPosX() - getPosX();
                float dz = socialTarget.getPosZ() - getPosZ();
                rotation.y = (float)Math.toDegrees(Math.atan2(dx, dz));
                updateIdleAnimation(dt);
            }
            return true;
        }

        if (socialTarget != null) {
            socialTarget = null;
            socialTimer = 0;
        }

        // Occasional social trigger
        if (entityManager != null && new Random().nextFloat() < 0.01f) {
            for (int i = 0; i < entityManager.getEntityCount(); i++) {
                Entity e = entityManager.getEntity(i);
                if (e instanceof VillagerEntity && e != this) {
                    float d = getPosition().distance(e.getPosition());
                    if (d < 6.0f) {
                        socialTimer = 4.0f + new Random().nextFloat() * 4.0f;
                        socialTarget = (VillagerEntity)e;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Priority 10: Idle head turns and looking around. */
    private boolean tryLookAround(float dt) {
        if (lookTimer > 0) {
            if (head != null) {
                head.rotation.set((float)Math.sin(animTime * 0.7f) * 3, 0, 0);
            }
            updateIdleAnimation(dt);
            return true;
        }

        if (new Random().nextFloat() < 0.005f) {
            lookTimer = 2.0f + new Random().nextFloat() * 3.0f;
            return true;
        }
        return false;
    }

    /** Priority 11: Wander around the village. */
    private boolean tryWander(float dt) {
        float dx = wanderTarget.x - getPosX();
        float dz = wanderTarget.z - getPosZ();
        float dist = (float)Math.sqrt(dx * dx + dz * dz);

        if (dist < 1.0f) {
            pickNewWanderTarget();
            return true;
        }

        Vector3f dir = new Vector3f(dx, 0, dz).normalize().mul(moveSpeed * dt);
        tryMove(dir.x, 0, dir.z);
        if (new Vector2f(dx, dz).length() > 0.01f)
            rotation.y = (float)Math.toDegrees(Math.atan2(dx, dz));
        updateWalkAnimation(dt);
        return true;
    }

    /** Priority 13: Execute build tasks. */
    private boolean tryBuild(float dt) {
        if (buildQueue.isEmpty()) return false;
        updateBuildingAnimation(dt);
        return true;
    }

    /** Priority 14: Idle. */
    private void tryIdle(float dt) {
        updateIdleAnimation(dt);
    }

    // ════════════════════════════════════════════════════════════════
    //  BUILD QUEUE PROCESSING
    // ════════════════════════════════════════════════════════════════

    private void processBuildQueue() {
        if (buildQueue.isEmpty()) return;
        buildProgress++;
        if (buildProgress >= BUILD_STEPS) {
            buildProgress = 0;
            BuildTask task = buildQueue.poll();
            if (task != null) {
                if (task.isPlace) {
                    if (world.getVoxel(task.position.x, task.position.y, task.position.z) == 0)
                        world.setVoxel(task.position.x, task.position.y, task.position.z, task.blockType);
                } else {
                    world.setVoxel(task.position.x, task.position.y, task.position.z, 0);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  MOVEMENT — Same robust collision as EnemyEntity
    // ════════════════════════════════════════════════════════════════

    private void moveToward(Vector3f target, float dt, float speed) {
        Vector3f dir = new Vector3f(target).sub(getPosition());
        float len = dir.length();
        if (len > 0.35f) {
            dir.normalize().mul(speed * dt);
            tryMove(dir.x, dir.y, dir.z);
            if (new Vector2f(dir.x, dir.z).length() > 0.001f) {
                rotation.y = (float)Math.toDegrees(Math.atan2(dir.x, dir.z));
            }
        }
    }

    private void tryMove(float dx, float dy, float dz) {
        float cx = getPosX(), cy = getPosY(), cz = getPosZ();
        if (canOccupy(cx + dx, cy + dy, cz + dz) && !isCollidingWithOtherEntities(cx + dx, cy + dy, cz + dz)) {
            addPosition(dx, dy, dz);
            return;
        }
        if (canOccupy(cx + dx, cy + 1.0f, cz + dz) && !isCollidingWithOtherEntities(cx + dx, cy + 1.0f, cz + dz)) {
            addPosition(dx, 1.0f, dz);
            return;
        }
        if (canOccupy(cx + dx, cy - 1.0f, cz + dz) && !isCollidingWithOtherEntities(cx + dx, cy - 1.0f, cz + dz)) {
            addPosition(dx, -1.0f, dz);
            return;
        }
        if (canOccupy(cx + dx, cy, cz) && !isCollidingWithOtherEntities(cx + dx, cy, cz))
            posX += FixedPoint.fromFloat(dx);
        if (canOccupy(cx, cy, cz + dz) && !isCollidingWithOtherEntities(cx, cy, cz + dz))
            posZ += FixedPoint.fromFloat(dz);
    }

    private boolean canOccupy(float x, float y, float z) {
        int ix = (int)Math.floor(x);
        int iy = (int)Math.floor(y + 0.1f);
        int iz = (int)Math.floor(z);
        return isWalkable(ix, iy, iz);
    }

    private boolean isWalkable(int x, int y, int z) {
        if (world.getVoxel(x, y - 1, z) == 0) return false;
        if (world.getVoxel(x, y, z) != 0) return false;
        if (world.getVoxel(x, y + 1, z) != 0) return false;
        return true;
    }

    private boolean isCollidingWithOtherEntities(float x, float y, float z) {
        if (entityManager == null) return false;
        Vector3f target = new Vector3f(x, y, z);
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            Entity other = entityManager.getEntity(i);
            if (other == null || other == this) continue;
            if (other instanceof EnemyEntity && ((EnemyEntity)other).isDead()) continue;
            float distSq = target.distanceSquared(other.getPosition());
            if (distSq < 0.36f) return true;
        }
        return false;
    }

    private void applyGravity(float dt) {
        int fx = (int)Math.floor(getPosX());
        int fy = (int)Math.floor(getPosY() - 0.01f);
        int fz = (int)Math.floor(getPosZ());
        if (world.getVoxel(fx, fy, fz) == 0) {
            tryMove(0, -9.8f * dt, 0);
        }
    }

    private void pickNewWanderTarget() {
        Random r = new Random();
        if (isInVillage && villageCenter != null) {
            wanderTarget.set(
                villageCenter.x + (r.nextFloat() - 0.5f) * villageRadius * 1.2f,
                getPosY(),
                villageCenter.z + (r.nextFloat() - 0.5f) * villageRadius * 1.2f
            );
        } else {
            wanderTarget.set(
                getPosX() + (r.nextFloat() - 0.5f) * 30,
                getPosY(),
                getPosZ() + (r.nextFloat() - 0.5f) * 30
            );
        }
    }

    /** Check if it's night time using the global world time. */
    private boolean isNightTime() {
        float tod = globalWorldTime % 1440; // 24h cycle in minutes
        return tod < 360 || tod > 1080; // night: 6PM-6AM (after 1080 or before 360)
    }

    // ════════════════════════════════════════════════════════════════
    //  ANIMATIONS
    // ════════════════════════════════════════════════════════════════

    private void updateIdleAnimation(float dt) {
        float breathe = (float)Math.sin(animTime * 1.5f) * 0.03f;
        posY += FixedPoint.fromFloat(breathe - bobAccum);
        bobAccum = breathe;

        // Connector + arms tilt forward together as one unit
        if (armConnector != null) armConnector.rotation.set(-43, 0, 0);
        if (leftArm != null) { leftArm.rotation.set(-43, 0, 0); }
        if (rightArm != null) { rightArm.rotation.set(-43, 0, 0); }
        if (leftLeg != null) leftLeg.rotation.x *= 0.8f;
        if (rightLeg != null) rightLeg.rotation.x *= 0.8f;

        if (head != null && lookTimer <= 0 && socialTimer <= 0) {
            head.rotation.set(
                (float)Math.sin(animTime * 0.7f) * 2,
                (float)Math.sin(animTime * 0.5f + 1.0f) * 3,
                0
            );
        }
        rotation.z = (float)Math.sin(animTime * 0.5f) * 0.5f;
    }

    private void updateWalkAnimation(float dt) {
        float walkSpeed = 8.0f;
        float swing = (float)Math.sin(animTime * walkSpeed) * 30.0f;
        float bob = (float)Math.abs(Math.sin(animTime * walkSpeed)) * 0.1f;
        posY += FixedPoint.fromFloat(bob - bobAccum);
        bobAccum = bob;

        if (leftLeg != null) leftLeg.rotation.x = -swing;
        if (rightLeg != null) rightLeg.rotation.x = swing;

        // Arms + connector stay attached as one unit while walking
        if (armConnector != null) armConnector.rotation.set(-43, 0, 0);
        if (leftArm != null) { leftArm.rotation.set(-43, 0, 0); }
        if (rightArm != null) { rightArm.rotation.set(-43, 0, 0); }
        rotation.z = (float)Math.sin(animTime * walkSpeed) * 3.0f;
        if (head != null) head.rotation.set(0, 0, 0);
    }

    private void updateSwimAnimation(float dt) {
        float swimWave = (float)Math.sin(animTime * 6.0f) * 20.0f;
        // Uncross arms for swimming
        if (armConnector != null) armConnector.rotation.set(0, 0, 0);
        if (leftArm != null) { leftArm.rotation.set(swimWave - 80, 0, 10); }
        if (rightArm != null) { rightArm.rotation.set(-swimWave - 80, 0, -10); }
        if (leftLeg != null) leftLeg.rotation.x = swimWave * 0.3f;
        if (rightLeg != null) rightLeg.rotation.x = -swimWave * 0.3f;
        if (head != null) head.rotation.set(0, 0, 0);
    }

    private void updateBuildingAnimation(float dt) {
        float buildSwing = (float)Math.sin(animTime * 4.0f) * 45.0f;
        // Partially uncross for building: connector + left arm tilt less, right arm reaches out
        if (armConnector != null) armConnector.rotation.set(-20, 0, 0);
        if (rightArm != null) {
            rightArm.rotation.set(buildSwing - 60, 0, 0);
        }
        if (leftArm != null) { leftArm.rotation.set(-20, 0, 0); }
        if (leftLeg != null) leftLeg.rotation.x = 0;
        if (rightLeg != null) rightLeg.rotation.x = 0;

        if (head != null && !buildQueue.isEmpty()) {
            BuildTask next = buildQueue.peek();
            if (next != null) {
                float dx = next.position.x + 0.5f - getPosX();
                float dy = next.position.y + 0.5f - (getPosY() + 1.5f);
                float dz = next.position.z + 0.5f - getPosZ();
                // Elevation is negated: engine +X pitch lowers the chin.
                head.rotation.set(
                    -(float)Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx+dz*dz))) * 0.5f,
                    0, 0);
            }
        }
    }

    private void updateWatchingAnimation(float dt) {
        float breathe = (float)Math.sin(animTime * 1.5f) * 0.01f;
        posY += FixedPoint.fromFloat(breathe - bobAccum);
        bobAccum = breathe;

        if (head != null && tvPosition != null) {
            float dx = tvPosition.x + 0.5f - getPosX();
            float dy = tvPosition.y + 0.5f - (getPosY() + 1.5f);
            float dz = tvPosition.z + 0.5f - getPosZ();
            // Elevation is negated: engine +X pitch lowers the chin.
            head.rotation.set(
                -(float)Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx+dz*dz))) * 0.7f,
                0, 0);
        }

        // Arms + connector stay attached while watching TV
        if (armConnector != null) armConnector.rotation.set(-43, 0, 0);
        if (leftArm != null) { leftArm.rotation.set(-43, 0, 0); }
        if (rightArm != null) { rightArm.rotation.set(-43, 0, 0); }
        if (leftLeg != null) leftLeg.rotation.x = 0;
        if (rightLeg != null) rightLeg.rotation.x = 0;
        rotation.z = 0;
    }

    // ════════════════════════════════════════════════════════════════
    //  AI-BRAIN API — used by com.voxel.ai brains; wraps the same private
    //  movement/animation helpers so brain-driven motion looks identical.
    // ════════════════════════════════════════════════════════════════

    public float aiWalkSpeed() { return moveSpeed; }
    public float aiFleeSpeed() { return fleeSpeed; }

    public Vector3f eyePosition() {
        return new Vector3f(getPosX(), getPosY() + 1.5f, getPosZ());
    }

    public boolean aiInWater() {
        int fx = (int) Math.floor(getPosX());
        int fy = (int) Math.floor(getPosY() + 0.5f);
        int fz = (int) Math.floor(getPosZ());
        return world != null && world.getVoxel(fx, fy, fz) == 15;
    }

    public void aiSwimUp(float dt) {
        if (!aiInWater()) return;
        tryMove(0, 1.5f * dt, 0);
        updateSwimAnimation(dt);
        applyEmoteOverlay();
    }

    public void aiMoveToward(Vector3f target, float speed, float dt) {
        moveToward(target, dt, speed);
        updateWalkAnimation(dt);
        applyEmoteOverlay();
    }

    /** Absolute world yaw in degrees — the direction the villager should face.
     *  The model is authored facing +Z like every other entity (nose at +Z),
     *  so atan2(dx,dz) faces it straight at the target with no offset. */
    public void aiFaceYaw(float yawDeg) {
        rotation.y = yawDeg;
        applyEmoteOverlay();
    }

    public void aiStandAnim(float dt) {
        updateIdleAnimation(dt);
        applyEmoteOverlay();
    }

    /**
     * Applies resolved gaze angles {pitchDeg, yawOffsetDeg} to the head.
     * GazeController returns math-convention pitch (+ = target above the eyes),
     * but this engine's part.rotation.x is a screen-style pitch: positive X
     * rotates the +Z nose DOWN (see raytracer rotateX). Negate here so both
     * aim-at-target and idle saccades look the right way, matching the
     * negated elevations in updateBuilding/updateWatchingAnimation.
     */
    public void aiAimHead(float[] gazeAngles) {
        if (head == null || gazeAngles == null || gazeAngles.length < 2) return;
        head.rotation.set(-gazeAngles[0], gazeAngles[1], 0);
    }

    public boolean aiPlayEmote(Emote emote, Vector3f pointAt) {
        return emotePlayer.play(emote, pointAt);
    }

    public boolean aiSpeak(String line) {
        return com.voxel.ai.speech.VillagerSpeech.say(id, line);
    }

    public boolean aiHasBuildWork() {
        return !buildQueue.isEmpty();
    }

    public Vector3i aiNextBuildTarget() {
        BuildTask next = buildQueue.peek();
        return next == null ? null : new Vector3i(next.position);
    }

    private void applyEmoteOverlay() {
        if (!emotePlayer.isActive()) return;

        float raise = emotePlayer.armRaise();
        if (raise > 0f) {
            if (armConnector != null) armConnector.rotation.set(0, 0, 0);
            if (leftArm != null) leftArm.rotation.set(-110f * raise, 0, 18f * raise);
            if (rightArm != null) rightArm.rotation.set(-110f * raise, 0, -18f * raise);
        }

        float cower = emotePlayer.cowerAmount();
        if (cower > 0f && head != null) {
            head.rotation.x += cower * 22f;
        }

        float nod = emotePlayer.nodPhase();
        if (nod != 0f && head != null) {
            head.rotation.x += nod * 12f;
        }
        float shake = emotePlayer.shakePhase();
        if (shake != 0f && head != null) {
            head.rotation.y += shake * 14f;
        }

        float reach = emotePlayer.armReachSwing();
        if (reach > 0f && rightArm != null) {
            if (emotePlayer.isPlaying(Emote.POINT)) {
                rightArm.rotation.set(-85f, 0, 0);
                if (emotePlayer.hasPointTarget()) {
                    Vector3f t = emotePlayer.pointTarget();
                    float relYaw = GazeController.wrap180(
                            (float) Math.toDegrees(Math.atan2(t.x - getPosX(), t.z - getPosZ()))
                                    - rotation.y);
                    relYaw = Math.max(-60f, Math.min(60f, relYaw));
                    rightArm.rotation.y = relYaw;
                }
            } else {
                rightArm.rotation.set(-40f - reach * 55f, 0, 0);
            }
        }

        if (emotePlayer.consumeHop()) {
            addPosition(0, 0.22f, 0);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════

    public void setWorld(World w) { this.world = w; }
    public boolean isMoving() { return isMoving; }
    public boolean isWillingToMate() { return isWillingToMate; }
    public void setWillingToMate(boolean w) { this.isWillingToMate = w; mateCooldown = w ? 0 : 200; }
    public int getCareerLevel() { return careerLevel; }

    /** Get the channel display text for rendering the TV screen UI. */
    public static String getTVDisplayForChannel(int channel, float worldTime, com.voxel.game.VillagerTVSystem tvSystem) {
        if (tvSystem == null) return "No Signal";
        return tvSystem.getChannelDisplay(channel, worldTime);
    }
}
