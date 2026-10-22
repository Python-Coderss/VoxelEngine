package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the creative Showcase World's full terrain override.
 *
 * The generator must materialise the whole authored hub (plaza, dimension
 * gateway with one lit portal per dimension, workshop, redstone works, farm and
 * item gallery) directly in {@link ShowcaseWorldGenerator#populateSection} so
 * the world exists even when the save directory has no chunk files.
 *
 * Layout: 128x128 blocks centered on origin, flat hub surface at y=62 (G),
 * player spawn at (0, 63, 8.5).
 */
public class ShowcaseWorldGeneratorTest {

    /** Block id the stub assigns to the dynamically-registered end_portal. */
    private static final int STUB_END_PORTAL = 995;

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
                    return STUB_END_PORTAL;
                }
                return null;
            }
        };
        gen = new ShowcaseWorldGenerator(7L, bdm);
    }

    @Test
    public void plazaFloorFountainAndGuideChestExist() {
        int g = ShowcaseWorldAuthor.G;
        // Stone-brick pavement across the plaza.
        assertEquals("plaza floor", 131, gen.getBlockType(3, g, 3, g));
        // Gold inlay ring on the pavement.
        assertEquals("gold ring", 138, gen.getBlockType(12, g, 0, g));
        // Fountain: contained reflecting pool, jet and lamp.
        assertEquals("fountain water", 15, gen.getBlockType(1, g + 1, 1, g));
        assertEquals("fountain lamp", 17, gen.getBlockType(0, g + 5, 0, g));
        // Guide chest beside the welcome bookshelves.
        assertEquals("guide chest", 118, gen.getBlockType(-4, g + 1, 9, g));
        assertEquals("welcome bookshelf", 136, gen.getBlockType(-9, g + 1, 9, g));
    }

    @Test
    public void gatewayHoldsOneLitPortalPerDimension() {
        int g = ShowcaseWorldAuthor.G;
        int wall = -41; // hall bay line (z0 + 1)
        assertEquals("hall floor", 131, gen.getBlockType(0, g, -30, g));
        // Nether / Aether / Sift bays, one portal column each at their centre.
        assertEquals("nether portal", 19, gen.getBlockType(-15, g + 2, wall, g));
        assertEquals("aether portal", 106, gen.getBlockType(-5, g + 2, wall, g));
        assertEquals("sift portal", 934, gen.getBlockType(5, g + 2, wall, g));
        // End bay: an active end_portal pad flush with the floor.
        assertEquals("end portal pad", STUB_END_PORTAL, gen.getBlockType(15, g, wall, g));
    }

    @Test
    public void featureDistrictsExist() {
        int g = ShowcaseWorldAuthor.G;
        // Workshop stations.
        assertEquals("crafting table", 115, gen.getBlockType(30, g + 1, -6, g));
        assertEquals("furnace", 116, gen.getBlockType(33, g + 1, -6, g));
        assertEquals("anvil", 456, gen.getBlockType(36, g + 1, -6, g));
        assertEquals("enchanting table", 458, gen.getBlockType(34, g + 1, 5, g));
        // Book display (the newly added book blocks).
        assertEquals("book block", 940, gen.getBlockType(28, g + 3, -9, g));
        assertEquals("enchanted book block", 946, gen.getBlockType(34, g + 3, -9, g));
        // Redstone works: powered lamp and beacon landmark.
        assertEquals("redstone lamp", 28, gen.getBlockType(-36, g + 2, -6, g));
        assertEquals("beacon", 457, gen.getBlockType(-51, g + 3, 0, g));
        // Farm: irrigated farmland, a channel and an orchard tree.
        assertEquals("farmland", 907, gen.getBlockType(-13, g, 20, g));
        assertEquals("irrigation channel", 15, gen.getBlockType(-10, g, 20, g));
        assertEquals("orchard log", 5, gen.getBlockType(10, g + 1, 24, g));
        // Gallery pedestal with an ore on top.
        assertEquals("ore pedestal", 81, gen.getBlockType(-16, g + 2, -60, g));
    }

    @Test
    public void districtLandmarksExist() {
        int g = ShowcaseWorldAuthor.G;
        // Gallery colonnade: gold capitals over stone-brick columns.
        assertEquals("colonnade column", 131, gen.getBlockType(9, g + 1, -47, g));
        assertEquals("colonnade capital", 138, gen.getBlockType(9, g + 4, -47, g));
        // Gateway: twin arch lintels with glowstone lamps.
        assertEquals("arch lintel", 131, gen.getBlockType(11, g + 5, -21, g));
        assertEquals("arch lamp", 17, gen.getBlockType(11, g + 6, -21, g));
        // Redstone works: clock tower capped with a lamp.
        assertEquals("clock tower cap", 17, gen.getBlockType(-36, g + 12, -16, g));
        // Farm: windmill sail over the tower.
        assertEquals("windmill sail", 72, gen.getBlockType(-14, g + 8, 22, g));
        // Plaza: four obelisks with glowstone tips.
        assertEquals("obelisk tip", 17, gen.getBlockType(14, g + 7, 14, g));
        assertEquals("obelisk tip", 17, gen.getBlockType(-14, g + 7, -14, g));
    }

    @Test
    public void flatPlainProfileOutsideDistricts() {
        int g = ShowcaseWorldAuthor.G;
        assertEquals("grass surface", 1, gen.getBlockType(-40, g, 55, g));
        assertEquals("dirt layer", 13, gen.getBlockType(-40, g - 2, 55, g));
        assertEquals("deep stone", 2, gen.getBlockType(-40, 20, 55, g));
        assertEquals("air above", 0, gen.getBlockType(-40, g + 20, 55, g));
        assertEquals("edge grass", 1,
                gen.getBlockType(ShowcaseWorldAuthor.MAX, g, ShowcaseWorldAuthor.MAX, g));
    }

    @Test
    public void everyDoorwayInTheHubIsFilledWithARealDoor() {
        int g = ShowcaseWorldAuthor.G;
        int doors = 0;
        // Gateway hall + corridor: every authored entrance must materialise as a
        // real door block, not the bare air gap of the original bug.
        for (int x = -22; x <= 22; x++) {
            for (int z = -50; z <= -18; z++) {
                for (int y = g + 1; y <= g + 2; y++) {
                    int b = gen.getBlockType(x, y, z, g);
                    if (b >= DecorBlocks.FIRST_ID
                            && b < DecorBlocks.FIRST_ID + DecorBlocks.NAMES.length) {
                        doors++;
                    }
                }
            }
        }
        // The gateway's south entrance and the corridor/gallery doorways are
        // double doors, so several leaves are expected.
        assertTrue("hub doorways carry real door blocks (found " + doors + ")", doors >= 6);
        // The workshop and redstone works each have a gated entrance too.
        int sideDoors = 0;
        for (int x = -50; x <= 50; x++) {
            for (int z = -14; z <= 14; z++) {
                for (int y = g + 1; y <= g + 2; y++) {
                    int b = gen.getBlockType(x, y, z, g);
                    if (b >= DecorBlocks.FIRST_ID
                            && b < DecorBlocks.FIRST_ID + DecorBlocks.NAMES.length) {
                        sideDoors++;
                    }
                }
            }
        }
        assertTrue("workshop/redstone entrances carry real doors", sideDoors >= 4);
    }

    @Test
    public void spawnHeightMatchesFlatSurface() {
        assertEquals(ShowcaseWorldAuthor.G, gen.getHeight(0, 63, 0));
        assertEquals(ShowcaseWorldAuthor.G, gen.getHeight(-50, 63, 50));
    }

    @Test
    public void populateSectionFillsGroundAndStructures() {
        World world = new World(256);
        int g = ShowcaseWorldAuthor.G;
        // A below-ground section is fully solid (16x16x16).
        int solid = gen.populateSection(0, 2, 0, world, 0);
        assertEquals("underground section fully solid", 16 * 16 * 16, solid);
        // The surface section under the plaza holds the floor plus fountain.
        solid = gen.populateSection(0, g >> 4, 0, world, 0);
        assertTrue("surface section should be full", solid >= 16 * 16);
        // A high-air section inside the authored area still has gateway blocks.
        solid = gen.populateSection(-1, 4, -3, world, 0); // y 64..79
        assertTrue("gateway blocks above surface missing", solid > 0);
        // A far, fully-air section reports zero solids.
        solid = gen.populateSection(30, 9, 30, world, 0);
        assertEquals("far sky section empty", 0, solid);
    }
}
