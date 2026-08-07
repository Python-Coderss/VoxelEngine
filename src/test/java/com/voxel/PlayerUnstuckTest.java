package com.voxel;

import com.voxel.utils.BlockDataManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlayerUnstuckTest {
    @Test
    public void raisesOneBlockWhenOnlyFeetAreBlocked() {
        World world = testWorld();
        world.setVoxelInPool(0, 0, 0, 0, 1);

        Player player = new Player(0.5, 0.0, 0.5);
        assertEquals(1, player.unstuck(world, fullBlockData()));
        assertEquals(1.0f, player.getPosition().y, 0.0f);
    }

    @Test
    public void keepsRaisingUntilTheEntireAabbIsClear() {
        World world = testWorld();
        world.setVoxelInPool(0, 0, 0, 0, 1);
        world.setVoxelInPool(0, 0, 1, 0, 1);

        Player player = new Player(0.5, 0.0, 0.5);
        assertEquals(2, player.unstuck(world, fullBlockData()));
        assertEquals(2.0f, player.getPosition().y, 0.0f);
    }

    @Test
    public void preservesHorizontalVelocity() {
        World world = testWorld();
        world.setVoxelInPool(0, 0, 0, 0, 1);

        Player player = new Player(0.5, 0.0, 0.5);
        player.getVelocityD().set(0.25, -0.5, -0.75);
        assertEquals(1, player.unstuck(world, fullBlockData()));
        assertEquals(0.25, player.getVelocityD().x, 0.0);
        assertEquals(0.0, player.getVelocityD().y, 0.0);
        assertEquals(-0.75, player.getVelocityD().z, 0.0);
    }

    @Test
    public void rollsBackWhenSafetyLimitIsReached() {
        World world = new World(1) {
            @Override
            public int getVoxel(int x, int y, int z) {
                return 1;
            }
        };
        Player player = new Player(0.5, 0.0, 0.5);

        assertEquals(-1, player.unstuck(world, fullBlockData()));
        assertEquals(0.0f, player.getPosition().y, 0.0f);
    }

    private static World testWorld() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0);
        return world;
    }

    private static BlockDataManager fullBlockData() {
        return new BlockDataManager() {
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId == 1;
            }
        };
    }
}
