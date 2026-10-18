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
 *
 * <h2>Geometry rules</h2>
 * These shapes are dropped onto whatever terrain the site happens to sit on,
 * so they are built from primitives that cannot produce a malformed result:
 * <ul>
 *   <li>{@link #fill}/{@link #box} for solid volumes and {@link #walls} for a
 *       perimeter shell — never a floating slab,</li>
 *   <li>{@link #gableRoof}/{@link #pyramidRoof} for roofs whose bottom course
 *       always rests on the wall ring and whose apex sits over the centre,</li>
 *   <li>{@link #doorInZWall}/{@link #doorInXWall} for a two-block-tall door in
 *       the correct wall orientation (see {@link DecorBlocks}),</li>
 *   <li>{@link #house} for a complete small building: floor, walled shell with
 *       log corners, roof, a lamp and a door that faces the right way.</li>
 * </ul>
 * Every enclosed building has a door you can walk through, and every block
 * above a building's floor sits on something.
 */
public final class SiteShapes {

    /** Where blocks are written (ChunkManager in production). */
    public interface Sink {
        void set(int x, int y, int z, int blockId);
    }

    private SiteShapes() {
    }

    private static final int WATER = 15, GLOWSTONE = 17;

    // ── Door kits: {lower Z-wall, upper Z-wall, lower X-wall, upper X-wall} ──
    private static final int[] OAK = {DecorBlocks.OAK_DOOR_LOWER,
            DecorBlocks.OAK_DOOR_UPPER, DecorBlocks.OAK_DOOR_LOWER_EW,
            DecorBlocks.OAK_DOOR_UPPER_EW};
    private static final int[] SPRUCE = {DecorBlocks.SPRUCE_DOOR_LOWER,
            DecorBlocks.SPRUCE_DOOR_UPPER, DecorBlocks.SPRUCE_DOOR_LOWER_EW,
            DecorBlocks.SPRUCE_DOOR_UPPER_EW};
    private static final int[] DARK_OAK = {DecorBlocks.DARK_OAK_DOOR_LOWER,
            DecorBlocks.DARK_OAK_DOOR_UPPER, DecorBlocks.DARK_OAK_DOOR_LOWER_EW,
            DecorBlocks.DARK_OAK_DOOR_UPPER_EW};

    // ── Common building blocks ──
    private static void fill(Sink s, int x0, int z0, int x1, int z1, int y, int b) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) s.set(x, y, z, b);
    }

    private static void box(Sink s, int x0, int y0, int z0, int x1, int y1, int z1, int b) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) s.set(x, y, z, b);
    }

    /** Perimeter shell only — walls, no floor and no ceiling. */
    private static void walls(Sink s, int x0, int z0, int x1, int z1, int y0, int y1, int b) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                s.set(x, y, z0, b);
                s.set(x, y, z1, b);
            }
            for (int z = z0; z <= z1; z++) {
                s.set(x0, y, z, b);
                s.set(x1, y, z, b);
            }
        }
    }

    /** Carves a two-tall opening (inclusive bounds, one Y pair). */
    private static void carve(Sink s, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                s.set(x, y, z, 0);
                s.set(x, y + 1, z, 0);
            }
    }

    /** Places a two-tall door leaf in a wall that runs along Z. */
    private static void doorInZWall(Sink s, int x, int y, int z, int[] kit) {
        s.set(x, y, z, kit[0]);
        s.set(x, y + 1, z, kit[1]);
    }

    /** Places a two-tall door leaf in a wall that runs along X. */
    private static void doorInXWall(Sink s, int x, int y, int z, int[] kit) {
        s.set(x, y, z, kit[2]);
        s.set(x, y + 1, z, kit[3]);
    }

    /**
     * Stepped gable roof stepping in from the Z ends, two blocks per level.
     * Level 0 spans the full footprint, so the roof always rests on the wall
     * ring and never floats. The top level becomes the ridge.
     *
     * @return the Y of the ridge.
     */
    private static int gableRoof(Sink s, int x0, int z0, int x1, int z1,
                                 int wallTop, int body, int ridge) {
        int maxK = 0;
        while (z0 + (maxK + 1) * 2 <= z1 - (maxK + 1) * 2) maxK++;
        for (int k = 0; k <= maxK; k++) {
            int y = wallTop + 1 + k;
            int a = z0 + k * 2, c = z1 - k * 2;
            fill(s, x0, a, x1, c, y, k == maxK ? ridge : body);
        }
        return wallTop + 1 + maxK;
    }

    /**
     * Stepped pyramid roof shrinking one block per side per level, so its apex
     * lands over the centre of the footprint. Level 0 spans the full (already
     * overhung) footprint and therefore sits on the wall ring.
     *
     * @return the Y of the apex.
     */
    private static int pyramidRoof(Sink s, int x0, int z0, int x1, int z1,
                                   int wallTop, int body, int ridge) {
        int last = wallTop + 1;
        for (int k = 0; ; k++) {
            int a = x0 + k, b = z0 + k, c = x1 - k, d = z1 - k;
            if (a > c || b > d) break;
            int y = wallTop + 1 + k;
            boolean apex = a == c && b == d;
            fill(s, a, b, c, d, y, apex ? ridge : body);
            last = y;
            if (apex) break;
        }
        return last;
    }

    /**
     * A complete small house: floor, perimeter walls with log corners, a
     * pyramid roof and a working door facing the point {@code (hx, hz)} points
     * to from the house centre — so no house opens onto the back of another.
     *
     * @return the Y of the roof apex.
     */
    private static int house(Sink s, int hx, int hz, int base, int wallId,
                             int roofId, int postId, int lightId, int[] kit) {
        int x0 = hx - 2, x1 = hx + 2, z0 = hz - 2, z1 = hz + 2;
        int floorY = base + 1, wallTop = base + 3;
        fill(s, x0, z0, x1, z1, floorY, wallId);
        walls(s, x0, z0, x1, z1, floorY + 1, wallTop, wallId);
        for (int y = floorY + 1; y <= wallTop; y++) {
            s.set(x0, y, z0, postId);
            s.set(x1, y, z0, postId);
            s.set(x0, y, z1, postId);
            s.set(x1, y, z1, postId);
        }
        int apexY = pyramidRoof(s, x0 - 1, z0 - 1, x1 + 1, z1 + 1, wallTop, roofId, postId);
        s.set(hx, apexY, hz, lightId);

        boolean xFace = Math.abs(hx) >= Math.abs(hz);
        if (hx == 0) xFace = false;
        if (hz == 0) xFace = true;
        if (xFace) {
            int wx = hx + Integer.signum(hx) * 2;
            carve(s, wx, hz, wx, hz, floorY);
            doorInZWall(s, wx, floorY, hz, kit);
        } else {
            int wz = hz + Integer.signum(hz) * 2;
            carve(s, hx, wz, hx, wz, floorY);
            doorInXWall(s, hx, floorY, wz, kit);
        }
        return apexY;
    }

    // ── Shapes (all centred on cx,cz, growing up from `base`) ──

    /** A paved plaza with a decorative rim and a lamp ring. */
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
        // Lamp posts on the pavement rim, standing on the paved disc.
        int o = Math.max(1, radius - 2);
        for (int[] c : new int[][]{{-o, 0}, {o, 0}, {0, -o}, {0, o}}) {
            s.set(cx + c[0], base + 1, cz + c[1], rimId);
            s.set(cx + c[0], base + 2, cz + c[1], rimId);
        }
    }

    /** A walled town: a curtain wall with a gated south entrance, a ring of
     *  real houses (each with a door and a lamp) around a canopied well. */
    public static void town(Sink s, int cx, int cz, int base, int half,
                            int wallId, int roofId, int postId, int lightId) {
        // Paved ground across the whole town.
        fill(s, cx - half, cz - half, cx + half, cz + half, base, wallId);
        // Curtain wall with a battlement course.
        walls(s, cx - half, cz - half, cx + half, cz + half, base + 1, base + 3, wallId);
        for (int x = cx - half; x <= cx + half; x += 2) {
            s.set(x, base + 4, cz - half, wallId);
            s.set(x, base + 4, cz + half, wallId);
        }
        for (int z = cz - half; z <= cz + half; z += 2) {
            s.set(cx - half, base + 4, z, wallId);
            s.set(cx + half, base + 4, z, wallId);
        }
        // South gate: a two-wide double door under a lit lintel.
        carve(s, cx - 1, cz + half, cx, cz + half, base + 1);
        doorInXWall(s, cx - 1, base + 1, cz + half, OAK);
        doorInXWall(s, cx, base + 1, cz + half, OAK);
        for (int x = cx - 1; x <= cx; x++) s.set(x, base + 3, cz + half, wallId);
        s.set(cx, base + 4, cz + half, lightId);

        // Ring of houses, each opening toward the well (kept clear of the wall).
        int[] ring = {-half + 4, 0, half - 4};
        for (int hx : ring) {
            for (int hz : ring) {
                if (hx == 0 && hz == 0) continue;
                house(s, cx + hx, cz + hz, base, wallId, roofId, postId, lightId, OAK);
            }
        }

        // Central well with a canopied crown.
        box(s, cx - 2, base, cz - 2, cx + 2, base, cz + 2, postId);
        box(s, cx - 1, base - 3, cz - 1, cx + 1, base - 1, cz + 1, 0);
        fill(s, cx - 1, cz - 1, cx + 1, cz + 1, base + 1, WATER);
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int y = 1; y <= 3; y++) s.set(cx + c[0], base + y, cz + c[1], postId);
        }
        fill(s, cx - 2, cz - 2, cx + 2, cz + 2, base + 4, roofId);
        fill(s, cx - 1, cz - 1, cx + 1, cz + 1, base + 5, roofId);
        s.set(cx, base + 6, cz, lightId);
    }

    /** A circular arena ring with banners and four gated entrances. */
    public static void arena(Sink s, int cx, int cz, int base, int radius,
                             int stoneId, int bannerId, int lightId, int floorId) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > radius * radius) continue;
                s.set(cx + dx, base, cz + dz, floorId);
                s.set(cx + dx, base - 1, cz + dz, stoneId);
            }
        }
        // Ring wall with banner courses.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 <= (radius - 2) * (radius - 2) || d2 > radius * radius) continue;
                for (int y = 1; y <= 5; y++) {
                    s.set(cx + dx, base + y, cz + dz,
                            (y == 3 && ((dx + dz) & 3) == 0) ? bannerId : stoneId);
                }
                if (((dx + dz) & 3) == 0) s.set(cx + dx, base + 6, cz + dz, lightId);
            }
        }
        // Cardinal gates: a single door in the wall cell on each axis, with a
        // lit lintel. Every gate stands on the arena floor.
        int[][] axis = {{0, -radius, 0}, {0, radius, 0}, {-radius, 0, 1}, {radius, 0, 1}};
        for (int[] g : axis) {
            int gx = cx + g[0], gz = cz + g[1];
            carve(s, gx, gz, gx, gz, base + 1);
            if (g[2] == 0) doorInXWall(s, gx, base + 1, gz, OAK);
            else doorInZWall(s, gx, base + 1, gz, OAK);
            s.set(gx, base + 4, gz, lightId);
        }
    }

    /** A stepped temple with a glowing altar and a lit entrance passage. */
    public static void temple(Sink s, int cx, int cz, int base, int radius,
                              int stoneId, int accentId, int lightId) {
        for (int step = 0; step < 3; step++) {
            int r = radius - step * 2;
            if (r < 0) break;
            fill(s, cx - r, cz - r, cx + r, cz + r, base + step, stoneId);
        }
        final int top = 3; // the third step's height
        box(s, cx - 2, base + top, cz - 2, cx + 2, base + top, cz + 2, accentId);
        box(s, cx - 1, base + top + 1, cz - 1, cx + 1, base + top + 1, cz + 1, accentId);
        s.set(cx, base + top + 2, cz, lightId);
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            if (radius - 4 < 3) break;
            for (int y = 1; y <= 3; y++) s.set(cx + c[0], base + top + y, cz + c[1], accentId);
            s.set(cx + c[0], base + top + 3, cz + c[1], lightId);
        }

        // Covered entrance passage cut into the south face: two tall, with a
        // real double door at its mouth and a lit ceiling along its length.
        // The floor at `base` is left intact, so the passage never becomes a pit.
        int mouth = cz + radius;
        for (int z = mouth; z >= cz + 2; z--) {
            for (int x = cx - 1; x <= cx; x++) {
                s.set(x, base + 1, z, 0);
                s.set(x, base + 2, z, 0);
                s.set(x, base, z, stoneId);       // passage floor
            }
            s.set(cx - 2, base + 1, z, stoneId);
            s.set(cx - 2, base + 2, z, stoneId);
            s.set(cx + 1, base + 1, z, stoneId);
            s.set(cx + 1, base + 2, z, stoneId);
            boolean lit = z % 3 == 0;
            for (int x = cx - 2; x <= cx + 1; x++)
                s.set(x, base + 3, z, lit ? lightId : stoneId);
        }
        // Lit alcove at the far end of the passage.
        s.set(cx - 1, base, cz + 1, accentId);
        s.set(cx, base, cz + 1, accentId);
        s.set(cx - 1, base + 1, cz + 1, lightId);
        s.set(cx, base + 1, cz + 1, lightId);
        doorInXWall(s, cx - 1, base + 1, mouth, OAK);
        doorInXWall(s, cx, base + 1, mouth, OAK);
    }

    /** A square keep: thick walls, battlement, a gated gatehouse, an inner
     *  hall and corner towers. */
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
        // Battlement course along the wall ring.
        for (int a = -half; a <= half; a += 2) {
            s.set(cx + a, base + height + 1, cz - half, wallId);
            s.set(cx + a, base + height + 1, cz + half, wallId);
            s.set(cx - half, base + height + 1, cz + a, wallId);
            s.set(cx + half, base + height + 1, cz + a, wallId);
        }
        // Corner towers rising above the wall, capped with light.
        for (int[] c : new int[][]{{-half, -half}, {half, -half}, {-half, half}, {half, half}}) {
            for (int y = 1; y <= height + 3; y++) {
                for (int a = -1; a <= 1; a++) {
                    s.set(cx + c[0] + a, base + y, cz + c[1], wallId);
                    s.set(cx + c[0], base + y, cz + c[1] + a, wallId);
                }
            }
            s.set(cx + c[0], base + height + 3, cz + c[1], lightId);
        }
        // South gatehouse: a two-wide double door with a lit lintel overhead.
        carve(s, cx - 1, cz + half, cx, cz + half, base + 1);
        doorInXWall(s, cx - 1, base + 1, cz + half, DARK_OAK);
        doorInXWall(s, cx, base + 1, cz + half, DARK_OAK);
        for (int x = cx - 2; x <= cx + 1; x++) s.set(x, base + 3, cz + half, wallId);
        s.set(cx, base + 3, cz + half, lightId);
        // Inner bailey: a small hall with its own door, so the courtyard reads
        // as a place rather than an empty square.
        if (half >= 6) {
            house(s, cx + 3, cz - 3, base, wallId, roofId, wallId, lightId, SPRUCE);
        }
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

    /** A split canyon trench with a railed wooden bridge. */
    public static void canyon(Sink s, int cx, int cz, int base, int half,
                              int wallId, int bridgeId, int accentId) {
        fill(s, cx - half, cz - half, cx + half, cz + half, base - 6, wallId);
        // Carve the trench.
        for (int dz = -1; dz <= 1; dz++) {
            for (int x = cx - half; x <= cx + half; x++) {
                for (int y = base - 5; y <= base + 4; y++) s.set(x, y, cz + dz, 0);
            }
        }
        // Bridge across it, with rails on both sides.
        for (int x = cx - half; x <= cx + half; x++) {
            s.set(x, base + 1, cz, bridgeId);
            s.set(x, base + 1, cz + 1, bridgeId);
            s.set(x, base + 2, cz - 1, bridgeId);
            s.set(x, base + 2, cz + 2, bridgeId);
        }
        // Accent cacti on the rim, standing on the canyon lip.
        for (int dz = -half; dz <= half; dz += 4) {
            s.set(cx - half, base + 1, cz + dz, accentId);
            s.set(cx + half, base + 1, cz + dz, accentId);
        }
    }

    /** A swamp with a stilted, walled hut reached by a plank dock. */
    public static void swampHut(Sink s, int cx, int cz, int base, int r,
                                int waterId, int postId, int wallId, int roofId) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                s.set(cx + dx, base - 2, cz + dz, waterId);
            }
        }
        // Stilts driven into the water, carrying the hut floor.
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            for (int y = -2; y <= 0; y++) s.set(cx + c[0], base + y, cz + c[1], postId);
        }
        fill(s, cx - 3, cz - 3, cx + 3, cz + 3, base + 1, wallId);
        walls(s, cx - 3, cz - 3, cx + 3, cz + 3, base + 2, base + 3, wallId);
        gableRoof(s, cx - 4, cz - 4, cx + 4, cz + 4, base + 3, roofId, roofId);
        // Door onto the dock, plus two lit windows.
        carve(s, cx - 1, cz + 3, cx, cz + 3, base + 2);
        doorInXWall(s, cx - 1, base + 2, cz + 3, SPRUCE);
        doorInXWall(s, cx, base + 2, cz + 3, SPRUCE);
        s.set(cx - 3, base + 3, cz, GLOWSTONE);
        s.set(cx + 3, base + 3, cz + 3, GLOWSTONE);
        // Dock out to the hut door.
        for (int z = cz + 4; z <= cz + r; z++) {
            s.set(cx - 1, base + 1, z, wallId);
            s.set(cx, base + 1, z, wallId);
        }
    }

    /** An underworld forge: walled, roofed, lava channels and a gated entrance. */
    public static void forge(Sink s, int cx, int cz, int base, int half,
                             int wallId, int lavaId, int metalId, int lightId) {
        fill(s, cx - half, cz - half, cx + half, cz + half, base, wallId);
        walls(s, cx - half, cz - half, cx + half, cz + half, base + 1, base + 4, wallId);
        // Recessed lava channels — flush with the floor, so nothing spills out.
        for (int x = cx - half + 2; x <= cx + half - 2; x++) {
            s.set(x, base, cz, lavaId);
            s.set(x, base, cz + 2, lavaId);
        }
        // Chimney columns at the corners, standing on the floor.
        for (int[] c : new int[][]{{-half + 1, -half + 1}, {half - 1, -half + 1},
                {-half + 1, half - 1}, {half - 1, half - 1}}) {
            for (int y = 1; y <= 5; y++) s.set(cx + c[0], base + y, cz + c[1], metalId);
            s.set(cx + c[0], base + 5, cz + c[1], lightId);
        }
        // Gated entrance on the south wall.
        carve(s, cx - 1, cz + half, cx, cz + half, base + 1);
        doorInXWall(s, cx - 1, base + 1, cz + half, DARK_OAK);
        doorInXWall(s, cx, base + 1, cz + half, DARK_OAK);
        for (int x = cx - 1; x <= cx; x++) s.set(x, base + 3, cz + half, wallId);
        // Roof over it all.
        gableRoof(s, cx - half - 1, cz - half - 1, cx + half + 1, cz + half + 1,
                base + 4, wallId, metalId);
        // The anvil, on a plinth so it does not sit inside the lava.
        s.set(cx, base + 1, cz + half - 1, metalId);
        s.set(cx, base + 2, cz + half - 1, lightId);
    }

    /** A hero outpost: a ring of pikes around a banner standard, with tents. */
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
        // Wall tents: two small huts with doors, so the outpost shelters its heroes.
        int o = Math.max(4, r - 6);
        house(s, cx - o, cz - o, base, postId, bannerId, postId, lightId, OAK);
        house(s, cx + o, cz + o, base, postId, bannerId, postId, lightId, OAK);
    }

    /** A rift hall: a walled, roofed hall holding several portal frames. */
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
        // Hall shell above the portal lintels, with lit windows and a gated door.
        walls(s, cx - half, cz - half, cx + half, cz + half, base + 1, base + 6, frameId);
        for (int a = cx - half + 2; a <= cx + half - 2; a += 4) {
            s.set(a, base + 3, cz - half, lightId);
            s.set(a, base + 3, cz + half, lightId);
            s.set(cx - half, base + 3, a, lightId);
            s.set(cx + half, base + 3, a, lightId);
        }
        carve(s, cx - 1, cz + half, cx, cz + half, base + 1);
        doorInXWall(s, cx - 1, base + 1, cz + half, OAK);
        doorInXWall(s, cx, base + 1, cz + half, OAK);
        gableRoof(s, cx - half - 1, cz - half - 1, cx + half + 1, cz + half + 1,
                base + 6, frameId, lightId);
    }

    /** A pumpkin field with a walled, roofed, gated stone barn. */
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
        // Barn: floor, walls, a gable roof and a real double door.
        int bx0 = cx + r - 6, bx1 = cx + r - 1, bz0 = cz - 3, bz1 = cz + 3;
        int wallTop = base + 4;
        fill(s, bx0, bz0, bx1, bz1, base + 1, barnId);
        walls(s, bx0, bz0, bx1, bz1, base + 2, wallTop, barnId);
        gableRoof(s, bx0 - 1, bz0 - 1, bx1 + 1, bz1 + 1, wallTop, barnId, barnId);
        carve(s, bx0 + 2, bz1, bx0 + 3, bz1, base + 2);
        doorInXWall(s, bx0 + 2, base + 2, bz1, OAK);
        doorInXWall(s, bx0 + 3, base + 2, bz1, OAK);
        for (int x = bx0 + 2; x <= bx0 + 3; x++) s.set(x, base + 4, bz1, barnId);
        s.set(bx0 + 3, base + 5, bz1, GLOWSTONE);
    }

    /** A forest clearing with trees and a camp cabin. */
    public static void forestCamp(Sink s, int cx, int cz, int base, int r,
                                  int groundId, int logId, int leafId, int lightId) {
        // Clearing: dirt below, grass on top, so `base` is the walkable surface
        // the cabin and trees stand on.
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                s.set(cx + dx, base - 1, cz + dz, groundId);
                s.set(cx + dx, base, cz + dz, groundId);
            }
        }
        // Tree layout is deliberately left exactly as it was: the brief excluded
        // trees. The only cells skipped are the ones the new cabin now occupies.
        for (int dx = -r + 2; dx <= r - 2; dx += 5) {
            for (int dz = -r + 2; dz <= r - 2; dz += 5) {
                if (dx * dx + dz * dz > (r - 4) * (r - 4)) continue;
                if (Math.abs(dx) <= 4 && Math.abs(dz) <= 4) continue; // cabin
                for (int y = 1; y <= 5; y++) s.set(cx + dx, base + y - 1, cz + dz, logId);
                for (int a = -2; a <= 2; a++)
                    for (int b = -2; b <= 2; b++)
                        s.set(cx + dx + a, base + 5, cz + dz + b, leafId);
            }
        }
        // Camp: a log cabin with a door, and a lantern on the clearing beside it.
        house(s, cx, cz, base, logId, logId, logId, lightId, SPRUCE);
        s.set(cx, base + 1, cz - 4, lightId);
    }

    /** An island realm: a floating grass disc under a walled, roofed shrine. */
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
        // Shrine: floor, walls, pyramid roof, a lamp and a door.
        int x0 = cx - 2, x1 = cx + 2, z0 = cz - 2, z1 = cz + 2;
        fill(s, x0, z0, x1, z1, base + 1, wallId);
        walls(s, x0, z0, x1, z1, base + 2, base + 3, wallId);
        int apexY = pyramidRoof(s, x0 - 1, z0 - 1, x1 + 1, z1 + 1, base + 3, fillId, wallId);
        s.set(cx, apexY, cz, lightId);
        carve(s, cx - 1, z1, cx, z1, base + 2);
        doorInXWall(s, cx - 1, base + 2, z1, DARK_OAK);
        doorInXWall(s, cx, base + 2, z1, DARK_OAK);
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
