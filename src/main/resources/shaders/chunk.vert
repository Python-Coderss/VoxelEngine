#version 430 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in uint aBlockData;  // blockId | (faceTexIndex << 16)
layout(location = 4) in uint aLight;      // packed RGB 10-bit light pool value

uniform mat4 u_VP;
uniform ivec3 u_WorldOffset;

out vec3 vWorldPos;
out vec2 vUV;
out vec3 vNormal;
flat out uint vBlockData;
flat out uint vLight;

void main() {
    vec3 worldPos = aPos + vec3(u_WorldOffset);
    vWorldPos = worldPos;
    vUV = aUV;
    vNormal = aNormal;
    vBlockData = aBlockData;
    vLight = aLight;
    gl_Position = u_VP * vec4(worldPos, 1.0);
}
