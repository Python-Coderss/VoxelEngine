package com.voxel.world;

import com.voxel.World;

/**
 * Voxel-level state transitions for a door column.
 *
 * <p>A door is one item backed by two stacked blocks (see
 * {@link DecorBlocks}). Keeping the pair math here — instead of in the
 * OpenGL-facing interaction code — makes the multiblock rules testable against
 * a plain {@link World}: placing stamps the top half, using swaps both halves
 * to the 90°-rotated model, and breaking removes the opposite half so no
 * floating top or bottom is left behind.</p>
 *
 * <p>Writes go through {@link Setter} so the caller can route them through
 * {@code ChunkManager} and keep dirty slots, lighting and redstone in sync.</p>
 */
public final class DoorColumn {

    /** Write hook; returns true when the voxel was actually stored. */
    public interface Setter {
        boolean set(int x, int y, int z, int blockId, int extraData);
    }

    private DoorColumn() {}

    /**
     * Places the top half above an already-validated lower cell. The caller is
     * responsible for checking that the cell is free and does not intersect
     * the player or an entity.
     *
     * @param lowerId the lower door block that has been (or is about to be) set
     * @return true when the top half was written
     */
    public static boolean placeUpper(Setter setter, int x, int y, int z, int lowerId) {
        if (!DecorBlocks.isDoorLower(lowerId)) return false;
        return setter.set(x, y + 1, z, DecorBlocks.doorPartner(lowerId), 0);
    }

    /**
     * Swings the whole column to the other orientation and flips the open bit.
     *
     * @return the new open state, or {@code null} when the two cells are not a
     *         matching pair (half missing, mismatched wood, not a door)
     */
    public static Boolean toggle(World world, Setter setter, int x, int y, int z, int blockId) {
        if (!DecorBlocks.isDoor(blockId)) return null;
        int lowerId = DecorBlocks.doorLowerOf(blockId);
        int lowerY = DecorBlocks.isDoorLower(blockId) ? y : y - 1;
        int lower = world.getVoxel(x, lowerY, z);
        int upper = world.getVoxel(x, lowerY + 1, z);
        if (DecorBlocks.doorLowerOf(lower) != lowerId
                || DecorBlocks.doorLowerOf(upper) != lowerId) {
            return null;
        }

        boolean open = DecorBlocks.isDoorOpen((world.getRawVoxel(x, y, z) >> 16) & 0xFF);
        boolean target = !open;
        for (int i = 0; i < 2; i++) {
            int py = lowerY + i;
            int part = world.getVoxel(x, py, z);
            int extra = ((world.getRawVoxel(x, py, z) >> 16) & 0xFF)
                    & ~DecorBlocks.DOOR_OPEN_BIT;
            if (target) extra |= DecorBlocks.DOOR_OPEN_BIT;
            setter.set(x, py, z, DecorBlocks.doorToggled(part), extra);
        }
        return target;
    }

    /**
     * Removes the opposite half of the column when it belongs to the same
     * wooden door. Tolerates an already half-broken column by doing nothing.
     *
     * @return true when a partner block was cleared
     */
    public static boolean removePartner(World world, Setter setter, int x, int y, int z, int blockId) {
        if (!DecorBlocks.isDoor(blockId)) return false;
        int partnerY = y + (DecorBlocks.isDoorLower(blockId) ? 1 : -1);
        int partner = world.getVoxel(x, partnerY, z);
        if (DecorBlocks.doorLowerOf(partner) != DecorBlocks.doorLowerOf(blockId)) {
            return false;
        }
        return setter.set(x, partnerY, z, 0, 0);
    }
}
