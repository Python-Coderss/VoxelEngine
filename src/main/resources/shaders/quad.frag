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

void main() {
    vec4 c = texture(inputTexture, TexCoords);
    if (u_CompositeUI == 1) {
        vec4 ui = texture(uiTexture, TexCoords);
        c.rgb = mix(c.rgb, linearToSrgb(ui.rgb), ui.a);
    }
    FragColor = vec4(c.rgb, 1.0);
}
