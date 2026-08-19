package com.voxel.world.beta;

/**
 * Faithful ports of Beta 1.8.1's GenLayer subclasses. Consolidated into one
 * file as package-private classes; obfuscated method names match the source.
 */

/** LayerIsland — the root layer: 1-in-10 chance of land, forced land at origin. */
class BetaLayerIsland extends BetaGenLayer {
    BetaLayerIsland(long seed) { super(seed); }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            for (int xx = 0; xx < width; ++xx) {
                this.func_35499_a((long) (x + xx), (long) (z + zz));
                out[xx + zz * width] = this.func_35498_a(10) == 0 ? 1 : 0;
            }
        }
        if (x > -width && x <= 0 && z > -height && z <= 0) {
            out[-x + -z * width] = 1;
        }
        return out;
    }
}

/** GenLayerIsland — expand/shrink land using a 2x2 neighborhood. */
class BetaGenLayerIsland extends BetaGenLayer {
    BetaGenLayerIsland(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int x0 = x - 1, z0 = z - 1;
        int w = width + 2, h = height + 2;
        int[] src = this.field_35504_a.func_35500_a(x0, z0, w, h);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            for (int xx = 0; xx < width; ++xx) {
                int v1 = src[xx + 0 + (zz + 0) * w];
                int v2 = src[xx + 2 + (zz + 0) * w];
                int v3 = src[xx + 0 + (zz + 2) * w];
                int v4 = src[xx + 2 + (zz + 2) * w];
                int center = src[xx + 1 + (zz + 1) * w];
                this.func_35499_a((long) (xx + x), (long) (zz + z));
                if (center != 0 || v1 == 0 && v2 == 0 && v3 == 0 && v4 == 0) {
                    if (center != 1 || v1 == 1 && v2 == 1 && v3 == 1 && v4 == 1) {
                        out[xx + zz * width] = center;
                    } else {
                        out[xx + zz * width] = 1 - this.func_35498_a(5) / 4;
                    }
                } else {
                    out[xx + zz * width] = 0 + this.func_35498_a(3) / 2;
                }
            }
        }
        return out;
    }
}

/** GenLayerZoom — classic 2x zoom with diagonal blending. */
class BetaGenLayerZoom extends BetaGenLayer {
    BetaGenLayerZoom(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int sx = x >> 1, sz = z >> 1;
        int w = (width >> 1) + 3, h = (height >> 1) + 3;
        int[] src = this.field_35504_a.func_35500_a(sx, sz, w, h);
        int[] tmp = BetaIntCache.func_35267_a(w * 2 * h * 2);
        int w2 = w << 1;

        for (int zz = 0; zz < h - 1; ++zz) {
            int zi = zz << 1;
            int off = zi * w2;
            int a = src[0 + (zz + 0) * w];
            int b = src[0 + (zz + 1) * w];
            for (int xx = 0; xx < w - 1; ++xx) {
                this.func_35499_a((long) (xx + sx << 1), (long) (zz + sz << 1));
                int c = src[xx + 1 + (zz + 0) * w];
                int d = src[xx + 1 + (zz + 1) * w];
                tmp[off] = a;
                tmp[off++ + w2] = this.func_35516_a(a, b);
                tmp[off] = this.func_35516_a(a, c);
                tmp[off++ + w2] = this.func_35514_b(a, c, b, d);
                a = c;
                b = d;
            }
        }

        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            System.arraycopy(tmp, (zz + (z & 1)) * w2 + (x & 1), out, zz * width, width);
        }
        return out;
    }

    protected int func_35516_a(int a, int b) {
        return this.func_35498_a(2) == 0 ? a : b;
    }

    protected int func_35514_b(int a, int b, int c, int d) {
        if (b == c && c == d) return b;
        else if (a == b && a == c) return a;
        else if (a == b && a == d) return a;
        else if (a == c && a == d) return a;
        else if (a == b && c != d) return a;
        else if (a == c && b != d) return a;
        else if (a == d && b != c) return a;
        else if (b == a && c != d) return b;
        else if (b == c && a != d) return b;
        else if (b == d && a != c) return b;
        else if (c == a && b != d) return c;
        else if (c == b && a != d) return c;
        else if (c == d && a != b) return c;
        else if (d == a && b != c) return c;
        else if (d == b && a != c) return c;
        else if (d == c && a != b) return c;
        else {
            int r = this.func_35498_a(4);
            return r == 0 ? a : (r == 1 ? b : (r == 2 ? c : d));
        }
    }

    static BetaGenLayer func_35515_a(long seed, BetaGenLayer layer, int count) {
        BetaGenLayer out = layer;
        for (int i = 0; i < count; ++i) {
            out = new BetaGenLayerZoom(seed + (long) i, out);
        }
        return out;
    }
}

