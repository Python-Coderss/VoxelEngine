package com.voxel.entity;

import com.voxel.Player;
import com.voxel.World;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import java.util.*;

/**
 * EnemyEntity - Omnipotent beings confined to a physical body.
 * Java 8 compatible + Anti-phasing fixes
 */
public class EnemyEntity extends Entity {

    private ModelPart head, leftArm, rightArm, leftLeg, rightLeg;

    protected float animTime = 0.0f;
    private float health = 20.0f;
    private float maxHealth = 20.0f;
    private boolean isDead = false;
    public float hitFlashTime = 0.0f;
    private float windUpTime = 0.0f;
    private boolean isWindingUp = false;

    public static EntityManager entityManager;
    public static void setEntityManager(EntityManager em) { entityManager = em; }

    public World world;

    private enum State { OBSERVING, PREDICTING, HUNTING, ATTACKING }
    private State state = State.OBSERVING;

    private List<Vector3i> path = new ArrayList<>();
    private int pathIndex = 0;
    private long lastPathfindTime = 0;

    private Vector3f lastKnownPlayerPos = new Vector3f();
    private Vector3f predictedPlayerPos = new Vector3f();

    private long lastSawPlayerTime = 0;
    private float attackCooldown = 0.0f;
    private float frustration = 0.0f;

    private Vector3f prevPosition = new Vector3f();
    private float lastBob = 0.0f;

    public Player player;

    // ==================== DEBUG ====================
    private static final boolean DEBUG_AI = false;

