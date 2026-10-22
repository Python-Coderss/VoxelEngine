package com.voxel.api;

/**
 * Entry point of a Voxel Engine mod.
 *
 * <p>A mod is a jar dropped into {@code mods/} that declares one or more
 * implementations of this interface in
 * {@code META-INF/services/com.voxel.api.VoxelMod} (the standard
 * {@link java.util.ServiceLoader} descriptor). The loader builds a
 * {@link ModContext} for each mod and calls {@link #onLoad(ModContext)} once,
 * after every built-in block and before item definitions are finalised.</p>
 *
 * <p>Blocks registered from {@code onLoad} are immediately usable in the world
 * and in the creative inventory. Their assets follow the vanilla layout inside
 * the jar:</p>
 *
 * <pre>
 * assets/&lt;modid&gt;/textures/blocks/&lt;texture&gt;.png
 * assets/&lt;modid&gt;/models/block/&lt;block&gt;.json
 * </pre>
 *
 * <p>{@code onLoad} must not touch OpenGL or the render thread: it runs during
 * resource setup. Exceptions thrown by a mod are logged and that mod is
 * disabled, so one broken mod cannot stop the game from booting.</p>
 */
public interface VoxelMod {

    /** Stable, lowercase identifier ({@code [a-z0-9_-]}), e.g. {@code "lanterns"}. */
    String id();

    /** Human-readable name shown in the startup log. */
    String name();

    /** Mod version; defaults to {@code "1.0"}. */
    default String version() {
        return "1.0";
    }

    /** Called once at startup. Register blocks, items and listeners here. */
    void onLoad(ModContext context);
}
