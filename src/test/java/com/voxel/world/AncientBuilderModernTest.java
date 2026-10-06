package com.voxel.world;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AncientBuilderModernTest {

    private static AncientBuilderModern.RecordingSink build(AncientBuilderModern.Site site,
                                                           int variant) {
        AncientBuilderModern.RecordingSink sink =
                new AncientBuilderModern.RecordingSink();
        AncientBuilderModern.build(site, variant, 68, sink);
        return sink;
    }

    @Test
    public void variantsAreDeterministicAndInRange() {
        for (AncientBuilderModern.Site site : AncientBuilderModern.SITES) {
            int a = AncientBuilderModern.variantFor(site);
            int b = AncientBuilderModern.variantFor(site);
            assertEquals("variant must be stable for a site", a, b);
            assertTrue("variant within family size", a >= 0 && a < site.variants);
        }
    }

    @Test
    public void everySitePlacesItsModernMaterials() {
        for (AncientBuilderModern.Site site : AncientBuilderModern.SITES) {
            for (int variant = 0; variant < site.variants; variant++) {
                AncientBuilderModern.RecordingSink sink = build(site, variant);
                assertTrue(site.name + " v" + variant + " uses modern materials",
                        sink.voxels.containsValue(AncientBuilderModern.CONCRETE)
                                || sink.voxels.containsValue(AncientBuilderModern.TILE_BLOCK)
                                || sink.voxels.containsValue(AncientBuilderModern.MARBLE));
                assertTrue(site.name + " v" + variant + " is lit",
                        sink.voxels.containsValue(AncientBuilderModern.CEILING_LIGHT));
            }
        }
    }

    @Test
    public void towerVariantsDifferInHeight() {
        AncientBuilderModern.RecordingSink shortTower = build(AncientBuilderModern.GLASS_TOWER, 0);
        AncientBuilderModern.RecordingSink tallTower = build(AncientBuilderModern.GLASS_TOWER, 2);
        assertTrue("taller variant must reach higher ("
                        + maxY(shortTower) + " vs " + maxY(tallTower) + ")",
                maxY(tallTower) > maxY(shortTower));
    }

    @Test
    public void towerHasEntranceGlassAndAntenna() {
        AncientBuilderModern.RecordingSink sink =
                build(AncientBuilderModern.GLASS_TOWER, 1);
        int cx = AncientBuilderModern.GLASS_TOWER.x;
        int cz = AncientBuilderModern.GLASS_TOWER.z;
        assertEquals("south entrance is open", 0, sink.get(cx, 69, cz - 5));
        assertTrue("facade uses office glass",
                sink.voxels.containsValue(AncientBuilderModern.OFFICE_GLASS));
        assertTrue("corners use steel",
                sink.voxels.containsValue(AncientBuilderModern.STEEL_BEAM));
    }

    @Test
    public void metroHallHasPlatformTracksAndRoof() {
        AncientBuilderModern.RecordingSink island =
                build(AncientBuilderModern.METRO_HALL, 0);
        AncientBuilderModern.RecordingSink sides =
                build(AncientBuilderModern.METRO_HALL, 1);
        int cx = AncientBuilderModern.METRO_HALL.x;
        int cz = AncientBuilderModern.METRO_HALL.z;
        // Variant 0: island platform in the centre, trenches at the edges.
        assertEquals("island platform tile in the middle",
                AncientBuilderModern.TILE_BLOCK, island.get(cx, 68, cz));
        assertEquals("edge column is trench, not platform",
                0, island.get(cx + 5, 68, cz));
        assertEquals("dark track bed under the island edge",
                AncientBuilderModern.CONCRETE_DARK, island.get(cx + 5, 66, cz));
        // Variant 1: side platforms at the edges, trench down the middle.
        assertEquals("side platform tile at the edge",
                AncientBuilderModern.TILE_BLOCK, sides.get(cx + 5, 68, cz));
        assertEquals("centre column is trench, not platform",
                0, sides.get(cx, 68, cz));
        assertEquals("steel rails on the trench edge",
                AncientBuilderModern.STEEL_BEAM, sides.get(cx + 3, 67, cz));
        // Both variants carry the lit concrete roof.
        assertTrue("roof lights", island.voxels.containsValue(AncientBuilderModern.CEILING_LIGHT));
        assertTrue("roof slab", sides.voxels.containsValue(AncientBuilderModern.CONCRETE));
    }

    @Test
    public void labVariantAddsTheAnnexWing() {
        AncientBuilderModern.RecordingSink base =
                build(AncientBuilderModern.RESEARCH_LAB, 0);
        AncientBuilderModern.RecordingSink annex =
                build(AncientBuilderModern.RESEARCH_LAB, 1);
        int cx = AncientBuilderModern.RESEARCH_LAB.x;
        assertTrue("annex reaches past the main wing",
                annex.voxels.containsKey((cx + 12) + ",68," + AncientBuilderModern.RESEARCH_LAB.z));
        assertTrue("base variant has no annex",
                !base.voxels.containsKey((cx + 12) + ",68," + AncientBuilderModern.RESEARCH_LAB.z));
    }

    @Test
    public void plazaVariantsBuildDifferentMonuments() {
        AncientBuilderModern.RecordingSink statue =
                build(AncientBuilderModern.BUILDER_PLAZA, 0);
        AncientBuilderModern.RecordingSink raised =
                build(AncientBuilderModern.BUILDER_PLAZA, 1);
        AncientBuilderModern.RecordingSink obelisk =
                build(AncientBuilderModern.BUILDER_PLAZA, 2);
        int cx = AncientBuilderModern.BUILDER_PLAZA.x;
        int cz = AncientBuilderModern.BUILDER_PLAZA.z;
        assertEquals("pedestal under every monument",
                AncientBuilderModern.MARBLE, statue.get(cx, 69, cz));
        assertEquals("obelisk crowned with a light orb",
                AncientBuilderModern.CEILING_LIGHT, obelisk.get(cx, 79, cz));
        assertTrue("raised-arm statue reaches higher on the side",
                raised.get(cx + 2, 76, cz) == AncientBuilderModern.STEEL_BEAM
                        && statue.get(cx + 2, 76, cz) == 0);
    }

    @Test
    public void sitesStayInsideTheirFootprints() {
        for (AncientBuilderModern.Site site : AncientBuilderModern.SITES) {
            AncientBuilderModern.RecordingSink sink = build(site, 0);
            for (Map.Entry<String, Integer> e : sink.voxels.entrySet()) {
                String[] p = e.getKey().split(",");
                int dx = Math.abs(Integer.parseInt(p[0]) - site.x);
                int dz = Math.abs(Integer.parseInt(p[2]) - site.z);
                assertTrue(site.name + " voxel within footprint ("
                                + dx + "," + dz + ")",
                        dx <= 20 && dz <= 20);
            }
        }
    }

    @Test
    public void rebuildingIsIdempotent() {
        AncientBuilderModern.RecordingSink first =
                build(AncientBuilderModern.GLASS_TOWER, 2);
        AncientBuilderModern.RecordingSink second =
                build(AncientBuilderModern.GLASS_TOWER, 2);
        assertEquals("re-stamping the same site yields the same voxels",
                first.voxels, second.voxels);
    }

    private static int maxY(AncientBuilderModern.RecordingSink sink) {
        int max = Integer.MIN_VALUE;
        for (String key : sink.voxels.keySet()) {
            int y = Integer.parseInt(key.split(",")[1]);
            if (y > max) max = y;
        }
        return max;
    }
}
