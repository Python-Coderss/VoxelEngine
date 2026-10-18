package com.voxel.world;

import com.voxel.World;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Beds and standing banners are multiblocks like doors: one item stamps both
 * cells, breaking either half removes the other, and a mismatched neighbour is
 * never deleted.
 */
public class BedBannerTest {

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static final class MapWorld extends World {
        private final Map<String, Integer> cells = new HashMap<String, Integer>();

        MapWorld() { super(1); }

        @Override
        public int getVoxel(int x, int y, int z) {
            Integer value = cells.get(key(x, y, z));
            return value == null ? 0 : value & 0xFFFF;
        }

        @Override
        public void setVoxel(int x, int y, int z, int type) {
            setVoxelWithData(x, y, z, type, 0);
        }

        @Override
        public void setVoxelWithData(int x, int y, int z, int type, int extra) {
            cells.put(key(x, y, z), (type & 0xFFFF) | ((extra & 0xFF) << 16));
        }
    }

    private static boolean write(World world, int x, int y, int z, int id, int extra) {
        world.setVoxelWithData(x, y, z, id, extra);
        return true;
    }

    @Test
    public void everyBedFacingPairsAFootAndAHeadCell() {
        for (DecorBlocks.Facing facing : DecorBlocks.Facing.values()) {
            int foot = DecorBlocks.bedFootForFacing(facing);
            int head = DecorBlocks.bedHeadForFoot(foot);

            assertTrue(facing + " foot is a bed", DecorBlocks.isBed(foot));
            assertTrue(facing + " foot is the foot", DecorBlocks.isBedFoot(foot));
            assertTrue(facing + " head is the head", DecorBlocks.isBedHead(head));
            assertEquals(facing + " partner", head, DecorBlocks.bedPartner(foot));
            assertEquals(facing + " partner back", foot, DecorBlocks.bedPartner(head));
            assertEquals(facing + " facing", facing, DecorBlocks.bedFacing(foot));
            assertEquals(facing + " facing is shared", facing, DecorBlocks.bedFacing(head));
            assertEquals("foot dx", facing.dx, DecorBlocks.bedPartnerDx(foot));
            assertEquals("foot dz", facing.dz, DecorBlocks.bedPartnerDz(foot));
            assertEquals("head dx", -facing.dx, DecorBlocks.bedPartnerDx(head));
            assertEquals("head dz", -facing.dz, DecorBlocks.bedPartnerDz(head));
        }
    }

