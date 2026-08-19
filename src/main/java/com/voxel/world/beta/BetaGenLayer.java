package com.voxel.world.beta;

/**
 * Faithful port of Beta 1.8.1's GenLayer — the biome assignment layer chain.
 * The obfuscated field/method names match the decompiled source so the port can
 * be verified line-by-line. field_35504_a = parent layer, field_35502_b =
 * world-gen seed, field_35503_c = per-cell chunk seed, field_35501_d = base
 * seed baked in the constructor.
 */
public abstract class BetaGenLayer {
    private long field_35502_b;
    protected BetaGenLayer field_35504_a;
    private long field_35503_c;
    private long field_35501_d;

    /**
     * func_35497_a — builds the full 1.8.1 biome/temperature/downfall layer
     * chain. Returns {biome (river-mixed), voronoi-biome, temperature, downfall}.
     */
    public static BetaGenLayer[] func_35497_a(long seed) {
        BetaLayerIsland var2 = new BetaLayerIsland(1L);
        BetaGenLayerZoomFuzzy var9 = new BetaGenLayerZoomFuzzy(2000L, var2);
        BetaGenLayerIsland var10 = new BetaGenLayerIsland(1L, var9);
        BetaGenLayerZoom var11 = new BetaGenLayerZoom(2001L, var10);
        var10 = new BetaGenLayerIsland(2L, var11);
        var11 = new BetaGenLayerZoom(2002L, var10);
        var10 = new BetaGenLayerIsland(3L, var11);
        var11 = new BetaGenLayerZoom(2003L, var10);
        var10 = new BetaGenLayerIsland(3L, var11);
        var11 = new BetaGenLayerZoom(2004L, var10);
        var10 = new BetaGenLayerIsland(3L, var11);
        byte var3 = 4;
        BetaGenLayer var4 = BetaGenLayerZoom.func_35515_a(1000L, var10, 0);
        BetaGenLayerRiverInit var12 = new BetaGenLayerRiverInit(100L, var4);
        var4 = BetaGenLayerZoom.func_35515_a(1000L, var12, var3 + 2);
        BetaGenLayerRiver var13 = new BetaGenLayerRiver(1L, var4);
        BetaGenLayerSmooth var14 = new BetaGenLayerSmooth(1000L, var13);
        BetaGenLayer var5 = BetaGenLayerZoom.func_35515_a(1000L, var10, 0);
        BetaGenLayerVillageLandscape var15 = new BetaGenLayerVillageLandscape(200L, var5);
        BetaGenLayer var16 = BetaGenLayerZoom.func_35515_a(1000L, var15, 2);
        BetaGenLayer var6 = new BetaGenLayerTemperature(var16);
        BetaGenLayer var7 = new BetaGenLayerDownfall(var16);

        for (int var8 = 0; var8 < var3; ++var8) {
            var16 = new BetaGenLayerZoom((long) (1000 + var8), var16);
            if (var8 == 0) {
                var16 = new BetaGenLayerIsland(3L, var16);
            }
            BetaGenLayerSmoothZoom var17 = new BetaGenLayerSmoothZoom((long) (1000 + var8), var6);
            var6 = new BetaGenLayerTemperatureMix(var17, var16, var8);
            BetaGenLayerSmoothZoom var21 = new BetaGenLayerSmoothZoom((long) (1000 + var8), var7);
            var7 = new BetaGenLayerDownfallMix(var21, var16, var8);
        }

        BetaGenLayerSmooth var18 = new BetaGenLayerSmooth(1000L, var16);
        BetaGenLayerRiverMix var20 = new BetaGenLayerRiverMix(100L, var18, var14);
        BetaGenLayer var19 = BetaGenLayerSmoothZoom.func_35517_a(1000L, var6, 2);
        BetaGenLayer var22 = BetaGenLayerSmoothZoom.func_35517_a(1000L, var7, 2);
        BetaGenLayerZoomVoronoi var23 = new BetaGenLayerZoomVoronoi(10L, var20);
        var20.func_35496_b(seed);
        var19.func_35496_b(seed);
        var22.func_35496_b(seed);
        var23.func_35496_b(seed);
        return new BetaGenLayer[]{var20, var23, var19, var22};
    }

    public BetaGenLayer(long baseSeed) {
        this.field_35501_d = baseSeed;
        this.field_35501_d *= this.field_35501_d * 6364136223846793005L + 1442695040888963407L;
        this.field_35501_d += baseSeed;
        this.field_35501_d *= this.field_35501_d * 6364136223846793005L + 1442695040888963407L;
        this.field_35501_d += baseSeed;
        this.field_35501_d *= this.field_35501_d * 6364136223846793005L + 1442695040888963407L;
        this.field_35501_d += baseSeed;
    }

    /** func_35496_b — initialize the world-gen seed recursively down the chain. */
    public void func_35496_b(long worldGenSeed) {
        this.field_35502_b = worldGenSeed;
        if (this.field_35504_a != null) {
            this.field_35504_a.func_35496_b(worldGenSeed);
        }
        this.field_35502_b *= this.field_35502_b * 6364136223846793005L + 1442695040888963407L;
        this.field_35502_b += this.field_35501_d;
        this.field_35502_b *= this.field_35502_b * 6364136223846793005L + 1442695040888963407L;
        this.field_35502_b += this.field_35501_d;
        this.field_35502_b *= this.field_35502_b * 6364136223846793005L + 1442695040888963407L;
        this.field_35502_b += this.field_35501_d;
    }

    /** func_35499_a — initialize the per-cell chunk seed for (x, z). */
    public void func_35499_a(long x, long z) {
        this.field_35503_c = this.field_35502_b;
        this.field_35503_c *= this.field_35503_c * 6364136223846793005L + 1442695040888963407L;
        this.field_35503_c += x;
        this.field_35503_c *= this.field_35503_c * 6364136223846793005L + 1442695040888963407L;
        this.field_35503_c += z;
        this.field_35503_c *= this.field_35503_c * 6364136223846793005L + 1442695040888963407L;
        this.field_35503_c += x;
        this.field_35503_c *= this.field_35503_c * 6364136223846793005L + 1442695040888963407L;
        this.field_35503_c += z;
    }

    /** func_35498_a — next pseudo-random int in [0, bound). */
    protected int func_35498_a(int bound) {
        int v = (int) ((this.field_35503_c >> 24) % (long) bound);
        if (v < 0) v += bound;
        this.field_35503_c *= this.field_35503_c * 6364136223846793005L + 1442695040888963407L;
        this.field_35503_c += this.field_35502_b;
        return v;
    }

    /** func_35500_a — generate the layer values for an (x, z) region. */
    public abstract int[] func_35500_a(int x, int z, int width, int height);
}