/** GenLayerZoomFuzzy — fuzzy 2x zoom. */
class BetaGenLayerZoomFuzzy extends BetaGenLayer {
    BetaGenLayerZoomFuzzy(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int sx = x >> 1, sz = z >> 1;
        int w = (width >> 1) + 3, h = (height >> 1) + 3;
        int[] src = this.field_35504_a.func_35500_a(sx, sz, w, h);
        int[] tmp = BetaIntCache.func_35267_a(w * 2 * h * 2);
        int w2 = w << 1;

        for (int zz = 0; zz < h - 1; ++zz) {
            int zi = zz << 1;
            int off = zi * w2;
            int a = src[0 + (zz + 0) * w];
            int b = src[0 + (zz + 1) * w];
            for (int xx = 0; xx < w - 1; ++xx) {
                this.func_35499_a((long) (xx + sx << 1), (long) (zz + sz << 1));
                int c = src[xx + 1 + (zz + 0) * w];
                int d = src[xx + 1 + (zz + 1) * w];
                tmp[off] = a;
                tmp[off++ + w2] = this.func_35511_a(a, b);
                tmp[off] = this.func_35511_a(a, c);
                tmp[off++ + w2] = this.func_35510_b(a, c, b, d);
                a = c;
                b = d;
            }
        }

        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            System.arraycopy(tmp, (zz + (z & 1)) * w2 + (x & 1), out, zz * width, width);
        }
        return out;
    }

    protected int func_35511_a(int a, int b) {
        return this.func_35498_a(2) == 0 ? a : b;
    }

    protected int func_35510_b(int a, int b, int c, int d) {
        int r = this.func_35498_a(4);
        return r == 0 ? a : (r == 1 ? b : (r == 2 ? c : d));
    }
}

