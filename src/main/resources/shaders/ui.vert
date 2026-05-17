#version 430 core

layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUV;

out vec2 vUV;

layout(location = 0) uniform mat4 uProjection;
layout(location = 1) uniform vec2 uPos;
layout(location = 2) uniform vec2 uSize;
layout(location = 3) uniform float uRotation;
layout(location = 4) uniform float uZ;
uniform vec2 uUVOffset;
uniform vec2 uUVScale;

void main() {
    float s = sin(uRotation);
    float c = cos(uRotation);
    mat2 rot = mat2(c, -s, s, c);
    
    vec2 p = inPos * uSize;
    p = rot * p;
    p += uPos;
    
    gl_Position = uProjection * vec4(p, uZ, 1.0);
    vUV = uUVOffset + inUV * uUVScale;
}
