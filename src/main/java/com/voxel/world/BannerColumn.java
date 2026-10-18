package com.voxel.world;

import com.voxel.World;

/**
 * Voxel-level state transitions for a standing banner: a lower cell plus an
 * upper cell on the same column.
 *
 * <p>Same contract as {@link DoorColumn} and {@link BedPair}: placing the
 * lower half stamps the upper half, breaking either clears the other, and a
 * mismatched neighbour is left alone.</p>
 */
public final class BannerColumn {

    /** Write hook; returns true when the voxel was actually stored. */
    public interface Setter {
        boolean set(int x, int y, int z, int blockId, int extraData);
    }

    private BannerColumn() {}

    /**
     * Places the upper half above an already-validated lower cell.
     *
     * @return true when the upper half was written
     */
    public static boolean placeUpper(World world, Setter setter,
                                    int x, int y, int z, int lowerId) {
        if (!DecorBlocks.isBannerLower(lowerId)) return false;
        return setter.set(x, y + 1, z, DecorBlocks.bannerUpperForLower(lowerId), 0);
    }

    /**
     * Clears the other half when it is exactly this banner's partner.
     *
     * @return true when the partner was cleared
     */
    public static boolean removePartner(World world, Setter setter,
                                        int x, int y, int z, int blockId) {
        if (!DecorBlocks.isBanner(blockId)) return false;
        int partnerY = y + (DecorBlocks.isBannerLower(blockId) ? 1 : -1);
        if (world.getVoxel(x, partnerY, z) != DecorBlocks.bannerPartner(blockId)) {
            return false;
        }
        return setter.set(x, partnerY, z, 0, 0);
    }
}
