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
        float t = worldTime;
        float cycle = (t / 1440.0f) % 1.0f;
        float angle = cycle * 2.0f * 3.14159265359f - (3.14159265359f * 0.5f);

        if (activeDimension == DimensionType.NETHER) {
            glUniform3f(locSunDir, 0f, -0.5f, 0f);
            glUniform3f(locSunColor, 0.6f, 0.2f, 0.05f);
            glUniform3f(locMoonDir, 0f, 0.5f, 0f);
            glUniform3f(locMoonColor, 0f, 0f, 0f);
            glUniform3f(locSkyZenith, 0.3f, 0.05f, 0.02f);
            glUniform3f(locSkyHorizon, 0.5f, 0.15f, 0.05f);
            glUniform3f(locAmbient, 0.08f, 0.03f, 0.01f);
        } else if (activeDimension == DimensionType.END) {
            glUniform3f(locSunDir, 0f, 1f, 0f);
            glUniform3f(locSunColor, 0.1f, 0.05f, 0.15f);
            glUniform3f(locMoonDir, 0f, -1f, 0f);
            glUniform3f(locMoonColor, 0f, 0f, 0f);
            glUniform3f(locSkyZenith, 0.01f, 0f, 0.05f);
            glUniform3f(locSkyHorizon, 0.05f, 0.02f, 0.1f);
            glUniform3f(locAmbient, 0.02f, 0.01f, 0.04f);
        } else if (activeDimension == DimensionType.AETHER) {
            glUniform3f(locSunDir, 0f, 1f, 0.3f);
            glUniform3f(locSunColor, 1f, 0.98f, 0.9f);
            glUniform3f(locMoonDir, 0f, -1f, -0.3f);
            glUniform3f(locMoonColor, 0f, 0f, 0f);
            glUniform3f(locSkyZenith, 0.4f, 0.7f, 0.95f);
            glUniform3f(locSkyHorizon, 0.7f, 0.85f, 0.95f);
            glUniform3f(locAmbient, 0.3f, 0.35f, 0.4f);
        } else {
            // Overworld: day/night cycle
            float sinA = (float) Math.sin(angle);
            float cosA = (float) Math.cos(angle);
            float sunX = cosA, sunY = sinA, sunZ = 0.3f;
            float len = (float) Math.sqrt(sunX * sunX + sunY * sunY + sunZ * sunZ);
            sunX /= len; sunY /= len; sunZ /= len;
            glUniform3f(locSunDir, sunX, sunY, sunZ);
            glUniform3f(locMoonDir, -sunX, -sunY, -sunZ);

            float h = sunY;
            float skyZenR = mix(0.01f, 0.1f, smoothstep(-0.15f, 0.15f, h));
            float skyZenG = mix(0.02f, 0.35f, smoothstep(-0.15f, 0.15f, h));
            float skyZenB = mix(0.05f, 0.75f, smoothstep(-0.15f, 0.15f, h));
            glUniform3f(locSkyZenith, skyZenR, skyZenG, skyZenB);

            float horR = mix(0.02f, mix(0.9f, 0.5f, smoothstep(0f, 0.3f, h)), smoothstep(-0.15f, 0.1f, h));
            float horG = mix(0.03f, mix(0.4f, 0.7f, smoothstep(0f, 0.3f, h)), smoothstep(-0.15f, 0.1f, h));
            float horB = mix(0.08f, mix(0.2f, 0.9f, smoothstep(0f, 0.3f, h)), smoothstep(-0.15f, 0.1f, h));
            glUniform3f(locSkyHorizon, horR, horG, horB);

            float scR = mix(1f, 1f, smoothstep(0f, 0.4f, h)) * smoothstep(-0.1f, 0.1f, h);
            float scG = mix(0.5f, 0.98f, smoothstep(0f, 0.4f, h)) * smoothstep(-0.1f, 0.1f, h);
            float scB = mix(0.2f, 0.9f, smoothstep(0f, 0.4f, h)) * smoothstep(-0.1f, 0.1f, h);
            glUniform3f(locSunColor, scR, scG, scB);

            glUniform3f(locMoonColor, 0.2f * smoothstep(-0.1f, 0.1f, -h), 0.25f * smoothstep(-0.1f, 0.1f, -h), 0.4f * smoothstep(-0.1f, 0.1f, -h));

            float ambR = mix(0.02f, skyZenR * 0.4f, smoothstep(-0.2f, 0.2f, h));
            float ambG = mix(0.03f, skyZenG * 0.4f, smoothstep(-0.2f, 0.2f, h));
            float ambB = mix(0.07f, skyZenB * 0.4f, smoothstep(-0.2f, 0.2f, h));
            glUniform3f(locAmbient, ambR, ambG, ambB);
        }
    }

    static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
