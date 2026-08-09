package com.voxel.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChunkManagerBootstrapTest {
    @Test
    public void bootstrapSkipsRecenterOnlyWhenImmediateCubeFits() {
        assertTrue(ChunkManager.canSkipInitialRecenter(true, 0, 0, 0, 64, 6, 64));
        assertFalse(ChunkManager.canSkipInitialRecenter(true, 0, 0, 0, 0, 6, 64));
        assertFalse(ChunkManager.canSkipInitialRecenter(true, 0, 0, 0, 64, 0, 64));
        assertFalse(ChunkManager.canSkipInitialRecenter(false, 0, 0, 0, 64, 6, 64));
        assertFalse(ChunkManager.canSkipInitialRecenter(true, -1024, -928, -1024, 64, 6, 64));
    }

    @Test
    public void betaDecorationIsDeferredOnlyDuringBootstrap() {
        assertTrue(ChunkManager.shouldDeferDecoration(true, true));
        assertFalse(ChunkManager.shouldDeferDecoration(false, true));
        assertFalse(ChunkManager.shouldDeferDecoration(true, false));
    }

    @Test
    public void playerCenteredGridAndHigherSectionsArePrioritized() {
        assertTrue(ChunkManager.isPlayer3x3(0, 0));
        assertTrue(ChunkManager.isPlayer3x3(-1, 1));
        assertFalse(ChunkManager.isPlayer3x3(2, 0));
        assertTrue(ChunkManager.compareHigherSectionFirst(8, 7) < 0);
        assertTrue(ChunkManager.compareHigherSectionFirst(7, 8) > 0);
        assertEquals(java.util.Arrays.asList(5, 4, 3), ChunkManager.orderedSections(3, 5));
    }

    @Test
    public void lowerSectionsEvictOnlyOutsideActiveWindow() {
        assertTrue(ChunkManager.shouldEvictLowerSection(2, 5, 3, false));
        assertFalse(ChunkManager.shouldEvictLowerSection(3, 5, 3, false));
        assertFalse(ChunkManager.shouldEvictLowerSection(2, 5, 3, true));
        assertFalse(ChunkManager.shouldEvictLowerSection(6, 5, 3, false));
    }

    @Test
    public void pendingDecorationPriorityPrefersNearbyColumns() {
        long nearby = ((long) 1 << 32) | 1L;
        long distant = ((long) 8 << 32) | 8L;
        assertTrue(ChunkManager.compareGenerationPriority(nearby, distant, 0, 0) < 0);
        assertTrue(ChunkManager.compareGenerationPriority(distant, nearby, 0, 0) > 0);
    }
}
