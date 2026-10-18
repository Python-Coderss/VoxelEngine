package com.voxel.world;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Guards the decoration-block table behind the door/trapdoor/ladder/flower-pot
 * fix. The reported bug was "missing doors": textures and models shipped but no
 * door block was ever registered, so every authored doorway was a bare gap.
 *
 * <p>These checks keep the single source of truth ({@link DecorBlocks}) honest:
 * unique ids in a free range, a real model file on disk for every name, and a
 * well-formed two-half door for every wood.</p>
 */
public class DecorationBlocksTest {

    @Test
    public void namesAndIdsAreUniqueAndInTheFreeRange() {
        int n = DecorBlocks.NAMES.length;
        assertEquals("four woods x four door halves + two trapdoors + ladder + pot"
                        + " + eight bed halves + four banner halves",
                32, n);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < n; i++) {
            assertTrue("name '" + DecorBlocks.NAMES[i] + "' is unique",
                    names.add(DecorBlocks.NAMES[i]));
            assertEquals("id(" + DecorBlocks.NAMES[i] + ")",
                    DecorBlocks.FIRST_ID + i, DecorBlocks.id(DecorBlocks.NAMES[i]));
        }
        // The range must stay clear of the Sift set (<= 937) and the book
        // blocks (940-947), or registration would clash.
        assertTrue("ids start above the book blocks", DecorBlocks.FIRST_ID > 947);
        assertEquals("unknown name", -1, DecorBlocks.id("nope_not_a_block"));
        // The canonical item-backed names are the first block of each family,
        // so the bed/banner items auto-link to a placeable block.
        assertEquals(DecorBlocks.BED_FOOT_NORTH, DecorBlocks.id("bed"));
        assertEquals(DecorBlocks.BANNER_LOWER_NS, DecorBlocks.id("banner"));
        assertFalse("doors and beds never overlap",
                DecorBlocks.isBed(DecorBlocks.OAK_DOOR_LOWER));
        assertFalse("banners are not doors",
                DecorBlocks.isDoor(DecorBlocks.BANNER_LOWER_NS));
    }

    @Test
    public void everyDoorIsATwoBlockLowerUpperPair() {
        int[][] kits = {
            {DecorBlocks.OAK_DOOR_LOWER, DecorBlocks.OAK_DOOR_UPPER,
             DecorBlocks.OAK_DOOR_LOWER_EW, DecorBlocks.OAK_DOOR_UPPER_EW},
            {DecorBlocks.SPRUCE_DOOR_LOWER, DecorBlocks.SPRUCE_DOOR_UPPER,
             DecorBlocks.SPRUCE_DOOR_LOWER_EW, DecorBlocks.SPRUCE_DOOR_UPPER_EW},
            {DecorBlocks.BIRCH_DOOR_LOWER, DecorBlocks.BIRCH_DOOR_UPPER,
             DecorBlocks.BIRCH_DOOR_LOWER_EW, DecorBlocks.BIRCH_DOOR_UPPER_EW},
            {DecorBlocks.DARK_OAK_DOOR_LOWER, DecorBlocks.DARK_OAK_DOOR_UPPER,
             DecorBlocks.DARK_OAK_DOOR_LOWER_EW, DecorBlocks.DARK_OAK_DOOR_UPPER_EW},
        };
        Set<Integer> seen = new HashSet<>();
        for (int[] kit : kits) {
            assertEquals("four blocks per wood", 4, kit.length);
            for (int id : kit) {
                assertTrue("door id above the decor base", id >= DecorBlocks.FIRST_ID);
                assertTrue("door id is unique", seen.add(id));
            }
            assertFalse("lower and upper differ", kit[0] == kit[1]);
            assertFalse("Z and EW panels differ", kit[0] == kit[2]);
        }
        assertEquals("16 door blocks", 16, seen.size());
    }

    @Test
    public void doorColumnsPairAndToggleWithinOneWood() {
        int[][] kits = {
            {DecorBlocks.OAK_DOOR_LOWER, DecorBlocks.OAK_DOOR_UPPER,
             DecorBlocks.OAK_DOOR_LOWER_EW, DecorBlocks.OAK_DOOR_UPPER_EW},
            {DecorBlocks.SPRUCE_DOOR_LOWER, DecorBlocks.SPRUCE_DOOR_UPPER,
             DecorBlocks.SPRUCE_DOOR_LOWER_EW, DecorBlocks.SPRUCE_DOOR_UPPER_EW},
            {DecorBlocks.BIRCH_DOOR_LOWER, DecorBlocks.BIRCH_DOOR_UPPER,
             DecorBlocks.BIRCH_DOOR_LOWER_EW, DecorBlocks.BIRCH_DOOR_UPPER_EW},
            {DecorBlocks.DARK_OAK_DOOR_LOWER, DecorBlocks.DARK_OAK_DOOR_UPPER,
             DecorBlocks.DARK_OAK_DOOR_LOWER_EW, DecorBlocks.DARK_OAK_DOOR_UPPER_EW},
        };
        for (int[] kit : kits) {
            int lower = kit[0], upper = kit[1], lowerEw = kit[2], upperEw = kit[3];
            assertTrue(DecorBlocks.isDoor(lower));
            assertTrue(DecorBlocks.isDoorLower(lower));
            assertFalse(DecorBlocks.isDoorLower(upper));
            assertTrue(DecorBlocks.isDoorUpper(upper));

            assertEquals("upper pairs with lower", upper, DecorBlocks.doorPartner(lower));
            assertEquals("lower pairs with upper", lower, DecorBlocks.doorPartner(upper));
            assertEquals(lower, DecorBlocks.doorLowerOf(upper));
            assertEquals(lowerEw, DecorBlocks.doorLowerOf(upperEw));

            // Opening a door is the 90°-rotated panel, and back again.
            assertEquals(lowerEw, DecorBlocks.doorToggled(lower));
            assertEquals(upperEw, DecorBlocks.doorToggled(upper));
            assertEquals(lower, DecorBlocks.doorToggled(lowerEw));
            assertEquals(upper, DecorBlocks.doorToggled(upperEw));

            assertEquals("north-south wall uses the vanilla panel",
                    lower, DecorBlocks.doorLowerForWall(lower, true));
            assertEquals("east-west wall uses the rotated panel",
                    lowerEw, DecorBlocks.doorLowerForWall(lower, false));
        }
        assertFalse(DecorBlocks.isDoor(DecorBlocks.OAK_TRAPDOOR));
        assertEquals(-1, DecorBlocks.doorPartner(1));
    }

    @Test
    public void openBitOnlyAnswersToItsOwnFlag() {
        assertFalse(DecorBlocks.isDoorOpen(0));
        assertTrue(DecorBlocks.isDoorOpen(DecorBlocks.DOOR_OPEN_BIT));
        assertTrue(DecorBlocks.isDoorOpen(0b1011));
        assertFalse("bit 1 belongs to other state", DecorBlocks.isDoorOpen(0b1110));
    }

    @Test
    public void everyDecorationNameHasAModelOnDisk() {
        File dir = new File("src/main/resources/assets/minecraft/models/block");
        assumeTrue("model directory present", dir.isDirectory());
        for (String name : DecorBlocks.NAMES) {
            File model = new File(dir, name + ".json");
            assertTrue("model for " + name + " exists at " + model.getPath(),
                    model.isFile());
        }
    }
}
