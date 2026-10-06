package com.voxel.entity;

import com.voxel.World;
import com.voxel.audio.LiveCaptions;
import com.voxel.ai.speech.CaptionSpeech;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless builder-entity contract: it constructs without a GL context, keeps
 * its identity, moves over real ground, and speaks only via captions.
 */
public class AncientBuilderEntityTest {

    private static final int STONE = 1;
    private static final int GROUND_Y = 63;

    private static final class FlatWorld extends World {
        FlatWorld() { super(8); }

        @Override
        public int getVoxel(int x, int y, int z) {
            return y <= GROUND_Y ? STONE : 0;
        }
    }

    @Before
    public void reset() {
        LiveCaptions.clear();
        CaptionSpeech.resetForTest();
    }

    @Test
    public void constructsWithoutTextureManagerAndKeepsIdentity() {
        AncientBuilderEntity builder =
                new AncientBuilderEntity(1, new Vector3f(0, 64, 0), null);
        builder.setRole(AncientBuilderEntity.Role.SCHOLAR);
        builder.setHomeSite(new Vector3i(240, 68, 240), "Grand Archive");
        assertEquals("Scholar of the Grand Archive", builder.getDisplayName());
        assertEquals(AncientBuilderEntity.Role.SCHOLAR, builder.getRole());
        assertTrue(builder.aiHasHomeSite());
        assertNotNull(builder.aiHomeSite());
    }

    @Test
    public void speakingGoesToCaptionsNotAudio() {
        AncientBuilderEntity builder =
                new AncientBuilderEntity(2, new Vector3f(0, 64, 0), null);
        builder.setRole(AncientBuilderEntity.Role.ARCHITECT);
        builder.setHomeSite(new Vector3i(0, 64, 0), "Observatory");
        String line = "The keystone goes in last.";
        assertTrue(builder.aiSpeak(line));
        LiveCaptions.Snapshot snap = LiveCaptions.current();
        assertNotNull(snap);
        assertEquals("Architect of the Observatory", snap.speaker);
        assertTrue(snap.revealedText.startsWith("The"));
        LiveCaptions.tick(LiveCaptions.estimateDuration(line, 1.0) + 0.05f);
        assertEquals(line, LiveCaptions.current().revealedText);
    }

    @Test
    public void walksTowardATargetOnRealGround() {
        AncientBuilderEntity builder =
                new AncientBuilderEntity(3, new Vector3f(0.5f, 64f, 0.5f), null);
        builder.setWorld(new FlatWorld());
        float before = builder.getPosX();
        for (int i = 0; i < 20; i++) {
            builder.aiMoveToward(new Vector3f(6f, 64f, 0.5f), 1.5f, 0.1f);
        }
        assertTrue("the builder should have advanced east (" + before
                        + " -> " + builder.getPosX() + ")",
                builder.getPosX() > before + 0.5f);
    }

    @Test
    public void updateWithNoWorldIsSafe() {
        AncientBuilderEntity builder =
                new AncientBuilderEntity(4, new Vector3f(0, 64, 0), null);
        builder.update(0.1f);
        assertFalse(Double.isNaN(builder.getPosX()));
    }
}
