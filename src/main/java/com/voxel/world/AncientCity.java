package com.voxel.world;

import com.voxel.World;
import com.voxel.game.MelodyPortal;

import java.util.HashMap;
import java.util.Map;

/**
 * The Ancient City — the builders' last refuge before extinction (§Melody rift).
 *
 * <p>A sunken soul-slate plaza ringed with stone brick, holding a dormant rift
 * gate and a row of {@code note_block} tuning stones. The gate stays shut until
 * a player plays the correct tune on the stones (see {@link MelodyPortal});
 * {@link #open(ChunkManager)} then fills the frame with a {@code sift_portal},
 * which the normal {@code PortalSystem} travel carries the player through into
 * the Sift.</p>
 *
 * <p>Stamped lazily and idempotently near the player, the same pattern as
 * {@link AncientBuilderSites}, so the Overworld is not psid up front.</p>
 */
public final class AncientCity {

    /** Plaza centre — also the rift gate's base column. */
    public static final int X = -384, Z = 384;
    /** How near the player must be for the city to stamp. */
    public static final int BUILD_RANGE = 200;

    private static final int STONE_BRICK = 131, SOUL_SLATE = 936,
            RIFT_FRAME = 933, SIFT_PORTAL = 934, NOTE_BLOCK = 937,
            SOUL_LAMP = 935;

    /** Where blocks are written (ChunkManager in production). */
    public interface Sink {
        void set(int x, int y, int z, int blockId);
    }

    private static boolean built;
    private static int originY;

    private AncientCity() {
    }

    /** Clears the built flag (tests / fresh worlds). */
    public static void resetForTest() {
        built = false;
    }

    /** True once the city has been stamped this session. */
    public static boolean isBuilt() {
        return built;
    }

    /**
     * Stamps the city once the player is near, registering the rift origin so
     * note-block pitches resolve. Idempotent.
     */
    public static void ensure(World world, ChunkManager chunkManager,
                              float playerX, float playerZ) {
        if (world == null || chunkManager == null || built) return;
        float dx = playerX - X, dz = playerZ - Z;
        if (dx * dx + dz * dz > BUILD_RANGE * BUILD_RANGE) return;
        built = true;
        int base = surfaceY(world, X, Z);
        build(X, Z, base, chunkManager::setVoxel);
        MelodyPortal.setOrigin(X, base, Z);
    }

    /**
     * Fills the rift gate's interior with a live {@code sift_portal} so the
     * frame can be walked through. Returns true when the city exists.
     */
    public static boolean open(ChunkManager chunkManager) {
        if (chunkManager == null || !built) return false;
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 4; dy++) {
                chunkManager.setVoxel(X + dx, originY + dy, Z, SIFT_PORTAL);
            }
        }
        return true;
    }

    /** Absolute Y of the plaza floor (0 before the city is stamped). */
    public static int originY() {
        return originY;
    }

    /** Lays out the plaza, gate, tuning stones and lamps. Package-private for tests. */
    static void build(int cx, int cz, int base, Sink s) {
        originY = base;
        // Sunken floor (two layers of soul slate so nothing floats).
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                s.set(cx + dx, base - 1, cz + dz, SOUL_SLATE);
                s.set(cx + dx, base - 2, cz + dz, SOUL_SLATE);
            }
        }
        // Stone-brick ring wall at the plaza edge.
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (Math.abs(dx) == 6 || Math.abs(dz) == 6) {
                    s.set(cx + dx, base, cz + dz, STONE_BRICK);
                }
            }
        }
        // South gate: a proper entrance to the refuge — two jambs, a lit lintel
        // and a double door — where before the curb had no way in at all.
        int gz = cz + 6;
        for (int y = 1; y <= 4; y++) {
            s.set(cx - 2, base + y, gz, STONE_BRICK);
            s.set(cx + 2, base + y, gz, STONE_BRICK);
        }
        for (int dx = -2; dx <= 2; dx++) s.set(cx + dx, base + 5, gz, STONE_BRICK);
        s.set(cx - 2, base + 5, gz, SOUL_LAMP);
        s.set(cx + 2, base + 5, gz, SOUL_LAMP);
        s.set(cx, base + 6, gz, SOUL_LAMP);
        // The doors take the two centre cells of the gateway (wall along X).
        for (int dx = -1; dx <= 0; dx++) {
            s.set(cx + dx, base, gz, DecorBlocks.DARK_OAK_DOOR_LOWER_EW);
            s.set(cx + dx, base + 1, gz, DecorBlocks.DARK_OAK_DOOR_UPPER_EW);
        }

        // Rift gate: plinth, posts, lintel. Interior left dormant (air).
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                s.set(cx + dx, base, cz + dz, SOUL_SLATE);
            }
        }
        for (int dy = 1; dy <= 4; dy++) {
            s.set(cx - 2, base + dy, cz, RIFT_FRAME);
            s.set(cx + 3, base + dy, cz, RIFT_FRAME);
        }
        for (int dx = -2; dx <= 3; dx++) {
            s.set(cx + dx, base + 5, cz, RIFT_FRAME);
        }
        // Tuning stones: a row in front. pitchAt() turns these offsets into 0..6.
        for (int dx = -3; dx <= 3; dx++) {
            s.set(cx + dx, base + 1, cz - 3, NOTE_BLOCK);
        }
        s.set(cx - 4, base + 1, cz - 3, SOUL_LAMP);
        s.set(cx + 4, base + 1, cz - 3, SOUL_LAMP);
    }

    /** Surface height (first air above the topmost solid), like the other sites. */
    static int surfaceY(World world, int x, int z) {
        for (int y = 110; y >= 8; y--) {
            if (world.getVoxel(x, y, z) != 0) return y + 1;
        }
        return 68;
    }

    /** Test sink that records every written voxel as "x,y,z" -> blockId. */
    public static final class RecordingSink implements Sink {
        public final Map<String, Integer> voxels = new HashMap<>();

        @Override
        public void set(int x, int y, int z, int blockId) {
            voxels.put(x + "," + y + "," + z, blockId);
        }
    }
}
