package com.voxel.world;

import com.voxel.World;

import java.util.HashSet;
import java.util.Set;

/**
 * The Minecraft: Story Mode locations, stamped into the survival overworld at
 * fixed sites near spawn (deterministic, discoverable on the map):
 *
 *   * EnderCon Arena       (128, 0)    — the gladiator ring from Episode 1
 *   * Soren's Building Site (-128, 96) — the Order's wool-and-glass build site
 *   * Pumpkin Hideout      (0, -128)   — Jack's pumpkin patch over the lair
 *
 * Structures are built lazily the first time the player comes within
 * {@link #BUILD_RANGE} blocks of a site, straight into the loaded chunks
 * (same runtime-stamping approach as the Aether dungeons). Each site sits on
 * the scanned surface height at its centre, and rebuilding over the same
 * voxels is idempotent, so a reload just re-stamps the identical layout.
 */
public final class McsmStructures {

    /** Player distance at which a site is built. */
    public static final float BUILD_RANGE = 220.0f;

    // ── Block IDs (mirror Main.registerBlock) ──
    private static final int GRASS = 1, GLASS = 3, OAK_LOG = 5, DIRT = 13,
            END_STONE = 18, GLOWSTONE = 17, PUMPKIN = 42, WOOL = 91, COBBLE = 71,
            PLANKS = 72, CRAFT_TABLE = 115, STONE_BRICK = 131, MOSSY = 132, GOLD_BLOCK = 138;
    /** MCSM content blocks (registered in Main). */
    private static final int FORMIDI_BOMB = 916, WHITE_PUMPKIN = 917;

    public static final int[] ENDERCON = {128, 0};
    public static final int[] SORENS_SITE = {-128, 96};
    public static final int[] PUMPKIN_HIDEOUT = {0, -128};

    private static final Set<String> built = new HashSet<>();

    private McsmStructures() { }

    /** Clears the built-site flags (tests / fresh worlds). */
    public static void resetForTest() {
        built.clear();
    }

    /**
     * Builds any MCSM site the player has just come near. Called per logic
     * tick while the Overworld is active; the distance check is a handful of
     * comparisons until a site triggers.
     */
    public static void ensure(World world, ChunkManager chunkManager,
                              float playerX, float playerZ) {
        if (world == null || chunkManager == null) return;
        buildIfNear(world, chunkManager, playerX, playerZ, "endercon", ENDERCON);
        buildIfNear(world, chunkManager, playerX, playerZ, "soren", SORENS_SITE);
        buildIfNear(world, chunkManager, playerX, playerZ, "pumpkin", PUMPKIN_HIDEOUT);
    }

    /** True once the given site has been stamped this session. */
    public static boolean isBuilt(String site) {
        return built.contains(site);
    }

    private static void buildIfNear(World world, ChunkManager chunkManager,
                                    float px, float pz, String site, int[] at) {
        if (built.contains(site)) return;
        float dx = px - at[0], dz = pz - at[1];
        if (dx * dx + dz * dz > BUILD_RANGE * BUILD_RANGE) return;
        built.add(site);
        int base = surfaceY(world, at[0], at[1]);
        if ("endercon".equals(site)) buildEnderCon(chunkManager, base);
        else if ("soren".equals(site)) buildSorensSite(chunkManager, base);
        else buildPumpkinHideout(chunkManager, base);
    }

    /** Surface height (first air block above the topmost solid) at a column. */
    private static int surfaceY(World world, int x, int z) {
        for (int y = 110; y >= 8; y--) {
            if (world.getVoxel(x, y, z) != 0) return y + 1;
        }
        return 68;
    }

    // ── helpers (all coordinates are relative to the site's base height) ──

