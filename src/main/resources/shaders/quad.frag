#version 430 core

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D inputTexture;

// Full-resolution UI composite: when the raytracer renders below window size
// (render scale), the HUD/menu canvas is blended here at native resolution so
// text and pixel-art stay crisp instead of being upscaled with the scene.
// The canvas stores linear-light color, encoded to sRGB with the same curve
// the raytracer uses at its output.
uniform sampler2D uiTexture;
uniform int u_CompositeUI;

vec3 linearToSrgb(vec3 linear) {
    linear = max(linear, vec3(0.0));
    vec3 low = linear * 12.92;
    vec3 high = 1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, step(linear, vec3(0.0031308)));
}

float sceneLuma(vec2 uv) {
    return dot(texture(inputTexture, uv).rgb, vec3(0.299, 0.587, 0.114));
}

/**
 * Cheap two-ring bloom for the Story Mode glow.
 *
 * Telltale's frames bloom around anything bright — the sun through the
 * skylight, glowstone, lava, a lantern — and without it a crisp voxel scene
 * looks like a screenshot of a game rather than a frame from a film. A real
 * bloom needs a blur pyramid; eight taps on two radii at full window
 * resolution are enough to halo the highlights, and sampling only the excess
 * above the threshold keeps lit grass and snow from bleaching out.
 */
vec3 bloomGlow(vec2 uv) {
    vec2 texel = 1.0 / vec2(textureSize(inputTexture, 0));
    const int TAPS = 8;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < TAPS; i++) {
        float ang = float(i) * 0.7853982; // 2*pi/8
        vec2 dir = vec2(cos(ang), sin(ang));
        vec3 near = max(texture(inputTexture, uv + dir * texel * 3.0).rgb - vec3(0.72), 0.0);
        vec3 far  = max(texture(inputTexture, uv + dir * texel * 9.0).rgb - vec3(0.72), 0.0);
        acc += near * 0.55 + far * 0.30;
    }
    return acc * (1.0 / float(TAPS));
}

void main() {
    vec4 c = texture(inputTexture, TexCoords);
    if (u_CompositeUI == 1) {
        // Story Mode ink: Telltale draws a dark line around geometry, which the
        // smooth block shading otherwise lacks. A Sobel-ish luma gradient on
        // the finished scene lands on block silhouettes and face creases; the
        // high threshold keeps grass and plank texels from inking themselves.
        // The HUD is composited afterwards, so text and prompts stay untouched.
        vec2 texel = 1.0 / vec2(textureSize(inputTexture, 0));
        float lx = sceneLuma(TexCoords + vec2(texel.x, 0.0));
        float lX = sceneLuma(TexCoords - vec2(texel.x, 0.0));
        float ly = sceneLuma(TexCoords + vec2(0.0, texel.y));
        float lY = sceneLuma(TexCoords - vec2(0.0, texel.y));
        float edge = length(vec2(lx - lX, ly - lY));
        float ink = smoothstep(0.22, 0.55, edge);
        c.rgb *= 1.0 - 0.5 * ink;

        // Warm highlight bloom, added before the HUD composite so text and
        // prompts never glow (MCSM keeps its UI flat over the shot).
        c.rgb += bloomGlow(TexCoords) * vec3(1.0, 0.94, 0.82) * 1.15;

        vec4 ui = texture(uiTexture, TexCoords);
        c.rgb = min(mix(c.rgb, linearToSrgb(ui.rgb), ui.a), vec3(1.0));
    }
    FragColor = vec4(c.rgb, 1.0);
}
