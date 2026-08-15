package com.voxel.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the face-remap table that rotates directional Create machines
 * (encased fan, crushing wheel, drill, saw, deployer, belt) to their per-voxel
 * facing. {@link CreateMachineManager#remapDirectionalFace} is the Java
 * reference for the identical table embedded in raytracer.comp, so these tests
 * pin down the rotation semantics the shader must honor.
 *
 * Face order: 0=down, 1=up, 2=north, 3=south, 4=west, 5=east.
 * Canonical model orientation: the "front" texture sits on the east (5) face.
 */
public class CreateMachineFacingTest {

    private static final int[] FACES = {0, 1, 2, 3, 4, 5};

    @Test
    public void directionalSetCoversOnlyTheSixMachines() {
        for (int block : new int[]{263, 409, 410, 411, 412, 413}) {
            assertTrue("block " + block + " should be directional",
                    CreateMachineManager.isDirectionalMachine(block));
        }
        // Non-directional Create blocks (crank, bearing, sail, press, millstone,
        // vault, brass casing) must not be treated as directional.
        for (int block : new int[]{262, 404, 405, 406, 407, 408, 414, 415}) {
            assertFalse("block " + block + " must not be directional",
                    CreateMachineManager.isDirectionalMachine(block));
        }
    }

    @Test
    public void remapIsAPermutationForEveryFacing() {
        for (int facing = 0; facing <= 5; facing++) {
            boolean[] seen = new boolean[6];
            for (int face : FACES) {
                int mapped = CreateMachineManager.remapDirectionalFace(face, facing);
                assertTrue("facing " + facing + " must map face " + face
                        + " to a valid face (got " + mapped + ")", mapped >= 0 && mapped <= 5);
                assertFalse("facing " + facing + " must be a bijection (face " + face
                        + " and " + mapped + " collide)", seen[mapped]);
                seen[mapped] = true;
            }
            for (int i = 0; i < 6; i++) {
                assertTrue("facing " + facing + " must cover every model face " + i, seen[i]);
            }
        }
    }

    @Test
    public void frontFaceLandsOnFacingFace() {
        // The canonical front (east, 5) must be drawn on the face the machine points at.
        for (int facing = 0; facing <= 5; facing++) {
            assertEquals("front texture must land on the facing face",
                    5, CreateMachineManager.remapDirectionalFace(facing, facing));
        }
    }

    @Test
    public void horizontalFacingsKeepTopAndBottom() {
        for (int facing : new int[]{2, 3, 4, 5}) {
            assertEquals("up must stay up for horizontal facing " + facing,
                    1, CreateMachineManager.remapDirectionalFace(1, facing));
            assertEquals("down must stay down for horizontal facing " + facing,
                    0, CreateMachineManager.remapDirectionalFace(0, facing));
        }
    }

    @Test
    public void lockedTableMatchesReference() {
        // Explicit full tables (world face -> model face) for each facing, so any
        // accidental divergence from the shader copy fails loudly.
        int[][] expected = {
            {5, 4, 2, 3, 0, 1}, // facing 0 = down
            {4, 5, 2, 3, 1, 0}, // facing 1 = up
            {0, 1, 5, 4, 2, 3}, // facing 2 = north
            {0, 1, 4, 5, 3, 2}, // facing 3 = south
            {0, 1, 3, 2, 5, 4}, // facing 4 = west
            {0, 1, 2, 3, 4, 5}, // facing 5 = east
        };
        for (int facing = 0; facing <= 5; facing++) {
            for (int face = 0; face <= 5; face++) {
                assertEquals("facing " + facing + " face " + face,
                        expected[facing][face],
                        CreateMachineManager.remapDirectionalFace(face, facing));
            }
        }
    }
}
