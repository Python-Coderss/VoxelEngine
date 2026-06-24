#version 430 core

// GBuffer Fragment Shader — writes position, normal, albedo, and material to MRT
// Early fragment tests for depth culling (sky pixels already masked by depth prepass)

layout(early_fragment_tests) in;

in vec3 vWorldPos;
flat in vec3 vNormal;
in vec2 vTexCoord;
flat in uint vBlockId;
flat in vec2 vLightmap;   // (skyLight/15.0, blockLight/15.0)
in float vFogFactor;

// GBuffer outputs (MRT)
layout(location = 0) out vec4 outPosition;   // rgb = worldPos, a = unused
layout(location = 1) out vec4 outNormal;     // rgb = normal, a = unused
layout(location = 2) out vec4 outAlbedo;     // rgb = albedo color, a = reflectivity
layout(location = 3) out vec4 outMaterial;   // r = blockLight, g = emissive, b = transmittance, a = blockId/256

layout(binding = 0) uniform sampler2DArray u_BlockTextures;
layout(binding = 1) uniform isamplerBuffer u_BlockData;

void main() {
    // Fetch block data
    int bid = int(vBlockId);
    ivec4 d0 = texelFetch(u_BlockData, bid * 3);
    ivec4 d1 = texelFetch(u_BlockData, bid * 3 + 1);
    ivec4 d2 = texelFetch(u_BlockData, bid * 3 + 2);
    
    // Determine face from normal
    int face;
    if (vNormal.y > 0.9) face = 1;         // top
    else if (vNormal.y < -0.9) face = 0;    // bottom
    else if (vNormal.z < -0.9) face = 2;    // north
    else if (vNormal.z > 0.9) face = 3;     // south
    else if (vNormal.x < -0.9) face = 4;    // west
    else face = 5;                          // east
    
    int tex = (face == 0) ? d0.x : (face == 1 ? d0.y : (face == 2 ? d0.z : (face == 3 ? d0.w : (face == 4 ? d1.x : d1.y))));
    
    // Sample texture — use opaque white fallback if alpha too low (handles missing atlas layers)
    vec4 texSample = texture(u_BlockTextures, vec3(vTexCoord, float(tex)));
    if (texSample.a < 0.5) texSample = vec4(1.0, 1.0, 1.0, 1.0);
    
    // Albedo = texture color * block tint
    vec3 albedo = texSample.rgb * (vec3(d2.rgb) / 255.0);
    
    // Material properties
    float reflectivity = float(d1.w & 0xFF) / 255.0;
    float emissive = float((d1.w >> 14) & 0xFF) / 255.0;
    float transmittance = float(d1.z) / 255.0;
    
    // Special material effects modify properties
    int effectId = (d1.w >> 10) & 0xF;
    if (effectId == 2) { // LIQUID
        reflectivity = 0.15;
        transmittance = 0.6;
    } else if (effectId == 1) { // PORTAL
        reflectivity = 0.0;
        transmittance = 0.8;
    }
    
    // Output GBuffer
    outPosition = vec4(vWorldPos, 1.0);
    outNormal = vec4(vNormal * 0.5 + 0.5, 1.0); // encode to [0,1]
    outAlbedo = vec4(albedo, reflectivity);
    // Minecraft-style: combined light = max of sky and block light
    float combinedLight = max(vLightmap.x, vLightmap.y);
    outMaterial = vec4(combinedLight, emissive, transmittance, float(bid) / 256.0);
}
