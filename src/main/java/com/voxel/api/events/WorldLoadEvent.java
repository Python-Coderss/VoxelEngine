package com.voxel.api.events;

import com.voxel.game.GameContext;

/**
 * Fired once a world (or dimension) has finished its deferred initialisation and
 * is safe to query and edit.
 */
public final class WorldLoadEvent {

    public final GameContext game;
    /** Save slot the world belongs to, or {@code "world"} for ad-hoc worlds. */
    public final String saveName;
    /**
     * True when the world was freshly created (as opposed to loaded from disk).
     * Mods can use this to place one-off structures at spawn.
     */
    public final boolean freshWorld;

    public WorldLoadEvent(GameContext game, String saveName, boolean freshWorld) {
        this.game = game;
        this.saveName = saveName;
        this.freshWorld = freshWorld;
    }
}
