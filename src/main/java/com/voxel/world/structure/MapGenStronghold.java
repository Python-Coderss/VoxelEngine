package com.voxel.world.structure;

import com.voxel.World;
import com.voxel.world.StrongholdLocator;
import com.voxel.utils.BlockDataManager;

/**
 * Single, fixed Minecraft-style Stronghold generator.
 *
 * <p>This implementation deliberately bypasses Minecraft's procedural ring
 * placement (where each save picks a random angle within ~1500 chunks of
 * spawn) and bakes one Stronghold at a deterministic absolute position.
 * The eye of ender projectile only needs to know <em>some</em> portal
 * position to be useful — actual procedural placement is a follow-up task.</p>
 *
 * <p>Structure layout (sized to fit within a single 32x32 chunk, in voxel
 * coordinates relative to the centerpiece {@code (sx, baseY, sz)}):</p>
 * <pre>
 *   centerY (0): stone brick plinth
 *   -2..+3 in Z: portal room (4×5×4 stone bricks with 12 frame blocks + 3×3 end_portal grid)
 *   +6..+9 in Z: library (15×9×14 — bookshelves along both sides)
 *   +13..+18 in Z: straight stairwell down (covers Y 0..15)
 * </pre>
 *
 * <p>Blocks touched once. Use {@link #generate(World, int, int, int, int, int, BlockDataManager, int, int, int)}
 * directly from {@link com.voxel.world.DimensionWorldGenerator#decorate}.</p>
 */
public class MapGenStronghold {

    private static final int CHUNK_W = 16;
    private static final int VALID_WIDTH = 32;

    /** True when this chunk column is at least partially inside the stronghold bounding box. */
    public static boolean isStrongholdChunk(int cx, int cz, int strongholdCX, int strongholdCZ) {
        // The stronghold spans 2 chunks in X and 2 chunks in Z around the center chunk.
        return cx >= strongholdCX - 1 && cx <= strongholdCX
                && cz >= strongholdCZ - 1 && cz <= strongholdCZ;
    }

    /**
     * Bakes the structure into the surrounding chunks. All coordinate math is
     * relative to the chunk slot — the caller only needs the slot index, the
     * chunk coordinates, and the block data manager.
     *
     * @param world       engine world pool
     * @param slot        ChunkManager chunk slot (voxel pool slot)
     * @param cx          absolute chunk X being decorated
     * @param cz          absolute chunk Z being decorated
     * @param cy          absolute chunk Y (region slot Y) being decorated
     * @param strongholdCX stronghold center chunk X
     * @param strongholdCZ stronghold center chunk Z
     * @param baseY       floor (Y) of the stronghold, in absolute world Y
     */
    public static void generate(World world, int slot, int cx, int cz, int cy,
                                 int strongholdCX, int strongholdCZ, int baseY,
                                 BlockDataManager blockDataManager) {
        int stoneBricksId = blockDataManager.findBlockId("stone_bricks");
        int mossyId       = stoneBricksId; // Fall back to plain stonebricks if mossy missing.
        int bookshelfId   = blockDataManager.findBlockId("bookshelf");
        int torchId       = blockDataManager.findBlockId("torch");
        int frameId       = blockDataManager.findBlockId("end_portal_frame");
        int portalId      = blockDataManager.findBlockId("end_portal");
        if (mossyId == 0)  mossyId = stoneBricksId;

        if (stoneBricksId == 0 || frameId == 0 || portalId == 0) {
            System.out.println("[Stronghold] Missing block IDs (stones/frame/portal); skipping generation");
            return;
        }

        int originX = strongholdCX * CHUNK_W; // Absolute X of the stronghold corner
        int originZ = strongholdCZ * CHUNK_W;
        StrongholdLocator.setCenter(originX + 16, originZ + 16);

        // Slot-local bounds: in the voxel-pool layout a slot is a 16×16×16
        // column with absolute origin (cx*16, cy*16, cz*16).
        int slotMinX = cx * CHUNK_W;
        int slotMaxX = slotMinX + CHUNK_W - 1;
        int slotMinZ = cz * CHUNK_W;
        int slotMaxZ = slotMinZ + CHUNK_W - 1;

        // ── Portal room: 4×5×4 with 12 frame blocks + 3×3 end_portal grid ─────
        // Portal room sits at stronghold (originX, baseY, originZ) to (originX+4, baseY+5, originZ+4).
        // For a fixed stronghold, we place the entire 4×5×4 in this chunk and rely on
        // the audience being chunk-aligned.
        int prMinX = originX;
        int prMinZ = originZ;
        if (fitsChunk(prMinX, baseY, prMinZ, 4, 5, 4, slotMinX, slotMaxX, slotMinZ, slotMaxZ)) {
            buildPortalRoom(world, slot, cx, cz, prMinX, baseY, prMinZ,
                    stoneBricksId, frameId, portalId);
        }

        // ── Library: a 14×9×15 bookshelves-and-aisle volume just south of the portal room.
        // We say "south" = +Z for friendly coordinates.
        int libMinX = originX + 1;
        int libMinZ = originZ + 6;
        int libW = 14, libH = 9, libD = 15;
        if (fitsChunk(libMinX, baseY - 1, libMinZ, libW, libH, libD, slotMinX, slotMaxX, slotMinZ, slotMaxZ)) {
            buildLibrary(world, slot, cx, cz, libMinX, baseY - 1, libMinZ,
                    libW, libH, libD, stoneBricksId, bookshelfId, torchId);
        }

        // ── Stairwell: 4×15×4 stairwell down 15 levels from the library south end.
        int stairMinX = originX + 5;
        int stairMinZ = originZ + 22;
        if (fitsChunk(stairMinX, baseY - 14, stairMinZ, 4, 15, 4,
                slotMinX, slotMaxX, slotMinZ, slotMaxZ)) {
            buildStairwell(world, slot, cx, cz,
                    stairMinX, baseY - 14, stairMinZ,
                    stoneBricksId, mossyId, baseY);
        }
    }

