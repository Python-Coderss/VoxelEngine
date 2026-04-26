#version 430 core

/**
 * Full-screen Quad Vertex Shader.
 * This shader runs once for each of the 6 vertices in our screen-filling rectangle.
 */

// Input from the Vertex Buffer Object (VBO)
layout(location = 0) in vec2 aPos;

// Output passed to the Fragment Shader
out vec2 TexCoords;

void main() {
    // Pass the position directly to the graphics pipeline
    // GL positions range from -1.0 to 1.0
    gl_Position = vec4(aPos, 0.0, 1.0);

    // Convert -1..1 range to 0..1 for texture coordinates
    TexCoords = aPos * 0.5 + 0.5;
}
