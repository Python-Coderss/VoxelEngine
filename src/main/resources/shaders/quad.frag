#version 430 core

/**
 * Full-screen Quad Fragment Shader with Bloom.
 */

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D screenTexture;

void main() {
    vec2 texelSize = 1.0 / textureSize(screenTexture, 0);
    vec3 color = texture(screenTexture, TexCoords).rgb;
    
    // Bloom extraction and blur (Multi-tap box blur)
    vec3 bloom = vec3(0.0);
    float bloomThreshold = 0.8;
    float bloomIntensity = 0.4;
    
    for(int x = -1; x <= 1; x++) {
        for(int y = -1; y <= 1; y++) {
            vec3 sampleColor = texture(screenTexture, TexCoords + vec2(x, y) * texelSize * 2.0).rgb;
            float brightness = max(sampleColor.r, max(sampleColor.g, sampleColor.b));
            if(brightness > bloomThreshold) {
                bloom += sampleColor * (brightness - bloomThreshold);
            }
        }
    }
    bloom /= 9.0;
    
    // Composite: Original + Bloom
    vec3 finalColor = color + bloom * bloomIntensity;
    
    FragColor = vec4(finalColor, 1.0);
}
