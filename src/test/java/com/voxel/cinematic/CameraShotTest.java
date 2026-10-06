package com.voxel.cinematic;

import org.joml.Vector3f;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CameraShotTest {

    @Test
    public void dollySamplesEndpointsAndMidpoint() {
        CameraShot shot = CameraShot.dolly(
                new Vector3f(0, 0, 0), new Vector3f(10, 0, 0),
                new Vector3f(5, 2, 0), 70f, 2f);
        CameraShot.Sample start = shot.sample(0f);
        CameraShot.Sample mid = shot.sample(0.5f);
        CameraShot.Sample end = shot.sample(1f);
        assertEquals(0f, start.pos.x, 1e-4f);
        assertEquals(10f, end.pos.x, 1e-4f);
        // SMOOTH easing is symmetric, so the midpoint is the geometric midpoint.
        assertEquals(5f, mid.pos.x, 1e-3f);
        assertEquals(70f, mid.fov, 1e-3f);
        assertEquals(5f, mid.lookAt.x, 1e-3f);
        assertEquals(2f, mid.lookAt.y, 1e-3f);
    }

    @Test
    public void trackingOverridesTheFixedLookAt() {
        AtomicReference<Vector3f> subject = new AtomicReference<>(new Vector3f(0, 0, 0));
        CameraShot shot = CameraShot.medium(new Vector3f(3, 0, 0), 0f, 4f, 1f, 1f)
                .tracking(subject::get);
        assertEquals(0f, shot.sample(0.5f).lookAt.x, 1e-3f);
        subject.set(new Vector3f(9, 1, 0));
        assertEquals(9f, shot.sample(0.5f).lookAt.x, 1e-3f);
    }

    @Test
    public void closeUpUsesALongerLensThanWide() {
        Vector3f subject = new Vector3f(0, 0, 0);
        CameraShot close = CameraShot.closeUp(subject, 0, 2f, 1f, 1f);
        CameraShot wide = CameraShot.wide(subject, 0, 0, 20f, 4f, 1f);
        assertTrue("close-up must be tighter than a wide",
                close.sample(0.5f).fov < wide.sample(0.5f).fov);
        assertTrue("close-up camera sits near the subject",
                close.sample(0f).pos.distance(subject) < 4f);
    }

    @Test
    public void dutchRollIsApplied() {
        CameraShot shot = CameraShot.lowAngle(new Vector3f(0, 0, 0), 0, 3f, 1f)
                .dutch(5f, -5f);
        assertEquals(5f, shot.sample(0f).roll, 1e-3f);
        assertEquals(-5f, shot.sample(1f).roll, 1e-3f);
        assertEquals(0f, shot.sample(0.5f).roll, 1e-3f);
    }

    @Test
    public void twoShotFramesBothSubjectsFromOffAxis() {
        Vector3f a = new Vector3f(0, 1, 0);
        Vector3f b = new Vector3f(4, 1, 0);
        CameraShot shot = CameraShot.twoShot(a, b, 2f);
        CameraShot.Sample s = shot.sample(0.5f);
        // Looks at the midpoint between the two speakers.
        assertEquals(2f, s.lookAt.x, 1e-3f);
        assertEquals(0f, s.lookAt.z, 1e-3f);
        // Camera sits off the line joining them and far enough back to frame both.
        assertTrue("camera must be off the subject axis", Math.abs(s.pos.z) > 1f);
        assertTrue("camera must frame the whole pair",
                s.pos.distance(new Vector3f(2, 1, 0)) >= 3f);
    }

    @Test
    public void craneRisesWhileKeepingTheSubjectFramed() {
        Vector3f subject = new Vector3f(1, 2, 3);
        CameraShot shot = CameraShot.crane(subject, 0f, 6f, 0f, 8f, 2f);
        assertEquals(2f, shot.sample(0f).pos.y, 1e-3f);
        assertEquals(10f, shot.sample(1f).pos.y, 1e-3f);
        assertEquals(1f, shot.sample(0.5f).lookAt.x, 1e-3f);
        assertEquals(3f, shot.sample(0.5f).lookAt.z, 1e-3f);
    }

    @Test
    public void pushInTightensDistanceAndLens() {
        Vector3f subject = new Vector3f(0, 0, 0);
        CameraShot shot = CameraShot.pushIn(subject, 0f, 8f, 2f, 1f, 1.5f);
        assertTrue("push-in must close the distance",
                shot.sample(1f).pos.distance(subject) < shot.sample(0f).pos.distance(subject));
        assertTrue("push-in must tighten the lens",
                shot.sample(1f).fov < shot.sample(0f).fov);
    }

    @Test
    public void orbitPathsAroundTheCenterAtTheGivenRadius() {
        Vector3f center = new Vector3f(10, 5, -4);
        CameraShot shot = CameraShot.orbit(center, 8f, 2f, 0f, 180f, 2f);
        // The shot dollies between two orbit positions; each endpoint sits on
        // the requested radius, height above the centre.
        for (float t : new float[]{0f, 1f}) {
            CameraShot.Sample s = shot.sample(t);
            float dx = s.pos.x - center.x, dz = s.pos.z - center.z;
            assertEquals("horizontal radius at t=" + t,
                    8f, (float) Math.sqrt(dx * dx + dz * dz), 1e-2f);
            assertEquals("height above center at t=" + t,
                    2f, s.pos.y - center.y, 1e-3f);
        }
    }
}
