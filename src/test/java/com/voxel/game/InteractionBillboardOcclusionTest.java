package com.voxel.game;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Raycast culling for the point-and-click prompts. A marker's anchor is tested
 * against the voxels between it and the camera, so a "click here" card cannot
 * glow through a wall; glass, leaves and fluids stay see-through because the
 * player can still click through them.
 */
public class InteractionBillboardOcclusionTest {

    /** Block id 1 is opaque; everything else (glass, water, leaves) is not. */
    private static final class TestWorld implements InteractionBillboardSystem.BlockOccluder {
        private final Map<String, Integer> blocks = new HashMap<String, Integer>();

        TestWorld block(int x, int y, int z, int id) {
            blocks.put(x + "," + y + "," + z, id);
            return this;
        }

        @Override
        public int voxelAt(int x, int y, int z) {
            Integer id = blocks.get(x + "," + y + "," + z);
            return id == null ? 0 : id;
        }

        @Override
        public boolean isOpaque(int blockId) {
            return blockId == 1;
        }
    }

    private static final float[] CAM = {0.5f, 0.5f, 0.5f};
    private static final float[] TARGET = {0.5f, 0.5f, 5.5f};

    private static boolean occluded(float[] cam, float[] target,
                                    InteractionBillboardSystem.BlockOccluder world) {
        return InteractionBillboardSystem.segmentOccluded(
                cam[0], cam[1], cam[2], target[0], target[1], target[2], world);
    }

    @Test
    public void clearLineOfSightKeepsThePrompt() {
        assertFalse(occluded(CAM, TARGET, new TestWorld()));
    }

    @Test
    public void aWallBetweenCameraAndTargetHidesThePrompt() {
        TestWorld wall = new TestWorld().block(0, 0, 2, 1);
        assertTrue(occluded(CAM, TARGET, wall));
    }

    @Test
    public void transparentBlocksDoNotHideThePrompt() {
        // Glass (2), water (3) and leaves (4) are not full opaque blocks: the
        // player can still click the chest behind them.
        TestWorld glass = new TestWorld()
                .block(0, 0, 2, 2)
                .block(0, 0, 3, 3)
                .block(0, 0, 4, 4);
        assertFalse(occluded(CAM, TARGET, glass));
    }

    @Test
    public void aWallBehindTheTargetIsIrrelevant() {
        TestWorld behind = new TestWorld().block(0, 0, 8, 1).block(0, 0, 9, 1);
        assertFalse(occluded(CAM, TARGET, behind));
    }

    @Test
    public void aWallBehindTheCameraIsIrrelevant() {
        TestWorld behind = new TestWorld().block(0, 0, -3, 1);
        assertFalse(occluded(CAM, TARGET, behind));
    }

    @Test
    public void theWalkCatchesOffAxisWallsToo() {
        // Target off to the side, with the occluding block on the diagonal.
        TestWorld wall = new TestWorld();
        float[] target = {6.5f, 0.5f, 0.5f};
        assertTrue(occluded(CAM, target, wall.block(3, 0, 0, 1)));
        assertFalse(occluded(CAM, target, new TestWorld()));
    }

    @Test
    public void aDegenerateSegmentIsNeverOccluded() {
        assertFalse(occluded(CAM, CAM, new TestWorld().block(0, 0, 0, 1)));
        assertFalse(InteractionBillboardSystem.segmentOccluded(
                0f, 0f, 0f, 0f, 0f, 0f, null));
    }
}
