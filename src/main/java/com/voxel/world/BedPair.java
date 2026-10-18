package com.voxel.world;

import com.voxel.World;

/**
 * Voxel-level state transitions for a bed: a foot cell plus a head cell one
 * step along the bed's facing.
 *
 * <p>Kept beside {@link DoorColumn} and away from the OpenGL-facing interaction
 * code so the multiblock rules are testable against a plain {@link World}:
 * placing the foot stamps the head, breaking either half clears the other, and
 * a mismatched neighbour is never touched.</p>
 */
public final class BedPair {

    /** Write hook; returns true when the voxel was actually stored. */
    public interface Setter {
        boolean set(int x, int y, int z, int blockId, int extraData);
    }

    private BedPair() {}

    /**
     * Places the head cell for a foot that has already been validated by the
     * caller (free, no player/entity overlap).
     *
     * @return true when the head was written
     */
    public static boolean place(World world, Setter setter, int x, int y, int z, int footId) {
        if (!DecorBlocks.isBedFoot(footId)) return false;
        int headId = DecorBlocks.bedHeadForFoot(footId);
        int headX = x + DecorBlocks.bedPartnerDx(footId);
        int headZ = z + DecorBlocks.bedPartnerDz(footId);
        return setter.set(headX, y, headZ, headId, 0);
    }

    /**
     * Clears the opposite cell when it is exactly this bed's partner.
     *
     * @return true when the partner was cleared
     */
    public static boolean removePartner(World world, Setter setter,
                                        int x, int y, int z, int blockId) {
        if (!DecorBlocks.isBed(blockId)) return false;
        int partnerX = x + DecorBlocks.bedPartnerDx(blockId);
        int partnerZ = z + DecorBlocks.bedPartnerDz(blockId);
        if (world.getVoxel(partnerX, y, partnerZ) != DecorBlocks.bedPartner(blockId)) {
            return false;
        }
        return setter.set(partnerX, y, partnerZ, 0, 0);
    }
}
