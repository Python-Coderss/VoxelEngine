package com.voxel.api.events;

import com.voxel.game.GameContext;

/**
 * Fired once per logic tick (20 Hz) while a world is running.
 *
 * <p>Equivalent to the vanilla server tick: safe to edit world state and spawn
 * entities, but never touch OpenGL here — this runs on the logic thread.</p>
 */
public final class TickEvent {

    public final GameContext game;
    /** Seconds since the previous logic tick. */
    public final float dt;
    /** Monotonic world time in seconds. */
    public final float worldTime;

    public TickEvent(GameContext game, float dt, float worldTime) {
        this.game = game;
        this.dt = dt;
        this.worldTime = worldTime;
    }
}
