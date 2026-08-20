package com.voxel.entity;

import com.voxel.utils.TextureManager;
import org.joml.Vector3f;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests MagmaCubeEntity size-shrinking mechanic. We avoid GL by extending
 * with a no-op loadModel().
 */
public class MagmaCubeEntityTest {

    private static MagmaCubeEntity newBareCube(int size) {
        return new MagmaCubeEntity(1, new Vector3f(0, 64, 0), null, null, size) {
            @Override
            public void loadModel(String path, TextureManager textureManager) {
                // Skip GL model load.
            }
        };
    }

    @Test
    public void initialSizeIsClamped() {
        MagmaCubeEntity big = newBareCube(8);
        assertEquals("size clamped to 4", 4, big.getSize());
        MagmaCubeEntity small = newBareCube(0);
        assertEquals("size clamped to 1", 1, small.getSize());
    }

    @Test
    public void threePunchesShrinkSizeFourToOne() {
        MagmaCubeEntity cube = newBareCube(4);
        cube.onPunch(); cube.onPunch(); cube.onPunch();
        assertEquals("after 3 hits size = 1", 1, cube.getSize());
        assertFalse("size=1 not yet dead", cube.isDead());
    }

    @Test
    public void fourthPunchKillsSmallestCube() {
        MagmaCubeEntity cube = newBareCube(4);
        cube.onPunch(); cube.onPunch(); cube.onPunch(); cube.onPunch();
        assertTrue("4 hits kill a size-4 cube", cube.isDead());
    }

    @Test
    public void smallestCubeDiesOnFirstPunch() {
        MagmaCubeEntity cube = newBareCube(1);
        cube.onPunch();
        assertTrue("size=1 dies on first punch", cube.isDead());
    }
}