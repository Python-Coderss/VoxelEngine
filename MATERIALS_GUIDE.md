# Voxel Engine Material Properties Guide

This guide explains how to use the unified material property system to control the visual appearance of blocks in the engine.

## Overview

The engine uses a data-driven material system that allows you to define how each block interacts with light, transparency, and special effects without modifying the core shader code. These properties are defined in Java and uploaded to the GPU via Texture Buffer Objects (TBOs).

## Available Properties

| Property | Type | Range | Description |
| :--- | :--- | :--- | :--- |
| **Transparency** | `int` | 0 - 255 | 0 = Opaque, 255 = Fully Transparent. |
| **Reflectivity** | `int` | 0 - 255 | Controls mirror-like reflections. 0 = Matte, 255 = Mirror. |
| **Emissive** | `int` | 0 - 255 | Light emission intensity. Blocks with > 0 emissive will glow in the dark. |
| **Distortion** | `int` | 0 - 255 | UV distortion intensity for animated effects (like portal swirls). |
| **Diffuse** | `int` | 0 - 255 | Base brightness multiplier. Default is 255. |
| **Effect** | `Enum` | - | Special shader logic for specific block types (Portal, Liquid, Wire). |

## Material Effects (MaterialEffect Enum)

The `MaterialEffect` enum allows you to select specialized rendering logic:

*   **`NONE` (0):** Standard block rendering.
*   **`PORTAL` (1):** Enables semi-transparency mixing, edge glow, and portal-specific tinting.
*   **`LIQUID` (2):** Enables liquid-specific blending and refraction-like properties (optimized for Water/Lava).
*   **`WIRE` (3):** Enables Redstone Wire logic, including power-based tinting and connection arm rendering.

## Usage in Java

### Registering a Standard Block
```java
blockDataManager.registerBlock(ID, "name", textureManager, modelsDir);
```

### Registering a Block with Custom Properties
```java
blockDataManager.registerBlock(ID, "glass", textureManager, modelsDir, 150, 50, 255);
// Params: id, name, texManager, modelsDir, transparency, reflectivity, diffuse
```

### Registering an Emissive Block
```java
blockDataManager.registerBlock(ID, "glowstone", textureManager, modelsDir, 0, 0, 255, 255);
// Params: id, name, texManager, modelsDir, transparency, reflectivity, diffuse, emissive
```

## GPU Bit-Packing Scheme

Materials are packed into `ivec4` slots in the `u_BlockData` TBO:

### `ivec4 1` (Packed Properties)
*   **Bits 0-7:** Reflectivity (0-255)
*   **Bit 8:** Is Tintable (Biome coloring)
*   **Bit 9:** Is Full Block (AABB optimization)
*   **Bits 10-13:** Effect ID (MaterialEffect)
*   **Bits 14-21:** Emissive Intensity (0-255)

### `ivec4 2` (Animation & Extra)
*   **Bit 0:** Is Animated
*   **Bits 1-6:** Frame Count
*   **Bits 8-15:** Diffuse Intensity (0-255)
*   **Bits 16-23:** UV Distortion Intensity (0-255)

## Shader Implementation (GLSL)

In `raytracer.comp`, properties are extracted and used as follows:

```glsl
// Extraction
int effectId = (d1w >> 10) & 0xF;
float emissive = float((d1w >> 14) & 0xFF) / 255.0;
float distortion = float((animPacked >> 16) & 0xFF) / 255.0;

// UV Distortion
if (distortion > 0.0) {
    vec2 swirlUv = uv + vec2(sin(u_Time), cos(u_Time)) * distortion;
    // ...
}

// Lighting
if (emissive > 0.0) {
    final += throu * alb * emissive * 2.0; // Boost emission for bloom-like effect
}
```

## Best Practices

1.  **Portals:** Always set `effect = MaterialEffect.PORTAL` and provide a `distortion` value (e.g., 20).
2.  **Light Sources:** Use `emissive = 255` for full brightness (Glowstone, Sea Lanterns).
3.  **Translucent Blocks:** Use `transparency` between 100-200 for best results.
4.  **Performance:** Prefer `MaterialEffect.NONE` unless specific logic is required, as the shader uses branching for effects.