/** GenLayerZoomVoronoi — voronoi-smoothed zoom. */
class BetaGenLayerZoomVoronoi extends BetaGenLayer {
    BetaGenLayerZoomVoronoi(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        x -= 2; z -= 2;
        byte scale = 2;
        int step = 1 << scale;
        int sx = x >> scale, sz = z >> scale;
        int w = (width >> scale) + 3, h = (height >> scale) + 3;
        int[] src = this.field_35504_a.func_35500_a(sx, sz, w, h);
        int wStep = w << scale, hStep = h << scale;
        int[] tmp = BetaIntCache.func_35267_a(wStep * hStep);

        for (int zz = 0; zz < h - 1; ++zz) {
            int a = src[0 + (zz + 0) * w];
            int b = src[0 + (zz + 1) * w];
            for (int xx = 0; xx < w - 1; ++xx) {
                double r = (double) step * 0.9D;
                this.func_35499_a((long) (xx + sx << scale), (long) (zz + sz << scale));
                double d1 = ((double) this.func_35498_a(1024) / 1024.0D - 0.5D) * r;
                double d2 = ((double) this.func_35498_a(1024) / 1024.0D - 0.5D) * r;
                this.func_35499_a((long) (xx + sx + 1 << scale), (long) (zz + sz << scale));
                double d3 = ((double) this.func_35498_a(1024) / 1024.0D - 0.5D) * r + (double) step;
                double d4 = ((double) this.func_35498_a(1024) / 1024.0D - 0.5D) * r;
                this.func_35499_a((long) (xx + sx << scale), (long) (zz + sz + 1 << scale));
                double d5 = ((double) this.func_35498_a(1024) / 1024.0D - 0.5D) * r;
                double d6 = ((double) this.func_35498_a(1024) / 1024.0D - 0.5D) * r + (double) step;
                this.func_35499_a((long) (xx + sx + 1 << scale), (long) (zz + sz + 1 << scale));
                double d7 = ((double) this.func_35498_a(1024) / 1024.0D - 0.5D) * r + (double) step;
                double d8 = ((double) this.func_35498_a(1024) / 1024.0D - 0.5D) * r + (double) step;
                int c = src[xx + 1 + (zz + 0) * w];
                int d = src[xx + 1 + (zz + 1) * w];

                for (int sy = 0; sy < step; ++sy) {
                    int off = ((zz << scale) + sy) * wStep + (xx << scale);
                    for (int sxx = 0; sxx < step; ++sxx) {
                        double v1 = ((double) sy - d2) * ((double) sy - d2) + ((double) sxx - d1) * ((double) sxx - d1);
                        double v2 = ((double) sy - d4) * ((double) sy - d4) + ((double) sxx - d3) * ((double) sxx - d3);
                        double v3 = ((double) sy - d6) * ((double) sy - d6) + ((double) sxx - d5) * ((double) sxx - d5);
                        double v4 = ((double) sy - d8) * ((double) sy - d8) + ((double) sxx - d7) * ((double) sxx - d7);
                        if (v1 < v2 && v1 < v3 && v1 < v4) tmp[off++] = a;
                        else if (v2 < v1 && v2 < v3 && v2 < v4) tmp[off++] = c;
                        else if (v3 < v1 && v3 < v2 && v3 < v4) tmp[off++] = b;
                        else tmp[off++] = d;
                    }
                }
                a = c;
                b = d;
            }
        }

        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            System.arraycopy(tmp, (zz + (z & step - 1)) * wStep + (x & step - 1), out, zz * width, width);
        }
        return out;
    }
}

/** GenLayerSmoothZoom — interpolating smooth zoom. */
class BetaGenLayerSmoothZoom extends BetaGenLayer {
    BetaGenLayerSmoothZoom(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int sx = x >> 1, sz = z >> 1;
        int w = (width >> 1) + 3, h = (height >> 1) + 3;
        int[] src = this.field_35504_a.func_35500_a(sx, sz, w, h);
        int[] tmp = BetaIntCache.func_35267_a(w * 2 * h * 2);
        int w2 = w << 1;

        for (int zz = 0; zz < h - 1; ++zz) {
            int zi = zz << 1;
            int off = zi * w2;
            int a = src[0 + (zz + 0) * w];
            int b = src[0 + (zz + 1) * w];
            for (int xx = 0; xx < w - 1; ++xx) {
                this.func_35499_a((long) (xx + sx << 1), (long) (zz + sz << 1));
                int c = src[xx + 1 + (zz + 0) * w];
                int d = src[xx + 1 + (zz + 1) * w];
                tmp[off] = a;
                tmp[off++ + w2] = a + (b - a) * this.func_35498_a(256) / 256;
                tmp[off] = a + (c - a) * this.func_35498_a(256) / 256;
                int e = a + (c - a) * this.func_35498_a(256) / 256;
                int f = b + (d - b) * this.func_35498_a(256) / 256;
                tmp[off++ + w2] = e + (f - e) * this.func_35498_a(256) / 256;
                a = c;
                b = d;
            }
        }

        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            System.arraycopy(tmp, (zz + (z & 1)) * w2 + (x & 1), out, zz * width, width);
        }
        return out;
    }

    static BetaGenLayer func_35517_a(long seed, BetaGenLayer layer, int count) {
        BetaGenLayer out = layer;
        for (int i = 0; i < count; ++i) {
            out = new BetaGenLayerSmoothZoom(seed + (long) i, out);
        }
        return out;
    }
}

