package com.voxel.modloader;

import com.voxel.api.events.BlockEvent;
import com.voxel.api.events.ModEvents;
import com.voxel.api.events.TickEvent;
import com.voxel.api.events.WorldLoadEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Process-wide event bus for mod listeners.
 *
 * <p>One instance is handed to each mod (carrying its id for log attribution),
 * but the listener lists are static: firing goes through the static methods and
 * works even when no mod is loaded. A failing listener is logged once with the
 * mod id and then skipped for that dispatch.</p>
 */
public final class EventBus implements ModEvents {

    private static final List<Listener<BlockEvent>> BREAK = new CopyOnWriteArrayList<>();
    private static final List<Listener<BlockEvent>> PLACE = new CopyOnWriteArrayList<>();
    private static final List<Listener<TickEvent>> TICK = new CopyOnWriteArrayList<>();
    private static final List<Listener<WorldLoadEvent>> LOAD = new CopyOnWriteArrayList<>();

    private final String owner;

    public EventBus() {
        this("mod");
    }

    public EventBus(String owner) {
        this.owner = owner == null ? "mod" : owner;
    }

    @Override
    public void onBlockBreak(Consumer<BlockEvent> listener) {
        if (listener != null) BREAK.add(new Listener<>(owner, listener));
    }

    @Override
    public void onBlockPlace(Consumer<BlockEvent> listener) {
        if (listener != null) PLACE.add(new Listener<>(owner, listener));
    }

    @Override
    public void onTick(Consumer<TickEvent> listener) {
        if (listener != null) TICK.add(new Listener<>(owner, listener));
    }

    @Override
    public void onWorldLoad(Consumer<WorldLoadEvent> listener) {
        if (listener != null) LOAD.add(new Listener<>(owner, listener));
    }

    // ── Dispatch (called by the engine) ─────────────────────────────────────

    public static void fireBlockBreak(BlockEvent event) {
        dispatch("block break", BREAK, event);
    }

    public static void fireBlockPlace(BlockEvent event) {
        dispatch("block place", PLACE, event);
    }

    public static void fireTick(TickEvent event) {
        dispatch("tick", TICK, event);
    }

    public static void fireWorldLoad(WorldLoadEvent event) {
        dispatch("world load", LOAD, event);
    }

    /** True when nobody is listening; lets hot paths skip allocation entirely. */
    public static boolean hasTickListeners() {
        return !TICK.isEmpty();
    }

    public static int listenerCount() {
        return BREAK.size() + PLACE.size() + TICK.size() + LOAD.size();
    }

    static void clearAll() {
        BREAK.clear();
        PLACE.clear();
        TICK.clear();
        LOAD.clear();
    }

    private static <T> void dispatch(String what, List<Listener<T>> listeners, T event) {
        for (Listener<T> listener : listeners) {
            try {
                listener.consumer.accept(event);
            } catch (Throwable t) {
                System.err.println("[Mods] " + listener.owner + " failed in " + what
                        + " listener: " + t);
            }
        }
    }

    private static final class Listener<T> {
        final String owner;
        final Consumer<T> consumer;

        Listener(String owner, Consumer<T> consumer) {
            this.owner = owner;
            this.consumer = consumer;
        }
    }
}
