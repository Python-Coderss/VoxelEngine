#version 430 core

// GBuffer Vertex Shader — transforms chunk mesh vertices and passes data to fragment shader
// Uses instanced rendering: each instance = one chunk section, world offset baked into instance data

layout(location = 0) in vec3 aPos;       // vertex position within chunk section (0..16)
layout(location = 1) in vec3 aNormal;    // face normal
layout(location = 2) in vec2 aTexCoord;  // texture UV
layout(location = 3) in uint aBlockId;   // block type ID
layout(location = 4) in uint aLightRGB;  // Minecraft packed lightmap: sky<<20 | block<<4

// Instance data: one per chunk section
layout(location = 5) in ivec3 iChunkCoord; // buffer-relative chunk coords (cx|cy|cz)

uniform mat4 u_VP;           // view-projection matrix
uniform ivec3 u_WorldOffset; // sliding-window origin in world coords
uniform vec3 u_CameraPos;    // camera position for distance calculations

// Outputs to fragment shader
out vec3 vWorldPos;
flat out vec3 vNormal;
out vec2 vTexCoord;
flat out uint vBlockId;
flat out vec2 vLightmap;     // (skyLight/15.0, blockLight/15.0)
out float vFogFactor;        // distance-based fog

const float FOG_START = 128.0;
const float FOG_END = 512.0;

void main() {
    // Compute world-space position
    vec3 chunkWorldOrigin = vec3(iChunkCoord) * 16.0 + vec3(u_WorldOffset);
    vec3 worldPos = aPos + chunkWorldOrigin;
    vWorldPos = worldPos;
    
    // Transform to clip space
    gl_Position = u_VP * vec4(worldPos, 1.0);
    
    vNormal = aNormal;
    vTexCoord = aTexCoord;
    vBlockId = aBlockId;
    
    // Decode Minecraft packed lightmap:
    //   bits 0-3  = sky light  (0-15), scaled by 240 to 0-240
    //   bits 4-7  = block light (0-15), scaled by 240
    //   bits 8-15 = unused (0)
    //   bits 16-19 = sky light in upper nibble (Minecraft format: sky<<20 | block<<4)
    // Actually format from getPackedLightmap() is (sky << 20) | (block << 4)
    // So:
    //   sky  = (aLightRGB >> 20) & 0xF  → 0-15
    //   block = (aLightRGB >> 4) & 0xF  → 0-15
    float skyLight = float((aLightRGB >> 20u) & 0xFu) / 15.0;
    float blockLight = float((aLightRGB >> 4u) & 0xFu) / 15.0;
    vLightmap = vec2(skyLight, blockLight);
    
    // Distance fog factor
    float dist = distance(vWorldPos, u_CameraPos);
    vFogFactor = clamp((dist - FOG_START) / (FOG_END - FOG_START), 0.0, 1.0);
}
