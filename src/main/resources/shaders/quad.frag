#version 430 core

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D inputTexture;

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 texel = 1.0 / textureSize(inputTexture, 0);

    vec3 centerColor = texture(inputTexture, TexCoords).rgb;

    float minLum = 1e9;
    float maxLum = -1e9;

    vec3 sumColor = vec3(0.0);
    int count = 0;

    // 25x25 neighborhood (-12 to +12)
    for (int x = -12; x <= 12; x++) {
        for (int y = -12; y <= 12; y++) {

            vec3 c = texture(inputTexture, TexCoords + vec2(x, y) * texel).rgb;
            float l = luminance(c);

            minLum = min(minLum, l);
            maxLum = max(maxLum, l);

            sumColor += c;
            count++;
        }
    }

    float range = maxLum - minLum;

    float threshold = 50.0 / 255.0;

    if (range > threshold) {
        FragColor = vec4(sumColor / float(count), 1.0);
    } else {
        FragColor = vec4(centerColor, 1.0);
    }
}
