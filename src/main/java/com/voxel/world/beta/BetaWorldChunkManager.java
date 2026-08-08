package com.voxel.world.beta;

import java.util.Random;

/**
 * Exact port of Beta 1.7.3's WorldChunkManager.
 * Generates temperature and humidity maps using NoiseGeneratorOctaves2,
 * then resolves biomes using the Beta 1.7.3 biome lookup table.
 * Preserves ALL bugs.
 */
public class BetaWorldChunkManager {
    private NoiseGeneratorOctaves2 field_4194_e; // temperature noise
    private NoiseGeneratorOctaves2 field_4193_f; // humidity noise
    private NoiseGeneratorOctaves2 field_4192_g; // variation noise
    public double[] temperature;
    public double[] humidity;
    public double[] field_4196_c;
    public int[] field_4195_d; // biome IDs (was BiomeGenBase[] in original)

    /**
     * Memoized 16×16 biome maps, one per chunk column. loadBlockGeneratorData
     * is deterministic per (cx,cz), yet each column's map was previously
     * recomputed from scratch every time it was touched — once for the column
     * itself and once per neighbor-column copy during decoration (8×), plus
     * single-point lookups (getBiomeGenAt) re-ran all three octave noise
     * passes per pixel. Serving repeat requests from this LRU turns all of
     * those into array copies. Results are byte-identical to a fresh pass.
     */
    private final java.util.LinkedHashMap<Long, BiomeMapEntry> biomeMapCache =
            new java.util.LinkedHashMap<Long, BiomeMapEntry>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, BiomeMapEntry> eldest) {
                    return size() > BIOME_MAP_CACHE_LIMIT;
                }
            };
    private static final int BIOME_MAP_CACHE_LIMIT = 4096;

    /** Immutable 16×16 map for one column; owned by the cache, never handed out. */
    private static final class BiomeMapEntry {
        final int[] biomes = new int[256];
        final double[] temperature = new double[256];
        final double[] humidity = new double[256];
    }

    private static long columnKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public BetaWorldChunkManager(long seed) {
        this(seed, BetaNumericProfile.DEFAULT);
    }

    public BetaWorldChunkManager(long seed, BetaNumericProfile numericProfile) {
        BetaNumericProfile profile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        this.field_4194_e = new NoiseGeneratorOctaves2(new Random(seed * 9871L), 4, profile);
        this.field_4193_f = new NoiseGeneratorOctaves2(new Random(seed * 39811L), 4, profile);
        this.field_4192_g = new NoiseGeneratorOctaves2(new Random(seed * 543321L), 2, profile);
    }

    public int getBiomeGenAt(int var1, int var2) {
        // Serve single-point lookups from the memoized 16×16 chunk map instead
        // of re-running all three octave noise passes for one pixel. The cell
        // value is identical to the old 1×1 path (each cell is evaluated
        // independently by the octave generators).
        int cx = var1 >> 4, cz = var2 >> 4;
        BiomeMapEntry entry = biomeMapCache.get(columnKey(cx, cz));
        if (entry == null) {
            loadBlockGeneratorData(null, cx * 16, cz * 16, 16, 16);
            entry = biomeMapCache.get(columnKey(cx, cz));
        }
        return entry.biomes[(var1 & 15) | ((var2 & 15) << 4)];
    }

    public double getTemperature(int var1, int var2) {
        this.temperature = this.field_4194_e.func_4112_a(this.temperature, (double) var1, (double) var2,
                1, 1, (double) 0.025F, (double) 0.025F, 0.5D);
        return this.temperature[0];
    }

    public int[] func_4069_a(int var1, int var2, int var3, int var4) {
        this.field_4195_d = this.loadBlockGeneratorData(this.field_4195_d, var1, var2, var3, var4);
        return this.field_4195_d;
    }

    public double[] getTemperatures(double[] var1, int var2, int var3, int var4, int var5) {
        if (var1 == null || var1.length < var4 * var5) {
            var1 = new double[var4 * var5];
        }

        var1 = this.field_4194_e.func_4112_a(var1, (double) var2, (double) var3, var4, var5,
                (double) 0.025F, (double) 0.025F, 0.25D);
        this.field_4196_c = this.field_4192_g.func_4112_a(this.field_4196_c, (double) var2, (double) var3,
                var4, var5, 0.25D, 0.25D, 0.5882352941176471D);
        int var6 = 0;

        for (int var7 = 0; var7 < var4; ++var7) {
            for (int var8 = 0; var8 < var5; ++var8) {
                double var9 = this.field_4196_c[var6] * 1.1D + 0.5D;
                double var11 = 0.01D;
                double var13 = 1.0D - var11;
                double var15 = (var1[var6] * 0.15D + 0.7D) * var13 + var9 * var11;
                var15 = 1.0D - (1.0D - var15) * (1.0D - var15);
                if (var15 < 0.0D) var15 = 0.0D;
                if (var15 > 1.0D) var15 = 1.0D;
                var1[var6] = var15;
                ++var6;
            }
        }

        return var1;
    }

    /**
     * Exact port of Beta 1.7.3's loadBlockGeneratorData.
     * Generates temperature+humidity maps and resolves biomes.
     * Returns int array of biome IDs instead of BiomeGenBase objects.
     * Chunk-aligned 16×16 requests are memoized per column, so repeated
     * generation (own column + 8 neighbor copies during decoration) only pays
     * the noise cost once.
     */
    public int[] loadBlockGeneratorData(int[] var1, int var2, int var3, int var4, int var5) {
        if (var1 == null || var1.length < var4 * var5) {
            var1 = new int[var4 * var5];
        }

        boolean chunkAligned = var4 == 16 && var5 == 16 && (var2 & 15) == 0 && (var3 & 15) == 0;
        if (chunkAligned) {
            BiomeMapEntry cached = biomeMapCache.get(columnKey(var2 >> 4, var3 >> 4));
            if (cached != null) {
                System.arraycopy(cached.biomes, 0, var1, 0, 256);
                if (this.temperature == null || this.temperature.length < 256) this.temperature = new double[256];
                if (this.humidity == null || this.humidity.length < 256) this.humidity = new double[256];
                System.arraycopy(cached.temperature, 0, this.temperature, 0, 256);
                System.arraycopy(cached.humidity, 0, this.humidity, 0, 256);
                return var1;
            }
        }

        this.temperature = this.field_4194_e.func_4112_a(this.temperature, (double) var2, (double) var3,
                var4, var4, (double) 0.025F, (double) 0.025F, 0.25D);
        this.humidity = this.field_4193_f.func_4112_a(this.humidity, (double) var2, (double) var3,
                var4, var4, (double) 0.05F, (double) 0.05F, 1.0D / 3.0D);
        this.field_4196_c = this.field_4192_g.func_4112_a(this.field_4196_c, (double) var2, (double) var3,
                var4, var4, 0.25D, 0.25D, 0.5882352941176471D);
        int var6 = 0;

        for (int var7 = 0; var7 < var4; ++var7) {
            for (int var8 = 0; var8 < var5; ++var8) {
                double var9 = this.field_4196_c[var6] * 1.1D + 0.5D;
                double var11 = 0.01D;
                double var13 = 1.0D - var11;
                double var15 = (this.temperature[var6] * 0.15D + 0.7D) * var13 + var9 * var11;
                var11 = 0.002D;
                var13 = 1.0D - var11;
                double var17 = (this.humidity[var6] * 0.15D + 0.5D) * var13 + var9 * var11;
                var15 = 1.0D - (1.0D - var15) * (1.0D - var15);
                if (var15 < 0.0D) var15 = 0.0D;
                if (var17 < 0.0D) var17 = 0.0D;
                if (var15 > 1.0D) var15 = 1.0D;
                if (var17 > 1.0D) var17 = 1.0D;

                this.temperature[var6] = var15;
                this.humidity[var6] = var17;
                var1[var6++] = BetaBiomeGenBase.getBiomeFromLookup(var15, var17);
            }
        }

        if (chunkAligned) {
            BiomeMapEntry entry = new BiomeMapEntry();
            System.arraycopy(var1, 0, entry.biomes, 0, 256);
            System.arraycopy(this.temperature, 0, entry.temperature, 0, 256);
            System.arraycopy(this.humidity, 0, entry.humidity, 0, 256);
            biomeMapCache.put(columnKey(var2 >> 4, var3 >> 4), entry);
        }

        return var1;
    }

    /** Drops all memoized biome maps (call when a column cache is cleared). */
    public void clearCache() {
        biomeMapCache.clear();
    }
}
