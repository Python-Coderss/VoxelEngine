package com.voxel.world;

import com.voxel.utils.BlockDataManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the Showcase detail pass: the plaza dressing, gateway runner and relics,
 * gallery mosaic and placards, workshop pegboard and awning, machine-hall shaft
 * run, farm paddock/shed/jetty, and the outdoor benches and flower beds.
 */
public class ShowcaseWorldDetailTest {

    private ShowcaseWorldGenerator gen;

    @Before
    public void setUp() {
        BlockDataManager bdm = new BlockDataManager() {
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId > 0;
            }

            @Override
            public Integer findBlockId(String name) {
                if (name != null && name.toLowerCase().contains("end_portal")
                        && !name.toLowerCase().contains("frame")) {
                    return 995;
                }
                return null;
            }
        };
        gen = new ShowcaseWorldGenerator(7L, bdm);
    }

    private int at(int x, int y, int z) {
        return gen.getBlockType(x, y, z, ShowcaseWorldAuthor.G);
    }

    @Test
    public void plazaDressingSurroundsTheFountain() {
        int g = ShowcaseWorldAuthor.G;
        assertEquals("gold statue", 138, at(10, g + 2, -6));
        assertEquals("planter rim", 926, at(-8, g + 1, -11));
        assertEquals("planter soil", 13, at(-7, g + 1, -11));
        assertEquals("fountain bench", 206, at(5, g + 1, -5));
        assertEquals("plaza hedge", 4, at(9, g + 1, -13));
        assertEquals("gate banner", DecorBlocks.BANNER_LOWER_NS, at(4, g + 3, -11));
        assertEquals("notice board", 72, at(-9, g + 2, -4));
    }

    @Test
    public void gatewayHallHasRunnersBannersAndRelics() {
        int g = ShowcaseWorldAuthor.G;
        assertEquals("wool runner", 91, at(2, g, -30));
        assertEquals("marble edging", 926, at(3, g, -30));
        assertEquals("centre aisle stays stone", 131, at(0, g, -30));
        assertEquals("wall banner", DecorBlocks.BANNER_LOWER_EW, at(-20, g + 2, -38));
        assertEquals("relic plinth", 926, at(-15, g + 1, -38));
        assertEquals("nether relic", 16, at(-15, g + 2, -38));
        assertEquals("map table", 72, at(0, g + 2, -28));
        assertEquals("hanging lantern", 17, at(6, g + 5, -32));
    }

    @Test
    public void galleryGetsMosaicPlacardsAndPosts() {
        int g = ShowcaseWorldAuthor.G;
        assertEquals("mosaic inlay", 900, at(0, g, -55));
        assertEquals("aisle post", 471, at(-3, g + 1, -62));
        assertEquals("placard cap", 138, at(-17, g + 2, -60));
        assertEquals("gallery bench", 206, at(-16, g + 1, -49));
        assertEquals("gallery banner", DecorBlocks.BANNER_LOWER_NS, at(8, g + 2, -48));
        assertEquals("entrance pot", DecorBlocks.FLOWER_POT, at(-3, g + 1, -49));
    }

    @Test
    public void workshopGetsWorkbenchPegboardAndAwning() {
        int g = ShowcaseWorldAuthor.G;
        assertEquals("workbench top", 72, at(29, g + 2, 2));
        assertEquals("tool on the bench", 417, at(27, g + 3, 2));
        assertEquals("pegboard", 72, at(40, g + 3, -9));
        assertEquals("pegboard tool", 417, at(40, g + 4, -9));
        assertEquals("stock crate", 91, at(44, g + 2, -8));
        assertEquals("sawdust", 14, at(37, g, -8));
        assertEquals("porch awning", 72, at(24, g + 4, 0));
        assertEquals("hand cart chest", 118, at(21, g + 2, 5));
    }

    @Test
    public void machineHallGetsShaftRunAndControlDesk() {
        int g = ShowcaseWorldAuthor.G;
        assertEquals("drive shaft", 292, at(-35, g + 4, -10));
        assertEquals("shaft corner cog", 295, at(-45, g + 4, -10));
        assertEquals("control lamp", 28, at(-39, g + 2, 3));
        assertEquals("gear sculpture", 295, at(-55, g + 3, -4));
        assertEquals("machine signpost", 5, at(-24, g + 3, 3));
    }

    @Test
    public void farmGetsLanesPaddockShedAndJetty() {
        int g = ShowcaseWorldAuthor.G;
        assertEquals("field lane", 131, at(0, g, 23));
        assertEquals("paddock post", 5, at(-22, g + 1, 43));
        assertEquals("paddock trough", 15, at(3, g + 1, 31));
        assertEquals("wool bale", 91, at(-13, g + 1, 37));
        assertEquals("scarecrow head", 42, at(6, g + 3, 46));
        assertEquals("compost soil", 13, at(-13, g + 1, 48));
        assertEquals("toolshed door", DecorBlocks.SPRUCE_DOOR_LOWER, at(19, g + 1, 27));
        assertEquals("pond jetty", 72, at(-5, g + 1, 43));
        assertEquals("jetty trapdoor", DecorBlocks.OAK_TRAPDOOR, at(-8, g + 2, 43));
        assertEquals("pond water", 15, at(-11, g, 43));
    }

    @Test
    public void walkwaysGetBenchesAndFlowerBeds() {
        int g = ShowcaseWorldAuthor.G;
        assertEquals("walkway bench", 206, at(-5, g + 1, -18));
        assertEquals("walkway flower pot", DecorBlocks.FLOWER_POT, at(4, g + 1, -15));
        assertEquals("walkway signpost", 5, at(4, g + 3, -19));
    }
}
