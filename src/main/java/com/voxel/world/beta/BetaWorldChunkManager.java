package com.voxel.world.beta;

/**
 * Faithful port of Beta 1.8.1's WorldChunkManager — owns the four GenLayer
 * outputs (biome, voronoi-biome, temperature, downfall) and serves biome /
 * temperature / downfall arrays. Obfuscated field/method names match source.
 */
public class BetaWorldChunkManager {
    private BetaGenLayer field_34903_b; // biome layer (river-mixed)
    private BetaGenLayer field_34902_c; // voronoi biome layer
    private BetaGenLayer field_34901_d; // temperature layer
    private BetaGenLayer field_35565_e; // downfall layer
    private final BetaBiomeCache field_35563_f;

    public BetaWorldChunkManager(long seed) {
        this.field_35563_f = new BetaBiomeCache(this);
        BetaGenLayer[] layers = BetaGenLayer.func_35497_a(seed);
        this.field_34903_b = layers[0];
        this.field_34902_c = layers[1];
        this.field_34901_d = layers[2];
        this.field_35565_e = layers[3];
    }

    /** getBiomeGenAt — biome at a single (x, z), cached. */
    public BetaBiomeGenBase getBiomeGenAt(int x, int z) {
        return this.field_35563_f.func_35725_a(x, z);
    }

    /** func_35557_b — biome array from the river-mixed biome layer (density kernel). */
    public BetaBiomeGenBase[] func_35557_b(BetaBiomeGenBase[] out, int x, int z, int w, int h) {
        BetaIntCache.func_35268_a();
        if (out == null || out.length < w * h) out = new BetaBiomeGenBase[w * h];
        int[] ids = this.field_34903_b.func_35500_a(x, z, w, h);
        for (int i = 0; i < w * h; ++i) {
            out[i] = BetaBiomeGenBase.field_35486_a[ids[i]];
        }
        return out;
    }

    /** loadBlockGeneratorData — 16×16 chunk biomes (cached), used for surface dressing. */
    public BetaBiomeGenBase[] loadBlockGeneratorData(BetaBiomeGenBase[] out, int x, int z, int w, int h) {
        return func_35555_a(out, x, z, w, h, true);
    }

    /** func_35555_a — biome array from the voronoi layer, with optional cache path. */
    public BetaBiomeGenBase[] func_35555_a(BetaBiomeGenBase[] out, int x, int z, int w, int h, boolean useCache) {
        BetaIntCache.func_35268_a();
        if (out == null || out.length < w * h) out = new BetaBiomeGenBase[w * h];

        if (useCache && w == 16 && h == 16 && (x & 15) == 0 && (z & 15) == 0) {
            BetaBiomeGenBase[] cached = this.field_35563_f.func_35723_d(x, z);
            System.arraycopy(cached, 0, out, 0, w * h);
            return out;
        }
        int[] ids = this.field_34902_c.func_35500_a(x, z, w, h);
        for (int i = 0; i < w * h; ++i) {
            out[i] = BetaBiomeGenBase.field_35486_a[ids[i]];
        }
        return out;
    }

    /** getTemperatures — temperature array (0..1) from the temperature layer. */
    public float[] getTemperatures(float[] out, int x, int z, int w, int h) {
        BetaIntCache.func_35268_a();
        if (out == null || out.length < w * h) out = new float[w * h];
        int[] ids = this.field_34901_d.func_35500_a(x, z, w, h);
        for (int i = 0; i < w * h; ++i) {
            float v = (float) ids[i] / 65536.0F;
            if (v > 1.0F) v = 1.0F;
            out[i] = v;
        }
        return out;
    }

    /** getDownfalls — downfall array (0..1) from the downfall layer (func_35560_b). */
    public float[] getDownfalls(float[] out, int x, int z, int w, int h) {
        BetaIntCache.func_35268_a();
        if (out == null || out.length < w * h) out = new float[w * h];
        int[] ids = this.field_35565_e.func_35500_a(x, z, w, h);
        for (int i = 0; i < w * h; ++i) {
            float v = (float) ids[i] / 65536.0F;
            if (v > 1.0F) v = 1.0F;
            out[i] = v;
        }
        return out;
    }

    public void clearCache() {
        // BiomeCache is an LRU; nothing to flush for correctness.
    }
}
