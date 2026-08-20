package com.voxel.world;

import com.voxel.lighting.LightEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for the block-light color-space contract mirrored by GLSL. */
public class BlockLightGammaTest {

    @Test
    public void blockLightDecodeIsBoundedAndKeepsLowLevelsDark() {
        assertEquals(0.0f, LightEngine.decodeBlockLight(-1.0f), 0.0f);
        assertEquals(255.0f, LightEngine.decodeBlockLight(255.0f), 0.0f);
        assertEquals(255.0f, LightEngine.decodeBlockLight(512.0f), 0.0f);

        float encodedHalf = 127.5f;
        float decodedHalf = LightEngine.decodeBlockLight(encodedHalf);
        assertTrue("gamma decode must remain monotonic", decodedHalf > 0.0f && decodedHalf < 255.0f);
        assertTrue("a half-level light must not be treated as half linear radiance",
                decodedHalf < encodedHalf);
    }

    @Test
    public void srgbEncodingIsMonotonicAndUsesTheLinearToe() {
        assertEquals(0.0f, LightEngine.linearToSrgb(0.0f), 0.0f);
        assertEquals(1.0f, LightEngine.linearToSrgb(1.0f), 0.000001f);
        assertEquals(0.0031308f * 12.92f,
                LightEngine.linearToSrgb(0.0031308f), 0.000001f);
        assertTrue(LightEngine.linearToSrgb(0.25f) < LightEngine.linearToSrgb(0.5f));
        assertTrue(LightEngine.linearToSrgb(0.5f) < LightEngine.linearToSrgb(1.0f));
    }
}
