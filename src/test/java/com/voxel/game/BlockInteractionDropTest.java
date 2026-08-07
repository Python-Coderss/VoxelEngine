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
}
