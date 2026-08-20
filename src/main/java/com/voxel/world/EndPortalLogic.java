package com.voxel.world;

import com.voxel.Player;
import com.voxel.World;
import com.voxel.game.GameContext;
import com.voxel.utils.BlockDataManager;
import com.voxel.world.ChunkManager;

/**
 * End Portal logic: tracks Eye of Ender insertion on each portal frame, fills
 * the 3x3 interior once twelve eyes are placed, and handles the player teleport
 * when they step into the active {@code end_portal} block.
 *
 * <p>The portal frame has four rotation variants in Minecraft; eye bits live
 * in the lowest 4 bits of the chunk voxel flags. We expose a flat scan window
 * so the right-click handler in {@link com.voxel.game.BlockInteraction} can
 * ask "is this spot already filled?" without re-implementing the geometry.</p>
 */
public final class EndPortalLogic {
    private EndPortalLogic() {}

    // ---------- Eye tracking -------------------------------------------------

    /** Mask covers the 12 frame positions plus their facing bits. */
    private static final int EYE_MASK = 0x0F;
    /** Bit set in flags when the frame block has been placed via eye insertion. */
    private static final int FILLED_MASK = 0x10;

    /** Bit flag on the frame block to indicate "clicked with eye of ender". */
    public static boolean isEyeInserted(int flags) { return (flags & FILLED_MASK) != 0; }

    /** Direction index 0..3 stored in the low nibble (facing). */
    public static int frameFacing(int flags) { return flags & EYE_MASK; }

    /**
     * Returns true if the block id is an End Portal Frame variant (any facing).
     * The engine keeps frame rotation via block-data flags rather than a
     * separate block id, so we identify the family by block id only.
     */
    public static boolean isFrameBlock(BlockDataManager bdm, int blockId) {
        if (blockId == 0) return false;
        String name = bdm.getName(blockId);
        // Both 'end_portal_frame' and any alias match.
        return name != null && name.startsWith("end_portal_frame");
    }

    /** Eye-of-Ender insertion point: rotates face flag and sets filled bit. */
    public static int filledFrameFlags(int facing) {
        return (facing & EYE_MASK) | FILLED_MASK;
    }

    /**
     * Counts how many of the 12 portal-frame blocks around the given portal
     * room origin have the FILLED flag set. Used to decide if the 3x3 interior
     * should be filled with light.
     *
     * <p>The portal room layout: 4x4 frame bounding box with corners carved
     * out — 12 frames total. We scan the standard exterior first.</p>
     */
    public static int countEyesAround(World world, BlockDataManager bdm, int minX, int y, int minZ) {
        int count = 0;
        // Bottom row, 4 frames
        for (int x = 0; x < 4; x++) {
            int flags = world.getVoxelExtra(minX + x, y, minZ);
            if ((flags & FILLED_MASK) != 0) count++;
        }
        // Top row, 4 frames
        for (int x = 0; x < 4; x++) {
            int flags = world.getVoxelExtra(minX + x, y + 4, minZ);
            if ((flags & FILLED_MASK) != 0) count++;
        }
        // Left and right columns between the corners
        for (int dy = 1; dy < 4; dy++) {
            int l = world.getVoxelExtra(minX, y + dy, minZ);
            int r = world.getVoxelExtra(minX + 3, y + dy, minZ);
            if ((l & FILLED_MASK) != 0) count++;
            if ((r & FILLED_MASK) != 0) count++;
        }
        return count;
    }

    /**
     * Right-click outcome: inserts an eye of ender into the frame block at
     * {@code (x, y, z)}. Returns true if the insertion succeeded.
     *
     * <p>If the player clicked the *front-facing* side of a frame, the
     * FILLED bit is set. We then scan the surrounding room; if 12 eyes are
     * now filled, the 3×3 interior is filled with end_portal.</p>
     */
    public static boolean tryInsertEye(ChunkManager chunkManager, World world, BlockDataManager bdm,
                                        int x, int y, int z) {
        int blockId = world.getVoxel(x, y, z);
        if (!isFrameBlock(bdm, blockId)) return false;
        int flags = world.getVoxelExtra(x, y, z);
        if ((flags & FILLED_MASK) != 0) return false; // already filled
        flags |= FILLED_MASK;
        // Use the chunk manager's flag-aware setter; falls back to setVoxel
        // when the slot isn't loaded.
        try {
            chunkManager.setVoxelWithFlags(x, y, z, blockId, flags, flags);
        } catch (RuntimeException ex) {
            chunkManager.setVoxel(x, y, z, blockId);
        }
        // Try to fill the portal interior. The portal-room base position is
        // derived from the frame's location: we assume the frame block is
        // either the bottom row, top row, or one of the side columns of a
        // 4×5×4 room. The simplest scan is from (minX=originX, y, minZ=originZ)
        // where the frame block sits — try a few candidate origins.
        int[][] candidateOrigins = {
            {x, y, z},     // clicked frame is the bottom-left corner
            {x - 3, y, z},  // clicked frame is bottom-right corner
            {x, y - 4, z},  // clicked frame is top-left corner
            {x - 3, y - 4, z} // clicked frame is top-right corner
        };
        for (int[] o : candidateOrigins) {
            tryFillPortal(world, bdm, o[0], o[1], o[2]);
        }
        return true;
    }

