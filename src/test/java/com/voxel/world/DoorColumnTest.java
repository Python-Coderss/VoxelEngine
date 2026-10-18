package com.voxel.world;

import com.voxel.World;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Behavioural tests for the door multiblock, run against a plain in-memory
 * world so no GL context or {@code ChunkManager} is needed.
 *
 * <p>The rules being pinned down: one item stamps both halves, using the door
 * swings both halves into the 90°-rotated model (and back), breaking either
 * half removes the other, and a mismatched/half-broken column is left alone.</p>
 */
public class DoorColumnTest {

    private static final int LOWER = DecorBlocks.OAK_DOOR_LOWER;
    private static final int UPPER = DecorBlocks.OAK_DOOR_UPPER;

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** Minimal overridable world: stores packed voxels in a map. */
    private static final class MapWorld extends World {
        private final Map<String, Integer> cells = new HashMap<>();

        MapWorld() { super(1); }

        @Override
        public int getVoxel(int x, int y, int z) {
            return cells.getOrDefault(key(x, y, z), 0) & 0xFFFF;
        }

        @Override
        public int getRawVoxel(int x, int y, int z) {
            return cells.getOrDefault(key(x, y, z), 0);
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
    public void placingStampsTheUpperHalfAboveTheLower() {
        MapWorld world = new MapWorld();
        world.setVoxel(3, 5, 7, LOWER);

        assertTrue(DoorColumn.placeUpper((x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                3, 5, 7, LOWER));

        assertEquals(LOWER, world.getVoxel(3, 5, 7));
        assertEquals(UPPER, world.getVoxel(3, 6, 7));
        assertEquals("a freshly placed door is closed", 0, world.getRawVoxel(3, 6, 7) >> 16);
    }

    @Test
    public void placingUsesTheWallOrientationOfTheLowerHalf() {
        MapWorld world = new MapWorld();
        int lowerEw = DecorBlocks.OAK_DOOR_LOWER_EW;
        world.setVoxel(1, 2, 3, lowerEw);

        assertTrue(DoorColumn.placeUpper((x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                1, 2, 3, lowerEw));
        assertEquals(DecorBlocks.OAK_DOOR_UPPER_EW, world.getVoxel(1, 3, 3));
    }

    @Test
    public void usingTheDoorSwingsBothHalvesAndTogglesBack() {
        MapWorld world = new MapWorld();
        world.setVoxel(4, 5, 4, LOWER);
        world.setVoxel(4, 6, 4, UPPER);

        Boolean open = DoorColumn.toggle(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                4, 5, 4, LOWER);
        assertEquals(Boolean.TRUE, open);
        assertEquals(DecorBlocks.OAK_DOOR_LOWER_EW, world.getVoxel(4, 5, 4));
        assertEquals(DecorBlocks.OAK_DOOR_UPPER_EW, world.getVoxel(4, 6, 4));
        assertTrue(DecorBlocks.isDoorOpen(world.getRawVoxel(4, 5, 4) >> 16));
        assertTrue(DecorBlocks.isDoorOpen(world.getRawVoxel(4, 6, 4) >> 16));

        // Clicking the top half closes it again, restoring the original blocks.
        Boolean closed = DoorColumn.toggle(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                4, 6, 4, DecorBlocks.OAK_DOOR_UPPER_EW);
        assertEquals(Boolean.FALSE, closed);
        assertEquals(LOWER, world.getVoxel(4, 5, 4));
        assertEquals(UPPER, world.getVoxel(4, 6, 4));
        assertFalse(DecorBlocks.isDoorOpen(world.getRawVoxel(4, 5, 4) >> 16));
    }

    @Test
    public void toggleKeepsUnrelatedExtraData() {
        MapWorld world = new MapWorld();
        // e.g. a door placed by a machine that stamped bits 1-3.
        world.setVoxelWithData(4, 5, 4, LOWER, 0b1110);
        world.setVoxelWithData(4, 6, 4, UPPER, 0b1110);

        DoorColumn.toggle(world, (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                4, 5, 4, LOWER);
        assertEquals(0b1111, (world.getRawVoxel(4, 5, 4) >> 16) & 0xFF);
        assertEquals(0b1111, (world.getRawVoxel(4, 6, 4) >> 16) & 0xFF);
    }

    @Test
    public void halfBrokenDoorRefusesToOpen() {
        MapWorld world = new MapWorld();
        world.setVoxel(4, 5, 4, LOWER); // top half missing

        Boolean result = DoorColumn.toggle(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                4, 5, 4, LOWER);
        assertNull(result);
        assertEquals("a half door must not swing into a ghost panel", LOWER, world.getVoxel(4, 5, 4));
        assertEquals(0, world.getVoxel(4, 6, 4));
    }

    @Test
    public void mismatchedWoodDoesNotToggleTogether() {
        MapWorld world = new MapWorld();
        world.setVoxel(4, 5, 4, LOWER);
        world.setVoxel(4, 6, 4, DecorBlocks.SPRUCE_DOOR_UPPER);

        assertNull(DoorColumn.toggle(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                4, 5, 4, LOWER));
        assertEquals(LOWER, world.getVoxel(4, 5, 4));
    }

    @Test
    public void breakingEitherHalfRemovesTheColumn() {
        MapWorld world = new MapWorld();
        world.setVoxel(8, 9, 8, LOWER);
        world.setVoxel(8, 10, 8, UPPER);

        assertTrue(DoorColumn.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                8, 9, 8, LOWER));
        assertEquals("breaking the bottom takes the top", 0, world.getVoxel(8, 10, 8));
        assertEquals("the clicked cell is cleared by breakBlock", LOWER, world.getVoxel(8, 9, 8));

        world.setVoxel(8, 9, 8, LOWER);
        world.setVoxel(8, 10, 8, UPPER);
        assertTrue(DoorColumn.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                8, 10, 8, UPPER));
        assertEquals("breaking the top takes the bottom", 0, world.getVoxel(8, 9, 8));
    }

    @Test
    public void removingAPartnerToleratesMissingOrMismatchedHalves() {
        MapWorld world = new MapWorld();
        world.setVoxel(8, 9, 8, LOWER);
        world.setVoxel(8, 10, 8, DecorBlocks.SPRUCE_DOOR_UPPER);

        assertFalse(DoorColumn.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                8, 9, 8, LOWER));
        assertEquals("a different wood is not ours to break",
                DecorBlocks.SPRUCE_DOOR_UPPER, world.getVoxel(8, 10, 8));

        assertFalse(DoorColumn.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                8, 9, 8, 1));
    }

    @Test
    public void openDoorStillBreaksAsOneColumn() {
        MapWorld world = new MapWorld();
        world.setVoxel(2, 3, 2, DecorBlocks.OAK_DOOR_LOWER_EW);
        world.setVoxel(2, 4, 2, DecorBlocks.OAK_DOOR_UPPER_EW);

        assertTrue(DoorColumn.removePartner(world,
                (x, y, z, id, extra) -> write(world, x, y, z, id, extra),
                2, 4, 2, DecorBlocks.OAK_DOOR_UPPER_EW));
        assertEquals(0, world.getVoxel(2, 3, 2));
    }
}
