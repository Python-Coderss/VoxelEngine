package com.voxel.ai.body;

import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EmotePlayerTest {

    private static final float DT = 1f / 60f;

    @Test
    public void playsAndExpires() {
        EmotePlayer p = new EmotePlayer();
        assertTrue(p.play(Emote.NOD));
        assertTrue(p.isActive());
        for (float t = 0; t <= Emote.NOD.duration + DT; t += DT) p.update(DT);
        assertFalse("emote ends after its duration", p.isActive());
        assertTrue(p.play(Emote.NOD));
    }

    @Test
    public void refusesNewEmoteWhileBusy() {
        EmotePlayer p = new EmotePlayer();
        assertTrue(p.play(Emote.WAVE_FRANTIC));
        assertFalse("cannot interrupt mid-emote", p.play(Emote.NOD));
        for (float t = 0; t < Emote.WAVE_FRANTIC.duration; t += DT) p.update(DT);
        assertTrue(p.play(Emote.NOD));
    }

    @Test
    public void cancelClearsImmediately() {
        EmotePlayer p = new EmotePlayer();
        p.play(Emote.COWER);
        p.cancel();
        assertFalse(p.isActive());
        assertEquals(0f, p.cowerAmount(), 0f);
    }

    @Test
    public void nodOscillatesOnlyWhilePlaying() {
        EmotePlayer p = new EmotePlayer();
        assertEquals(0f, p.nodPhase(), 0f);
        p.play(Emote.NOD);
        boolean sawNonZero = false;
        for (int i = 0; i < 20; i++) {
            p.update(DT);
            if (p.nodPhase() != 0f) sawNonZero = true;
        }
        assertTrue(sawNonZero);
        for (float t = 0; t <= Emote.NOD.duration; t += DT) p.update(DT);
        assertEquals(0f, p.nodPhase(), 0f);
    }

    @Test
    public void waveKeepsArmsRaisedAboveThreshold() {
        EmotePlayer p = new EmotePlayer();
        p.play(Emote.WAVE_FRANTIC);
        for (int i = 0; i < 30; i++) {
            p.update(DT);
            assertTrue(p.armRaise() >= 0.29f && p.armRaise() <= 1.01f);
        }
    }

    @Test
    public void cheerFiresTwoDistinctHops() {
        EmotePlayer p = new EmotePlayer();
        p.play(Emote.JUMP_CHEER);
        int hops = 0;
        long totalSteps = (long) (Emote.JUMP_CHEER.duration / DT) + 1;
        for (long i = 0; i < totalSteps; i++) {
            if (p.consumeHop()) hops++;
            p.update(DT);
        }
        assertEquals(2, hops);
    }

    @Test
    public void pointCarriesReferent() {
        EmotePlayer p = new EmotePlayer();
        Vector3f target = new Vector3f(4, 65, -3);
        p.play(Emote.POINT, target);
        assertTrue(p.isPointing());
        assertTrue(p.hasPointTarget());
        assertEquals(new Vector3f(4, 65, -3), p.pointTarget());
        target.set(99, 99, 99);
        assertEquals("referent is copied, not aliased",
                new Vector3f(4, 65, -3), p.pointTarget());
        for (float t = 0; t <= Emote.POINT.duration + DT; t += DT) p.update(DT);
        assertFalse(p.hasPointTarget());
    }
}
