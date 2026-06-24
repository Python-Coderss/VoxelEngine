#version 430 core

layout(location = 0) out vec4 outAlbedo;     // rgb=albedo, a=material packed
layout(location = 1) out vec4 outPosition;   // rgb=world pos, a=unused
layout(location = 2) out vec4 outNormal;     // rgb=normal, a=reflectivity

in vec3 vWorldPos;
in vec2 vUV;
in vec3 vNormal;
flat in uint vBlockData;
flat in uint vLight;

uniform sampler2DArray u_BlockTextures;
uniform isamplerBuffer u_BlockData;
uniform sampler2D u_BiomeMap;
uniform sampler2D u_GrassColormap;
uniform sampler2D u_FoliageColormap;
uniform ivec3 u_WorldOffset;
uniform float u_Time;

void main() {
    uint blockId = vBlockData & 0xFFFFu;
    uint faceTex = (vBlockData >> 16) & 0xFFu;
    
    // Sample texture
    vec4 texSample = texture(u_BlockTextures, vec3(vUV, float(faceTex)));
    if (texSample.a < 0.1) discard;
    
    // Get block data for material properties
    ivec4 d1 = texelFetch(u_BlockData, int(blockId) * 3 + 1);
    ivec4 d2 = texelFetch(u_BlockData, int(blockId) * 3 + 2);
    
    // Albedo from texture * block color
    vec3 albedo = texSample.rgb * (vec3(d2.rgb) / 255.0);
    
    // Animated texture: d2.w has animation info
    int animPacked = d2.w;
    bool isAnimated = (animPacked & 1) == 1;
    int frameCount = (animPacked >> 1) & 0x3F;
    if (isAnimated && frameCount > 1) {
        // Animation already handled by vertex UV offset — skip in fragment
    }
    
    // Biome tinting
    int tintIndex = (d1.w >> 22) & 3;
    if (tintIndex > 0) {
        // Determine face index from normal
        int face;
        vec3 an = abs(vNormal);
        if (an.y > 0.9) face = (vNormal.y > 0.0) ? 1 : 0;
        else if (an.z > 0.9) face = (vNormal.z > 0.0) ? 3 : 2;
        else face = (vNormal.x > 0.0) ? 5 : 4;
        int tintFaceMask = (d1.w >> 24) & 0x3F;
        if (((tintFaceMask >> face) & 1) == 1 && texSample.a > 0.5) {
            vec2 bp = texture(u_BiomeMap, (vWorldPos.xz - vec2(u_WorldOffset.xz)) / 2048.0).rg;
            vec2 cmUV = vec2(1.0 - bp.x, 1.0 - bp.y);
            if (tintIndex == 1) albedo *= texture(u_GrassColormap, cmUV).rgb;
            else albedo *= texture(u_FoliageColormap, cmUV).rgb;
        }
    }
    
    // Material packing in alpha:
    // bits 0-7: reflectivity
    // bits 8-15: emissive
    // bit 16: isFullBlock
    // bits 17-20: effectId (portal=1, liquid=2, wire=3)
    int refl = d1.w & 0xFF;
    int emissive = (d1.w >> 14) & 0xFF;
    int isFull = (d1.w >> 9) & 1;
    int effectId = (d1.w >> 10) & 0xF;
    float matPacked = float(refl | (emissive << 8) | (isFull << 16) | (effectId << 17)) / float(0xFFFFFFFF);
    
    outAlbedo = vec4(albedo, matPacked);
    outPosition = vec4(vWorldPos, 1.0);
    
    // Normal + reflectivity in alpha
    float reflectivity = float(refl) / 255.0;
    outNormal = vec4(normalize(vNormal) * 0.5 + 0.5, reflectivity);
    
    // Light value stored in position alpha
    outPosition.a = float(vLight & 0x3FFu) / 1023.0; // Store R channel of light as hint
}
