package com.voxel.ai.speech;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AncientBuilderLinesTest {

    @Test
    public void everyTopicHasLines() {
        for (AncientBuilderLines.Topic topic : AncientBuilderLines.Topic.values()) {
            String[] pool = AncientBuilderLines.pool(topic);
            assertNotNull(topic + " pool", pool);
            assertTrue(topic + " pool non-empty", pool.length > 0);
            for (String line : pool) {
                assertNotNull(line);
                assertFalse("blank line in " + topic, line.trim().isEmpty());
            }
        }
    }

    @Test
    public void pickIsAlwaysAValidLineAndInRange() {
        Random rng = new Random(42);
        for (int i = 0; i < 50; i++) {
            String line = AncientBuilderLines.pick(AncientBuilderLines.Topic.WORK, rng);
            assertNotNull(line);
            assertTrue(line.length() > 0);
        }
        // Null rng and null topic must not throw.
        assertNotNull(AncientBuilderLines.pick(null, null));
    }
}
