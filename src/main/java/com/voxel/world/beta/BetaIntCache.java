package com.voxel.world.beta;

import java.util.ArrayList;
import java.util.List;

/** Faithful port of Beta 1.8.1's IntCache — reusable int[] pools. */
public final class BetaIntCache {
    private static int maxSize = 256;
    private static final List<int[]> freeSmall = new ArrayList<>();
    private static final List<int[]> inUseSmall = new ArrayList<>();
    private static final List<int[]> freeLarge = new ArrayList<>();
    private static final List<int[]> inUseLarge = new ArrayList<>();

    /** IntCache.func_35267_a — obtain a reusable int[] of at least the requested size. */
    public static int[] func_35267_a(int size) {
        if (size <= 256) {
            if (freeSmall.isEmpty()) {
                int[] a = new int[256];
                inUseSmall.add(a);
                return a;
            }
            int[] a = freeSmall.remove(freeSmall.size() - 1);
            inUseSmall.add(a);
            return a;
        } else if (size > maxSize) {
            maxSize = size;
            freeLarge.clear();
            inUseLarge.clear();
            int[] a = new int[maxSize];
            inUseLarge.add(a);
            return a;
        } else if (freeLarge.isEmpty()) {
            int[] a = new int[maxSize];
            inUseLarge.add(a);
            return a;
        } else {
            int[] a = freeLarge.remove(freeLarge.size() - 1);
            inUseLarge.add(a);
            return a;
        }
    }

    /** IntCache.func_35268_a — return all in-use buffers to the free pools. */
    public static void func_35268_a() {
        if (!freeLarge.isEmpty()) freeLarge.remove(freeLarge.size() - 1);
        if (!freeSmall.isEmpty()) freeSmall.remove(freeSmall.size() - 1);
        freeLarge.addAll(inUseLarge);
        freeSmall.addAll(inUseSmall);
        inUseLarge.clear();
        inUseSmall.clear();
    }

    /** Clear-name aliases for readability at call sites. */
    public static int[] getIntCache(int size) { return func_35267_a(size); }
    public static void resetIntCache() { func_35268_a(); }
}
