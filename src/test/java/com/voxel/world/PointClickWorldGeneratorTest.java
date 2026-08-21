package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the Point & Click demo world's full terrain override.
 *
 * The generator must materialise the authored plaza (crafting table, furnace,
 * chest, both portals, gold pedestal, lamp posts) directly in
 * {@link PointClickWorldGenerator#populateSection} so the scene exists even
 * when the save directory has no chunk files. Layout: 128x128 blocks centered
 * on origin, flat plain surface at y=62 (G), player spawns at (0, 63, 0).
 */
public class PointClickWorldGeneratorTest {

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

    @Test
    public void plazaStationsExistAtAuthoredCoordinates() {
        int g = PointClickWorldAuthor.G;
        // Interactable stations clustered north of spawn.
        assertEquals("crafting table", 115, gen.getBlockType(-6, g + 1, -4, g));
        assertEquals("furnace", 116, gen.getBlockType(-3, g + 1, -4, g));
        assertEquals("chest", 118, gen.getBlockType(0, g + 1, -4, g));
        // Nether portal: obsidian base + portal interior column.
        assertEquals("nether portal frame base", 16, gen.getBlockType(4, g, -5, g));
        assertEquals("nether portal interior", 19, gen.getBlockType(4, g + 2, -5, g));
        // Aether portal: glowstone base + portal interior column.
        assertEquals("aether portal frame base", 17, gen.getBlockType(7, g, -5, g));
        assertEquals("aether portal interior", 106, gen.getBlockType(7, g + 2, -5, g));
        // Villager pedestal + welcome sign.
        assertEquals("gold pedestal", 138, gen.getBlockType(-2, g + 1, 4, g));
        assertEquals("bookshelf sign", 136, gen.getBlockType(0, g + 1, 6, g));
    }

    @Test
    public void plazaFloorAndLampPostsRender() {
        int g = PointClickWorldAuthor.G;
        // Stone-brick plaza floor overrides the grass within +-10 of origin.
        assertEquals("plaza floor", 131, gen.getBlockType(3, g, 3, g));
        assertEquals("plaza border wall", 131, gen.getBlockType(10, g + 1, 0, g));
        // Lamp post glowstone heads at the four corners.
        assertEquals("lamp glowstone", 17, gen.getBlockType(-8, g + 3, -8, g));
        assertEquals("lamp glowstone", 17, gen.getBlockType(8, g + 3, 8, g));
    }

    @Test
    public void flatPlainProfileOutsidePlaza() {
        int g = PointClickWorldAuthor.G;
        assertEquals("grass surface", 1, gen.getBlockType(40, g, 40, g));
        assertEquals("dirt layer", 13, gen.getBlockType(40, g - 2, 40, g));
        assertEquals("deep stone", 2, gen.getBlockType(40, 20, 40, g));
        assertEquals("air above", 0, gen.getBlockType(40, g + 6, 40, g));
        // Grass right up to the authored edge (world is open, not walled off).
        assertEquals("edge grass", 1, gen.getBlockType(PointClickWorldAuthor.MAX, g, PointClickWorldAuthor.MAX, g));
    }

    @Test
    public void spawnHeightMatchesFlatSurface() {
        assertEquals(PointClickWorldAuthor.G, gen.getHeight(0, 63, 0));
        assertEquals(PointClickWorldAuthor.G, gen.getHeight(-50, 63, 50));
    }

    @Test
    public void populateSectionFillsGroundAndStructures() {
        World world = new World(256);
        int g = PointClickWorldAuthor.G;
        // A below-ground section is fully solid (16x16x16).
        int solid = gen.populateSection(0, 2, 0, world, 0);
        assertEquals("underground section fully solid", 16 * 16 * 16, solid);
        // The surface section under the plaza holds the floor + portal bases.
        solid = gen.populateSection(0, g >> 4, 0, world, 0);
        assertTrue("surface section should be full", solid >= 16 * 16);
        // A high-air section inside the authored area still has portal columns.
        solid = gen.populateSection(0, 4, 0, world, 0); // y 64..79, x/z 0..15
        assertTrue("portal/lamp blocks above surface missing", solid > 0);
        // A far, fully-air section reports zero solids.
        solid = gen.populateSection(5, 9, 7, world, 0);
        assertEquals("far sky section empty", 0, solid);
    }
}
