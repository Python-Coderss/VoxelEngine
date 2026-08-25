package com.voxel.ai.body;

import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GazeControllerTest {

    private static final float DT = 1f / 60f;

    @Test
    public void convergesTowardLookTarget() {
        GazeController g = new GazeController();
        g.lookAt(new Vector3f(10, 0, 0), 5f, 5f);
        float[] angles = new float[2];
        for (int i = 0; i < 240; i++) {
            g.tick(DT);
            angles = g.resolve(new Vector3f(0, 0, 0), 0f, DT);
        }
        assertTrue("yaw should approach the target direction (+X => +75 clamp)",
                angles[1] > 60f);
    }

    @Test
    public void higherPriorityWinsOverLower() {
        GazeController g = new GazeController();
        g.lookAt(new Vector3f(-10, 0, 0), 2f, 10f);
        g.lookAt(new Vector3f(10, 0, 0), 9f, 10f);
        float[] angles = new float[2];
        for (int i = 0; i < 240; i++) {
            g.tick(DT);
            angles = g.resolve(new Vector3f(0, 0, 0), 0f, DT);
        }
        assertTrue("threat-side target (+X) dominates", angles[1] > 60f);
    }

    @Test
    public void clearWeakerThanDropsLowPriorities() {
        GazeController g = new GazeController();
        g.lookAt(new Vector3f(1, 0, 0), 2f, 10f);
        g.lookAt(new Vector3f(-1, 0, 0), 8f, 10f);
        g.clearWeakerThan(5f);
        assertTrue(g.hasTargetAtLeast(8f));
        assertFalse(g.hasTargetAtLeast(9f));
    }

    @Test
    public void expiredTargetsFallBackToIdleSaccades() {
        GazeController g = new GazeController();
        g.lookAt(new Vector3f(0, 0, 10), 5f, 0.05f);
        g.tick(1f);
        float[] angles = g.resolve(new Vector3f(), 0f, 1f);
        assertTrue("idle saccade stays small",
                Math.abs(angles[1]) < 25f && Math.abs(angles[0]) < 10f);
    }

    @Test
    public void yawOffsetClampsToNeckLimit() {
        GazeController g = new GazeController();
        g.lookAt(new Vector3f(1000, 0, 0), 5f, 10f);
        float[] angles = new float[2];
        for (int i = 0; i < 600; i++) {
            g.tick(DT);
            angles = g.resolve(new Vector3f(0, 0, 0), 0f, DT);
        }
        assertTrue(angles[1] <= 75.01f);
    }

    @Test
    public void wrap180Normalizes() {
        assertTrue(Math.abs(GazeController.wrap180(270) + 90f) < 1e-4f);
        assertTrue(Math.abs(GazeController.wrap180(-200) - 160f) < 1e-4f);
        assertTrue(Math.abs(GazeController.wrap180(45) - 45f) < 1e-4f);
    }
}
