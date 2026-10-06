package com.voxel.ai.brain;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ComedyMindTest {

    @Test
    public void personalityIsStableAndBounded() {
        ComedyMind a = ComedyMind.forEntity(42);
        ComedyMind b = ComedyMind.forEntity(42);
        assertEquals(a.boldness, b.boldness, 1e-6);
        assertEquals(a.distractibility, b.distractibility, 1e-6);
        assertEquals(a.drama, b.drama, 1e-6);
        assertEquals(a.incompetence, b.incompetence, 1e-6);
        for (float value : new float[]{a.boldness, a.distractibility,
                a.drama, a.incompetence}) {
            assertTrue("personality trait within 0..1", value >= 0f && value <= 1f);
        }
    }

    @Test
    public void distractibleMindsHaveShorterAttentionSpans() {
        ComedyMind focused = ComedyMind.of(0.5f, 0f, 0.5f, 0.5f);
        ComedyMind flighty = ComedyMind.of(0.5f, 1f, 0.5f, 0.5f);
        assertTrue(focused.attentionSpan() > flighty.attentionSpan());
    }

    @Test
    public void attentionExpiresAfterTheSpan() {
        ComedyMind mind = ComedyMind.of(0.5f, 0.9f, 0.5f, 0.5f);
        mind.resetAttention();
        mind.tick(mind.attentionSpan() - 0.5f);
        assertFalse(mind.attentionExpired());
        mind.tick(1f);
        assertTrue(mind.attentionExpired());
    }

    @Test
    public void chaosCompoundsTheComedyRate() {
        ComedyMind mind = ComedyMind.of(0.5f, 0.8f, 0.5f, 0.5f);
        assertTrue("more comedy under chaos",
                mind.comedyRate(1f) > mind.comedyRate(0f));
    }

    @Test
    public void pollIsDeterministicForASeed() {
        ComedyMind a = ComedyMind.of(0.7f, 0.8f, 0.6f, 0.5f);
        ComedyMind b = ComedyMind.of(0.7f, 0.8f, 0.6f, 0.5f);
        Random ra = new Random(1234);
        Random rb = new Random(1234);
        for (int i = 0; i < 40; i++) {
            assertEquals("same seed, same comedy",
                    a.poll(0.25f, 0.5f, ra, true, true),
                    b.poll(0.25f, 0.5f, rb, true, true));
        }
    }

    @Test
    public void contextFiltersImpossibleBeats() {
        ComedyMind mind = ComedyMind.of(0.5f, 1f, 1f, 0.5f);
        Random rng = new Random(7);
        for (int i = 0; i < 200; i++) {
            mind.tick(5f); // burn through cooldowns
            ComedyMind.Event event = mind.poll(0.25f, 1f, rng, false, false);
            assertTrue("no arguments without a friend",
                    event != ComedyMind.Event.ARGUE && event != ComedyMind.Event.WRONG_WAVE);
            assertTrue("no loitering without the player",
                    event != ComedyMind.Event.FOLLOW_PLAYER);
        }
    }

    @Test
    public void cooldownSuppressesImmediateRepeats() {
        ComedyMind mind = ComedyMind.of(0.5f, 1f, 1f, 0.5f);
        Random rng = new Random(1);
        ComedyMind.Event first = ComedyMind.Event.NONE;
        for (int i = 0; i < 50 && first == ComedyMind.Event.NONE; i++) {
            mind.tick(10f);
            first = mind.poll(0.25f, 1f, rng, true, true);
        }
        assertTrue("a beat should eventually fire", first != ComedyMind.Event.NONE);
        assertEquals("cooldown blocks the next beat",
                ComedyMind.Event.NONE, mind.poll(0.25f, 1f, rng, true, true));
    }

    @Test
    public void boldMindsBluffMoreAndStandCloser() {
        int boldBluffs = 0;
        int timidBluffs = 0;
        for (int seed = 0; seed < 100; seed++) {
            if (ComedyMind.of(1f, 0.5f, 0.5f, 0.5f)
                    .shouldBluff(new Random(seed), 0.5f)) boldBluffs++;
            if (ComedyMind.of(0f, 0.5f, 0.5f, 0.5f)
                    .shouldBluff(new Random(seed), 0.5f)) timidBluffs++;
        }
        assertTrue("bold mobs bluff more (" + boldBluffs + " vs " + timidBluffs + ")",
                boldBluffs > timidBluffs);
        assertEquals(2.5f, ComedyMind.of(1f, 0.5f, 0.5f, 0.5f).cowardDistance(), 1e-6);
        assertEquals(7.0f, ComedyMind.of(0f, 0.5f, 0.5f, 0.5f).cowardDistance(), 1e-6);
    }

    @Test
    public void spookedMindsDoNotBluffAgain() {
        ComedyMind mind = ComedyMind.of(1f, 0.5f, 0.5f, 0.5f);
        mind.spook();
        assertFalse(mind.shouldBluff(new Random(1), 1f));
        mind.resetAttention();
        assertTrue("a fresh episode may earn fresh bravado",
                mind.shouldBluff(new Random(1), 1f));
    }

    @Test
    public void dramaticMobsHerdFollowMore() {
        int herd = 0;
        int loner = 0;
        for (int seed = 0; seed < 100; seed++) {
            if (ComedyMind.of(0.5f, 0.5f, 1f, 0.5f)
                    .herdFollow(new Random(seed), 1f)) herd++;
            if (ComedyMind.of(0.5f, 0.5f, 0f, 0.5f)
                    .herdFollow(new Random(seed), 0f)) loner++;
        }
        assertTrue("dramatic mobs follow the crowd more (" + herd + " vs " + loner + ")",
                herd > loner);
    }
}
