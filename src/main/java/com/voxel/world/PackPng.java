package com.voxel.world;

/**
 * The cracked seed of Minecraft's {@code pack.png} — the default resource-pack
 * icon, and the most recognisable landscape in the game.
 *
 * <p>Minecraft@Home recovered the world in September 2020: seed
 * {@code 3257840388504953787}, generated in Alpha 1.2.2a (the same terrain runs
 * through Beta 1.7.3). The camera stood at roughly {@code x=49, z=0} looking at
 * the hill at {@code x=116, z=-31}.</p>
 *
 * <p>The engine's Overworld generator is a faithful Beta 1.8.1 port; 1.8 changed
 * the terrain noise, so this seed produces the same region of the world, but
 * not necessarily a pixel-identical copy of the icon. The seed and view point
 * are still the canonical pack.png pair and are used as the default new-world
 * seed so every fresh world is the "pack.png world".</p>
 */
public final class PackPng {

    /** Minecraft@Home's recovered pack.png seed. */
    public static final long SEED = 3257840388504953787L;

    /** Camera column from the original screenshot (x=49, z=0). */
    public static final int VIEW_X = 49;
    public static final int VIEW_Z = 0;

    /** The hill visible in the icon. */
    public static final int HILL_X = 116;
    public static final int HILL_Z = -31;

    /** Player spawn: the screenshot column, so the first view matches the icon. */
    public static final int SPAWN_X = VIEW_X;
    public static final int SPAWN_Z = VIEW_Z;

    /** Yaw (degrees) that looks from the camera column toward the hill. */
    public static final float SPAWN_YAW = -24.8f;

    private PackPng() {}
}
