package com.voxel.tools;

import com.voxel.game.GameContext;
import com.voxel.world.LightingDemoWorldAuthor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Writes the save metadata for the Lighting Demo world.
 *
 * <p>No chunk files: the two halls are regenerated at runtime by
 * {@link com.voxel.world.LightingDemoWorldGenerator} into every section it is
 * asked to populate, so {@code level.dat} is the whole template.</p>
 */
public final class LightingDemoWorldExporter {

    /** Save folder name under saves/. */
    public static final String SAVE_NAME = "lighting";

    /** Starter kit: light sources and building stock, to extend the demo in place. */
    private static final String[] STARTER_KIT = {
        "torch", "glowstone", "oak_planks", "stone_brick", "glass", "lava_bucket", "chest", "coal"
    };

    private LightingDemoWorldExporter() {}

    /** Creates {@code saveDir} and writes its level.dat. */
    public static void exportTo(File saveDir) throws Exception {
        saveDir.mkdirs();
        writeLevelDat(saveDir);
    }

    /** Hand-writes level.dat (seed, mode, spawn, starter kit). */
    public static void writeLevelDat(File saveDir) throws Exception {
        JSONObject root = new JSONObject();
        root.put("saveName", SAVE_NAME);
        root.put("seed", com.voxel.world.PackPng.SEED);
        root.put("worldSize", "SMALL");
        root.put("gameMode", GameContext.GameMode.CREATIVE.name());
        // Mid-morning: the light hall should be at full sun on entry.
        root.put("worldTime", 720.0);
        root.put("tutorial", false);
        root.put("lastPlayed", System.currentTimeMillis());

        JSONObject player = new JSONObject();
        player.put("x", LightingDemoWorldAuthor.SPAWN_X);
        player.put("y", (double) (LightingDemoWorldAuthor.G + 1));
        player.put("z", LightingDemoWorldAuthor.SPAWN_Z);
        player.put("yaw", -90.0);
        player.put("pitch", 0.0);
        player.put("health", 20.0);
        player.put("hunger", 20.0);
        player.put("saturation", 5.0);
        player.put("dimension", "overworld");

        JSONArray inv = new JSONArray();
        for (int i = 0; i < STARTER_KIT.length; i++) {
            JSONObject s = new JSONObject();
            s.put("slot", i);
            s.put("item", STARTER_KIT[i]);
            s.put("count", 64);
            s.put("durability", 0);
            inv.put(s);
        }
        player.put("inventory", inv);
        root.put("player", player);

        File file = new File(saveDir, "level.dat");
        file.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
            w.write(root.toString(2));
        }
    }
}
