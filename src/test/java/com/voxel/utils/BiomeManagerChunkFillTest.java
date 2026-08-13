package com.voxel.utils;

import com.voxel.biome.BiomeProvider;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Verifies the incremental per-chunk biome-map population path:
 *  - fillBiomeDataForChunk() produces byte-identical texels to the full-map bake
 *    (generateBiomeData) for the covered 4×4 tile at offset 0;
 *  - buffer offsets translate into the correct world-coordinate sampling;
 *  - slideBiomeMap() preserves overlapping tiles, drops shifted-out data, and
 *    leaves newly exposed tiles neutral (they are filled by chunk generation).
 */
public class BiomeManagerChunkFillTest {

    private static final int SCALE = 4; // BiomeManager.BIOME_MAP_SCALE
    private static final int TILE = 4;  // 4×4 texels per chunk column

    @Test
    public void chunkFillMatchesFullBake() {
        long seed = 12345L;

        // Reference: full 512×512 bake at offset 0.
        BiomeManager full = new BiomeManager();
        full.setBiomeProvider(new BiomeProvider(seed));
        full.generateBiomeData(2048);

        // Incremental: fallback (neutral) + fill a single chunk column.
        BiomeManager inc = new BiomeManager();
        inc.setBiomeProvider(new BiomeProvider(seed));
        inc.generateFallbackBiomeData(2048);
        inc.fillBiomeDataForChunk(3, 5, 0, 0); // chunk column (3,5) → texels 12..15 × 20..23

        for (int lz = 0; lz < TILE; lz++) {
            for (int lx = 0; lx < TILE; lx++) {
                int tx = 3 * TILE + lx;
                int tz = 5 * TILE + lz;
                assertArrayEquals("texel (" + tx + "," + tz + ") must match full bake",
                        full.getBiomeTexelBytes(tx, tz), inc.getBiomeTexelBytes(tx, tz));
            }
        }
    }

    @Test
    public void chunkFillHonorsBufferOffset() {
        long seed = 777L;
        BiomeProvider provider = new BiomeProvider(seed);

        BiomeManager bm = new BiomeManager();
        bm.setBiomeProvider(provider);
        bm.generateFallbackBiomeData(2048);

        // Buffer origin at (256, 128): chunk (20, 10) is buffer-relative (4, 2),
        // so its tile is texels 16..19 × 8..11. World sample point for texel
        // (tx,tz) = offset + tx*4 + 2.
        int offsetX = 256, offsetZ = 128;
        bm.fillBiomeDataForChunk(20, 10, offsetX, offsetZ);

        for (int lz = 0; lz < TILE; lz++) {
            for (int lx = 0; lx < TILE; lx++) {
                int tx = 4 * TILE + lx;
                int tz = 2 * TILE + lz;
                int wx = offsetX + tx * SCALE + SCALE / 2;
                int wz = offsetZ + tz * SCALE + SCALE / 2;
                byte[] expected = providerBytes(provider, wx, wz);
                assertArrayEquals("offset texel (" + tx + "," + tz + ") at world (" + wx + "," + wz + ")",
                        expected, bm.getBiomeTexelBytes(tx, tz));
            }
        }
    }

    @Test
    public void outOfBufferChunkIsIgnored() {
        BiomeManager bm = new BiomeManager();
        bm.setBiomeProvider(new BiomeProvider(42L));
        bm.generateFallbackBiomeData(2048);

        // Chunk far outside the 128×128 buffer window at offset 0 → no-op.
        bm.fillBiomeDataForChunk(300, 5, 0, 0);
        for (int lz = 0; lz < TILE; lz++) {
            for (int lx = 0; lx < TILE; lx++) {
                assertEquals("untouched texel stays neutral",
                        (byte) 128, bm.getBiomeTexelBytes(lx, lz)[0]);
            }
        }
    }

    @Test
    public void slidePreservesOverlapAndNeutralizesExposed() {
        long seed = 999L;
        BiomeProvider provider = new BiomeProvider(seed);

        BiomeManager bm = new BiomeManager();
        bm.setBiomeProvider(provider);
        bm.generateFallbackBiomeData(2048);

        // Fill chunk (20,5) at offset 0 → texels 80..83 × 20..23.
        bm.fillBiomeDataForChunk(20, 5, 0, 0);
        byte[][][] before = new byte[TILE][TILE][];
        for (int lz = 0; lz < TILE; lz++) {
            for (int lx = 0; lx < TILE; lx++) {
                before[lz][lx] = bm.getBiomeTexelBytes(20 * TILE + lx, 5 * TILE + lz);
            }
        }

        // Slide +64 blocks X (16 texels): new texel (dx) reads old (dx+16).
        bm.slideBiomeMap(0, 0, 64, 0);

        // Overlap: the filled tile moved to texels 64..67 × 20..23.
        for (int lz = 0; lz < TILE; lz++) {
            for (int lx = 0; lx < TILE; lx++) {
                assertArrayEquals("overlap tile must be preserved by slide",
                        before[lz][lx], bm.getBiomeTexelBytes(16 * TILE + lx, 5 * TILE + lz));
            }
        }

        // Exposed strip: with shiftX=16, new texel dx reads old dx+16, so dx >= 496
        // has no source texel (old buffer was 512 wide). It must stay neutral, not
        // get noise-filled by the slide.
        for (int tx = 496; tx < 512; tx += 4) {
            assertEquals("exposed texel stays neutral", (byte) 128, bm.getBiomeTexelBytes(tx, 5 * TILE)[0]);
        }
    }

    private static byte[] providerBytes(BiomeProvider provider, int x, int z) {
        com.voxel.biome.Biome biome = provider.getBiome(x, z);
        float t = Math.max(0.0f, Math.min(1.0f, biome.getTemperature(x, z)));
        float h = Math.max(0.0f, Math.min(1.0f, biome.getHumidity(x, z)));
        // Mirror BiomeManager's temp+humidity<=1 colormap clamp.
        float sum = t + h;
        if (sum > 1.0f) { t /= sum; h /= sum; }
        return new byte[]{(byte) (t * 255), (byte) (h * 255)};
    }
}
