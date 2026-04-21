#version 430 core
in vec2 vUV;
out vec4 FragColor;
layout(binding = 0) uniform sampler2D texSource;
void main() {
    FragColor = texture(texSource, vUV);
}
