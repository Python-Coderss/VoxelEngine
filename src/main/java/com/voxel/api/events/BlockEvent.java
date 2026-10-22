package com.voxel.api.events;

import com.voxel.World;
import com.voxel.game.GameContext;

/**
 * Fired after a block has been broken or placed.
 *
 * <p>The world already contains the change when a listener runs: a break event
 * sees air at the position, a place event sees the new block id.</p>
 */
public final class BlockEvent {

    public enum Action { BREAK, PLACE }

    public final Action action;
    public final GameContext game;
    public final World world;
    public final int x, y, z;
    /** Block id that was removed ({@code BREAK}) or inserted ({@code PLACE}). */
    public final int blockId;

    public BlockEvent(Action action, GameContext game, World world,
                      int x, int y, int z, int blockId) {
        this.action = action;
        this.game = game;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockId = blockId;
    }

    /** Registered block name, or {@code "unknown"} when the id has no entry. */
    public String blockName() {
        if (game == null || game.blockDataManager == null) return "unknown";
        String name = game.blockDataManager.getName(blockId);
        return name == null ? "unknown" : name;
    }

    @Override
    public String toString() {
        return action + " " + blockName() + "@(" + x + "," + y + "," + z + ")";
    }
}
