package com.voxel.world;

import java.util.HashMap;
import java.util.Map;

/**
 * Reusable block-plan shapes shared by the MCSM, Minecraft Legends and
 * Dungeons site families (§7). Each site class names a kind of place — a town,
 * an arena, a temple, a keep, a rift hall — and {@code SiteShapes} stamps the
 * recognisable silhouette from the common block palette.
 *
 * <p>Writes go through {@link Sink}, so production passes
 * {@code chunkManager::setVoxel} and tests pass a {@link RecordingSink}. Every
 * shape is deterministic and idempotent: stamping it twice writes the same
 * voxels.</p>
 */
public final class SiteShapes {

    /** Where blocks are written (ChunkManager in production). */
    public interface Sink {
        void set(int x, int y, int z, int blockId);
    }

    private SiteShapes() {
    }

    // ── Common building blocks ──
    private static void fill(Sink s, int x0, int z0, int x1, int z1, int y, int b) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) s.set(x, y, z, b);
    }

    private static void box(Sink s, int x0, int y0, int z0, int x1, int y1, int z1, int b) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) s.set(x, y, z, b);
    }

    // ── Shapes (all centred on cx,cz, growing up from `base`) ──

    /** A paved plaza with a decorative rim. */
    public static void plaza(Sink s, int cx, int cz, int base, int radius,
                             int floorId, int rimId) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > radius * radius) continue;
                s.set(cx + dx, base, cz + dz, floorId);
                s.set(cx + dx, base - 1, cz + dz, rimId);
            }
        }
    }

    /** A walled town: a rectangular shell of houses around a central well. */
    public static void town(Sink s, int cx, int cz, int base, int half,
                            int wallId, int roofId, int postId, int lightId) {
        fill(s, cx - half, cz - half, cx + half, cz + half, base, wallId);
        // Ring of houses.
        for (int dx = -half + 1; dx <= half - 1; dx += 3) {
            for (int dz = -half + 1; dz <= half - 1; dz += 3) {
                boolean edge = Math.abs(dx) >= half - 4 || Math.abs(dz) >= half - 4;
                if (!edge) continue;
                for (int y = 1; y <= 3; y++) {
                    s.set(cx + dx, base + y, cz + dz, wallId);
                    s.set(cx + dx + 1, base + y, cz + dz, wallId);
                }
                s.set(cx + dx, base + 4, cz + dz, roofId);
                s.set(cx + dx + 1, base + 4, cz + dz, roofId);
                s.set(cx + dx, base + 1, cz + dz + 1, postId);
            }
        }
        // Central well with a glowing crown.
        box(s, cx - 2, base, cz - 2, cx + 2, base, cz + 2, postId);
        box(s, cx - 1, base - 3, cz - 1, cx + 1, base - 1, cz + 1, 0);
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int y = 1; y <= 2; y++) s.set(cx + c[0], base + y, cz + c[1], postId);
            s.set(cx + c[0], base + 2, cz + c[1], lightId);
        }
    }

    /** A circular arena ring with banners. */
    public static void arena(Sink s, int cx, int cz, int base, int radius,
                             int stoneId, int bannerId, int lightId, int floorId) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > radius * radius) continue;
                s.set(cx + dx, base, cz + dz, floorId);
                s.set(cx + dx, base - 1, cz + dz, stoneId);
                if (d2 > (radius - 2) * (radius - 2)) {
                    for (int y = 1; y <= 5; y++) {
                        s.set(cx + dx, base + y, cz + dz,
                                (y == 3 && ((dx + dz) & 3) == 0) ? bannerId : stoneId);
                    }
                    if (((dx + dz) & 3) == 0) s.set(cx + dx, base + 6, cz + dz, lightId);
                }
            }
        }
    }

    /** A stepped temple with a glowing altar. */
    public static void temple(Sink s, int cx, int cz, int base, int radius,
                              int stoneId, int accentId, int lightId) {
        for (int step = 0; step < 3; step++) {
            int r = radius - step * 2;
            fill(s, cx - r, cz - r, cx + r, cz + r, base + step, stoneId);
        }
        final int top = 3; // the third step's height
        box(s, cx - 2, base + top, cz - 2, cx + 2, base + top, cz + 2, accentId);
        box(s, cx - 1, base + top + 1, cz - 1, cx + 1, base + top + 1, cz + 1, accentId);
        s.set(cx, base + top + 2, cz, lightId);
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            for (int y = 1; y <= 3; y++) s.set(cx + c[0], base + top + y, cz + c[1], accentId);
            s.set(cx + c[0], base + top + 3, cz + c[1], lightId);
        }
    }

    /** A square keep: thick walls, a gate, and corner towers. */
    public static void keep(Sink s, int cx, int cz, int base, int half, int height,
                            int wallId, int roofId, int lightId) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
                s.set(cx + dx, base, cz + dz, roofId);
                if (edge) {
                    for (int y = 1; y <= height; y++) s.set(cx + dx, base + y, cz + dz, wallId);
                    if (y_band(dx, dz)) s.set(cx + dx, base + height / 2, cz + dz, lightId);
                }
            }
        }
        // Corner towers rising above the wall.
        for (int[] c : new int[][]{{-half, -half}, {half, -half}, {-half, half}, {half, half}}) {
            for (int y = 1; y <= height + 3; y++) {
                for (int a = -1; a <= 1; a++) {
                    s.set(cx + c[0] + a, base + y, cz + c[1], wallId);
                    s.set(cx + c[0], base + y, cz + c[1] + a, wallId);
                }
            }
            s.set(cx + c[0], base + height + 3, cz + c[1], lightId);
        }
        // South gate.
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int y = 1; y <= 3; y++) s.set(x, base + y, cz + half, 0);
    }

    private static boolean y_band(int dx, int dz) {
        return ((dx * 3 + dz * 5) & 3) == 0;
    }

    /** A spiked pinnacle of dark stone around a glowing core. */
    public static void spire(Sink s, int cx, int cz, int base, int height,
                             int bodyId, int coreId, int lightId) {
        for (int y = 0; y < height; y++) {
            int r = Math.max(0, (height - y) / 4);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > r) continue;
                    s.set(cx + dx, base + y, cz + dz, bodyId);
                }
            }
            if (y % 6 == 0) {
                s.set(cx + (y % 3) - 1, base + y, cz, coreId);
                s.set(cx, base + y, cz + (y % 3) - 1, coreId);
            }
        }
        s.set(cx, base + height, cz, lightId);
    }

    /** A split canyon trench with a wooden bridge. */
    public static void canyon(Sink s, int cx, int cz, int base, int half,
                              int wallId, int bridgeId, int accentId) {
        fill(s, cx - half, cz - half, cx + half, cz + half, base - 6, wallId);
        // Carve the trench.
        for (int dz = -1; dz <= 1; dz++) {
            for (int x = cx - half; x <= cx + half; x++) {
                for (int y = base - 5; y <= base + 4; y++) s.set(x, y, cz + dz, 0);
            }
        }
        // Bridge across it.
        for (int x = cx - half; x <= cx + half; x++) {
            s.set(x, base + 1, cz, bridgeId);
            s.set(x, base + 1, cz + 1, bridgeId);
        }
        // Accent cacti on the rim.
        for (int dz = -half; dz <= half; dz += 4) {
            s.set(cx - half, base + 1, cz + dz, accentId);
            s.set(cx + half, base + 1, cz + dz, accentId);
        }
    }

    /** A swamp with a stilted hut over murky water. */
    public static void swampHut(Sink s, int cx, int cz, int base, int r,
                                int waterId, int postId, int wallId, int roofId) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                s.set(cx + dx, base - 2, cz + dz, waterId);
            }
        }
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            for (int y = -1; y <= 3; y++) s.set(cx + c[0], base + y, cz + c[1], postId);
        }
        fill(s, cx - 4, cz - 4, cx + 4, cz + 4, base + 4, wallId);
        fill(s, cx - 4, cz - 4, cx + 4, cz + 4, base + 5, roofId);
    }

    /** An underworld forge: lava channels and an anvil. */
    public static void forge(Sink s, int cx, int cz, int base, int half,
                             int wallId, int lavaId, int metalId, int lightId) {
        fill(s, cx - half, cz - half, cx + half, cz + half, base, wallId);
        for (int x = cx - half + 2; x <= cx + half - 2; x++) {
            s.set(x, base, cz, lavaId);
            s.set(x, base, cz + 2, lavaId);
        }
        for (int[] c : new int[][]{{-half + 1, -half + 1}, {half - 1, -half + 1},
                {-half + 1, half - 1}, {half - 1, half - 1}}) {
            for (int y = 1; y <= 5; y++) s.set(cx + c[0], base + y, cz + c[1], metalId);
            s.set(cx + c[0], base + 5, cz + c[1], lightId);
        }
        s.set(cx, base + 1, cz + half - 1, metalId);
        s.set(cx, base + 2, cz + half - 1, lightId);
    }

    /** A hero outpost: a ring of pikes around a banner standard. */
    public static void outpost(Sink s, int cx, int cz, int base, int r,
                               int groundId, int postId, int bannerId, int lightId) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                s.set(cx + dx, base, cz + dz, groundId);
            }
        }
        for (int[] c : new int[][]{{-r + 2, 0}, {r - 2, 0}, {0, -r + 2}, {0, r - 2}}) {
            for (int y = 1; y <= 3; y++) s.set(cx + c[0], base + y, cz + c[1], postId);
            s.set(cx + c[0], base + 3, cz + c[1], bannerId);
        }
        for (int y = 1; y <= 5; y++) s.set(cx, base + y, cz, postId);
        s.set(cx, base + 6, cz, lightId);
    }

    /** A rift hall: a slab platform holding several portal frames. */
    public static void riftHall(Sink s, int cx, int cz, int base, int half,
                                int floorId, int frameId, int portalId, int lightId) {
        fill(s, cx - half, cz - half, cx + half, cz + half, base, floorId);
        fill(s, cx - half, cz - half, cx + half, cz + half, base - 1, floorId);
        int[] xs = {cx - half + 3, cx, cx + half - 3};
        for (int x : xs) {
            for (int dy = 1; dy <= 4; dy++) {
                s.set(x, base + dy, cz - 1, frameId);
                s.set(x, base + dy, cz + 2, frameId);
            }
            for (int dz = -1; dz <= 2; dz++) s.set(x, base + 5, cz + dz, frameId);
            for (int dy = 1; dy <= 4; dy++) s.set(x, base + dy, cz, portalId);
            for (int dy = 1; dy <= 4; dy++) s.set(x, base + dy, cz + 1, portalId);
            s.set(x, base, cz, lightId);
        }
    }

    /** A pumpkin field with a stone barn. */
    public static void pumpkinField(Sink s, int cx, int cz, int base, int r,
                                    int grassId, int dirtId, int pumpkinId, int barnId) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                s.set(cx + dx, base - 1, cz + dz, dirtId);
                if (((dx * 7 + dz * 13) & 5) == 0) {
                    s.set(cx + dx, base, cz + dz, pumpkinId);
                } else {
                    s.set(cx + dx, base, cz + dz, grassId);
                }
            }
        }
        box(s, cx + r - 5, base + 1, cz - 2, cx + r - 1, base + 4, cz + 2, barnId);
        box(s, cx + r - 4, base + 1, cz - 1, cx + r - 2, base + 3, cz + 1, 0);
    }

    /** A forest clearing with trees and a camp. */
    public static void forestCamp(Sink s, int cx, int cz, int base, int r,
                                  int groundId, int logId, int leafId, int lightId) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                s.set(cx + dx, base - 1, cz + dz, groundId);
            }
        }
        for (int dx = -r + 2; dx <= r - 2; dx += 5) {
            for (int dz = -r + 2; dz <= r - 2; dz += 5) {
                if (dx * dx + dz * dz > (r - 4) * (r - 4)) continue;
                for (int y = 1; y <= 5; y++) s.set(cx + dx, base + y - 1, cz + dz, logId);
                for (int a = -2; a <= 2; a++)
                    for (int b = -2; b <= 2; b++)
                        s.set(cx + dx + a, base + 5, cz + dz + b, leafId);
            }
        }
        s.set(cx, base, cz, lightId);
    }

    /** An island realm: a floating grass disc under a small shrine. */
    public static void skyIsland(Sink s, int cx, int cz, int base, int r,
                                 int topId, int fillId, int wallId, int lightId) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                s.set(cx + dx, base, cz + dz, topId);
                s.set(cx + dx, base - 1, cz + dz, fillId);
                if (d2 > (r - 2) * (r - 2)) s.set(cx + dx, base - 2, cz + dz, fillId);
            }
        }
        box(s, cx - 2, base + 1, cz - 2, cx + 2, base + 3, cz + 2, wallId);
        fill(s, cx - 2, cz - 2, cx + 2, cz + 2, base + 4, wallId);
        s.set(cx, base + 5, cz, lightId);
    }

    /** Test sink that records every written voxel as "x,y,z" -> blockId. */
    public static final class RecordingSink implements Sink {
        public final Map<String, Integer> voxels = new HashMap<>();

        @Override
        public void set(int x, int y, int z, int blockId) {
            voxels.put(x + "," + y + "," + z, blockId);
        }

        public int count(int blockId) {
            int n = 0;
            for (int id : voxels.values()) if (id == blockId) n++;
            return n;
        }
    }
}
