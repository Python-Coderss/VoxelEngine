#version 430 core

/**
 * Chunk Depth Prepass — Vertex Shader
 * Hardware-accelerated rasterization of chunk section AABBs (16³ cubes).
 * One draw call, instanced: gl_InstanceID indexes into the visibility SSBO.
 * 
 * Optimizations:
 * - Compact vertex format (3 floats, unit-cube VBO)
 * - Single instanced draw call for all visible chunks
 * - Frustum-culled instance list from CPU
 * - Back-face culling via GL state
 * - Early-Z via fragment shader early_fragment_tests
 */

layout(location = 0) in vec3 aPos; // unit cube vertex [0,1]

// Chunk visibility list: packed (cx, cy, cz) for each visible chunk section
// cx = bits 0-7, cy = bits 8-15, cz = bits 16-23
layout(std430, binding = 10) buffer readonly restrict ChunkVisibility {
    uint visibleChunks[];
};

uniform mat4 u_VP;
uniform ivec3 u_WorldOffset; // buffer sliding-window origin

flat out uint vChunkOrigin; // packed chunk section coords for fragment shader

void main() {
    uint packedv = visibleChunks[gl_InstanceID];
    uvec3 chunkCoord = uvec3(
        packedv & 0xFFu,
        (packedv >> 8) & 0xFFu,
        (packedv >> 16) & 0xFFu
    );
    vec3 chunkOrigin = vec3(chunkCoord) * 16.0 + vec3(u_WorldOffset);
    vChunkOrigin = packedv; // forward packed coords to fragment
    
    vec3 worldPos = aPos * 16.0 + chunkOrigin;
    gl_Position = u_VP * vec4(worldPos, 1.0);
}
