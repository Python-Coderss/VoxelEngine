package com.voxel.ui;

import org.joml.Vector4f;

/**
 * The interface palette: Story Mode's indigo plates and cool white lettering.
 *
 * <p>The hues are sampled from Minecraft: Story Mode's own menu art — the
 * PSP-port spritesheet draws every row as an indigo plate in a pale-yellow
 * frame (see {@code tools/cut_mcsm_ui.py}), and the panels, prompts and
 * dialogue cards sit on the same navy family. Gold stays as the single warm
 * accent because that is the colour of those frames; nothing here is sepia,
 * which the earlier "warm storybook" pass made the whole HUD read as brown.
 * Panels stay translucent so the world still shows through, and every surface
 * shares one outline ink so the HUD reads as a single sheet rather than a
 * stack of tinted rectangles.</p>
 *
 * <p><b>Colour space:</b> the constants below are <em>display</em> sRGB values,
 * the colours you would pick from a screenshot. The HUD framebuffer is
 * composited through the raytracer, which encodes its input to sRGB again, so a
 * raw 0.19 panel reached the screen as a washed-out 0.47. Every factory here
 * therefore returns the linear value ({@code display^2.2}) that the composite
 * turns back into the intended colour. Use {@link #srgb} for one-off tints so
 * they stay in the same space; never feed a bare literal to a colour setter.</p>
 */
public final class UiPalette {

    private UiPalette() {}

    /** Encoding exponent of the composite pass (plain 2.2 sRGB approximation). */
    private static final float GAMMA = 2.2f;

    /** Outline and drop-shadow ink: near-black navy (#0A1020). */
    public static final Vector4f INK = new Vector4f(0.039f, 0.063f, 0.125f, 1f);
    /** The darkest panel, used behind dialogue and full-screen menus (#131C30). */
    public static final Vector4f PANEL_DEEP = new Vector4f(0.075f, 0.110f, 0.188f, 1f);
    /** Standard card / prompt plate (#1E2C48). */
    public static final Vector4f PANEL = new Vector4f(0.118f, 0.173f, 0.282f, 1f);
    /** Raised surface: hovered button, selected row (#34488F, the plate fill). */
    public static final Vector4f PANEL_LIFT = new Vector4f(0.204f, 0.282f, 0.561f, 1f);
    /** Body copy and labels (#EDF2FF). */
    public static final Vector4f CREAM = new Vector4f(0.929f, 0.949f, 1f, 1f);
    /** Secondary copy: hints, counters, idle prompt names (#A9B8D6). */
    public static final Vector4f MUTED = new Vector4f(0.663f, 0.722f, 0.839f, 1f);
    /** The accent: selection bars, timers, key hints (#6F6FDC, the plate's indigo). */
    public static final Vector4f AMBER = new Vector4f(0.435f, 0.435f, 0.863f, 1f);
    /**
     * The pale-yellow frame the menu plates are outlined with (#FDFD9A): used
     * for the focused row's rim, splashes and the active tab, never for body
     * copy (it disappears against skylight).
     */
    public static final Vector4f GOLD = new Vector4f(0.992f, 0.992f, 0.604f, 1f);
    /** Danger red, kept warm so it still reads against the navy chrome (#BF5230). */
    public static final Vector4f EMBER = new Vector4f(0.749f, 0.322f, 0.188f, 1f);

    public static Vector4f ink(float a) { return of(INK, a); }
    public static Vector4f panelDeep(float a) { return of(PANEL_DEEP, a); }
    public static Vector4f panel(float a) { return of(PANEL, a); }
    public static Vector4f panelLift(float a) { return of(PANEL_LIFT, a); }
    public static Vector4f cream(float a) { return of(CREAM, a); }
    public static Vector4f muted(float a) { return of(MUTED, a); }
    public static Vector4f amber(float a) { return of(AMBER, a); }
    public static Vector4f gold(float a) { return of(GOLD, a); }
    public static Vector4f ember(float a) { return of(EMBER, a); }

    /** Converts a display sRGB colour (0..1 per channel) to the linear value to submit. */
    public static Vector4f srgb(float r, float g, float b, float a) {
        return new Vector4f(linear(r), linear(g), linear(b), a);
    }

    /** Converts a display sRGB constant to the linear value to submit. */
    public static Vector4f of(Vector4f display, float a) {
        return srgb(display.x, display.y, display.z, a);
    }

    /**
     * The display value unchanged, for passes that present the UI canvas
     * directly instead of through the raytracer's sRGB encode (the boot
     * screen's fullscreen quad). Everywhere else use {@link #of}.
     */
    public static Vector4f unencoded(Vector4f display, float a) {
        return new Vector4f(display.x, display.y, display.z, a);
    }

    /** Linear-space interpolation between two display colours (for pulses). */
    public static Vector4f blend(Vector4f from, Vector4f to, float t, float a) {
        float k = Math.max(0f, Math.min(1f, t));
        return srgb(from.x + (to.x - from.x) * k,
                from.y + (to.y - from.y) * k,
                from.z + (to.z - from.z) * k, a);
    }

    private static float linear(float display) {
        return (float) Math.pow(Math.max(0f, Math.min(1f, display)), GAMMA);
    }
}
