package com.voxel.entity;

import com.voxel.Player;
import com.voxel.World;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Basic enemy entity with simple AI and Story Mode style animations.
 * Now uses the same humanoid model as the player.
 */
public class EnemyEntity extends Entity {
    private ModelPart head;
    private ModelPart leftArm, rightArm;
    private ModelPart leftLeg, rightLeg;
    protected float animTime = 0.0f;
    private float health = 20.0f;
    private boolean isDead = false;
    private float hitFlashTime = 0.0f;

    private World world;
    private List<Vector3i> path = new ArrayList<>();
    private int pathIndex = 0;
    private long lastPathfindTime = 0;

    protected EnemyEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        
        // Load dedicated zombie model (identical to player except uses zombie_skin texture)
        loadModel("src/main/resources/assets/minecraft/models/entity/zombie.json", textureManager);

        // Cache parts for animation
        for (ModelPart part : parts) {
            if (part.name.equals("head")) head = part;
            else if (part.name.equals("left_arm")) leftArm = part;
            else if (part.name.equals("right_arm")) rightArm = part;
            else if (part.name.equals("left_leg")) leftLeg = part;
            else if (part.name.equals("right_leg")) rightLeg = part;
        }
    }

    private float lastBob = 0.0f;
    private Vector3f prevPosition = new Vector3f();

    @Override
    public void update(float dt) {
    	super.update(dt);
        if (isDead) return;

        // Calculate movement speed for responsive animation
        Vector3f velocity = new Vector3f(position).sub(prevPosition);
        float speed = velocity.length() / Math.max(dt, 0.00001f);
        prevPosition.set(position);

        animTime += dt * Math.max(0.6f, speed);

        float walkIntensity = Math.min(1.0f, speed * 0.18f);


        // Bobbing + subtle body tilt
        float bob = (float) Math.abs(Math.sin(animTime * 5.5f)) * 0.09f * walkIntensity;
        position.y -= lastBob;
        position.y += bob;
        lastBob = bob;

        rotation.z = (float) Math.sin(animTime * 5.0f) * 3.5f * walkIntensity;

        if (hitFlashTime > 0) hitFlashTime -= dt;
    }

    public void takeDamage(float amount, Vector3f knockback) {
        if (isDead) return;

        health -= amount;
        position.add(knockback);
        hitFlashTime = 0.3f;

        // Per-enemy hit reaction
        onHit();

        if (health <= 0) {
            die();
        }
    }

    protected void onHit() {
        // Default hit behavior - override in subclasses for custom effects
    }

    public void performAttack(Player player) {
        // Default enemy attack behavior - override per enemy type
        if (player != null && !isDead) {
            Vector3f toPlayer = new Vector3f(player.getPosition()).sub(this.position);
            if (toPlayer.length() < 2.5f) {
                // TODO: Deal damage to player
                System.out.println("Enemy attacked player!");
            }
        }
    }

    private void die() {
        isDead = true;
        rotation.x = 90.0f; // Fall over
        // position.y -= 0.3f;
    }

    public boolean isDead() { return isDead; }

    public void setWorld(World world) {
        this.world = world;
    }

    public void updateAI(Vector3f playerPos, float dt) {
        if (isDead || world == null) return;

        long now = System.currentTimeMillis();

        // Recalculate path every 800ms or when path is empty
        if (path.isEmpty() || now - lastPathfindTime > 800) {
            path = findPath(this.position, playerPos);
            pathIndex = 0;
            lastPathfindTime = now;
        }

        if (path.isEmpty() || pathIndex >= path.size()) {
            // Fallback: direct movement if no path
            Vector3f toPlayer = new Vector3f(playerPos).sub(this.position);
            if (toPlayer.length() > 1.5f && toPlayer.length() < 25f) {
                toPlayer.normalize().mul(dt * 2.0f);
                this.position.add(toPlayer.x, 0, toPlayer.z);
                this.rotation.y = (float) Math.toDegrees(Math.atan2(toPlayer.x, toPlayer.z));
            }
            return;
        }

        // Follow current path node
        Vector3i target = path.get(Math.min(pathIndex, path.size() - 1));
        Vector3f targetPos = new Vector3f(target.x + 0.5f, target.y, target.z + 0.5f);

        Vector3f toTarget = new Vector3f(targetPos).sub(this.position);
        float dist = toTarget.length();

        if (dist < 0.6f) {
            pathIndex++;
        } else {
            toTarget.normalize().mul(dt * 2.3f);
            this.position.add(toTarget.x, 0, toTarget.z);
            this.rotation.y = (float) Math.toDegrees(Math.atan2(toTarget.x, toTarget.z));
        }
    }

    private boolean isWalkable(int x, int y, int z) {
        if (world == null) return true;

        // Check two blocks high (zombie height) + ground below
        boolean ground = world.getVoxel(x, y - 1, z) != 0;           // solid block below
        boolean feet   = world.getVoxel(x, y,     z) == 0;           // air at feet
        boolean head   = world.getVoxel(x, y + 1, z) == 0;           // air at head

        return ground && feet && head;
    }

    private List<Vector3i> findPath(Vector3f start, Vector3f goal) {
        if (world == null) return Collections.emptyList();

        int sx = (int) Math.floor(start.x);
        int sy = (int) Math.floor(start.y);
        int sz = (int) Math.floor(start.z);

        int gx = (int) Math.floor(goal.x);
        int gy = (int) Math.floor(goal.y);
        int gz = (int) Math.floor(goal.z);

        // Simple A* for short range
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Set<Vector3i> closed = new HashSet<>();

        Node startNode = new Node(sx, sy, sz, 0, heuristic(sx, sy, sz, gx, gy, gz));
        open.add(startNode);

        java.util.Map<Vector3i, Vector3i> cameFrom = new java.util.HashMap<>();

        int maxNodes = 200; // limit search
        int count = 0;

        while (!open.isEmpty() && count < maxNodes) {
            Node current = open.poll();
            Vector3i cpos = new Vector3i(current.x, current.y, current.z);

            if (current.x == gx && current.z == gz && Math.abs(current.y - gy) <= 1) {
                // Reconstruct path
                List<Vector3i> path = new ArrayList<>();
                Vector3i curr = cpos;
                while (curr != null) {
                    path.add(curr);
                    curr = cameFrom.get(curr);
                }
                Collections.reverse(path);
                return path;
            }

            closed.add(cpos);
            count++;

            // 4 directions (grid movement)
            int[][] dirs = {{1,0}, {-1,0}, {0,1}, {0,-1}};
            for (int[] d : dirs) {
                int nx = current.x + d[0];
                int nz = current.z + d[1];
                int ny = current.y;

                Vector3i neighbor = new Vector3i(nx, ny, nz);
                if (closed.contains(neighbor)) continue;
                if (!isWalkable(nx, ny, nz)) continue;

                double g = current.g + 1;
                double h = heuristic(nx, ny, nz, gx, gy, gz);
                Node next = new Node(nx, ny, nz, g, h);

                open.add(next);
                cameFrom.put(neighbor, cpos);
            }
        }

        return Collections.emptyList();
    }

    private double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(z - gz) + Math.abs(y - gy) * 0.5;
    }

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
