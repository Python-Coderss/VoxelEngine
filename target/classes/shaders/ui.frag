#version 430 core

in vec2 vUV;
out vec4 outColor;

layout(location = 10) uniform vec4 uColor;
layout(location = 11) uniform float uHasTexture;
uniform int uTextureType; // 0: single, 1: font, 2: array
uniform int uLayer;

layout(binding = 0) uniform sampler2D uSampler;
layout(binding = 1) uniform sampler2DArray uArraySampler;

void main() {
    vec4 col = uColor;
    if (uHasTexture > 0.5) {
        if (uTextureType == 2) {
            col *= texture(uArraySampler, vec3(vUV, float(uLayer)));
        } else {
            col *= texture(uSampler, vUV);
        }
    }
    if (col.a < 0.001) discard;
    outColor = col;
}
