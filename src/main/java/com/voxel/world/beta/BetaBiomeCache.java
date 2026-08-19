package com.voxel.world.beta;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Faithful port of Beta 1.8.1's BiomeCache — caches the 16×16 biome /
 * temperature / downfall blocks per chunk region. The vanilla PlayerList LRU is
 * replaced with a LinkedHashMap LRU (same observable behavior).
 */
public class BetaBiomeCache {
    private final BetaWorldChunkManager chunkManager;
    private final Map<Long, BiomeCacheBlock> cache = new LinkedHashMap<Long, BiomeCacheBlock>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, BiomeCacheBlock> eldest) {
            return size() > 1024;
        }
    };

    public BetaBiomeCache(BetaWorldChunkManager cm) {
        this.chunkManager = cm;
    }

    private BiomeCacheBlock getBlock(int x, int z) {
        x >>= 4;
        z >>= 4;
        long key = (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
        BiomeCacheBlock block = cache.get(key);
        if (block == null) {
            block = new BiomeCacheBlock(x, z);
            cache.put(key, block);
        }
        return block;
    }

    /** func_35725_a — biome at a single (x, z). */
    public BetaBiomeGenBase func_35725_a(int x, int z) {
        return getBlock(x, z).biomes[x & 15 | (z & 15) << 4];
    }

    /** func_35723_d — the full 16×16 biome array for a chunk. */
    public BetaBiomeGenBase[] func_35723_d(int x, int z) {
        return getBlock(x, z).biomes;
    }

    private final class BiomeCacheBlock {
        final float[] temperatures = new float[256];
        final float[] downfalls = new float[256];
        final BetaBiomeGenBase[] biomes = new BetaBiomeGenBase[256];

        BiomeCacheBlock(int x, int z) {
            chunkManager.getTemperatures(temperatures, x << 4, z << 4, 16, 16);
            chunkManager.getDownfalls(downfalls, x << 4, z << 4, 16, 16);
            // useCache=false avoids recursing back into this cache (matches vanilla).
            chunkManager.func_35555_a(biomes, x << 4, z << 4, 16, 16, false);
        }
    }
}
