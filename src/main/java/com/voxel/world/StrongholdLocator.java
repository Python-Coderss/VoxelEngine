package com.voxel.world;

/**
 * Single source of truth for "where do thrown Eyes of Ender fly to".
 *
 * <p>The MCP-stronghold ring inside {@code MapGenStronghold} reports its
 * resolved center via {@link #setCenter(int, int)} the first time the chunk
 * around the saved player column passes through worldgen. Until then,</p>
 *
 * <ul>
 *   <li>If a save was loaded and the level.dat has a remembered position,
 *       that is used as the fallback so a freshly-loaded player immediately
 *       gets a usable trail.</li>
 *   <li>Otherwise the locator falls back to {@code (0, 0)} and {@link #markNotSpawned()}
 *       arms the locator: the next chunk decorated by {@link
 *       com.voxel.world.structure.MapGenStronghold} takes over.</li>
 * </ul>
 *
 * <p>The portal frame loop inside EndPortalLogic reads {@link #getCenterX()} /
 * {@link #getCenterZ()} to compute its eye-particle target, and the obsolete
 * "flag" is intentionally absent: a portal always has one (and only one)
 * candidate target per save.</p>
 */
public final class StrongholdLocator {
    private static boolean spawned;
    private static int centerX;
    private static int centerZ;
    private static boolean debugOverride;
    // Chunk coordinates where the singleton stronghold lives; populated by
    // the overworld's DimensionWorldGenerator hook (decorated chunks ARE the
    // stronghold chunk, so this is essentially the resolved center chunk).
    private static int strongholdChunkX;
    private static int strongholdChunkZ;
    private static int strongholdBaseY = 32; // Surface-aligned portal room base.

    private StrongholdLocator() {}

    /** True once worldgen has resolved the stronghold position. */
    public static boolean isSpawned() { return spawned; }

    public static int getCenterX() { return centerX; }
    public static int getCenterZ() { return centerZ; }

    /**
     * Seeds the locator with a fallback position before worldgen runs.
     * Useful for save loads so the player immediately has a direction to throw
     * eyes at.
     */
    public static void seedFallback(int x, int z) {
        if (debugOverride) return;
        centerX = x;
        centerZ = z;
    }

    /**
     * Called by MapGenStronghold when it has placed the stronghold. WINS over
     * any prior fallback — the locator now reflects the real portal position.
     */
    public static void setCenter(int x, int z) {
        if (debugOverride) return; // a debug-set center wins over worldgen
        centerX = x;
        centerZ = z;
        spawned = true;
    }

    /**
     * Sets the chunk-coordinate location where the (single) stronghold should
     * be baked. Called once when the world boots so DimensionWorldGenerator
     * knows which chunk column to hand off to MapGenStronghold.
     */
    public static void setStrongholdChunk(int cx, int cz, int baseY) {
        strongholdChunkX = cx;
        strongholdChunkZ = cz;
        strongholdBaseY = baseY;
    }

    public static int getStrongholdChunkX() { return strongholdChunkX; }
    public static int getStrongholdChunkZ() { return strongholdChunkZ; }
    public static int getStrongholdBaseY() { return strongholdBaseY; }

    public static boolean hasStrongholdChunk() {
        return strongholdChunkX != 0 || strongholdChunkZ != 0
                || StrongholdLocator.debugOverride;
    }

    /** Clears any prior state, used when leaving a dimension or starting a new world. */
    public static void reset() {
        spawned = false;
        centerX = 0;
        centerZ = 0;
        debugOverride = false;
        strongholdChunkX = 0;
        strongholdChunkZ = 0;
    }

    /**
     * Manual override (debug / /give-eye shortcut). When set, only
     * {@link #clearOverride()} resets the locator; worldgen-driven
     * {@link #setCenter(int, int)} is ignored.
     */
    public static void debugSetCenter(int x, int z) {
        centerX = x;
        centerZ = z;
        spawned = true;
        debugOverride = true;
    }

    public static void clearOverride() {
        debugOverride = false;
    }
}
