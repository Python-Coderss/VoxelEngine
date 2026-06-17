#version 430 core

/**
 * Chunk Depth Prepass — Fragment Shader
 * Writes the chunk section origin (packed coords) to a GL_R32UI attachment.
 * 
 * Optimizations:
 * - early_fragment_tests: GPU skips fragment shader for occluded fragments
 * - No texture reads, no branches, no loops — minimal fragment cost
 * - Single uint output to GL_R32UI texture
 * - Back-face culling handled by GL state (glCullFace GL_BACK)
 */

layout(early_fragment_tests) in;

flat in uint vChunkOrigin; // packed chunk section coords (cx | cy<<8 | cz<<16)

layout(location = 0) out uint outChunkOrigin;

void main() {
    outChunkOrigin = vChunkOrigin;
}