/** GenLayerRiverInit — seed river source (0/2+) from land. */
class BetaGenLayerRiverInit extends BetaGenLayer {
    BetaGenLayerRiverInit(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int[] src = this.field_35504_a.func_35500_a(x, z, width, height);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            for (int xx = 0; xx < width; ++xx) {
                this.func_35499_a((long) (xx + x), (long) (zz + z));
                out[xx + zz * width] = src[xx + zz * width] > 0 ? this.func_35498_a(2) + 2 : 0;
            }
        }
        return out;
    }
}

/** GenLayerRiver — detect river cells (-1 = non-river, river-id = river). */
class BetaGenLayerRiver extends BetaGenLayer {
    BetaGenLayerRiver(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int x0 = x - 1, z0 = z - 1;
        int w = width + 2, h = height + 2;
        int[] src = this.field_35504_a.func_35500_a(x0, z0, w, h);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            for (int xx = 0; xx < width; ++xx) {
                int v1 = src[xx + 0 + (zz + 1) * w];
                int v2 = src[xx + 2 + (zz + 1) * w];
                int v3 = src[xx + 1 + (zz + 0) * w];
                int v4 = src[xx + 1 + (zz + 2) * w];
                int center = src[xx + 1 + (zz + 1) * w];
                if (center != 0 && v1 != 0 && v2 != 0 && v3 != 0 && v4 != 0) {
                    if (center == v1 && center == v3 && center == v2 && center == v4) {
                        out[xx + zz * width] = -1;
                    } else {
                        out[xx + zz * width] = BetaBiomeGenBase.field_35487_i.field_35494_y;
                    }
                } else {
                    out[xx + zz * width] = BetaBiomeGenBase.field_35487_i.field_35494_y;
                }
            }
        }
        return out;
    }
}

/** GenLayerRiverMix — splice rivers into the biome layer. */
class BetaGenLayerRiverMix extends BetaGenLayer {
    private BetaGenLayer field_35512_b;
    private BetaGenLayer field_35513_c;

    BetaGenLayerRiverMix(long seed, BetaGenLayer biomes, BetaGenLayer rivers) {
        super(seed);
        this.field_35512_b = biomes;
        this.field_35513_c = rivers;
    }

    public void func_35496_b(long seed) {
        this.field_35512_b.func_35496_b(seed);
        this.field_35513_c.func_35496_b(seed);
        super.func_35496_b(seed);
    }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int[] biomes = this.field_35512_b.func_35500_a(x, z, width, height);
        int[] rivers = this.field_35513_c.func_35500_a(x, z, width, height);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int i = 0; i < width * height; ++i) {
            if (biomes[i] == BetaBiomeGenBase.field_35484_b.field_35494_y) {
                out[i] = biomes[i];
            } else {
                out[i] = rivers[i] >= 0 ? rivers[i] : biomes[i];
            }
        }
        return out;
    }
}

/** GenLayerSmooth — smooth biome borders. */
class BetaGenLayerSmooth extends BetaGenLayer {
    BetaGenLayerSmooth(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int x0 = x - 1, z0 = z - 1;
        int w = width + 2, h = height + 2;
        int[] src = this.field_35504_a.func_35500_a(x0, z0, w, h);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            for (int xx = 0; xx < width; ++xx) {
                int v1 = src[xx + 0 + (zz + 1) * w];
                int v2 = src[xx + 2 + (zz + 1) * w];
                int v3 = src[xx + 1 + (zz + 0) * w];
                int v4 = src[xx + 1 + (zz + 2) * w];
                int center = src[xx + 1 + (zz + 1) * w];
                if (v1 == v2 && v3 == v4) {
                    this.func_35499_a((long) (xx + x), (long) (zz + z));
                    center = this.func_35498_a(2) == 0 ? v1 : v3;
                } else {
                    if (v1 == v2) center = v1;
                    if (v3 == v4) center = v3;
                }
                out[xx + zz * width] = center;
            }
        }
        return out;
    }
}

/** GenLayerTemperature — map biome → temperature (×65536). */
class BetaGenLayerTemperature extends BetaGenLayer {
    BetaGenLayerTemperature(BetaGenLayer parent) { super(0L); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int[] src = this.field_35504_a.func_35500_a(x, z, width, height);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int i = 0; i < width * height; ++i) {
            out[i] = BetaBiomeGenBase.field_35486_a[src[i]].func_35474_f();
        }
        return out;
    }
}

