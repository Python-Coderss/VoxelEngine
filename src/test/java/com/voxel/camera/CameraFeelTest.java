package com.voxel.camera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the gameplay-camera feel layer: trauma shake, head bob and
 * the speed FOV kick. Everything here is deterministic and GL-free, so the
 * curves can be asserted exactly.
 */
public class CameraFeelTest {

    private static final float STEP = 0.016f;

    private static void run(CameraFeel feel, float seconds) {
        int steps = Math.max(1, Math.round(seconds / STEP));
        for (int i = 0; i < steps; i++) feel.update(STEP);
    }

    @Test
    public void traumaIsClampedAndDecaysToRest() {
        CameraFeel feel = new CameraFeel();
        feel.addTrauma(5f);
        feel.update(STEP);
        assertTrue("trauma must clamp to 1", feel.getTrauma() <= CameraFeel.MAX_TRAUMA);

        run(feel, 2f);
        assertEquals(0f, feel.getTrauma(), 0f);
        assertEquals(0f, feel.getShakeYawDegrees(), 0f);
        assertEquals(0f, feel.getShakePitchDegrees(), 0f);
        assertEquals(0f, feel.getShakeOffsetX(), 0f);
        assertEquals(0f, feel.getShakeOffsetY(), 0f);
        assertEquals(0f, feel.getShakeOffsetZ(), 0f);
    }

    @Test
    public void shakeStaysSubtle() {
        CameraFeel feel = new CameraFeel();
        feel.addTrauma(1f);
        float maxYaw = 0f, maxPitch = 0f, maxRoll = 0f, maxOffset = 0f;
        for (int i = 0; i < 120; i++) {
            feel.update(0.008f);
            maxYaw = Math.max(maxYaw, Math.abs(feel.getShakeYawDegrees()));
            maxPitch = Math.max(maxPitch, Math.abs(feel.getShakePitchDegrees()));
            maxRoll = Math.max(maxRoll, Math.abs(feel.getShakeRollDegrees()));
            maxOffset = Math.max(maxOffset, Math.abs(feel.getShakeOffsetX()));
            maxOffset = Math.max(maxOffset, Math.abs(feel.getShakeOffsetY()));
            maxOffset = Math.max(maxOffset, Math.abs(feel.getShakeOffsetZ()));
        }
        assertTrue("yaw shake too large: " + maxYaw, maxYaw <= 1.6f);
        assertTrue("pitch shake too large: " + maxPitch, maxPitch <= 1.2f);
        assertTrue("roll shake too large: " + maxRoll, maxRoll <= 2.0f);
        assertTrue("positional shake too large: " + maxOffset, maxOffset <= 0.04f);
    }

    @Test
    public void shakeEnvelopeDecaysSmoothly() {
        CameraFeel feel = new CameraFeel();
        feel.addTrauma(1f);
        feel.update(STEP);
        float early = feel.getTrauma();
        run(feel, 0.3f);
        float mid = feel.getTrauma();
        run(feel, 0.6f);
        assertEquals(0f, feel.getTrauma(), 0f);
        assertTrue("trauma should fall monotonically", mid < early);
    }

    @Test
    public void shakeIsDeterministic() {
        CameraFeel a = new CameraFeel();
        CameraFeel b = new CameraFeel();
        a.addTrauma(0.7f);
        b.addTrauma(0.7f);
        for (int i = 0; i < 40; i++) {
            a.update(STEP);
            b.update(STEP);
            assertEquals(a.getShakeYawDegrees(), b.getShakeYawDegrees(), 0f);
            assertEquals(a.getShakePitchDegrees(), b.getShakePitchDegrees(), 0f);
            assertEquals(a.getShakeRollDegrees(), b.getShakeRollDegrees(), 0f);
        }
    }

    @Test
    public void decayIsFrameRateIndependent() {
        CameraFeel fast = new CameraFeel();
        CameraFeel slow = new CameraFeel();
        fast.addTrauma(1f);
        slow.addTrauma(1f);
        for (int i = 0; i < 60; i++) fast.update(0.008f);   // 0.48 s
        for (int i = 0; i < 30; i++) slow.update(0.016f);   // 0.48 s
        assertEquals(fast.getTrauma(), slow.getTrauma(), 1e-4f);
    }

    @Test
    public void sprintWidensTheLensAndItEasesBack() {
        CameraFeel feel = new CameraFeel();
        feel.setLocomotion(0.28f, true, true);
        run(feel, 0.6f);
        assertTrue("sprint should widen the FOV: " + feel.getFovOffsetDegrees(),
                feel.getFovOffsetDegrees() > 4f);
        assertTrue(feel.getFovOffsetDegrees() <= 6.01f);

        feel.setLocomotion(0f, true, false);
        run(feel, 1.5f);
        assertEquals(0f, feel.getFovOffsetDegrees(), 0.05f);
    }

    @Test
    public void fovKickIsSuppressedForCinematics() {
        CameraFeel feel = new CameraFeel();
        feel.setFovEnabled(false);
        feel.setLocomotion(0.28f, true, true);
        run(feel, 0.5f);
        assertEquals(0f, feel.getFovOffsetDegrees(), 0f);
    }

    @Test
    public void bobFollowsSpeedAndStopsWhenDisabledOrAirborne() {
        CameraFeel feel = new CameraFeel();
        feel.setLocomotion(0.20f, true, false);
        run(feel, 0.5f);
        assertTrue("walking should produce bob", feel.getBobAmount() > 0.5f);

        feel.setBobEnabled(false);
        run(feel, 1.0f);
        assertEquals(0f, feel.getBobAmount(), 0.01f);
        assertEquals(0f, feel.getBobY(), 0.001f);
        assertEquals(0f, feel.getBobX(), 0.001f);

        feel.setBobEnabled(true);
        feel.setLocomotion(0.20f, false, false); // in the air
        run(feel, 1.0f);
        assertEquals(0f, feel.getBobAmount(), 0.01f);
    }

    @Test
    public void bobIsSmallEnoughToStayComfortable() {
        CameraFeel feel = new CameraFeel();
        feel.setLocomotion(0.30f, true, true);
        float maxY = 0f, maxX = 0f;
        for (int i = 0; i < 120; i++) {
            feel.update(0.008f);
            maxY = Math.max(maxY, Math.abs(feel.getBobY()));
            maxX = Math.max(maxX, Math.abs(feel.getBobX()));
        }
        assertTrue("vertical bob too large: " + maxY, maxY <= 0.05f);
        assertTrue("lateral bob too large: " + maxX, maxX <= 0.035f);
    }
}
