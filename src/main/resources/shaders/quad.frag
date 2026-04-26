#version 430 core

/**
 * Full-screen Quad Fragment Shader.
 * This shader runs for every pixel on the screen and determines its color.
 */

// Input from the Vertex Shader
in vec2 TexCoords;

// The output color of the pixel
out vec4 FragColor;

// The texture containing our raytraced image (calculated in raytracer.comp)
uniform sampler2D screenTexture;

void main() {
    // Sample the color from the texture and output it
    FragColor = texture(screenTexture, TexCoords);
}
