package com.voxel.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

public class CommandBlockManagerTest {
    @Test
    public void survivalOnlyAllowsFullyRelativeTeleport() {
        assertTrue(CommandBlockManager.isAllowedInSurvival("tp ~1 ~ ~-2"));
        assertFalse(CommandBlockManager.isAllowedInSurvival("give stone 1"));
    }

    @Test
    public void macrosExpandBuilderCoordinates() {
        assertEquals("tp 3064 189 1032 overworld", CommandBlockManager.expandMacros(
            "tp $(x) $(y) $(z) $(dimension)", null, 3064, 189, 1032));
    }

    @Test
    public void commandStateRoundTrips() throws Exception {
        File save = File.createTempFile("command-blocks", ".dat");
        try {
            CommandBlockManager manager = new CommandBlockManager();
            manager.getOrCreate(4, 5, 6).command = "tp ~1 ~ ~";
            manager.saveToFile(save);

            CommandBlockManager loaded = new CommandBlockManager();
            loaded.loadFromFile(save);
            assertEquals("tp ~1 ~ ~", loaded.get(4, 5, 6).command);
        } finally {
            save.delete();
        }
    }
}