    @Test
    public void placingTheFootStampsTheHeadOneCellAway() {
        MapWorld world = new MapWorld();
        int foot = DecorBlocks.BED_FOOT_EAST;
        world.setVoxel(5, 6, 5, foot);

        assertTrue(BedPair.place(world, (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                5, 6, 5, foot));
        assertEquals("the head sits one cell east", DecorBlocks.BED_HEAD_EAST,
                world.getVoxel(6, 6, 5));
        assertEquals("and only one cell", 0, world.getVoxel(5, 6, 6));
    }

    @Test
    public void breakingEitherHalfRemovesTheOther() {
        for (int foot : new int[]{DecorBlocks.BED_FOOT_NORTH, DecorBlocks.BED_FOOT_SOUTH,
                DecorBlocks.BED_FOOT_EAST, DecorBlocks.BED_FOOT_WEST}) {
            MapWorld world = new MapWorld();
            int head = DecorBlocks.bedHeadForFoot(foot);
            world.setVoxel(3, 4, 3, foot);
            world.setVoxel(3 + DecorBlocks.bedPartnerDx(foot), 4,
                    3 + DecorBlocks.bedPartnerDz(foot), head);

            assertTrue("breaking the foot removes the head",
                    BedPair.removePartner(world,
                            (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                            3, 4, 3, foot));
            assertEquals(0, world.getVoxel(3 + DecorBlocks.bedPartnerDx(foot), 4,
                    3 + DecorBlocks.bedPartnerDz(foot)));

            // Now break from the head side.
            world.setVoxel(3, 4, 3, foot);
            int headX = 3 + DecorBlocks.bedPartnerDx(foot);
            int headZ = 3 + DecorBlocks.bedPartnerDz(foot);
            world.setVoxel(headX, 4, headZ, head);
            assertTrue("breaking the head removes the foot",
                    BedPair.removePartner(world,
                            (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                            headX, 4, headZ, head));
            assertEquals(0, world.getVoxel(3, 4, 3));
        }
    }

    @Test
    public void aMismatchedBedHalfIsNotDeleted() {
        MapWorld world = new MapWorld();
        world.setVoxel(3, 4, 3, DecorBlocks.BED_FOOT_NORTH);
        world.setVoxel(3, 4, 2, DecorBlocks.BED_HEAD_SOUTH);

        assertFalse(BedPair.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                3, 4, 3, DecorBlocks.BED_FOOT_NORTH));
        assertEquals("the other bed stays", DecorBlocks.BED_HEAD_SOUTH,
                world.getVoxel(3, 4, 2));
        assertFalse(BedPair.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                3, 4, 3, 1));
    }

    @Test
    public void bannersPairLowerAndUpperOnOneColumn() {
        assertEquals(DecorBlocks.BANNER_UPPER_NS, DecorBlocks.bannerPartner(DecorBlocks.BANNER_LOWER_NS));
        assertEquals(DecorBlocks.BANNER_LOWER_NS, DecorBlocks.bannerPartner(DecorBlocks.BANNER_UPPER_NS));
        assertEquals(DecorBlocks.BANNER_UPPER_EW, DecorBlocks.bannerPartner(DecorBlocks.BANNER_LOWER_EW));
        assertEquals(DecorBlocks.BANNER_UPPER_EW, DecorBlocks.bannerUpperForLower(DecorBlocks.BANNER_LOWER_EW));
        assertEquals(-1, DecorBlocks.bannerPartner(1));

        assertEquals(DecorBlocks.BANNER_LOWER_NS,
                DecorBlocks.bannerLowerForFacing(DecorBlocks.Facing.NORTH));
        assertEquals(DecorBlocks.BANNER_LOWER_NS,
                DecorBlocks.bannerLowerForFacing(DecorBlocks.Facing.SOUTH));
        assertEquals(DecorBlocks.BANNER_LOWER_EW,
                DecorBlocks.bannerLowerForFacing(DecorBlocks.Facing.EAST));
        assertEquals(DecorBlocks.BANNER_LOWER_EW,
                DecorBlocks.bannerLowerForFacing(DecorBlocks.Facing.WEST));
    }

    @Test
    public void placingABannerStampsTheUpperCell() {
        MapWorld world = new MapWorld();
        world.setVoxel(2, 5, 2, DecorBlocks.BANNER_LOWER_EW);

        assertTrue(BannerColumn.placeUpper(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                2, 5, 2, DecorBlocks.BANNER_LOWER_EW));
        assertEquals(DecorBlocks.BANNER_UPPER_EW, world.getVoxel(2, 6, 2));
        assertEquals(0, world.getVoxel(2, 4, 2));
    }

    @Test
    public void breakingEitherBannerHalfRemovesTheColumn() {
        MapWorld world = new MapWorld();
        world.setVoxel(8, 9, 8, DecorBlocks.BANNER_LOWER_NS);
        world.setVoxel(8, 10, 8, DecorBlocks.BANNER_UPPER_NS);

        assertTrue(BannerColumn.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                8, 9, 8, DecorBlocks.BANNER_LOWER_NS));
        assertEquals(0, world.getVoxel(8, 10, 8));

        world.setVoxel(8, 9, 8, DecorBlocks.BANNER_LOWER_NS);
        world.setVoxel(8, 10, 8, DecorBlocks.BANNER_UPPER_NS);
        assertTrue(BannerColumn.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                8, 10, 8, DecorBlocks.BANNER_UPPER_NS));
        assertEquals(0, world.getVoxel(8, 9, 8));
    }

    @Test
    public void aMismatchedBannerHalfIsNotDeleted() {
        MapWorld world = new MapWorld();
        world.setVoxel(1, 2, 1, DecorBlocks.BANNER_LOWER_NS);
        world.setVoxel(1, 3, 1, DecorBlocks.BANNER_UPPER_EW);

        assertFalse(BannerColumn.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                1, 2, 1, DecorBlocks.BANNER_LOWER_NS));
        assertEquals(DecorBlocks.BANNER_UPPER_EW, world.getVoxel(1, 3, 1));
    }
}
