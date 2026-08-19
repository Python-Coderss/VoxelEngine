package com.voxel.world.beta;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;

/**
 * Chunk generation (gen thread) and the HUD's per-frame biome readout
 * (render/logic thread) both call into the same {@link BetaWorldChunkManager}.
 * The GenLayer chain is single-threaded (vanilla semantics): a concurrent
 * {@code getBiomeGenAt} corrupts the layer's per-cell seed mid-density-kernel,
 * which produced whole chunks of missing stone at spawn.
 *
 * This test reproduces that interleaving: a reader thread hammers
 * {@code getBetaBiomeId} (the HUD path) while the main thread repeatedly
 * regenerates a chunk's full column (the gen path). With synchronized access
 * the terrain must stay byte-for-byte deterministic; without it, the reader's
 * seed writes bleed into the density kernel and the column diverges.
 */
public class BetaBiomeConcurrencyTest {

    private static final long SEED = 8078811528755789733L; // "New World" boot-log seed

    private static BetaChunkProvider makeProvider() {
        return new BetaChunkProvider(SEED, new BetaBlocks(
                2, 1, 13, 2, 15, 21, 14, 54, 59, 68, 67, 4, 5,
                121, 122, 35, 36, 39, 42, 0, 0, 40, 55,
                61, 81, 82, 83, 26, 85, 71, 132, 118, 258));
    }

    /** Captures the raw beta block ids of an entire 16×16×128 column. */
    private static byte[] captureColumn(BetaChunkProvider p, int cx, int cz) {
        byte[] out = new byte[16 * 128 * 16];
        int i = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 128; y++) {
                    out[i++] = (byte) p.getBetaBlock(cx * 16 + x, cz * 16 + z, y);
                }
            }
        }
        return out;
    }

    @Test
    public void concurrentBiomeReadoutDoesNotCorruptTerrain() throws Exception {
        BetaChunkProvider provider = makeProvider();

        // Baseline: correct, uncontended column at spawn.
        byte[] baseline = captureColumn(provider, 0, 0);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> readerError = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                int i = 0;
                while (!stop.get()) {
                    // Sweep the immediate spawn area like the HUD title readout does.
                    int x = (i % 64) - 32;
                    int z = ((i / 64) % 64) - 32;
                    provider.getBetaBiomeId(x, z);
                    i++;
                }
            } catch (Throwable t) {
                readerError.set(t);
            }
        }, "hud-biome-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            for (int iter = 0; iter < 400; iter++) {
                provider.invalidateCache();
                byte[] current = captureColumn(provider, 0, 0);
                assertArrayEquals("terrain corrupted at iteration " + iter, baseline, current);
            }
        } finally {
            stop.set(true);
            reader.join(5000);
        }

        if (readerError.get() != null) {
            throw new AssertionError("reader thread failed", readerError.get());
        }
    }
}