/** GenLayerTemperatureMix — mix temperature with the biome layer. */
class BetaGenLayerTemperatureMix extends BetaGenLayer {
    private BetaGenLayer field_35505_b;
    private int field_35506_c;

    BetaGenLayerTemperatureMix(BetaGenLayer temp, BetaGenLayer biomes, int level) {
        super(0L);
        this.field_35504_a = biomes;
        this.field_35505_b = temp;
        this.field_35506_c = level;
    }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int[] biomes = this.field_35504_a.func_35500_a(x, z, width, height);
        int[] temps = this.field_35505_b.func_35500_a(x, z, width, height);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int i = 0; i < width * height; ++i) {
            out[i] = temps[i] + (BetaBiomeGenBase.field_35486_a[biomes[i]].func_35474_f() - temps[i]) / (this.field_35506_c * 2 + 1);
        }
        return out;
    }
}

/** GenLayerDownfall — map biome → downfall (×65536). */
class BetaGenLayerDownfall extends BetaGenLayer {
    BetaGenLayerDownfall(BetaGenLayer parent) { super(0L); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int[] src = this.field_35504_a.func_35500_a(x, z, width, height);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int i = 0; i < width * height; ++i) {
            out[i] = BetaBiomeGenBase.field_35486_a[src[i]].func_35476_e();
        }
        return out;
    }
}

/** GenLayerDownfallMix — mix downfall with the biome layer. */
class BetaGenLayerDownfallMix extends BetaGenLayer {
    private BetaGenLayer field_35507_b;
    private int field_35508_c;

    BetaGenLayerDownfallMix(BetaGenLayer downfall, BetaGenLayer biomes, int level) {
        super(0L);
        this.field_35504_a = biomes;
        this.field_35507_b = downfall;
        this.field_35508_c = level;
    }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int[] biomes = this.field_35504_a.func_35500_a(x, z, width, height);
        int[] downs = this.field_35507_b.func_35500_a(x, z, width, height);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int i = 0; i < width * height; ++i) {
            out[i] = downs[i] + (BetaBiomeGenBase.field_35486_a[biomes[i]].func_35476_e() - downs[i]) / (this.field_35508_c + 1);
        }
        return out;
    }
}

/** GenLayerVillageLandscape — assign a random biome to each land cell. */
class BetaGenLayerVillageLandscape extends BetaGenLayer {
    // 1.8.1 picks from six biomes; the six legacy 1.7.3 biomes are appended so
    // they also appear in the continental landscape.
    private BetaBiomeGenBase[] field_35509_b = new BetaBiomeGenBase[]{
        BetaBiomeGenBase.desert, BetaBiomeGenBase.forest, BetaBiomeGenBase.field_35483_e,
        BetaBiomeGenBase.swampland, BetaBiomeGenBase.field_35485_c, BetaBiomeGenBase.taiga,
        BetaBiomeGenBase.rainforest, BetaBiomeGenBase.seasonalForest,
        BetaBiomeGenBase.savanna, BetaBiomeGenBase.shrubland,
        BetaBiomeGenBase.iceDesert, BetaBiomeGenBase.tundra
    };

    BetaGenLayerVillageLandscape(long seed, BetaGenLayer parent) { super(seed); this.field_35504_a = parent; }

    public int[] func_35500_a(int x, int z, int width, int height) {
        int[] src = this.field_35504_a.func_35500_a(x, z, width, height);
        int[] out = BetaIntCache.func_35267_a(width * height);
        for (int zz = 0; zz < height; ++zz) {
            for (int xx = 0; xx < width; ++xx) {
                this.func_35499_a((long) (xx + x), (long) (zz + z));
                out[xx + zz * width] = src[xx + zz * width] > 0
                        ? this.field_35509_b[this.func_35498_a(this.field_35509_b.length)].field_35494_y
                        : 0;
            }
        }
        return out;
    }
}
