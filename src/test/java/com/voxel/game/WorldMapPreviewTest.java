package com.voxel.game;

import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import com.voxel.biome.BiomeProperties;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the map preview texture (simplified biome view for unloaded chunks):
 *  - biome colors fill the region inside the world border
 *  - texels beyond the world border are void (alpha 0) — the map stops there
 *  - the ring fill completes incrementally without leaving gaps
 *  - origin/scale stay consistent with the shader's UV math
 */
public class WorldMapPreviewTest {

    /** Deterministic biome provider that returns one fixed biome. */
    private static final class FixedBiomeProvider extends BiomeProvider {
        private final Biome biome;
        FixedBiomeProvider(String name) {
            super(42L);
            this.biome = new Biome(name, new BiomeProperties(name));
        }
        @Override
        public Biome getBiome(int x, int z) {
            return biome;
        }
    }

    private static final BiomeProvider PLAINS = new FixedBiomeProvider("Plains");
    private static final BiomeProvider ICE_DESERT = new FixedBiomeProvider("Ice Desert");

    private static void fillToCompletion(WorldMapRenderer r, BiomeProvider biomes, float zoom, float border) {
        // Always do at least one pass so the (initially-true) fillDone flag is
        // reset and the first region is actually baked.
        r.updatePreview(biomes, 0f, 0f, zoom, border);
        for (int i = 0; i < 2000 && !r.isFullyFilled(); i++) {
            r.updatePreview(biomes, 0f, 0f, zoom, border);
        }
        assertTrue("preview should finish filling within budget", r.isFullyFilled());
    }

    private static int rgb(byte[] t) {
        return ((t[0] & 0xFF) << 16) | ((t[1] & 0xFF) << 8) | (t[2] & 0xFF);
    }

    @Test
    public void biomeColorsFillRegionInsideBorder() {
        WorldMapRenderer r = new WorldMapRenderer();
        fillToCompletion(r, PLAINS, 1f, 100_000f);

        // Plains → (0.43, 0.45, 0.47) → (109, 114, 119) (int truncation).
        byte[] center = r.getTexelBytes(WorldMapRenderer.TEX_SIZE / 2, WorldMapRenderer.TEX_SIZE / 2);
        assertEquals(255, center[3] & 0xFF);
        assertEquals(0x6D7277, rgb(center));

        // A far corner of the region is still inside the border → filled too.
        byte[] corner = r.getTexelBytes(8, 8);
        assertEquals(255, corner[3] & 0xFF);
        assertEquals(0x6D7277, rgb(corner));
    }

    @Test
    public void differentBiomesGiveDifferentColors() {
        WorldMapRenderer a = new WorldMapRenderer();
        fillToCompletion(a, ICE_DESERT, 1f, 100_000f);
        byte[] ice = a.getTexelBytes(WorldMapRenderer.TEX_SIZE / 2, WorldMapRenderer.TEX_SIZE / 2);
        // Ice Desert → Ice group (0.66, 0.70, 0.74) → (168, 178, 188).
        assertEquals(0xA8B2BC, rgb(ice));
        assertNotEquals(0x6D7277, rgb(ice));
    }

    @Test
    public void mapStopsAtWorldBorder() {
        WorldMapRenderer r = new WorldMapRenderer();
        // Border at ±20 blocks; zoom 1 → 2 blocks/texel, region ±256 blocks.
        fillToCompletion(r, PLAINS, 1f, 20f);

        // Texel covering world block (8, 8) → chunk (0,0) center (8,8) → inside.
        byte[] inside = r.getTexelBytes(132, 132);
        assertEquals(255, inside[3] & 0xFF);
        assertEquals(0x6D7277, rgb(inside));

        // Texel covering world block (40, 40) → chunk (2,2) center (40,40) → beyond.
        byte[] outside = r.getTexelBytes(148, 148);
        assertEquals("texels beyond the border must be void (alpha 0)", 0, outside[3] & 0xFF);

        // Everything beyond the border on the negative side too.
        byte[] negOutside = r.getTexelBytes(120, 120); // block (-16,-16) — chunk (-1,-1) center (-8,-8) inside;
        assertEquals(255, negOutside[3] & 0xFF);
        // pick a clearly outside texel: block (-40,-40) → tx = (-40+256)/2 = 108.
        byte[] negFar = r.getTexelBytes(108, 108);
        assertEquals(0, negFar[3] & 0xFF);
    }