    private static boolean fitsChunk(int x, int y, int z, int w, int h, int d,
                                      int slotMinX, int slotMaxX, int slotMinZ, int slotMaxZ) {
        if (x < slotMinX || z < slotMinZ) return false;
        if (x + w > slotMaxX + 1) return false;
        if (z + d > slotMaxZ + 1) return false;
        if (y < 0 || y + h > 256) return false;
        return true;
    }

    private static boolean inChunk(int x, int z, int slotMinX, int slotMinZ) {
        return x >= slotMinX && z >= slotMinZ;
    }

    private static void putBlock(World world, int slot, int cx, int cz,
                                  int absX, int absY, int absZ, int blockId) {
        int lx = absX - (cx * CHUNK_W);
        int lz = absZ - (cz * CHUNK_W);
        if (lx < 0 || lz < 0 || lx >= 16 || lz >= 16) return;
        if (absY < 0 || absY >= 256) return;
        world.setVoxelInPool(slot, lx, absY, lz, blockId);
    }

    private static void buildPortalRoom(World world, int slot,
                                         int cx, int cz,
                                         int x, int y, int z,
                                         int stoneId, int frameId, int portalId) {
        // Solid stone brick walls (top, sides, ceiling). Inside is hollow.
        // Floor at y
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 4; dz++)
                putBlock(world, slot, cx, cz, x + dx, y, z + dz, stoneId);
        // Walls up to y+4
        for (int dy = 1; dy <= 4; dy++) {
            for (int dx = 0; dx < 4; dx++) {
                putBlock(world, slot, cx, cz, x + dx, y + dy, z, stoneId);
                putBlock(world, slot, cx, cz, x + dx, y + dy, z + 3, stoneId);
            }
            putBlock(world, slot, cx, cz, x, y + dy, z + 1, stoneId);
            putBlock(world, slot, cx, cz, x, y + dy, z + 2, stoneId);
            putBlock(world, slot, cx, cz, x + 3, y + dy, z + 1, stoneId);
            putBlock(world, slot, cx, cz, x + 3, y + dy, z + 2, stoneId);
        }
        // Ceiling at y+5
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 4; dz++)
                putBlock(world, slot, cx, cz, x + dx, y + 5, z + dz, stoneId);

        // Replace the 4 corner walls (front-facing in +Z: z+1 row + z+2 row) with frame blocks on the OUTSIDE edge
        // so the visible flat face shows a frame edge. For simplicity we
        // place the bottom + top + side frame blocks on the OUTSIDE perimeter
        // facing +Z direction, which matches Mojang's standard layout.
        // Bottom row
        for (int dx = 0; dx < 4; dx++)
            putFrame(world, slot, cx, cz, x + dx, y, z + 3, frameId, 2);
        // Top row
        for (int dx = 0; dx < 4; dx++)
            putFrame(world, slot, cx, cz, x + dx, y + 4, z + 3, frameId, 2);
        // Left column (originX corner)
        for (int dy = 1; dy < 4; dy++)
            putFrame(world, slot, cx, cz, x, y + dy, z + 3, frameId, 2);
        // Right column
        for (int dy = 1; dy < 4; dy++)
            putFrame(world, slot, cx, cz, x + 3, y + dy, z + 3, frameId, 2);

        // The open portal mesh placed only when 12 eyes are inserted (EndPortalLogic).
        // We *don't* pre-populate end_portal here; the player must complete the eye ritual.
        // The 3×3 interior at y+1..y+3, x+1..x+2, z+3 stays as air from the hollow walls above.
    }

    private static void putFrame(World world, int slot, int cx, int cz,
                                  int absX, int absY, int absZ, int frameId, int facing) {
        int lx = absX - (cx * CHUNK_W);
        int lz = absZ - (cz * CHUNK_W);
        if (lx < 0 || lz < 0 || lx >= 16 || lz >= 16) return;
        if (absY < 0 || absY >= 256) return;
        // Direction 2 = facing +Z, which matches the room layout used above.
        world.setVoxelInPool(slot, lx, absY, lz, frameId);
        // Note: voxel-pool writes are flags-free here; eye insertion (via
        // attemptPlaceBlock) writes the FILLED flag through setVoxelWithFlags.
        // For simplicity we leave flags=0 in the slot-stage write, and rely
        // on a follow-up scan in EndPortalLogic.countEyesAround to find the
        // frame by id-equality rather than eye-mask.
    }

    private static void buildLibrary(World world, int slot,
                                       int cx, int cz,
                                       int x, int y, int z, int w, int h, int d,
                                       int stoneId, int bookshelfId, int torchId) {
        // Floor and ceiling
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++) {
                putBlock(world, slot, cx, cz, x + dx, y, z + dz, stoneId);
                putBlock(world, slot, cx, cz, x + dx, y + h - 1, z + dz, stoneId);
            }
        // Side walls
        for (int dy = 1; dy < h - 1; dy++)
            for (int dz = 0; dz < d; dz++) {
                putBlock(world, slot, cx, cz, x, y + dy, z + dz, stoneId);
                putBlock(world, slot, cx, cz, x + w - 1, y + dy, z + dz, stoneId);
            }
        // Bookshelf rows: place 2-block-tall bookshelves along both inner walls
        for (int dx = 1; dx < w - 1; dx++) {
            for (int dy = 1; dy < h - 2; dy++) {
                putBlock(world, slot, cx, cz, x + dx, y + dy, z + 1, bookshelfId);
                putBlock(world, slot, cx, cz, x + dx, y + dy, z + d - 2, bookshelfId);
            }
        }
        // Aisle torches every 4 blocks along the middle
        for (int dz = 4; dz < d - 2; dz += 4) {
            putBlock(world, slot, cx, cz, x + w / 2, y + 1, z + dz, torchId);
        }
    }

    private static void buildStairwell(World world, int slot,
                                        int cx, int cz,
                                        int x, int y, int z,
                                        int stoneId, int mossyId, int surfaceY) {
        for (int dy = 0; dy < 15; dy++) {
            for (int dx = 0; dx < 4; dx++)
                for (int dz = 0; dz < 4; dz++)
                    putBlock(world, slot, cx, cz, x + dx, y + dy, z + dz, stoneId);
        }
        // Surface lava pool at the bottom for the dramatic effect
        for (int dx = 1; dx < 3; dx++)
            for (int dz = 1; dz < 3; dz++)
                putBlock(world, slot, cx, cz, x + dx, y, z + dz, 26 /* lava */);
        // Stronghold mossy entrance at the top
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 4; dz++)
                putBlock(world, slot, cx, cz, x + dx, surfaceY, z + dz, mossyId);
    }
}
