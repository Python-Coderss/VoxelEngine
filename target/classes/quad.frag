#version 430 core

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform sampler2D u_GodRayTexture;
uniform int u_Pass;

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 texel = 1.0 / textureSize(inputTexture, 0);

    if (u_Pass == 0) {
        // Pass 0: Horizontal Blur of God Rays
        vec3 sum = vec3(0.0);
        for (int x = -12; x <= 12; x++) {
            sum += texture(inputTexture, TexCoords + vec2(x, 0) * texel).rgb;
        }
        FragColor = vec4(sum / 25.0, 1.0);
    } else {
        // Pass 1: Vertical Blur of God Rays and Combine with Sharp Scene
        vec3 sceneColor = texture(inputTexture, TexCoords).rgb;
        
        vec3 sum = vec3(0.0);
        for (int y = -12; y <= 12; y++) {
            sum += texture(u_GodRayTexture, TexCoords + vec2(0, y) * texel).rgb;
        }
        vec3 blurredGodRay = sum / 25.0;

        // Combine the sharp scene with the blurred god rays
        FragColor = vec4(sceneColor + blurredGodRay, 1.0);
    }
}
