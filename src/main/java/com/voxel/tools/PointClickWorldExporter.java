package com.voxel.tools;

import com.voxel.game.ChestManager;
import com.voxel.world.DimensionType;
import com.voxel.world.PointClickWorldAuthor;
import com.voxel.world.WorldSaveManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.zip.GZIPOutputStream;

/**
 * One-time dev tool: builds the Point & Click demo world into a dense
 * in-memory array and exports it as a bundled template under
 * src/main/resources/pointclick_world (git-tracked). At runtime the game
 * copies this template into the save directory and loads it like any save.
 *
 * The chunk files use the same v2 format WorldSaveManager#loadChunk reads.
 *
 * Run with:
 *   MAVEN_OPTS="-Xmx2g" ./mvnw compile exec:java@export-pointclick
 */
public final class PointClickWorldExporter {

    private static final String OUT_DIR = "src/main/resources/pointclick_world";
    private static final int W = PointClickWorldAuthor.MAX - PointClickWorldAuthor.MIN + 1; // 128
    private static final int MIN = PointClickWorldAuthor.MIN;
    private static final int H = PointClickWorldAuthor.AREA_H; // 72

    public static void main(String[] args) throws Exception {
        exportTo(new File(OUT_DIR));
    }

    /**
     * Builds the full demo world and writes it (chunk .dat files, chest.dat,
     * level.dat) into {@code outDir} in the exact layout WorldSaveManager
     * reads. Called by the dev-tool main() for the bundled template AND by
     * Main at runtime when entering the demo — generating the 32x32 world is
     * instant and removes any dependency on the working directory matching
     * the repo root (the old file-copy of src/main/resources broke when the
     * game was launched from an IDE/jar, leaving only the flat fallback).
     */
    public static void exportTo(File outDir) throws Exception {
        long start = System.nanoTime();

        // Dense voxel store: [y][x-min][z-min] packed as type | extra<<16.
        final int[] vox = new int[W * H * W];
        PointClickWorldAuthor.Sink sink = new PointClickWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                int lx = x - MIN, lz = z - MIN;
                if (y < 0 || y >= H || lx < 0 || lx >= W || lz < 0 || lz >= W) return;
                vox[(y * W + lx) * W + lz] = (type & 0xFFFF) | ((extra & 0xFF) << 16);
            }
        };

        System.out.println("Building Point & Click demo world (" + W + "x" + W + "x" + H + ")...");
        ChestManager chests = new ChestManager();
        PointClickWorldAuthor.buildAll(sink, chests);
        System.out.println("Build complete. Writing chunk files...");

        deleteRecursively(outDir);
        File dimDir = new File(outDir, DimensionType.OVERWORLD.name);
        dimDir.mkdirs();

        int minC = MIN >> 4, maxC = (PointClickWorldAuthor.MAX) >> 4;
        int written = 0;
        for (int cx = minC; cx <= maxC; cx++) {
            for (int cz = minC; cz <= maxC; cz++) {
                writeChunk(dimDir, cx, cz, vox);
                written++;
            }
        }
        System.out.println("Wrote " + written + " chunk files.");

        chests.saveToFile(new File(dimDir, "chest.dat"));
        writeLevelDat(outDir);
        System.out.println("Wrote chest.dat + level.dat.");

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        System.out.println("DONE in " + elapsedMs + " ms -> " + new File(OUT_DIR).getAbsolutePath());
    }

    /** Writes one chunk column in the v2 format loadChunk() reads back. */
    private static void writeChunk(File dimDir, int cx, int cz, int[] vox) throws Exception {
        File regionDir = new File(dimDir, "chunks" + File.separator + (cx >> 5) + "_" + (cz >> 5));
        regionDir.mkdirs();
        File file = new File(regionDir, cx + "_" + cz + ".dat");

        // Count non-air first so we can write a tight payload.
        int count = 0;
        for (int cy = 0; cy * 16 < H; cy++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = (cx << 4) + lx;
                for (int ly = 0; ly < 16; ly++) {
                    int y = (cy << 4) + ly;
                    if (y >= H) continue;
                    for (int lz = 0; lz < 16; lz++) {
                        int wz = (cz << 4) + lz;
                        int lxr = wx - MIN, lzr = wz - MIN;
                        if (lxr < 0 || lxr >= W || lzr < 0 || lzr >= W) continue;
                        if ((vox[(y * W + lxr) * W + lzr] & 0xFFFF) > 0) count++;
                    }
                }
            }
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(WorldSaveManager.CHUNK_MAGIC);
            out.writeInt(cx);
            out.writeInt(cz);
            out.writeInt(count);
            for (int cy = 0; cy * 16 < H; cy++) {
                for (int lx = 0; lx < 16; lx++) {
                    int wx = (cx << 4) + lx;
                for (int ly = 0; ly < 16; ly++) {
                    int y = (cy << 4) + ly;
                    if (y >= H) continue;
                    for (int lz = 0; lz < 16; lz++) {
                            int wz = (cz << 4) + lz;
                            int lxr = wx - MIN, lzr = wz - MIN;
                            if (lxr < 0 || lxr >= W || lzr < 0 || lzr >= W) continue;
                            int raw = vox[(y * W + lxr) * W + lzr];
                            int type = raw & 0xFFFF;
                            if (type == 0) continue;
                            int idx = (cy * 16 + ly) * 256 + lx * 16 + lz;
                            out.writeShort(idx);
                            out.writeShort(type);
                            out.writeByte((raw >>> 16) & 0xFF);
                        }
                    }
                }
            }
        }
    }

    /** Hand-writes the template level.dat (seed, mode, spawn, starter kit). */
    private static void writeLevelDat(File outDir) throws Exception {
        JSONObject root = new JSONObject();
        root.put("saveName", "pointclick");
        root.put("seed", 42L);
        root.put("worldSize", "SMALL");
        root.put("gameMode", "SURVIVAL");
        root.put("worldTime", 720.0);
        root.put("tutorial", false);
        root.put("lastPlayed", System.currentTimeMillis());

        JSONObject player = new JSONObject();
        player.put("x", 0.5);
        player.put("y", (double) (PointClickWorldAuthor.G + 1));
        player.put("z", 0.5);
        player.put("yaw", -90.0);
        player.put("pitch", 0.0);
        player.put("health", 20.0);
        player.put("dimension", "overworld");

        // Starter kit: tools to interact with every station immediately.
        String[] kit = {
            "flint_and_steel", "water_bucket", "ender_eye",  // portal activators
            "oak_log", "coal", "iron_ore",                     // smelting fodder
            "stick", "torch", "apple", "iron_ingot"           // general utility
        };
        JSONArray inv = new JSONArray();
        for (int i = 0; i < kit.length; i++) {
            JSONObject s = new JSONObject();
            s.put("slot", i);
            s.put("item", kit[i]);
            s.put("count", kit[i].equals("stick") ? 16 : 8);
            s.put("durability", 0);
            inv.put(s);
        }
        player.put("inventory", inv);
        root.put("player", player);

        File file = new File(outDir, "level.dat");
        file.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
            w.write(root.toString(2));
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }
}
