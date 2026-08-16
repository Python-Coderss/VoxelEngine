package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Diagnostic: dump the baked light pool around a glowstone + torch and print
 * what the raytracer's block-light shading math produces, so we can see the
 * exact halo color. Not a pass/fail test — prints to stdout.
 */
public class LightHaloDumpTest {

    private static final int X = 8, Z = 8, Y = 68;

    private static final class Harness implements AutoCloseable {
        final World world;
        final ChunkManager chunkManager;
        final LightEngine lightEngine;

        Harness() throws Exception {
            world = new World(128);
            BlockDataManager bdm = stubBdm();
            WorldSaveManager saveManager = new WorldSaveManager(
                    System.getProperty("java.io.tmpdir") + "/voxel-light-dump-" + System.nanoTime());
            WorldGenerator gen = new WorldGenerator(2L, bdm) {
                @Override
                public int populateSection(int cx, int cy, int cz, World w, int slot) {
                    return 0; // all-air
                }
            };
            lightEngine = new LightEngine(world, bdm);
            chunkManager = new ChunkManager(world, gen, lightEngine, 4, saveManager,
                    DimensionType.OVERWORLD, null, bdm);
        }

        void ready() throws InterruptedException {
            chunkManager.update(new Vector3f(X + 0.5f, Y + 0.5f, Z + 0.5f), 0f);
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline
                    && !chunkManager.isPlayerSectionGenerated(0, 4, 0)) Thread.sleep(10);
            long settle = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < settle) {
                if (world.getOffsetX() != 0 || world.getOffsetY() != 0 || world.getOffsetZ() != 0) break;
                Thread.sleep(10);
            }
            deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline
                    && !chunkManager.isPlayerSectionGenerated(0, 4, 0)) Thread.sleep(10);
        }

        void place(int x, int y, int z, int block) {
            chunkManager.setVoxel(x, y, z, block);
        }

        /** Runs the light engine synchronously on the loaded columns (as the light thread would). */
        void relight() throws Exception {
            Map<Long, NavigableMap<Integer, Integer>> columns = new HashMap<>();
            for (Map.Entry<Long, NavigableMap<Integer, Integer>> e : chunkManager.getLoadedChunks().entrySet()) {
                columns.put(e.getKey(), e.getValue());
            }
            lightEngine.propagateBlockLightRegion(columns);
            for (Map.Entry<Long, NavigableMap<Integer, Integer>> e : columns.entrySet()) {
                int cx = (int) (e.getKey() >> 32);
                int cz = e.getKey().intValue();
                lightEngine.generateSkyLight(cx, cz, e.getValue());
            }
        }

        @Override
        public void close() {
            chunkManager.shutdown();
        }
    }

    @Test
    public void dumpHalo() throws Exception {
        try (Harness h = new Harness()) {
            h.ready();
            // Floor so torch/glowstone sit on something.
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                h.place(X + dx, Y - 1, Z + dz, 1); // stone floor
            }
            h.place(X, Y, Z, 17);      // glowstone at (8,68,8)
            h.place(X + 3, Y, Z, 211); // torch 3 blocks east
            h.relight();

            int[] pool = h.world.getLightPool();
            System.out.println("=== lightPool around glowstone (8,68,8), row z=8, y=68 ===");
            for (int x = X - 3; x <= X + 3; x++) {
                int cx = x >> 4, cy = Y >> 4, cz = Z >> 4;
                int slot = h.world.getChunkSlot(x, Y, Z);
                int lx = x & 15, ly = Y & 15, lz = Z & 15;
                int raw = pool[(slot << 12) | (lx | (ly << 4) | (lz << 8))];
                int sky = raw & 0xFF, r = (raw >>> 8) & 0xFF, g = (raw >>> 16) & 0xFF, b = (raw >>> 24) & 0xFF;
                System.out.printf("  (%d,68,8) sky=%3d blockRGB=(%3d,%3d,%3d)%n", x, sky, r, g, b);
            }
            System.out.println("=== shader simulation: OLD (extrapolating mix) vs FIXED (clamped mix) ===");
            // Noon tutorial atmosphere: ambient=(0.04,0.14,0.3), sun=(0.7,0.68,0.63), celRamp(1)=1
            double[] amb = {0.04, 0.14, 0.30};
            double[] sun = {0.70, 0.68, 0.63};
            for (int x = X - 3; x <= X + 3; x++) {
                int slot = h.world.getChunkSlot(x, Y, Z);
                int lx = x & 15, ly = Y & 15, lz = Z & 15;
                int raw = pool[(slot << 12) | (lx | (ly << 4) | (lz << 8))];
                double sky = (raw & 0xFF) / 255.0 * 1.12;
                double br = ((raw >>> 8) & 0xFF) * 4.0;
                double bg = ((raw >>> 16) & 0xFF) * 4.0;
                double bb = ((raw >>> 24) & 0xFF) * 4.0;
                double bi = Math.max(br, Math.max(bg, bb));
                double bc = Math.max(bi * 5.0, 1.0) / 255.0;
                double total = Math.max(sky, bc);
                double[] tint = bi > 0.001 ? new double[]{br / bi, bg / bi, bb / bi} : new double[]{1, 1, 1};
                double t = bc * 0.8;
                // ACESFilm identical to the shader: clamp((x*(2.51x+0.03))/(x*(2.43x+0.59)+0.14), 0, 1)
                java.util.function.DoubleUnaryOperator aces = v -> Math.min(1.0, Math.max(0.0,
                        (v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14)));
                double[] oldBeam = new double[3], newBeam = new double[3];
                for (int c = 0; c < 3; c++) {
                    double oldMix = 1.0 * (1 - t) + tint[c] * t; // extrapolates when t>1 (the bug)
                    double newMix = 1.0 * (1 - Math.min(t, 1.0)) + tint[c] * Math.min(t, 1.0); // clamped (fix)
                    oldBeam[c] = (amb[c] + sun[c]) * total * oldMix + amb[c] * 0.3;
                    newBeam[c] = (amb[c] + sun[c]) * total * newMix + amb[c] * 0.3;
                }
                System.out.printf("  x=%d block=(%.0f,%.0f,%.0f) tint=(%.2f,%.2f,%.2f) mixT=%.2f%n",
                        x, br, bg, bb, tint[0], tint[1], tint[2], t);
                System.out.printf("    OLD  beam=(%7.2f,%7.2f,%7.2f) -> ACES (%.2f,%.2f,%.2f)  %s%n",
                        oldBeam[0], oldBeam[1], oldBeam[2],
                        aces.applyAsDouble(oldBeam[0]), aces.applyAsDouble(oldBeam[1]), aces.applyAsDouble(oldBeam[2]),
                        aces.applyAsDouble(oldBeam[0]) > 0.9 && aces.applyAsDouble(oldBeam[1]) < 0.75 && aces.applyAsDouble(oldBeam[2]) > 0.9 ? "<-- MAGENTA" : "");
                System.out.printf("    NEW  beam=(%7.2f,%7.2f,%7.2f) -> ACES (%.2f,%.2f,%.2f)%n",
                        newBeam[0], newBeam[1], newBeam[2],
                        aces.applyAsDouble(newBeam[0]), aces.applyAsDouble(newBeam[1]), aces.applyAsDouble(newBeam[2]));
            }
        }
    }

    private static BlockDataManager stubBdm() {
        return new BlockDataManager() {
            @Override public boolean isFullBlock(int id) { return id > 0 && id != 211 && id != 41; }
            @Override public boolean isLiquid(int id) { return false; }
            @Override public int getEmissiveFast(int id) {
                if (id == 17) return 255;
                if (id == 211) return 204; // torch
                return 0;
            }
            @Override public int[] getEmissiveArray() {
                int[] a = new int[512];
                a[17] = 255; a[211] = 204;
                return a;
            }
            @Override public int getLightColorFast(int id) {
                if (id == 17) return 0xFFDC96;   // glowstone amber (255,220,150)
                if (id == 211) return 0xFFDC8C;  // torch amber (255,220,140)
                return 0xFFFFFF;
            }
            @Override public int[] getLightColorArray() {
                int[] a = new int[512];
                java.util.Arrays.fill(a, 0xFFFFFF);
                a[17] = 0xFFDC96; a[211] = 0xFFDC8C;
                return a;
            }
            @Override public int getOpacityFast(int id) { return id > 0 && id != 211 ? 16 : 0; }
            @Override public int getOpacity(int id) { return getOpacityFast(id); }
            @Override public String getName(int id) { return id == 17 ? "glowstone" : id == 211 ? "torch" : String.valueOf(id); }
        };
    }
}
