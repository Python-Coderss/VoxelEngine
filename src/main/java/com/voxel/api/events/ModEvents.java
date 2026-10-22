package com.voxel.api.events;

import java.util.function.Consumer;

/**
 * Event hooks a mod can subscribe to.
 *
 * <p>Listeners run synchronously on the logic thread. A listener that throws is
 * logged with the mod id and skipped; it never takes the game down.</p>
 */
public interface ModEvents {

    void onBlockBreak(Consumer<BlockEvent> listener);

    void onBlockPlace(Consumer<BlockEvent> listener);

    void onTick(Consumer<TickEvent> listener);

    void onWorldLoad(Consumer<WorldLoadEvent> listener);
}
