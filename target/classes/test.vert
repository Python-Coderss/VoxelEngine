#version 430 core

/**
 * Simple Test Vertex Shader.
 * Used for debugging and basic rendering tests.
 */

layout(location = 0) in vec3 aPos;

void main() {
    gl_Position = vec4(aPos, 1.0);
}
