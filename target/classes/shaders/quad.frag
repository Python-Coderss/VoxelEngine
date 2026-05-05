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
    
    for(int x = -2; x <= 2; x++) {
        for(int y = -2; y <= 2; y++) {
            vec3 sampleColor = texture(screenTexture, TexCoords + vec2(x, y) * texelSize * 1.5).rgb;
            float brightness = dot(sampleColor, vec3(0.2126, 0.7152, 0.0722));
            if(brightness > bloomThreshold) {
                bloom += sampleColor * (brightness - bloomThreshold);
            }
        }
    }
    bloom /= 25.0;
    
    // Composite: Original + Bloom
    vec3 finalColor = color + bloom * bloomIntensity;
    
    FragColor = vec4(finalColor, 1.0);
}
