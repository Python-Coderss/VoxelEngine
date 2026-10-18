package com.voxel.game;

import com.voxel.world.DimensionType;

import static org.lwjgl.opengl.GL20.*;

/**
 * Precomputes atmosphere uniforms per dimension (sun/moon/sky/ambient colors).
 * Avoids per-pixel GetAtmosphere in the shader.
 */
public class AtmosphereRenderer {
    private final int locSunDir, locSunColor, locMoonDir, locMoonColor;
    private final int locSkyZenith, locSkyHorizon, locAmbient;
    private final int locDimensionID;
    private final float[] atmoScratch = new float[21]; // reusable Overworld atmosphere (no per-frame alloc)
    // Cached r+g+b sums of the last-uploaded sun/moon colors — the same
    // "lit" measure the raytracer's god-ray march gates on. Lets Main skip
    // regenerating light pools whose source is currently invisible (day =>
    // moon unused, night => sun unused).
    private float sunLum, moonLum;

    /** r+g+b of the active sun color (0 at night). */
    public float sunLuminance() { return sunLum; }

    /** r+g+b of the active moon color (0 at day). */
    public float moonLuminance() { return moonLum; }

    public AtmosphereRenderer(int computeProgram) {
        locSunDir = glGetUniformLocation(computeProgram, "u_SunDir");
        locSunColor = glGetUniformLocation(computeProgram, "u_SunColor");
        locMoonDir = glGetUniformLocation(computeProgram, "u_MoonDir");
        locMoonColor = glGetUniformLocation(computeProgram, "u_MoonColor");
        locSkyZenith = glGetUniformLocation(computeProgram, "u_SkyZenith");
        locSkyHorizon = glGetUniformLocation(computeProgram, "u_SkyHorizon");
        locAmbient = glGetUniformLocation(computeProgram, "u_Ambient");
        locDimensionID = glGetUniformLocation(computeProgram, "u_DimensionID");
    }

    public int locDimensionID() { return locDimensionID; }

    public void upload(float worldTime, DimensionType activeDimension) {
        if (activeDimension == DimensionType.NETHER) {
            sunLum = 0.6f + 0.2f + 0.05f; moonLum = 0f;
            glUniform3f(locSunDir, 0f, -0.5f, 0f);
            glUniform3f(locSunColor, 0.6f, 0.2f, 0.05f);
            glUniform3f(locMoonDir, 0f, 0.5f, 0f);
            glUniform3f(locMoonColor, 0f, 0f, 0f);
            glUniform3f(locSkyZenith, 0.3f, 0.05f, 0.02f);
            glUniform3f(locSkyHorizon, 0.5f, 0.15f, 0.05f);
            glUniform3f(locAmbient, 0.08f, 0.03f, 0.01f);
        } else if (activeDimension == DimensionType.END) {
            sunLum = 0.1f + 0.05f + 0.15f; moonLum = 0f;
            glUniform3f(locSunDir, 0f, 1f, 0f);
            glUniform3f(locSunColor, 0.1f, 0.05f, 0.15f);
            glUniform3f(locMoonDir, 0f, -1f, 0f);
            glUniform3f(locMoonColor, 0f, 0f, 0f);
            glUniform3f(locSkyZenith, 0.01f, 0f, 0.05f);
            glUniform3f(locSkyHorizon, 0.05f, 0.02f, 0.1f);
            glUniform3f(locAmbient, 0.02f, 0.01f, 0.04f);
        } else if (activeDimension == DimensionType.AETHER) {
            sunLum = 1f + 0.98f + 0.9f; moonLum = 0f;
            glUniform3f(locSunDir, 0f, 1f, 0.3f);
            glUniform3f(locSunColor, 1f, 0.98f, 0.9f);
            glUniform3f(locMoonDir, 0f, -1f, -0.3f);
            glUniform3f(locMoonColor, 0f, 0f, 0f);
            glUniform3f(locSkyZenith, 0.4f, 0.7f, 0.95f);
            glUniform3f(locSkyHorizon, 0.7f, 0.85f, 0.95f);
            glUniform3f(locAmbient, 0.3f, 0.35f, 0.4f);
        } else {
            // Overworld: day/night cycle
            overworld(worldTime, atmoScratch);
            glUniform3f(locSunDir, atmoScratch[0], atmoScratch[1], atmoScratch[2]);
            glUniform3f(locMoonDir, atmoScratch[3], atmoScratch[4], atmoScratch[5]);
            glUniform3f(locSunColor, atmoScratch[6], atmoScratch[7], atmoScratch[8]);
            glUniform3f(locMoonColor, atmoScratch[9], atmoScratch[10], atmoScratch[11]);
            glUniform3f(locSkyZenith, atmoScratch[12], atmoScratch[13], atmoScratch[14]);
            glUniform3f(locSkyHorizon, atmoScratch[15], atmoScratch[16], atmoScratch[17]);
            glUniform3f(locAmbient, atmoScratch[18], atmoScratch[19], atmoScratch[20]);

            sunLum = atmoScratch[6] + atmoScratch[7] + atmoScratch[8];
            moonLum = atmoScratch[9] + atmoScratch[10] + atmoScratch[11];
        }
    }

