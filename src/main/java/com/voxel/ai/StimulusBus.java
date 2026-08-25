package com.voxel.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Logic-thread event bus carrying {@link Stimulus} instances from world
 * emitters to subscribed brains. Queue drains once per tick via
 * {@link #dispatch()} (called from EntityManager.update).
 */
public final class StimulusBus {

    public interface Listener {
        void onStimulus(Stimulus stimulus);
    }

    public static final StimulusBus GLOBAL = new StimulusBus();

    private final List<Listener> listeners = new ArrayList<>();
    private final List<Stimulus> queue = new ArrayList<>();

    public void subscribe(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    public int listenerCount() {
        return listeners.size();
    }

    public int queuedCount() {
        return queue.size();
    }

    public void publish(Stimulus stimulus) {
        if (stimulus != null) {
            queue.add(stimulus);
        }
    }

    /** Delivers every queued stimulus to every listener in subscription order. */
    public void dispatch() {
        if (queue.isEmpty()) return;
        List<Stimulus> batch = new ArrayList<>(queue);
        queue.clear();
        for (int i = 0; i < batch.size(); i++) {
            Stimulus stimulus = batch.get(i);
            for (int j = 0; j < listeners.size(); j++) {
                listeners.get(j).onStimulus(stimulus);
            }
        }
    }
}
