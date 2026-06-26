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
    
    // Decode 8-bit packed lightmap:
    //   bits 24-31 = sky light (0-255)
    //   bits 16-23 = block R (0-255)
    //   bits 8-15  = block G (0-255)
    //   bits 0-7   = block B (0-255)
    // Format from getPackedLightmap() is (sky << 24) | (r << 16) | (g << 8) | b
    float skyLight = float((aLightRGB >> 24u) & 0xFFu) / 255.0;
    float blockR = float((aLightRGB >> 16u) & 0xFFu) / 255.0;
    float blockG = float((aLightRGB >> 8u) & 0xFFu) / 255.0;
    float blockB = float(aLightRGB & 0xFFu) / 255.0;
    float blockLight = max(max(blockR, blockG), blockB);
    vLightmap = vec2(skyLight, blockLight);
    
    // Distance fog factor
    float dist = distance(vWorldPos, u_CameraPos);
    vFogFactor = clamp((dist - FOG_START) / (FOG_END - FOG_START), 0.0, 1.0);
}
