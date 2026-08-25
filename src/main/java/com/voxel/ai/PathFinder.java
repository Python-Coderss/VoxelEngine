package com.voxel.ai;

import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Budget-capped 3D A* over voxel cells. Same neighbor set, costs, heuristic,
 * goal tolerance and default 500-node budget as the original EnemyEntity
 * pathfinder so existing mob behavior is unchanged.
 */
public final class PathFinder {

    public static final int DEFAULT_MAX_NODES = 500;

    private static final int[][] NEIGHBOR_DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1}
    };

    private static final double DIAGONAL_COST = 1.414;
    private static final double VERTICAL_COST = 0.5;

    private PathFinder() {
    }

    /** Convenience entry matching the legacy EnemyEntity call signature. */
    public static List<Vector3i> findPath(VoxelView view,
                                          float startX, float startY, float startZ,
                                          float goalX, float goalY, float goalZ) {
        return findPath(view, Walkability.HUMANOID,
                floor(startX), floor(startY + 0.1f), floor(startZ),
                floor(goalX), floor(goalY + 0.1f), floor(goalZ),
                DEFAULT_MAX_NODES);
    }

    /**
     * Find a path, or an empty list when the goal is sealed or the node budget
     * runs out. The open queue may hold stale duplicates (lazy deletion); a
     * node is only expanded while it is still the best known way to reach its
     * cell, and a cell re-reached through a strictly cheaper route is
     * re-opened. cameFrom can therefore never be overwritten by a worse
     * predecessor (which made a plain closed-set search produce suboptimal
     * routes and redundant expansions).
     */
    public static List<Vector3i> findPath(VoxelView view, Walkability walkability,
                                          int sx, int sy, int sz,
                                          int gx, int gy, int gz,
                                          int maxNodes) {
        if (view == null || walkability == null) return Collections.emptyList();

        PriorityQueue<Node> open = new PriorityQueue<>(new Comparator<Node>() {
            @Override
            public int compare(Node n1, Node n2) {
                return Double.compare(n1.f, n2.f);
            }
        });

        Map<Vector3i, Double> bestG = new HashMap<>();
        Map<Vector3i, Vector3i> cameFrom = new HashMap<>();

        Vector3i start = new Vector3i(sx, sy, sz);
        bestG.put(new Vector3i(start), 0.0);
        open.add(new Node(sx, sy, sz, 0, heuristic(sx, sy, sz, gx, gy, gz)));

        int nodesSearched = 0;
        while (!open.isEmpty() && nodesSearched < maxNodes) {
            Node current = open.poll();
            Vector3i cpos = new Vector3i(current.x, current.y, current.z);
            Double best = bestG.get(cpos);
            if (best == null || current.g > best.doubleValue() + 1.0e-9) {
                // Stale duplicate: a cheaper route to this cell was found after
                // this entry was queued, so it must not be expanded.
                continue;
            }

            if (Math.abs(current.x - gx) <= 1 && Math.abs(current.z - gz) <= 1
                    && Math.abs(current.y - gy) <= 1) {
                return reconstructPath(cameFrom, cpos);
            }

            nodesSearched++;

            for (int[] dir : NEIGHBOR_DIRECTIONS) {
                int nx = current.x + dir[0];
                int ny = current.y + dir[1];
                int nz = current.z + dir[2];

                Vector3i neighbor = new Vector3i(nx, ny, nz);
                if (!walkability.isWalkable(view, nx, ny, nz)) continue;

                double g = current.g + ((dir[0] != 0 && dir[2] != 0) ? DIAGONAL_COST : 1.0);
                if (dir[1] != 0) g += VERTICAL_COST;

                Double previous = bestG.get(neighbor);
                if (previous != null && g >= previous.doubleValue() - 1.0e-9) {
                    continue;
                }
                bestG.put(neighbor, g);
                cameFrom.put(neighbor, cpos);
                open.add(new Node(nx, ny, nz, g, heuristic(nx, ny, nz, gx, gy, gz)));
            }
        }
        return Collections.emptyList();
    }

    private static List<Vector3i> reconstructPath(Map<Vector3i, Vector3i> cameFrom, Vector3i end) {
        List<Vector3i> pathList = new ArrayList<>();
        Vector3i current = end;
        while (current != null) {
            pathList.add(current);
            current = cameFrom.get(current);
        }
        Collections.reverse(pathList);
        return pathList;
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(y - gy) + Math.abs(z - gz);
    }

    private static int floor(float v) {
        return (int) Math.floor(v);
    }

    private static final class Node {
        final int x, y, z;
        final double g, h, f;

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