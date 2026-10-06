package com.voxel.world;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AncientBuilderSitesTest {

    /** Light-emitting ids used across the site set. */
    private static final int GLOWSTONE = 17, TORCH = 211, BEACON = 457,
            SEA_LANTERN = 470, CEILING_LIGHT = 923;

    private static AncientBuilderSites.RecordingSink build(
            AncientBuilderSites.Site site, int variant) {
        AncientBuilderSites.RecordingSink sink =
                new AncientBuilderSites.RecordingSink();
        AncientBuilderSites.build(site, variant, 68, sink);
        return sink;
    }

    @Test
    public void variantsAreDeterministicAndInRange() {
        for (AncientBuilderSites.Site site : AncientBuilderSites.SITES) {
            int a = AncientBuilderSites.variantFor(site);
            int b = AncientBuilderSites.variantFor(site);
            assertEquals("variant must be stable for a site", a, b);
            assertTrue("variant within family size", a >= 0 && a < site.variants);
        }
    }

    @Test
    public void everySiteAndVariantBuildsAndIsLit() {
        for (AncientBuilderSites.Site site : AncientBuilderSites.SITES) {
            for (int variant = 0; variant < site.variants; variant++) {
                AncientBuilderSites.RecordingSink sink = build(site, variant);
                assertTrue(site.name + " v" + variant + " places blocks",
                        !sink.voxels.isEmpty());
                assertTrue(site.name + " v" + variant + " is lit",
                        sink.voxels.containsValue(GLOWSTONE)
                                || sink.voxels.containsValue(TORCH)
                                || sink.voxels.containsValue(BEACON)
                                || sink.voxels.containsValue(SEA_LANTERN)
                                || sink.voxels.containsValue(CEILING_LIGHT));
                assertTrue(site.name + " v" + variant + " uses several blocks",
                        new java.util.HashSet<>(sink.voxels.values()).size() >= 3);
            }
        }
    }

    @Test
    public void sitesStayInsideTheirFootprints() {
        for (AncientBuilderSites.Site site : AncientBuilderSites.SITES) {
            AncientBuilderSites.RecordingSink sink = build(site, 0);
            for (Map.Entry<String, Integer> e : sink.voxels.entrySet()) {
                String[] p = e.getKey().split(",");
                int dx = Math.abs(Integer.parseInt(p[0]) - site.x);
                int dz = Math.abs(Integer.parseInt(p[2]) - site.z);
                assertTrue(site.name + " voxel within footprint (" + dx + "," + dz + ")",
                        dx <= 30 && dz <= 30);
            }
        }
    }

    @Test
    public void watchSpireVariantIsTaller() {
        assertTrue("the second spire design is taller",
                maxY(build(AncientBuilderSites.WATCH_SPIRE, 1))
                        > maxY(build(AncientBuilderSites.WATCH_SPIRE, 0)));
    }

    @Test
    public void grandArchiveVariantAddsTheWestWing() {
        AncientBuilderSites.RecordingSink base = build(AncientBuilderSites.GRAND_ARCHIVE, 0);
        AncientBuilderSites.RecordingSink wing = build(AncientBuilderSites.GRAND_ARCHIVE, 1);
        int cx = AncientBuilderSites.GRAND_ARCHIVE.x;
        int cz = AncientBuilderSites.GRAND_ARCHIVE.z;
        assertTrue("the wing reaches west of the hall",
                wing.voxels.containsKey((cx - 26) + ",68," + cz));
        assertTrue("the base variant has no wing",
                !base.voxels.containsKey((cx - 26) + ",68," + cz));
    }

    @Test
    public void engineWorksIsMachineFilled() {
        AncientBuilderSites.RecordingSink sink = build(AncientBuilderSites.ENGINE_WORKS, 0);
        assertTrue("shafts", sink.voxels.containsValue(291));
        assertTrue("cogwheels", sink.voxels.containsValue(294));
        assertTrue("a steam engine", sink.voxels.containsValue(396));
        assertTrue("a mechanical press", sink.voxels.containsValue(407));
    }

    @Test
    public void observatoryHasAGlassDome() {
        AncientBuilderSites.RecordingSink sink = build(AncientBuilderSites.OBSERVATORY, 0);
        assertTrue("the dome uses glass", sink.voxels.containsValue(3));
        assertTrue("the mast has an instrument ring",
                sink.voxels.containsValue(138 /* gold_block */));
    }

    @Test
    public void rebuildingIsIdempotent() {
        AncientBuilderSites.RecordingSink first =
                build(AncientBuilderSites.ENGINE_WORKS, 1);
        AncientBuilderSites.RecordingSink second =
                build(AncientBuilderSites.ENGINE_WORKS, 1);
        assertEquals("re-stamping the same site yields the same voxels",
                first.voxels, second.voxels);
    }

    private static int maxY(AncientBuilderSites.RecordingSink sink) {
        int max = Integer.MIN_VALUE;
        for (String key : sink.voxels.keySet()) {
            int y = Integer.parseInt(key.split(",")[1]);
            if (y > max) max = y;
        }
        return max;
    }
}
