#version 430 core

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D inputTexture;

void main() {
    FragColor = vec4(texture(inputTexture, TexCoords).rgb, 1.0);
}
