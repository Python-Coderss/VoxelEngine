package com.voxel.audio;

import com.voxel.entity.VillagerEntity;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Silent placeholder for the villager voice feature.
 *
 * The Java neural voice pipeline was removed pending a reimplementation with a
 * stronger model (the Python baseline lives in villager_voice/python/ and stays
 * authoritative). This stub keeps the engine integration intact: it still
 * selects the profession/time-aware dialogue line so the HUD chat bubble works,
 * but produces no audio at all.
 */
public final class VillagerAudioManager implements AutoCloseable {
    private static final String DEFAULT_LINE = "Hmm...";

    private final ConcurrentHashMap<Integer, Integer> interactionCounts =
            new ConcurrentHashMap<Integer, Integer>();

    public VillagerAudioManager() {
    }

    public VillagerAudioManager(Path modelDirectory) {
    }

    public VillagerAudioManager(Path modelDirectory, Path cacheDirectory) {
    }

    /** No-op: the voice pipeline is currently a silent stub. */
    public void initialize() {
    }

    /** No-op: speech playback is disabled. */
    public void requestSpeech(String text) {
    }

    /** Convenience method for the default villager greeting. */
    public void requestVillagerGreeting() {
    }

    /** Select and return a profession/time-aware line for the HUD. */
    public String requestVillagerDialogue(VillagerEntity villager, float worldTime) {
        if (villager == null) {
            return DEFAULT_LINE;
        }
        Integer oldCount = interactionCounts.get(villager.id);
        int count = oldCount == null ? 0 : oldCount;
        interactionCounts.put(villager.id, count + 1);
        return VillagerDialogue.choose(villager, worldTime, count);
    }

    /** No-op: there is nothing to pump. */
    public void update() {
    }

    @Override
    public void close() {
    }
}