    private static void floor(ChunkManager m, int x0, int z0, int x1, int z1, int y, int b) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) m.setVoxel(x, y, z, b);
    }

    private static void box(ChunkManager m, int x0, int y0, int z0, int x1, int y1, int z1, int b) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) m.setVoxel(x, y, z, b);
    }

    /**
     * EnderCon Arena: a stone-brick ring with wool banners, glowstone lights,
     * an end-stone champion's stage and a gold trophy — Episode 1's setting.
     */
    private static void buildEnderCon(ChunkManager m, int b) {
        int cx = ENDERCON[0], cz = ENDERCON[1];
        int r = 14;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                m.setVoxel(cx + dx, b, cz + dz, STONE_BRICK);
                m.setVoxel(cx + dx, b - 1, cz + dz, COBBLE);
                if (d2 > (r - 2) * (r - 2)) {
                    for (int y = 1; y <= 5; y++) {
                        m.setVoxel(cx + dx, b + y, cz + dz,
                                (y == 3 && ((dx + dz) & 3) == 0) ? WOOL : STONE_BRICK);
                    }
                    if (((dx + dz) & 3) == 0) m.setVoxel(cx + dx, b + 6, cz + dz, GLOWSTONE);
                }
            }
        }
        // Champion's stage + trophy.
        floor(m, cx - 3, cz - 3, cx + 3, cz + 3, b + 1, END_STONE);
        box(m, cx - 1, b + 2, cz - 1, cx + 1, b + 2, cz + 1, END_STONE);
        m.setVoxel(cx, b + 3, cz, GOLD_BLOCK);
        // Entry arch (south side).
        for (int y = 1; y <= 4; y++) {
            for (int i = -2; i <= 2; i++) m.setVoxel(cx + i, b + y, cz + r, 0);
        }
        for (int x = cx - 3; x <= cx + 3; x++) m.setVoxel(x, b + 5, cz + r, OAK_LOG);
    }

    /**
     * Soren's Building Site: log scaffolding around a half-finished wool and
     * glass tower with work benches — where the Order of the Stone builds.
     */
    private static void buildSorensSite(ChunkManager m, int b) {
        int cx = SORENS_SITE[0], cz = SORENS_SITE[1];
        floor(m, cx - 10, cz - 10, cx + 10, cz + 10, b, PLANKS);
        // Half-finished tower: wool courses with glass windows.
        for (int y = 1; y <= 10; y++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == 4;
                    if (!edge) continue;
                    if (y > 7 && ((dx + dz) & 1) == 0) continue; // unfinished top
                    boolean window = y >= 3 && y <= 5 && ((dx + dz) % 4 == 0);
                    m.setVoxel(cx + dx, b + y, cz + dz, window ? GLASS : WOOL);
                }
            }
        }
        // Scaffolding: log poles and plank walkways.
        for (int[] p : new int[][]{{-7, -7}, {7, -7}, {-7, 7}, {7, 7}}) {
            for (int y = 1; y <= 12; y++) m.setVoxel(cx + p[0], b + y, cz + p[1], OAK_LOG);
        }
        for (int dx = -7; dx <= 7; dx++) {
            m.setVoxel(cx + dx, b + 6, cz - 7, PLANKS);
            m.setVoxel(cx + dx, b + 6, cz + 7, PLANKS);
        }
        // Work benches.
        m.setVoxel(cx - 6, b + 1, cz + 5, CRAFT_TABLE);
        m.setVoxel(cx - 5, b + 1, cz + 5, CRAFT_TABLE);
        m.setVoxel(cx + 6, b + 1, cz - 5, CRAFT_TABLE);
    }

    /**
     * Pumpkin Hideout: a pumpkin patch over Jack's stone-brick lair, with the
     * white pumpkin on a pedestal — the boss trigger (see McsmStory).
     */
    private static void buildPumpkinHideout(ChunkManager m, int b) {
        int cx = PUMPKIN_HIDEOUT[0], cz = PUMPKIN_HIDEOUT[1];
        // Pumpkin patch on the surface.
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                if (((dx * 7 + dz * 13) & 5) == 0) {
                    m.setVoxel(cx + dx, b, cz + dz, PUMPKIN);
                } else if (((dx * 5 + dz * 3) & 7) == 0) {
                    m.setVoxel(cx + dx, b, cz + dz, STONE_BRICK);
                } else {
                    m.setVoxel(cx + dx, b, cz + dz, GRASS);
                    m.setVoxel(cx + dx, b - 1, cz + dz, DIRT);
                }
            }
        }
        // The lair below: a mossy stone room.
        box(m, cx - 8, b - 8, cz - 8, cx + 8, b - 2, cz + 8, MOSSY);
        box(m, cx - 7, b - 7, cz - 7, cx + 7, b - 3, cz + 7, 0);
        for (int y = b - 7; y <= b + 1; y++) {
            for (int i = -1; i <= 1; i++) {
                m.setVoxel(cx + i, y, cz, 0); // entry shaft
                m.setVoxel(cx, y, cz + i, 0);
            }
        }
        // Pedestal with the white pumpkin — the story's boss trigger.
        box(m, cx - 2, b - 7, cz - 2, cx + 2, b - 7, cz + 2, STONE_BRICK);
        box(m, cx - 1, b - 6, cz - 1, cx + 1, b - 6, cz + 1, STONE_BRICK);
        m.setVoxel(cx, b - 5, cz, WHITE_PUMPKIN);
        m.setVoxel(cx - 4, b - 3, cz - 4, GLOWSTONE);
        m.setVoxel(cx + 4, b - 3, cz - 4, GLOWSTONE);
        m.setVoxel(cx - 4, b - 3, cz + 4, GLOWSTONE);
        m.setVoxel(cx + 4, b - 3, cz + 4, GLOWSTONE);
        // A Formidi-Bomb crate by the wall (Episode 4's stash).
        m.setVoxel(cx + 5, b - 6, cz + 5, FORMIDI_BOMB);
        m.setVoxel(cx + 5, b - 5, cz + 5, FORMIDI_BOMB);
    }
}
