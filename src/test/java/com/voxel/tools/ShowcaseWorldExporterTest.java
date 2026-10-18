package com.voxel.tools;

import com.voxel.game.GameContext;
import com.voxel.world.ShowcaseWorldAuthor;
import com.voxel.world.WorldSaveManager;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end check that the Showcase World save metadata round-trips through
 * {@link WorldSaveManager}: the world must load as CREATIVE at the plaza spawn
 * with a starter kit, which is the contract the menu entry depends on.
 */
public class ShowcaseWorldExporterTest {

    @Test
    public void exporterWritesACreativeSaveTheLoaderCanRead() throws Exception {
        File dir = Files.createTempDirectory("showcase-save").toFile();
        try {
            ShowcaseWorldExporter.exportTo(dir);
            File level = new File(dir, "level.dat");
            assertTrue("level.dat written", level.isFile());

            WorldSaveManager mgr = new WorldSaveManager(dir.getPath());
            GameContext ctx = new GameContext();
            WorldSaveManager.PlayerState ps = mgr.loadLevelData(ctx);

            assertNotNull("player state loaded", ps);
            assertEquals("creative mode", GameContext.GameMode.CREATIVE, ctx.gameMode);
            assertEquals("spawn x", ShowcaseWorldAuthor.SPAWN_X, ps.x, 0.001);
            assertEquals("spawn y", ShowcaseWorldAuthor.G + 1, ps.y, 0.001);
            assertEquals("spawn z", ShowcaseWorldAuthor.SPAWN_Z, ps.z, 0.001);

            assertNotNull("starter kit", ps.inventory);
            assertNotNull("first kit slot", ps.inventory[0]);
            assertEquals("flint_and_steel", ps.inventory[0].itemId);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }
}
