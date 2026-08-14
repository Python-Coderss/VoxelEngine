package com.voxel.tools;

import com.voxel.game.ChestManager;
import com.voxel.world.DimensionType;
import com.voxel.world.TutorialWorldAuthor;
import com.voxel.world.TutorialWorldGenerator;
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
 * One-time dev tool: builds the full handcrafted Tutorial World into a dense
 * in-memory array and exports it as a bundled template under
 * {@code src/main/resources/tutorial_world} (git-tracked). At runtime the game
 * copies this template into the save directory and streams the chunks in from
 * disk — no runtime procedural build, and no chunk-boundary truncation.
 *
 * The chunk files use the same v2 format {@link WorldSaveManager#loadChunk}
 * reads (magic, cx, cz, count, then short idx / short type / byte extra).
 *
 * Run with:
 *   MAVEN_OPTS="-Xmx2g" ./mvnw compile exec:java -Dexec.mainClass=com.voxel.tools.TutorialWorldExporter
 */
public final class TutorialWorldExporter {

    private static final String OUT_DIR = "src/main/resources/tutorial_world";
    private static final int W = TutorialWorldAuthor.AREA_CHUNKS * 16; // 768
    private static final int MIN = TutorialWorldAuthor.MIN;
    private static final int H = TutorialWorldAuthor.AREA_H; // 112

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();

        // Dense voxel store: [y][x-min][z-min] packed as type | extra<<16.
        final int[] vox = new int[W * H * W];
        TutorialWorldAuthor.Sink sink = new TutorialWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                int lx = x - MIN, lz = z - MIN;
                if (y < 0 || y >= H || lx < 0 || lx >= W || lz < 0 || lz >= W) return;
                vox[(y * W + lx) * W + lz] = (type & 0xFFFF) | ((extra & 0xFF) << 16);
            }

            @Override
            public int get(int x, int y, int z) {
                int lx = x - MIN, lz = z - MIN;
                if (y < 0 || y >= H || lx < 0 || lx >= W || lz < 0 || lz >= W) return 0;
                return vox[(y * W + lx) * W + lz] & 0xFFFF;
            }
        };

        System.out.println("Building handcrafted world (" + W + "x" + W + "x" + H + ")...");
        ChestManager chests = new ChestManager();
        TutorialWorldAuthor.buildAll(sink, chests);
        System.out.println("Build complete. Writing chunk files...");

        deleteRecursively(new File(OUT_DIR));
        File dimDir = new File(OUT_DIR, DimensionType.OVERWORLD.name);
        dimDir.mkdirs();

        int minC = MIN >> 4, maxC = TutorialWorldAuthor.MAX >> 4;
        int written = 0;
        for (int cx = minC; cx <= maxC; cx++) {
            for (int cz = minC; cz <= maxC; cz++) {
                writeChunk(dimDir, cx, cz, vox);
                written++;
            }
            if ((cx & 7) == 0) System.out.println("  columns " + (cx - minC) + "/" + (maxC - minC + 1));
        }
        System.out.println("Wrote " + written + " chunk files.");

        chests.saveToFile(new File(dimDir, "chest.dat"));
        writeLevelDat();
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
    private static void writeLevelDat() throws Exception {
        JSONObject root = new JSONObject();
        root.put("saveName", "tutorial");
        root.put("seed", 1234567L);
        root.put("worldSize", "MEDIUM");
        root.put("gameMode", "SURVIVAL");
        root.put("worldTime", 720.0);
        root.put("tutorial", true);
        root.put("lastPlayed", System.currentTimeMillis());

        JSONObject player = new JSONObject();
        player.put("x", 0.5);
        player.put("y", (double) (TutorialWorldGenerator.GROUND + 1));
        player.put("z", 6.5);
        player.put("yaw", -90.0);
        player.put("pitch", 0.0);
        player.put("health", 20.0);
        player.put("dimension", "overworld");

        // Starter kit: the Create tools so the player can experiment right away.
        String[] kit = {
            "wrench", "goggles", "brass_ingot", "hand_crank", "windmill_bearing",
            "windmill_sail", "shaft", "cogwheel", "large_cogwheel", "millstone",
            "mechanical_press", "crushing_wheel", "mechanical_drill", "mechanical_saw",
            "belt_conveyor", "item_vault", "brass_casing", "blaze_burner", "steam_engine",
            "copper_tank"
        };
        JSONArray inv = new JSONArray();
        for (int i = 0; i < kit.length; i++) {
            JSONObject s = new JSONObject();
            s.put("slot", i);
            s.put("item", kit[i]);
            int count = (kit[i].equals("shaft") || kit[i].equals("cogwheel")) ? 4 : 1;
            s.put("count", count);
            s.put("durability", 0);
            inv.put(s);
        }
        player.put("inventory", inv);
        root.put("player", player);

        File file = new File(OUT_DIR, "level.dat");
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
