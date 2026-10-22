package com.voxel.ai;

import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.List;

/**
 * Spatial reasoning for mobs and villagers: the part an LLM does badly because
 * it cannot see the world. Everything here reads voxels directly, so a brain
 * can stay comedically dumb about <em>what</em> it wants while the body stays
 * competent about <em>how</em> to get there.
 *
 * <p>Rules of thumb encoded here:</p>
 * <ul>
 *   <li>A step is safe when there is solid ground under it, two blocks of
 *   clearance, and no drop deeper than {@link #MAX_SAFE_DROP}.</li>
 *   <li>An escape cell is scored by distance from the threat, how much open
 *   ground surrounds it (dead ends lose), and whether it is reachable.</li>
 *   <li>Cover is a cell from which the threat has no line of sight.</li>
 *   <li>Crowds separate instead of stacking on the same cell.</li>
 * </ul>
 *
 * <p>Pure static math over {@link VoxelView} plus JOML vectors: no entity
 * state, no ticking, no rendering, so it is unit testable against a small
 * handcrafted grid.</p>
 */
public final class SpatialMind {

    /** Anything deeper than this counts as a cliff, not a step. */
    public static final int MAX_SAFE_DROP = 3;

    /** Body height used for clearance checks. */
    public static final int BODY_HEIGHT = 2;

    private SpatialMind() {
    }

    /** Solid ground under the cell, room for the body, no liquid at the feet. */
    public static boolean isSafeStand(VoxelView view, int x, int y, int z) {
        if (view == null) return false;
        if (view.getVoxel(x, y - 1, z) == 0) return false;
        for (int dy = 0; dy < BODY_HEIGHT; dy++) {
            if (view.getVoxel(x, y + dy, z) != 0) return false;
        }
        // Standing in a fluid is not standing.
        if (view.getVoxel(x, y, z) != 0) return false;
        return true;
    }

    /** True when the drop below this cell is too deep to step off. */
    public static boolean isCliff(VoxelView view, int x, int y, int z) {
        if (view == null) return true;
        for (int depth = 1; depth <= MAX_SAFE_DROP; depth++) {
            if (view.getVoxel(x, y - depth, z) != 0) return false;
        }
        return true;
    }

    /**
     * Openness of a cell: how many of its 8 horizontal neighbours are safe
     * standing cells. A dead end scores 1, open ground scores 8.
     */
    public static int openness(VoxelView view, int x, int y, int z) {
        int open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (isSafeStand(view, x + dx, y, z + dz)) open++;
            }
        }
        return open;
    }

    /**
     * Best cell to run to from {@code self} when {@code threat} is closing in:
     * farther from the threat, preferably open ground, never a dead end. The
     * search is a small deterministic ring around the entity, so fleeing is
     * cheap and does not need a full A*.
     *
     * @return a standing cell to run to, or null when nothing nearby is safe
     */
    public static Vector3i escapeTarget(VoxelView view, Vector3f self,
                                        Vector3f threat, int radius) {
        if (view == null || self == null) return null;
        int originX = floor(self.x);
        int originY = floor(self.y);
        int originZ = floor(self.z);
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean found = false;
        int r = Math.max(1, radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int x = originX + dx;
                    int y = originY + dy;
                    int z = originZ + dz;
                    if (!isSafeStand(view, x, y, z)) continue;
                    if (isCliff(view, x, y, z)) continue;
                    double fromThreat = threat == null ? 0.0
                            : Math.sqrt(sq(x + 0.5 - threat.x, z + 0.5 - threat.z));
                    double openness = openness(view, x, y, z);
                    double score = fromThreat * 1.5 + openness * 0.6
                            - Math.abs(dy) * 0.5;
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestY = y;
                        bestZ = z;
                        found = true;
                    }
                }
            }
        }
        return found ? new Vector3i(bestX, bestY, bestZ) : null;
    }

    /**
     * A cell near {@code self} that breaks the threat's line of sight, or null
     * when there is no cover in range. Prefers cover that stays close, so the
     * mob does not abandon the fight to sprint across the field.
     */
    public static Vector3i coverCell(VoxelView view, Vector3f self,
                                     Vector3f threat, int radius) {
        if (view == null || self == null || threat == null) return null;
        int originX = floor(self.x);
        int originY = floor(self.y);
        int originZ = floor(self.z);
        Vector3i best = null;
        double bestDistance = Double.MAX_VALUE;
        int r = Math.max(1, radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = originX + dx;
                    int y = originY + dy;
                    int z = originZ + dz;
                    if (!isSafeStand(view, x, y, z)) continue;
                    boolean blocked = !Raycaster.lineOfSight(view,
                            x + 0.5f, y + 1.2f, z + 0.5f,
                            threat.x, threat.y + 1.2f, threat.z);
                    if (!blocked) continue;
                    double distance = sq(x + 0.5 - self.x, z + 0.5 - self.z);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new Vector3i(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    /**
     * A cell to the side of the target, for a hunter that wants an angle rather
     * than a straight line into the player's face.
     *
     * @param side  -1 or +1 picks the perpendicular; 0 picks the roomier one
     */
    public static Vector3i flankCell(VoxelView view, Vector3f self, Vector3f target,
                                     int side, int distance) {
        if (view == null || self == null || target == null) return null;
        double dx = target.x - self.x;
        double dz = target.z - self.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) return null;
        double px = -dz / length;
        double pz = dx / length;
        int chosen = side;
        if (chosen == 0) {
            chosen = openness(view, floor(target.x + px * distance),
                    floor(target.y), floor(target.z + pz * distance))
                    >= openness(view, floor(target.x - px * distance),
                    floor(target.y), floor(target.z - pz * distance)) ? 1 : -1;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            double scale = distance * (1.0 - attempt * 0.3);
            int x = floor(target.x + px * scale * chosen);
            int z = floor(target.z + pz * scale * chosen);
            for (int dy = 1; dy >= -2; dy--) {
                int y = floor(target.y) + dy;
                if (isSafeStand(view, x, y, z) && !isCliff(view, x, y, z)) {
                    return new Vector3i(x, y, z);
                }
            }
        }
        return null;
    }

    /**
     * Push vector that keeps a mob out of its neighbours' space without
     * breaking the shape of the group. Zero when nobody is close.
     */
    public static Vector3f separation(Vector3f self, List<Vector3f> others,
                                      float desired) {
        Vector3f push = new Vector3f();
        if (self == null || others == null || desired <= 0f) return push;
        for (Vector3f other : others) {
            if (other == null) continue;
            float dx = self.x - other.x;
            float dz = self.z - other.z;
            float distanceSq = dx * dx + dz * dz;
            if (distanceSq <= 1.0e-6f || distanceSq > desired * desired) continue;
            float distance = (float) Math.sqrt(distanceSq);
            float strength = (desired - distance) / desired;
            push.x += dx / distance * strength;
            push.z += dz / distance * strength;
        }
        float lengthSq = push.x * push.x + push.z * push.z;
        if (lengthSq > 1f) {
            float length = (float) Math.sqrt(lengthSq);
            push.x /= length;
            push.z /= length;
        }
        return push;
    }

    /** Which way around an obstacle to go, so two mobs do not mirror each other. */
    public static int sideFor(int seed) {
        return (seed & 1) == 0 ? 1 : -1;
    }

    private static int floor(float value) {
        return (int) Math.floor(value);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static double sq(double x, double z) {
        return x * x + z * z;
    }
}
