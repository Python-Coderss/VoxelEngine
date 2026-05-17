#version 430 core

in vec2 vUV;
out vec4 outColor;

layout(location = 10) uniform vec4 uColor;
layout(location = 11) uniform float uHasTexture;
layout(binding = 0) uniform sampler2D uSampler;

void main() {
    vec4 col = uColor;
    if (uHasTexture > 0.5) col *= texture(uSampler, vUV);
    if (col.a < 0.001) discard;
    outColor = col;
}