    /**
     * If the portal has 12 filled eyes, fills the 3x3 interior with the
     * {@code end_portal} block id. Idempotent — the portal is a non-solid
     * block that does not block chunks, so re-calling is cheap.
     *
     * <p>Returns true if the portal is open (or was already open).</p>
     */
    public static boolean tryFillPortal(World world, BlockDataManager bdm, int minX, int y, int minZ) {
        if (countEyesAround(world, bdm, minX, y, minZ) < 12) return false;
        int portalId = bdm.findBlockId("end_portal");
        if (portalId <= 0) return false;
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                int x = minX + dx;
                int z = minZ;
                int target = world.getVoxel(x, y + dy, z);
                if (target == 0) {
                    world.setVoxelWithFlags(x, y + dy, z, portalId, 0, 0);
                }
            }
        }
        return true;
    }

    // ---------- Teleport ---------------------------------------------------

    /**
     * Per-tick check: if the player is standing inside an active end_portal
     * block, switch dimensions and place them at the End's spawn platform.
     *
     * <p>Called from the logic thread (Main.tickLoop) right after
     * {@code player.update(...)}. Mutates world / chunk manager / dimension
     * manager state through helpers above.</p>
     *
     * @return true if a teleport fired this tick.
     */
    public static boolean tickPortalEntryCheck(Player player,
                                                World world,
                                                BlockDataManager bdm,
                                                GameContext ctx,
                                                DimensionManager dimensionManager) {
        if (player == null || world == null || bdm == null || ctx == null) return false;
        int feetY = com.voxel.utils.FixedPoint.blockX(player.getFixedY());
        int feetX = com.voxel.utils.FixedPoint.blockX(player.getFixedX());
        int feetZ = com.voxel.utils.FixedPoint.blockX(player.getFixedZ());
        int blockId = world.getVoxel(feetX, feetY, feetZ);
        if (blockId == 0) return false;
        String name = bdm.getName(blockId);
        if (name == null || !name.contains("end_portal")) return false;
        // Only a *real* end_portal block, not a frame.
        if (name.contains("frame")) return false;

        // Mark target as a one-way transition until the player re-touches the
        // gateway in the End; this also suppresses rapid re-entry.
        if (ctx.endPortalCooldownTicks > 0) return false;
        ctx.endPortalCooldownTicks = 40;

        teleportToEnd(player, ctx, dimensionManager);
        return true;
    }

    /** Player-side teleport from Overworld into the End. */
    public static void teleportToEnd(Player player, GameContext ctx, DimensionManager dimensionManager) {
        if (dimensionManager == null) return;
        // Make sure the End dimension is registered before we hand the player off.
        dimensionManager.getOrCreateDimension(DimensionType.END, 6);
        dimensionManager.switchTo(DimensionType.END);
        ctx.activeDimension = DimensionType.END;
        ctx.endReturnX = player.getPosition().x;
        ctx.endReturnZ = player.getPosition().z;
        ctx.endReturnY = player.getPosition().y;
        // The End spawn platform. Y=64 lines up with the decorated 5x5 obsidian
        // platform; +1 to stand on top of it.
        player.setPosition(100.5, 65.0, 0.5);
        player.resetVelocity();
    }

    /**
     * Teleport the player back to the Overworld. Triggered by walking into an
     * end_gateway block in the End.
     */
    public static void teleportBackToOverworld(Player player, GameContext ctx, DimensionManager dimensionManager) {
        if (dimensionManager == null) return;
        dimensionManager.switchTo(DimensionType.OVERWORLD);
        ctx.activeDimension = DimensionType.OVERWORLD;
        double tx = ctx.endReturnX, ty = ctx.endReturnY, tz = ctx.endReturnZ;
        if (tx == 0 && tz == 0) {
            // Save never recorded a return point — teleport to the stronghold
            // center instead so the player has a meaningful destination.
            tx = StrongholdLocator.getCenterX();
            tz = StrongholdLocator.getCenterZ();
            ty = 64.0;
        }
        player.setPosition(tx + 0.5, ty, tz + 0.5);
        player.resetVelocity();
    }
}
