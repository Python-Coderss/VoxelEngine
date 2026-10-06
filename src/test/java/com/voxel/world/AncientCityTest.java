package com.voxel.world;

import com.voxel.game.MelodyPortal;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ancient City must lay out a dormant rift and a full row of tuning stones
 * whose positions map to the seven pitches the melody needs.
 */
public class AncientCityTest {

    private static final int RIFT_FRAME = 933, SIFT_PORTAL = 934,
            SOUL_SLATE = 936, NOTE_BLOCK = 937;

    @Test
    public void buildsAPlazaGateAndTuningStones() {
        AncientCity.RecordingSink sink = new AncientCity.RecordingSink();
        AncientCity.build(AncientCity.X, AncientCity.Z, 68, sink);
        assertFalse("city places blocks", sink.voxels.isEmpty());
        assertTrue("has a rift frame", sink.voxels.containsValue(RIFT_FRAME));
        assertTrue("has a soul-slate floor", sink.voxels.containsValue(SOUL_SLATE));
        int notes = 0;
        for (int id : sink.voxels.values()) if (id == NOTE_BLOCK) notes++;
        assertEquals("seven tuning stones", 7, notes);
        assertFalse("the rift is dormant until the tune is played",
                sink.voxels.containsValue(SIFT_PORTAL));
    }

    @Test
    public void tuningStonesMapToEveryPitch() {
        MelodyPortal.resetForTest();
        AncientCity.build(AncientCity.X, AncientCity.Z, 68, new AncientCity.RecordingSink());
        MelodyPortal.setOrigin(AncientCity.X, 68, AncientCity.Z);
        boolean[] seen = new boolean[MelodyPortal.NOTES];
        for (int dx = -3; dx <= 3; dx++) {
            int pitch = MelodyPortal.pitchAt(AncientCity.X + dx, 69, AncientCity.Z - 3);
            assertTrue("pitch in range", pitch >= 0 && pitch < MelodyPortal.NOTES);
            seen[pitch] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue("pitch " + i + " is reachable", seen[i]);
        }
        MelodyPortal.resetForTest();
    }

    @Test
    public void rebuildingIsIdempotent() {
        AncientCity.RecordingSink first = new AncientCity.RecordingSink();
        AncientCity.RecordingSink second = new AncientCity.RecordingSink();
        AncientCity.build(AncientCity.X, AncientCity.Z, 70, first);
        AncientCity.build(AncientCity.X, AncientCity.Z, 70, second);
        assertEquals(first.voxels, second.voxels);
    }

    @Test
    public void openWithoutAChunkManagerIsASafeNoOp() {
        AncientCity.resetForTest();
        assertFalse(AncientCity.open(null));
    }
}
