package com.voxel.ai;

import org.joml.Vector3f;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StimulusBusTest {

    private static Stimulus stimulus(Stimulus.Type type, float severity) {
        return new Stimulus(type, 7, new Vector3f(1, 2, 3), severity, "payload", 42L);
    }

    @Test
    public void deliversQueuedStimuliInOrder() {
        StimulusBus bus = new StimulusBus();
        final List<Stimulus.Type> received = new ArrayList<>();
        StimulusBus.Listener listener = s -> received.add(s.type);

        bus.subscribe(listener);
        bus.publish(stimulus(Stimulus.Type.THREAT_SEEN, 1));
        bus.publish(stimulus(Stimulus.Type.DAMAGE_TAKEN, 2));
        assertEquals(2, bus.queuedCount());

        bus.dispatch();

        assertEquals(2, received.size());
        assertEquals(Stimulus.Type.THREAT_SEEN, received.get(0));
        assertEquals(Stimulus.Type.DAMAGE_TAKEN, received.get(1));
        assertEquals(0, bus.queuedCount());
    }

    @Test
    public void broadcastReachesAllListeners() {
        StimulusBus bus = new StimulusBus();
        final int[] hits = {0, 0};
        bus.subscribe(s -> hits[0]++);
        bus.subscribe(s -> hits[1]++);

        bus.publish(stimulus(Stimulus.Type.POINT_GESTURE, 1));
        bus.dispatch();

        assertEquals(1, hits[0]);
        assertEquals(1, hits[1]);
    }

    @Test
    public void unsubscribedListenerStopsReceiving() {
        StimulusBus bus = new StimulusBus();
        final int[] hits = {0};
        StimulusBus.Listener listener = s -> hits[0]++;
        bus.subscribe(listener);

        bus.publish(stimulus(Stimulus.Type.SPEECH_HEARD, 1));
        bus.dispatch();
        assertEquals(1, hits[0]);

        bus.unsubscribe(listener);
        bus.publish(stimulus(Stimulus.Type.SPEECH_HEARD, 1));
        bus.dispatch();
        assertEquals("no delivery after unsubscribe", 1, hits[0]);
    }

    @Test
    public void duplicateSubscriptionIgnoredAndNullPublishSafe() {
        StimulusBus bus = new StimulusBus();
        final int[] hits = {0};
        StimulusBus.Listener listener = s -> hits[0]++;

        bus.subscribe(listener);
        bus.subscribe(listener);
        assertEquals(1, bus.listenerCount());

        bus.publish(null);
        bus.dispatch();
        assertTrue(hits[0] == 0);
    }

    @Test
    public void emptyDispatchIsNoOp() {
        StimulusBus bus = new StimulusBus();
        final int[] hits = {0};
        bus.subscribe(s -> hits[0]++);
        bus.dispatch();
        assertEquals(0, hits[0]);
    }

    @Test
    public void stimulusDefensivelyCopiesPosition() {
        Vector3f pos = new Vector3f(1, 1, 1);
        Stimulus s = new Stimulus(Stimulus.Type.NOVEL_EVENT, -1, pos, 5f, null, 0L);
        pos.set(9, 9, 9);
        assertEquals(new Vector3f(1, 1, 1), s.position);
    }
}
