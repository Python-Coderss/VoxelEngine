package com.voxel.ai;

/**
 * Amanatides &amp; Woo voxel grid traversal used for line-of-sight checks.
 */
public final class Raycaster {

    private Raycaster() {
    }

    /**
     * @return true when the segment from a to b crosses no non-air voxel,
     *         endpoints excluded (the source and target cells never block).
     */
    public static boolean lineOfSight(VoxelView view,
                               float ax, float ay, float az,
                               float bx, float by, float bz) {
        int x = floor(ax), y = floor(ay), z = floor(az);
        final int endX = floor(bx), endY = floor(by), endZ = floor(bz);

        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 1e-8f) return true;
        float len = (float) Math.sqrt(lenSq);
        dx /= len; dy /= len; dz /= len;

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        float tDeltaX = stepX != 0 ? Math.abs(1.0f / dx) : Float.MAX_VALUE;
        float tDeltaY = stepY != 0 ? Math.abs(1.0f / dy) : Float.MAX_VALUE;
        float tDeltaZ = stepZ != 0 ? Math.abs(1.0f / dz) : Float.MAX_VALUE;

        float tMaxX = boundaryT(ax, dx, stepX);
        float tMaxY = boundaryT(ay, dy, stepY);
        float tMaxZ = boundaryT(az, dz, stepZ);

        float t = 0.0f;
        while (t <= len) {
            if (!(x == floor(ax) && y == floor(ay) && z == floor(az))
                    && !(x == endX && y == endY && z == endZ)
                    && !view.isAir(x, y, z)) {
                return false;
            }
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX; t = tMaxX; tMaxX += tDeltaX;
                } else {
                    z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY; t = tMaxY; tMaxY += tDeltaY;
                } else {
                    z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ;
                }
            }
        }
        return true;
    }

    /** Distance along the ray to the first voxel boundary on one axis. */
    private static float boundaryT(float origin, float dirComponent, int step) {
        if (step == 0) return Float.MAX_VALUE;
        float frac = step > 0
                ? (floor(origin) + 1.0f - origin)
                : (origin - floor(origin));
        return frac / Math.abs(dirComponent);
    }

    private static int floor(float v) {
        return (int) Math.floor(v);
    }
}