    /**
     * CPU-side Overworld atmosphere for a world time on the 1440-unit day
     * (0 = midnight, 360 = sunrise, 720 = noon, 1080 = dusk).
     *
     * <p>{@link #upload} hands exactly these numbers to the shader as
     * {@code u_SunDir / u_MoonDir / u_SunColor / u_MoonColor / u_SkyZenith /
     * u_SkyHorizon / u_Ambient}, and the raytracer builds its {@code Atmosphere}
     * struct straight from those uniforms ({@code at.sunDir = u_SunDir; ...}) — so
     * this method is the entire CPU half of the world-lighting data path.
     * Exposed so tests can assert what the renderer feeds the shader, including
     * the pole that matters most here: {@code sunColor} is zeroed by its horizon
     * fade ({@code smoothstep(-0.1, 0.1, h)}) whenever the sun is below the
     * horizon, and terrain is shaded with {@code ambient + sunColor * celRamp}.</p>
     *
     * @param out receives 21 floats: sunDir(3), moonDir(3), sunColor(3),
     *            moonColor(3), skyZenith(3), skyHorizon(3), ambient(3)
     */
    public static void overworld(float worldTime, float[] out) {
        float[] sd = new float[3];
        computeSunDir(DimensionType.OVERWORLD, worldTime, sd);
        out[0] = sd[0]; out[1] = sd[1]; out[2] = sd[2];
        out[3] = -sd[0]; out[4] = -sd[1]; out[5] = -sd[2];

        float h = sd[1];
        float skyZenR = mix(0.01f, 0.1f, smoothstep(-0.15f, 0.15f, h));
        float skyZenG = mix(0.02f, 0.35f, smoothstep(-0.15f, 0.15f, h));
        float skyZenB = mix(0.05f, 0.75f, smoothstep(-0.15f, 0.15f, h));
        out[12] = skyZenR; out[13] = skyZenG; out[14] = skyZenB;

        out[15] = mix(0.02f, mix(0.9f, 0.5f, smoothstep(0f, 0.3f, h)), smoothstep(-0.15f, 0.1f, h));
        out[16] = mix(0.03f, mix(0.4f, 0.7f, smoothstep(0f, 0.3f, h)), smoothstep(-0.15f, 0.1f, h));
        out[17] = mix(0.08f, mix(0.2f, 0.9f, smoothstep(0f, 0.3f, h)), smoothstep(-0.15f, 0.1f, h));

        // Sun intensity scaled to 0.7× — the previous full-white noon sun
        // (1.0, 0.98, 0.9) washed out open-air scenes like the tutorial world.
        out[6] = mix(0.7f, 0.7f, smoothstep(0f, 0.4f, h)) * smoothstep(-0.1f, 0.1f, h);
        out[7] = mix(0.35f, 0.68f, smoothstep(0f, 0.4f, h)) * smoothstep(-0.1f, 0.1f, h);
        out[8] = mix(0.14f, 0.63f, smoothstep(0f, 0.4f, h)) * smoothstep(-0.1f, 0.1f, h);

        out[9] = 0.2f * smoothstep(-0.1f, 0.1f, -h);
        out[10] = 0.25f * smoothstep(-0.1f, 0.1f, -h);
        out[11] = 0.4f * smoothstep(-0.1f, 0.1f, -h);

        out[18] = mix(0.02f, skyZenR * 0.4f, smoothstep(-0.2f, 0.2f, h));
        out[19] = mix(0.03f, skyZenG * 0.4f, smoothstep(-0.2f, 0.2f, h));
        out[20] = mix(0.07f, skyZenB * 0.4f, smoothstep(-0.2f, 0.2f, h));
    }

    /**
     * Computes the sun direction for a dimension (identical math to upload()).
     * Used by Main to build the sun shadow-map basis.
     */
    public static void computeSunDir(DimensionType dim, float worldTime, float[] out) {
        float cycle = ((worldTime / 1440.0f) % 1.0f);
        float angle = cycle * 2.0f * 3.14159265359f - (3.14159265359f * 0.5f);
        if (dim == DimensionType.NETHER) { out[0] = 0f; out[1] = -0.5f; out[2] = 0f; return; }
        if (dim == DimensionType.END)    { out[0] = 0f; out[1] = 1f; out[2] = 0f; return; }
        if (dim == DimensionType.AETHER) { out[0] = 0f; out[1] = 1f; out[2] = 0.3f; return; }
        float sinA = (float) Math.sin(angle);
        float cosA = (float) Math.cos(angle);
        float sunX = cosA, sunY = sinA, sunZ = 0.3f;
        float len = (float) Math.sqrt(sunX * sunX + sunY * sunY + sunZ * sunZ);
        out[0] = sunX / len; out[1] = sunY / len; out[2] = sunZ / len;
    }

    static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
