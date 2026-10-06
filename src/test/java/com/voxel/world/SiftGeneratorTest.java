package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Sift's terrain contract: two biomes (Meadow and Carapace) over soul-lit
 * ground, a surface band near the dimension spawn height, and a rift gate at
 * the origin so the realm is reachable.
 */
public class SiftGeneratorTest {

    private static final int SIFT_STONE = 927, MEADOW_GRASS = 928, SIFT_SOIL = 929,
            CARAPACE = 930, CARAPACE_LAMP = 931, SIFT_BED = 932,
            RIFT_FRAME = 933, SIFT_PORTAL = 934, SOUL_LAMP = 935;

    private SiftGenerator gen;

    @Before
    public void setUp() {
        BlockDataManager bdm = new BlockDataManager() {
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId > 0;
            }
        };
        gen = new SiftGenerator(42L, bdm);
    }

    /** World stub that records decorate() writes so the gate can be inspected. */
    private static final class MapWorld extends World {
        final Map<String, Integer> map = new HashMap<>();

        MapWorld() { super(8); }

        @Override
        public void setVoxel(int x, int y, int z, int type) {
            map.put(x + "," + y + "," + z, type);
        }

        @Override
        public int getVoxel(int x, int y, int z) {
            Integer v = map.get(x + "," + y + "," + z);
            return v == null ? 0 : v;
        }
    }

    @Test
    public void bothBiomesAppear() {
        boolean meadow = false, carapace = false;
        for (int x = -400; x <= 400 && !(meadow && carapace); x += 16) {
            for (int z = -400; z <= 400; z += 16) {
                int b = gen.biomeAt(x, z);
                if (b == SiftGenerator.MEADOW) meadow = true;
                if (b == SiftGenerator.CARAPACE) carapace = true;
            }
        }
        assertTrue("Meadow must exist", meadow);
        assertTrue("Carapace must exist", carapace);
    }

    @Test
    public void surfaceHeightStaysInAReasonableBand() {
        for (int x = -200; x <= 200; x += 7) {
            for (int z = -200; z <= 200; z += 11) {
                int h = gen.columnHeight(x, z);
                assertTrue("height " + h + " at " + x + "," + z, h > 45 && h < 110);
            }
        }
    }

    @Test
    public void surfaceAndSubsurfaceUseTheSiftPalette() {
        for (int x = -64; x <= 64; x += 5) {
            for (int z = -64; z <= 64; z += 5) {
                int h = gen.columnHeight(x, z);
                int surface = gen.getBlockType(x, h, z, h);
                assertTrue("surface block " + surface + " at " + x + "," + z,
                        surface == MEADOW_GRASS || surface == CARAPACE
                                || surface == CARAPACE_LAMP);
                int sub = gen.getBlockType(x, h - 1, z, h);
                assertTrue("subsurface block " + sub,
                        sub == SIFT_SOIL || sub == CARAPACE);
                assertEquals("air above the surface", 0, gen.getBlockType(x, h + 3, z, h));
                assertEquals("void below the world", 0, gen.getBlockType(x, -1, z, h));
            }
        }
    }

    @Test
    public void riftGateStandsNearTheOrigin() {
        MapWorld world = new MapWorld();
        int gh = gen.columnHeight(6, 0);
        gen.decorate(0, gh >> 4, 0, 0, world);
        // The portal interior column and the plinth under it.
        assertEquals("portal interior", SIFT_PORTAL, world.getVoxel(6, gh + 1, 0));
        assertEquals("gate plinth", 936 /* soul_slate */, world.getVoxel(5, gh, 0));
        assertEquals("gate lintel", RIFT_FRAME, world.getVoxel(6, gh + 5, 0));
    }

    @Test
    public void decorationsOnlyUseSiftBlocks() {
        MapWorld world = new MapWorld();
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                for (int cy = 4; cy <= 6; cy++) {
                    gen.decorate(cx, cy, cz, 0, world);
                }
            }
        }
        for (int id : world.map.values()) {
            assertTrue("unexpected decoration block " + id,
                    id == SIFT_BED || id == SOUL_LAMP || id == CARAPACE
                            || id == CARAPACE_LAMP || id == RIFT_FRAME
                            || id == SIFT_PORTAL || 936 == id);
        }
    }
}
