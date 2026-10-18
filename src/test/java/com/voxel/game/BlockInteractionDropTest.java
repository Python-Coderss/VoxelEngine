package com.voxel.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BlockInteractionDropTest {
    @Test
    public void stoneDropsCobblestone() {
        assertEquals("cobblestone", BlockInteraction.dropItemForBlock(2));
    }

    @Test
    public void existingSpecialOreDropsRemainExplicit() {
        assertEquals("redstone_wire", BlockInteraction.dropItemForBlock(26));
        assertEquals("lapis_ore", BlockInteraction.dropItemForBlock(85));
    }

    @Test
    public void ordinaryBlocksUseTheirCanonicalDropMapping() {
        assertNull(BlockInteraction.dropItemForBlock(1));
        assertNull(BlockInteraction.dropItemForBlock(71));
    }

    @Test
    public void everyMultiblockHalfDropsTheOneItemThatPlacedIt() {
        for (int foot : new int[]{com.voxel.world.DecorBlocks.BED_FOOT_NORTH,
                com.voxel.world.DecorBlocks.BED_FOOT_SOUTH,
                com.voxel.world.DecorBlocks.BED_FOOT_EAST,
                com.voxel.world.DecorBlocks.BED_FOOT_WEST}) {
            assertEquals("bed foot drops the bed", "bed",
                    BlockInteraction.dropItemForBlock(foot));
            assertEquals("bed head drops the bed", "bed",
                    BlockInteraction.dropItemForBlock(foot + 1));
        }
        assertEquals("banner drops the banner", "banner",
                BlockInteraction.dropItemForBlock(com.voxel.world.DecorBlocks.BANNER_LOWER_NS));
        assertEquals("banner top drops the banner", "banner",
                BlockInteraction.dropItemForBlock(com.voxel.world.DecorBlocks.BANNER_UPPER_EW));
    }
}
