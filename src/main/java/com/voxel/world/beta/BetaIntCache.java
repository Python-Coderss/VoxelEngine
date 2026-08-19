package com.voxel.world.beta;

import java.util.ArrayList;
import java.util.List;

/**
 * Faithful port of Beta 1.8.1's IntCache — reusable int[] pools.
 *
 * Thread-safety note: vanilla's IntCache is a single static pool used from one
 * thread (the server thread). This engine runs chunk generation on a dedicated
 * gen thread while the map preview queries biomes on the logic thread, so the
 * pools are made per-thread (ThreadLocal). Each thread gets the exact vanilla
 * single-threaded semantics — {@link #func_35268_a()} only ever recycles arrays
 * that its own thread handed out — so no thread can reuse an array another
 * thread is still using, and the free-list bookkeeping can never race.
 */
public final class BetaIntCache {

    /** Per-thread pools: vanilla behavior preserved exactly within one thread. */
    private static final ThreadLocal<Pool> POOL = ThreadLocal.withInitial(Pool::new);

    private static final class Pool {
        private int maxSize = 256;
        private final List<int[]> freeSmall = new ArrayList<>();
        private final List<int[]> inUseSmall = new ArrayList<>();
        private final List<int[]> freeLarge = new ArrayList<>();
        private final List<int[]> inUseLarge = new ArrayList<>();
    }

    /** IntCache.func_35267_a — obtain a reusable int[] of at least the requested size. */
    public static int[] func_35267_a(int size) {
        Pool p = POOL.get();
        if (size <= 256) {
            if (p.freeSmall.isEmpty()) {
                int[] a = new int[256];
                p.inUseSmall.add(a);
                return a;
            }
            int[] a = p.freeSmall.remove(p.freeSmall.size() - 1);
            p.inUseSmall.add(a);
            return a;
        } else if (size > p.maxSize) {
            p.maxSize = size;
            p.freeLarge.clear();
            p.inUseLarge.clear();
            int[] a = new int[p.maxSize];
            p.inUseLarge.add(a);
            return a;
        } else if (p.freeLarge.isEmpty()) {
            int[] a = new int[p.maxSize];
            p.inUseLarge.add(a);
            return a;
        } else {
            int[] a = p.freeLarge.remove(p.freeLarge.size() - 1);
            p.inUseLarge.add(a);
            return a;
        }
    }

    /** IntCache.func_35268_a — return all in-use buffers to the free pools. */
    public static void func_35268_a() {
        Pool p = POOL.get();
        if (!p.freeLarge.isEmpty()) p.freeLarge.remove(p.freeLarge.size() - 1);
        if (!p.freeSmall.isEmpty()) p.freeSmall.remove(p.freeSmall.size() - 1);
        p.freeLarge.addAll(p.inUseLarge);
        p.freeSmall.addAll(p.inUseSmall);
        p.inUseLarge.clear();
        p.inUseSmall.clear();
    }

    /** Clear-name aliases for readability at call sites. */
    public static int[] getIntCache(int size) { return func_35267_a(size); }
    public static void resetIntCache() { func_35268_a(); }
}