    @Test
    public void incrementalFillLeavesNoGaps() {
        WorldMapRenderer r = new WorldMapRenderer();
        // One call = one budget of chunks; must not be complete immediately at zoom 1.
        r.updatePreview(PLAINS, 0f, 0f, 1f, 100_000f);
        int calls = 1;
        while (!r.isFullyFilled() && calls < 2000) {
            r.updatePreview(PLAINS, 0f, 0f, 1f, 100_000f);
            calls++;
        }
        assertTrue("fill should complete", r.isFullyFilled());
        assertTrue("a full bake should need several incremental calls (got " + calls + ")", calls >= 3);

        // Every texel inside the border is filled (no gaps from the ring cursor).
        for (int tz = 0; tz < WorldMapRenderer.TEX_SIZE; tz++) {
            for (int tx = 0; tx < WorldMapRenderer.TEX_SIZE; tx++) {
                byte[] t = r.getTexelBytes(tx, tz);
                assertEquals("texel(" + tx + "," + tz + ") must be filled", 255, t[3] & 0xFF);
            }
        }
    }

    @Test
    public void panSlidesExistingContentInsteadOfRegen() {
        WorldMapRenderer r = new WorldMapRenderer();
        fillToCompletion(r, ICE_DESERT, 1f, 100_000f);

        // Pan +64 blocks at zoom 1 (bpt 2) with a DIFFERENT biome provider. If
        // the texture slides, the painted overlap keeps the ice-desert color;
        // only the newly-exposed strip is repainted with plains.
        r.updatePreview(PLAINS, 64f, 0f, 1f, 100_000f);

        // The old center texel content slid to (TEX_SIZE/2 - 32). It must STILL
        // be ice desert — proving the pan slid, not cleared + regenerated.
        byte[] slid = r.getTexelBytes(WorldMapRenderer.TEX_SIZE / 2 - 32, WorldMapRenderer.TEX_SIZE / 2);
        assertEquals("overlap must slide, not regenerate", 0xA8B2BC, rgb(slid));
        // The center texel is in the overlap too, so it keeps its painted color.
        assertEquals(0xA8B2BC, rgb(r.getTexelBytes(WorldMapRenderer.TEX_SIZE / 2, WorldMapRenderer.TEX_SIZE / 2)));

        // The newly-exposed right strip fills in over subsequent calls.
        for (int i = 0; i < 2000 && !r.isFullyFilled(); i++) {
            r.updatePreview(PLAINS, 64f, 0f, 1f, 100_000f);
        }
        assertTrue("preview should finish filling after the pan", r.isFullyFilled());

        // A texel in the exposed strip (world x ~319 → chunk 19) is now plains.
        byte[] exposed = r.getTexelBytes(WorldMapRenderer.TEX_SIZE - 1, WorldMapRenderer.TEX_SIZE / 2);
        assertEquals("newly-exposed strip must fill with the new provider", 0x6D7277, rgb(exposed));
    }

    @Test
    public void originAndScaleFollowShaderMath() {
        WorldMapRenderer r = new WorldMapRenderer();
        r.updatePreview(PLAINS, 1000f, -2000f, 4f, 100_000f);
        // zoom 4 → camY 500 → bpt = ceil(2*500*1.35/256) = ceil(5.27) = 6.
        assertEquals(6, r.getBlocksPerTexel());
        assertEquals(1000f - (WorldMapRenderer.TEX_SIZE / 2f) * 6, r.getOriginX(), 1e-3);
        assertEquals(-2000f - (WorldMapRenderer.TEX_SIZE / 2f) * 6, r.getOriginZ(), 1e-3);

        // A small pan inside the half-texel threshold keeps the same region.
        r.updatePreview(PLAINS, 1001f, -2001f, 4f, 100_000f);
        assertEquals(1000f - (WorldMapRenderer.TEX_SIZE / 2f) * 6, r.getOriginX(), 1e-3);

        // A pan past half a texel re-bakes around the new center.
        r.updatePreview(PLAINS, 1010f, -2010f, 4f, 100_000f);
        assertEquals(1010f - (WorldMapRenderer.TEX_SIZE / 2f) * 6, r.getOriginX(), 1e-3);
    }
}
