package com.voxel.tools;

import com.voxel.game.GameContext;
import com.voxel.world.ShowcaseWorldAuthor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Writes the save metadata for the creative Showcase World.
 *
 * <p>Unlike {@link PointClickWorldExporter} and {@link TutorialWorldExporter}
 * this emits <b>no chunk files</b>: the hub is regenerated at runtime by
 * {@link com.voxel.world.ShowcaseWorldGenerator} into every section it is asked
 * to populate, so all that has to live on disk is {@code level.dat}.</p>
 *
 * <p>{@code level.dat} is what makes the world appear in the Load Save list and
 * carries the creative game mode, the plaza spawn point and a starter kit. It
 * uses exactly the keys {@link com.voxel.world.WorldSaveManager#loadLevelData}
 * reads back.</p>
 */
public final class ShowcaseWorldExporter {

    /** Save folder name under saves/. */
    public static final String SAVE_NAME = "showcase";

    /** Starter items placed in the player's hotbar on entry. */
    private static final String[] STARTER_KIT = {
        "flint_and_steel", "water_bucket", "eye_of_ender",
        "diamond_pickaxe", "torch", "oak_planks", "stone", "diamond"
    };

    private ShowcaseWorldExporter() {}

    /** Creates {@code saveDir} and writes its level.dat. */
    public static void exportTo(File saveDir) throws Exception {
        saveDir.mkdirs();
        writeLevelDat(saveDir);
    }

    /** Hand-writes level.dat (seed, mode, spawn, starter kit). */
    public static void writeLevelDat(File saveDir) throws Exception {
        JSONObject root = new JSONObject();
        root.put("saveName", SAVE_NAME);
        root.put("seed", 0L);
        root.put("worldSize", "MEDIUM");
        root.put("gameMode", GameContext.GameMode.CREATIVE.name());
        root.put("worldTime", 1000.0);
        root.put("tutorial", false);
        root.put("lastPlayed", System.currentTimeMillis());

        JSONObject player = new JSONObject();
        player.put("x", ShowcaseWorldAuthor.SPAWN_X);
        player.put("y", (double) (ShowcaseWorldAuthor.G + 1));
        player.put("z", ShowcaseWorldAuthor.SPAWN_Z);
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
