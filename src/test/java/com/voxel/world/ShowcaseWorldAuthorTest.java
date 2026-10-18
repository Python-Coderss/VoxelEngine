package com.voxel.world;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure unit tests for the Showcase World's authored layout metadata. */
public class ShowcaseWorldAuthorTest {

    @Test
    public void everyDistrictHasATitleCard() {
        ShowcaseWorldAuthor.Zone[] zones = ShowcaseWorldAuthor.zones();
        assertEquals("six districts", 6, zones.length);
        for (ShowcaseWorldAuthor.Zone z : zones) {
            assertFalse("zone name", z.name.isEmpty());
            assertFalse("zone subtitle", z.subtitle.isEmpty());
            assertTrue("zone bounds", z.minX < z.maxX && z.minZ < z.maxZ);
        }
    }

    @Test
    public void zoneAtIdentifiesEachDistrict() {
        assertEquals("plaza", 5, ShowcaseWorldAuthor.zoneAt(0, 0));
        assertEquals("gateway", 1, ShowcaseWorldAuthor.zoneAt(0, -32));
        assertEquals("workshop", 3, ShowcaseWorldAuthor.zoneAt(35, 0));
        assertEquals("redstone works", 2, ShowcaseWorldAuthor.zoneAt(-35, 0));
        assertEquals("farm", 4, ShowcaseWorldAuthor.zoneAt(0, 34));
        assertEquals("item gallery", 0, ShowcaseWorldAuthor.zoneAt(0, -55));
        // Open ground between districts belongs to no zone.
        assertEquals("outside", -1, ShowcaseWorldAuthor.zoneAt(60, 60));
    }

    @Test
    public void spawnSitsInsideThePlazaClearOfTheFountain() {
        assertEquals("plaza", 5,
                ShowcaseWorldAuthor.zoneAt((float) ShowcaseWorldAuthor.SPAWN_X,
                        (float) ShowcaseWorldAuthor.SPAWN_Z));
        // The fountain occupies |x|,|z| <= 2; the spawn must stand outside it.
        assertTrue("spawn clear of fountain",
                Math.abs(ShowcaseWorldAuthor.SPAWN_Z) > 2 || Math.abs(ShowcaseWorldAuthor.SPAWN_X) > 2);
    }

    @Test
    public void worldBoundsMatchTheDocumentedLayout() {
        assertEquals(62, ShowcaseWorldAuthor.G);
        assertEquals(-64, ShowcaseWorldAuthor.MIN);
        assertEquals(63, ShowcaseWorldAuthor.MAX);
        assertEquals(96, ShowcaseWorldAuthor.AREA_H);
    }

    @Test
    public void districtsDoNotOverlap() {
        ShowcaseWorldAuthor.Zone[] zones = ShowcaseWorldAuthor.zones();
        for (int i = 0; i < zones.length; i++) {
            for (int j = i + 1; j < zones.length; j++) {
                ShowcaseWorldAuthor.Zone a = zones[i], b = zones[j];
                boolean disjoint = a.maxX < b.minX || b.maxX < a.minX
                        || a.maxZ < b.minZ || b.maxZ < a.minZ;
                assertTrue("zones overlap: " + a.name + " / " + b.name, disjoint);
            }
        }
    }

    @Test
    public void everyBuildingBlockHasADoorId() {
        // Guards the shared door table the authored worlds rely on.
        assertTrue(DecorBlocks.OAK_DOOR_LOWER > 0);
        assertTrue(DecorBlocks.OAK_DOOR_UPPER != DecorBlocks.OAK_DOOR_LOWER);
        assertEquals(DecorBlocks.OAK_DOOR_LOWER, DecorBlocks.id("oak_door_lower"));
        assertEquals(DecorBlocks.LADDER, DecorBlocks.id("ladder"));
        assertEquals(DecorBlocks.FLOWER_POT, DecorBlocks.id("flower_pot"));
    }
}