    protected EnemyEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p) {
        super(id, position);

        loadModel("src/main/resources/assets/minecraft/models/entity/zombie.json", textureManager);

        for (ModelPart part : parts) {
            if (part.name.equals("head")) head = part;
            else if (part.name.equals("left_arm")) leftArm = part;
            else if (part.name.equals("right_arm")) rightArm = part;
            else if (part.name.equals("left_leg")) leftLeg = part;
            else if (part.name.equals("right_leg")) rightLeg = part;
        }
        this.player = p;
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead) return;

        attackCooldown = Math.max(0, attackCooldown - dt);
        frustration = Math.max(0, frustration - dt * 0.25f);

        Vector3f velocity = new Vector3f(position).sub(prevPosition);
        float speed = velocity.length() / Math.max(dt, 0.00001f);
        prevPosition.set(position);

        animTime += dt * Math.max(0.9f, speed);

        float walkIntensity = Math.min(1.0f, speed * 0.24f);

        float bob = (float) Math.sin(animTime * 7.0f) * 0.092f * walkIntensity;
        position.y -= lastBob;
        position.y += bob;
        lastBob = bob;

        rotation.z = (float) Math.sin(animTime * 6.2f) * 4.8f * walkIntensity;

        if (hitFlashTime > 0) hitFlashTime -= dt;
    }

    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (isDead || world == null) return;

        float distance = position.distance(playerPos);

        if (DEBUG_AI) {
            System.out.printf("[AI] Entity(%.1f, %.1f, %.1f) | Player(%.1f, %.1f, %.1f) | Dist: %.2f | State: %s%n",
                    position.x, position.y, position.z,
                    playerPos.x, playerPos.y, playerPos.z,
                    distance, state);
        }

        // Update last known player position
        if (distance < 26f) {
            lastKnownPlayerPos.set(playerPos);
            lastSawPlayerTime = System.currentTimeMillis();

            if (playerVelocity != null) {
                predictedPlayerPos.set(playerPos).add(new Vector3f(playerVelocity).mul(0.75f));
            } else {
                predictedPlayerPos.set(playerPos);
            }

            if (DEBUG_AI) {
                System.out.printf("[AI] Saw player → Predicted: (%.1f, %.1f, %.1f)%n",
                        predictedPlayerPos.x, predictedPlayerPos.y, predictedPlayerPos.z);
            }
        }

        // Determine state
        State newState;
        if (distance < 2.7f) {
            newState = State.ATTACKING;
        } else if (distance < 20f || (System.currentTimeMillis() - lastSawPlayerTime) < 7000) {
            newState = State.HUNTING;
        } else {
            newState = State.PREDICTING;
        }

        if (newState != state) {
            if (DEBUG_AI) System.out.println("[AI] State changed: " + state + " → " + newState);
            state = newState;
        }

        switch (state) {
            case PREDICTING:
                observeAndPredict(dt);
                break;
            case HUNTING:
                hunt(dt);
                break;
            case ATTACKING:
                attackPlayer(playerPos, dt);
                break;
        }
    }

    public void updateAI(Vector3f playerPos, float dt) {
        updateAI(playerPos, player.getVelocity(), dt);
    }

    private void observeAndPredict(float dt) {
        if (DEBUG_AI) System.out.println("[AI] PREDICTING - moving toward predicted position");
        moveToward(predictedPlayerPos, dt, 1.45f);
        frustration += dt * 0.35f;
    }

    private void hunt(float dt) {
        long now = System.currentTimeMillis();

        boolean shouldRepath = path.isEmpty() || pathIndex >= path.size() || now - lastPathfindTime > 420;

        if (DEBUG_AI && shouldRepath) {
            System.out.printf("[AI] Repathing triggered | Empty: %b | Index: %d/%d | Time: %dms%n",
                    path.isEmpty(), pathIndex, path.size(), now - lastPathfindTime);
        }

        if (shouldRepath) {
            path = findPath(this.position, predictedPlayerPos);
            pathIndex = 0;
            lastPathfindTime = now;

            if (DEBUG_AI) {
                System.out.println("[AI] Pathfinding complete. Path length: " + path.size());
                if (!path.isEmpty()) {
                    Vector3i start = path.get(0);
                    Vector3i end = path.get(path.size() - 1);
                    System.out.printf("[AI] Path: (%d,%d,%d) ... → (%d,%d,%d)%n",
                            start.x, start.y, start.z, end.x, end.y, end.z);
                }
            }
        }

        if (!path.isEmpty() && pathIndex < path.size()) {
            followPath(dt);
        } else {
            if (DEBUG_AI) System.out.println("[AI] No valid path - falling back to direct movement");
            moveToward(predictedPlayerPos, dt, 2.4f);
        }
    }

    private void attackPlayer(Vector3f playerPos, float dt) {
        if (DEBUG_AI) System.out.println("[AI] ATTACKING");

        Vector3f toPlayer = new Vector3f(playerPos).sub(position);
        float dist = toPlayer.length();

        if (dist > 0.01f) {
            rotation.y = (float) Math.toDegrees(Math.atan2(toPlayer.x, toPlayer.z));
        }

        if (attackCooldown <= 0 && dist < 3.1f) {
            if (!isWindingUp) {
                // Start wind-up: telegraph the attack
                isWindingUp = true;
                windUpTime = 0.0f;
                hitFlashTime = 0.01f;
            } else {
                windUpTime += dt;
                // Telegraph glow: white → yellow → red over 0.75s
                float telegraphProgress = Math.min(1.0f, windUpTime / 0.75f);
                hitFlashTime = 0.5f + telegraphProgress * 0.5f; // Flash intensity
                
                if (windUpTime >= 0.75f) {
                    // Attack!
                    performAttack(playerPos);
                    attackCooldown = 1.4f;
                    frustration += 1.0f;
                    isWindingUp = false;
                    windUpTime = 0.0f;
                    hitFlashTime = 0.0f;
                }
            }
        } else {
            isWindingUp = false;
            windUpTime = 0.0f;
            if (hitFlashTime <= 0) hitFlashTime = 0;
        }

        if (dist < 1.5f) {
            toPlayer.normalize().mul(-dt * 3.2f);
            tryMove(toPlayer.x, 0, toPlayer.z);
        }
    }

    public void performAttack(Vector3f playerPos) {
        if (DEBUG_AI) System.out.println("The confined entity strikes with unnatural hatred!");
        if (player != null) {
            player.takeDamage(2.0f); // 2 HP damage per strike
        }
    }

    // ====================== MOVEMENT ======================

    protected void moveToward(Vector3f target, float dt, float speed) {
        Vector3f dir = new Vector3f(target).sub(position);
        // Allow some vertical direction, but collision will keep us grounded
        float len = dir.length();

        if (DEBUG_AI) {
            System.out.printf("[MOVE] Target(%.1f,%.1f,%.1f) Dist=%.2f Speed=%.2f%n",
                    target.x, target.y, target.z, len, speed);
        }

        if (len > 0.35f) {
            dir.normalize().mul(speed * dt);
            tryMove(dir.x, dir.y, dir.z);

            if (new Vector2f(dir.x, dir.z).length() > 0.001f) {
                rotation.y = (float) Math.toDegrees(Math.atan2(dir.x, dir.z));
            }
        }
    }

    private void followPath(float dt) {
        Vector3i targetNode = path.get(Math.min(pathIndex, path.size() - 1));
        // Target the center of the node, but keep Y as the base
        Vector3f targetPos = new Vector3f(targetNode.x + 0.5f, (float)targetNode.y, targetNode.z + 0.5f);

        if (DEBUG_AI) {
            System.out.printf("[PATH] Following node %d/%d → (%.1f, %.1f, %.1f)%n",
                    pathIndex, path.size(), targetPos.x, targetPos.y, targetPos.z);
        }

        moveToward(targetPos, dt, 2.45f);

        // Distance check in 2D (XZ) is often more reliable for pathing
        float distSq = new Vector2f(position.x - targetPos.x, position.z - targetPos.z).lengthSquared();
        if (distSq < 0.36f && Math.abs(position.y - targetPos.y) < 1.1f) {
            if (DEBUG_AI) System.out.println("[PATH] Reached node " + pathIndex);
            pathIndex++;
        }
    }

    private void tryMove(float dx, float dy, float dz) {
        if (DEBUG_AI) {
            System.out.printf("[TRY MOVE] dx=%.3f, dy=%.3f, dz=%.3f | Current(%.2f,%.2f,%.2f)%n",
                    dx, dy, dz, position.x, position.y, position.z);
        }

        // 1. Try full move
        if (canOccupy(position.x + dx, position.y + dy, position.z + dz) && !isCollidingWithOtherEntities(position.x + dx, position.y + dy, position.z + dz)) {
            position.add(dx, dy, dz);
            if (DEBUG_AI) System.out.println("[TRY MOVE] Full move SUCCESS");
            return;
        }

        // 2. Auto-step UP
        if (canOccupy(position.x + dx, position.y + 1.0f, position.z + dz) && !isCollidingWithOtherEntities(position.x + dx, position.y + 1.0f, position.z + dz)) {
            position.add(dx, 1.0f, dz);
            if (DEBUG_AI) System.out.println("[TRY MOVE] Auto-step UP success");
            return;
        }

        // 3. Auto-step DOWN
        if (canOccupy(position.x + dx, position.y - 1.0f, position.z + dz) && !isCollidingWithOtherEntities(position.x + dx, position.y - 1.0f, position.z + dz)) {
            position.add(dx, -1.0f, dz);
            if (DEBUG_AI) System.out.println("[TRY MOVE] Auto-step DOWN success");
            return;
        }

        if (DEBUG_AI) System.out.println("[TRY MOVE] Collision detected - trying X/Z separately");

        // 4. Try X only
        if (canOccupy(position.x + dx, position.y, position.z) && !isCollidingWithOtherEntities(position.x + dx, position.y, position.z)) {
            position.x += dx;
            if (DEBUG_AI) System.out.println("[TRY MOVE] X move accepted");
        }

        // 5. Try Z only
        if (canOccupy(position.x, position.y, position.z + dz) && !isCollidingWithOtherEntities(position.x, position.y, position.z + dz)) {
            position.z += dz;
            if (DEBUG_AI) System.out.println("[TRY MOVE] Z move accepted");
        }
    }

    private boolean isCollidingWithOtherEntities(float x, float y, float z) {
        if (entityManager == null) return false;
        Vector3f target = new Vector3f(x, y, z);
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            Entity other = entityManager.getEntity(i);
            if (other == null || other == this || other.position == null) continue;
            // Ignore dead enemies
            if (other instanceof EnemyEntity && ((EnemyEntity)other).isDead()) continue;
            
            float distSq = target.distanceSquared(other.position);
            if (distSq < 0.36f) return true; // Collision radius of 0.6 blocks
        }
        return false;
    }

    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }

    private boolean canOccupy(float x, float y, float z) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y + 0.1f); // Add epsilon to handle bobbing/jitter
        int iz = (int) Math.floor(z);

        // Simplification: isWalkable already checks ground, feet, and head.
        boolean walkable = isWalkable(ix, iy, iz);

        if (DEBUG_AI) {
            System.out.printf("[CAN OCCUPY] (%d,%d,%d) -> %b%n", ix, iy, iz, walkable);
        }
        return walkable;
    }

    // ====================== PATHFINDING ======================

    private List<Vector3i> findPath(Vector3f start, Vector3f goal) {
        if (world == null) {
            if (DEBUG_AI) System.out.println("[PATHFIND] ERROR: World is null");
            return Collections.emptyList();
        }

        // Apply epsilon to Y coordinates to ensure we are checking the correct block level
        int sx = (int) Math.floor(start.x), sy = (int) Math.floor(start.y + 0.1f), sz = (int) Math.floor(start.z);
        int gx = (int) Math.floor(goal.x), gy = (int) Math.floor(goal.y + 0.1f), gz = (int) Math.floor(goal.z);

        if (DEBUG_AI) {
            System.out.printf("[PATHFIND] Start(%d,%d,%d) → Goal(%d,%d,%d)%n", sx, sy, sz, gx, gy, gz);
        }

        PriorityQueue<Node> open = new PriorityQueue<>(new Comparator<Node>() {
            public int compare(Node n1, Node n2) {
                return Double.compare(n1.f, n2.f);
            }
        });

        Set<Vector3i> closed = new HashSet<>();
        Map<Vector3i, Vector3i> cameFrom = new HashMap<>();

        Node startNode = new Node(sx, sy, sz, 0, heuristic(sx, sy, sz, gx, gy, gz));
        open.add(startNode);

        int nodesSearched = 0;
        final int MAX_NODES = 500; // Increased search limit for 3D

        while (!open.isEmpty() && nodesSearched < MAX_NODES) {
            Node current = open.poll();
            Vector3i cpos = new Vector3i(current.x, current.y, current.z);

            if (Math.abs(current.x - gx) <= 1 && Math.abs(current.z - gz) <= 1 &&
                Math.abs(current.y - gy) <= 1) { // Tightened Y tolerance
                List<Vector3i> result = reconstructPath(cameFrom, cpos);
                if (DEBUG_AI) System.out.println("[PATHFIND] Path found successfully! Length: " + result.size());
                return result;
            }

            closed.add(cpos);
            nodesSearched++;

            for (int[] dir : NEIGHBOR_DIRECTIONS) {
                int nx = current.x + dir[0];
                int ny = current.y + dir[1];
                int nz = current.z + dir[2];

                Vector3i neighbor = new Vector3i(nx, ny, nz);
                if (closed.contains(neighbor) || !isWalkable(nx, ny, nz)) continue;

                double g = current.g + ((dir[0] != 0 && dir[2] != 0) ? 1.414 : 1.0);
                if (dir[1] != 0) g += 0.5; // Slight penalty for vertical movement
                
                double h = heuristic(nx, ny, nz, gx, gy, gz);
                Node next = new Node(nx, ny, nz, g, h);

                open.add(next);
                cameFrom.put(neighbor, cpos);
            }
        }

        if (DEBUG_AI) System.out.println("[PATHFIND] No path found or search limit reached");
        return Collections.emptyList();
    }

    private List<Vector3i> reconstructPath(Map<Vector3i, Vector3i> cameFrom, Vector3i end) {
        List<Vector3i> pathList = new ArrayList<>();
        Vector3i current = end;
        while (current != null) {
            pathList.add(current);
            current = cameFrom.get(current);
        }
        Collections.reverse(pathList);
        return pathList;
    }

    private static final int[][] NEIGHBOR_DIRECTIONS = {
            {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}, // Horizontal
            {1,0,1}, {1,0,-1}, {-1,0,1}, {-1,0,-1}, // Diagonal
            {1,1,0}, {-1,1,0}, {0,1,1}, {0,1,-1}, // Step up
            {1,-1,0}, {-1,-1,0}, {0,-1,1}, {0,-1,-1} // Step down
    };

    private boolean isWalkable(int x, int y, int z) {
        if (world.getVoxel(x, y - 1, z) == 0) return false; // no ground
        if (world.getVoxel(x, y, z) != 0) return false;     // feet blocked
        if (world.getVoxel(x, y + 1, z) != 0) return false; // head blocked
        return true;
    }

    private double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(y - gy) + Math.abs(z - gz);
    }

    public void takeDamage(float amount, Vector3f knockback) {
        if (isDead) return;

        health -= amount;
        position.add(knockback);
        hitFlashTime = 0.35f;
        frustration += 3.0f;

        if (DEBUG_AI) System.out.printf("[DAMAGE] Took %.1f damage. Remaining health: %.1f%n", amount, health);

        if (health <= 0) {
            die();
        }
    }

    public void die() {
        isDead = true;
        rotation.x = 78.0f;
        if (DEBUG_AI) System.out.println("The omnipotent being is released from its fleshly cage...");
    }

    public boolean isDead() { return isDead; }
    public void setWorld(World world) { this.world = world; }

    private static class Node {
        int x, y, z;
        double g, h, f;

        Node(int x, int y, int z, double g, double h) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.h = h;
            this.f = g + h;
        }
    }
}
