package com.voxel.world.structure;

import com.voxel.World;
import com.voxel.world.DecorBlocks;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * The village's detail pass: houses must be furnished (bed, workbench, furnace,
 * shelves, plant, lamp), the plaza dressed (benches, planters, banners, notice
 * board) and the market stalls stocked. Doors and chests must survive.
 */
public class VillageStructureDetailTest {

    private static final int GRASS = 1, OAK_LOG = 5, PLANKS = 72, COBBLESTONE = 71,
            BRICK = 130, WOOL = 91, PUMPKIN = 42, MELON = 43, OAK_SLAB = 206,
            OAK_STAIRS = 200, CHEST = 118, CRAFT_TABLE = 115, FURNACE = 116,
            BOOKSHELF = 136, GLOWSTONE = 17, ANVIL = 456;

    /** Flat grass at y=63 and air above, recording every write. */
    private static final class FlatWorld extends World {
        final Map<String, Integer> voxels = new HashMap<>();

        FlatWorld() { super(8); }

        @Override
        public void setVoxel(int x, int y, int z, int type) {
            voxels.put(x + "," + y + "," + z, type);
        }

        @Override
        public int getVoxel(int x, int y, int z) {
            Integer v = voxels.get(x + "," + y + "," + z);
            if (v != null) return v;
            // Surface at y=64: the generator requires a surface *above* the
            // VILLAGE_MIN_HEIGHT (63) line before it will place buildings.
            return y <= 64 ? GRASS : 0;
        }

        boolean has(int id) {
            return voxels.containsValue(id);
        }
    }

    /** Finds a seed whose first roll both passes the chance gate and generates. */
    private static FlatWorld generated() {
        int made = 0;
        for (long seed = 0; seed < 200; seed++) {
            // Mix the seed: small sequential seeds share their high bits, so
            // Java's Random gives them all the same first roll.
            long mixed = seed * 0x9E3779B97F4A7C15L;
            FlatWorld w = new FlatWorld();
            boolean ok = new MapGenVillage().generate(w, 4, 4, new Random(mixed));
            if (ok) made++;
            // Require a house (bed) so the interior assertions are meaningful.
            if (ok && w.has(DecorBlocks.BED_FOOT_NORTH)) return w;
        }
        throw new AssertionError("no seed generated a village (accepted=" + made + ")");
    }

    @Test
    public void housesAreFurnished() {
        FlatWorld w = generated();
        assertTrue("bed", w.has(DecorBlocks.BED_FOOT_NORTH));
        assertTrue("bed head", w.has(DecorBlocks.BED_HEAD_NORTH));
        assertTrue("crafting table", w.has(CRAFT_TABLE));
        assertTrue("furnace", w.has(FURNACE));
        assertTrue("bookshelf", w.has(BOOKSHELF));
        assertTrue("flower pot", w.has(DecorBlocks.FLOWER_POT));
        assertTrue("anvil in the workshop", w.has(ANVIL));
        assertTrue("chandelier/lamp", w.has(GLOWSTONE));
    }

    @Test
    public void plazaAndMarketAreDressed() {
        FlatWorld w = generated();
        assertTrue("benches", w.has(PLANKS) && w.has(OAK_SLAB));
        assertTrue("planters", w.has(BRICK));
        assertTrue("banners", w.has(DecorBlocks.BANNER_LOWER_NS));
        assertTrue("stall canopy wool", w.has(WOOL));
        assertTrue("stall produce", w.has(PUMPKIN) || w.has(MELON));
        assertTrue("stair seating", w.has(OAK_STAIRS));
    }

    @Test
    public void doorsChestsAndFencesSurvive() {
        FlatWorld w = generated();
        assertTrue("real doors", w.has(DecorBlocks.OAK_DOOR_LOWER_EW)
                || w.has(DecorBlocks.OAK_DOOR_LOWER));
        assertTrue("chests", w.has(CHEST));
        assertTrue("cobble footings", w.has(COBBLESTONE));
    }
}
