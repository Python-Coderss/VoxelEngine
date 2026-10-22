package com.voxel.world;

import com.voxel.utils.BlockDataManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the Point &amp; Click detail pass: the hamlet, pond, orchard, camp,
 * farmstead, quarry works and the props dressing the original districts.
 *
 * <p>These are all authored at fixed coordinates, so a regression here means a
 * district stopped being built (or was silently overwritten by the nature
 * scatter).</p>
 */
public class PointClickWorldDetailTest {

    private PointClickWorldGenerator gen;

    @Before
    public void setUp() {
        BlockDataManager bdm = new BlockDataManager() {
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId > 0;
            }
        };
        gen = new PointClickWorldGenerator(42L, bdm);
    }

    private int at(int x, int y, int z) {
        return gen.getBlockType(x, y, z, PointClickWorldAuthor.G);
    }

    @Test
    public void hamletCottagesWellAndGreenExist() {
        int g = PointClickWorldAuthor.G;
        // Cottage A: a real EW door facing south, a hip roof and a chimney.
        assertEquals("cottage door", DecorBlocks.OAK_DOOR_LOWER_EW, at(-33, g + 1, -23));
        assertEquals("cottage door upper", DecorBlocks.OAK_DOOR_UPPER_EW, at(-33, g + 2, -23));
        assertEquals("cottage roof", 72, at(-33, g + 5, -27));
        assertEquals("cottage chimney", 130, at(-29, g + 1, -27));
        assertEquals("cottage bed", DecorBlocks.BED_FOOT_NORTH, at(-35, g + 1, -25));
        // The green's well: contained water, cobble rim, plank canopy.
        assertEquals("well water", 15, at(-28, g, -22));
        assertEquals("well rim", 71, at(-27, g + 1, -22));
        assertEquals("well roof", 72, at(-28, g + 4, -22));
        // Vegetable plot behind the lane.
        assertEquals("pumpkin plot", 42, at(-23, g + 1, -17));
        assertEquals("plot post", 5, at(-24, g + 1, -17));
    }

    @Test
    public void pondJettyRowboatAndFishingHutExist() {
        int g = PointClickWorldAuthor.G;
        assertEquals("pond water", 15, at(-47, g, -40));
        assertEquals("jetty pile replaces water", 5, at(-45, g, -40));
        assertEquals("jetty deck", 72, at(-45, g + 1, -38));
        assertEquals("jetty pile", 5, at(-45, g, -38));
        assertEquals("lily pad", 41, at(-49, g + 1, -39));
        assertEquals("rowboat hull", 72, at(-48, g + 1, -38));
        assertEquals("hut door", DecorBlocks.OAK_DOOR_LOWER_EW, at(-35, g + 1, -44));
    }

    @Test
    public void orchardAndTravellersCampExist() {
        int g = PointClickWorldAuthor.G;
        // Mixed orchard rows: oak, then birch in the next column.
        assertEquals("orchard oak", 5, at(-42, g + 1, 17));
        assertEquals("orchard birch", 46, at(-38, g + 1, 17));
        assertEquals("orchard fence", 5, at(-44, g + 1, 20));
        assertEquals("campfire", 17, at(-38, g, 38));
        assertEquals("tent", 91, at(-41, g + 1, 35));
        assertEquals("camp chest", 118, at(-34, g + 1, 38));
    }

    @Test
    public void farmsteadBarnWindmillAndPaddockExist() {
        int g = PointClickWorldAuthor.G;
        assertEquals("barn door", DecorBlocks.OAK_DOOR_LOWER, at(-14, g + 1, 29));
        assertEquals("barn door upper", DecorBlocks.OAK_DOOR_UPPER, at(-14, g + 2, 29));
        assertEquals("barn roof", 73, at(-18, g + 5, 26));
        // Windmill: log hub and a plank sail tip at the authored height cap.
        assertEquals("windmill hub", 5, at(-20, g + 7, 21));
        assertEquals("windmill sail", 72, at(-22, g + 9, 21));
        // Paddock trough and scarecrow.
        assertEquals("trough water", 15, at(-19, g + 1, 40));
        assertEquals("scarecrow head", 42, at(-15, g + 3, 40));
        // Wool bales beside the barn door.
        assertEquals("wool bale", 91, at(-13, g + 2, 27));
    }

    @Test
    public void quarryWorksExposeTimberCraneAndSpoil() {
        int g = PointClickWorldAuthor.G;
        assertEquals("derrick leg", 5, at(39, g + 1, -2));
        assertEquals("crane jib", 5, at(37, g + 6, 0));
        assertEquals("slung chain", 471, at(35, g + 4, 0));
        assertEquals("spoil heap", 54, at(42, g, -6));
        assertEquals("spoil ore", 61, at(41, g + 2, -6));
        assertEquals("site office door", DecorBlocks.OAK_DOOR_LOWER_EW, at(40, g + 1, 7));
    }

    @Test
    public void watchtowerHasARealUpperStorey() {
        int g = PointClickWorldAuthor.G;
        assertEquals("upper floor", 72, at(30, g + 4, -29));
        assertEquals("ladder hatch stays open", DecorBlocks.LADDER, at(28, g + 4, -30));
        assertEquals("bunk", DecorBlocks.BED_FOOT_NORTH, at(31, g + 5, -32));
        assertEquals("wall banner", DecorBlocks.BANNER_LOWER_NS, at(30, g + 5, -32));
        assertEquals("buttress", 71, at(26, g + 1, -33));
        assertEquals("stable roof", 72, at(23, g + 3, -26));
    }

    @Test
    public void stoneCircleAndGraveyardCompleteTheRing() {
        int g = PointClickWorldAuthor.G;
        // Standing stone, worn path, altar and braziers.
        assertEquals("standing stone", 132, at(36, g + 1, -20));
        assertEquals("approach path", 131, at(35, g, -20));
        assertEquals("altar base", 131, at(40, g + 1, -20));
        assertEquals("altar lamp", 17, at(40, g + 3, -20));
        assertEquals("brazier", 17, at(35, g + 2, -25));
        // Graveyard: iron fence with a gate, headstones and the mausoleum.
        assertEquals("grave fence", 471, at(36, g + 1, 28));
        assertEquals("grave gate", 0, at(36, g + 1, 32));
        assertEquals("headstone", 208, at(37, g + 1, 29));
        assertEquals("grave flower", 34, at(38, g + 1, 31));
        assertEquals("gravel walk", 54, at(40, g, 31));
        assertEquals("mausoleum door", DecorBlocks.OAK_DOOR_LOWER, at(41, g + 1, 31));
        assertEquals("mausoleum door upper", DecorBlocks.OAK_DOOR_UPPER, at(41, g + 2, 31));
        assertEquals("mausoleum roof", 208, at(42, g + 4, 32));
        assertEquals("gold finial", 138, at(42, g + 5, 32));
        assertEquals("mausoleum chest", 118, at(42, g + 1, 32));
    }

    @Test
    public void districtPropsDressTheOldBuilds() {
        int g = PointClickWorldAuthor.G;
        // Market: brick bakery oven, flower stall posts, wool rug in the lounge.
        assertEquals("bakery oven", 116, at(9, g + 1, -26));
        assertEquals("flower stall post", 5, at(-13, g + 2, -32));
        assertEquals("lounge rug", 91, at(5, g, -31));
        // Plaza: monument, notice board, gate banner and flowers on the wall.
        assertEquals("plaza monument", 138, at(-4, g + 4, 8));
        assertEquals("notice board", 72, at(6, g + 3, 9));
        assertEquals("gate banner", DecorBlocks.BANNER_LOWER_EW, at(11, g + 2, -2));
        assertEquals("border flower pot", DecorBlocks.FLOWER_POT, at(10, g + 2, 4));
        // Ruins: colonnade, fallen statue and mosaic apron.
        assertEquals("ruins colonnade", 131, at(35, g + 4, 26));
        assertEquals("fallen statue", 926, at(26, g + 1, 35));
        assertEquals("mosaic apron", 59, at(25, g, 26));
        // Avenue props between plaza and districts.
        assertEquals("avenue signpost", 72, at(11, g + 3, 3));
        assertEquals("avenue bench", 72, at(3, g + 2, 14));
    }
}
