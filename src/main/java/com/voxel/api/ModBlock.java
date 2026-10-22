package com.voxel.api;

/**
 * Declarative description of a mod block.
 *
 * <p>The texture atlas, model resolution and material flags are all reused from
 * the engine's existing block pipeline, so a mod block looks and lights exactly
 * like a built-in one.</p>
 *
 * <pre>{@code
 * int id = ctx.registerBlock(ModBlock.of("glow_lantern")
 *         .model("glow_lantern")
 *         .emissive(255)
 *         .light(255, 214, 140)
 *         .hardness(1.2f)
 *         .build());
 * }</pre>
 */
public final class ModBlock {

    /** Block name (also the model file name unless {@link #model} overrides it). */
    public final String name;
    /** Model JSON file name (without {@code .json}) under {@code models/block}. */
    public final String model;
    /** 0 = opaque, 255 = fully transparent (see vanilla glass: 150). */
    public final int transparency;
    /** 0-255 specular weight. */
    public final int reflectivity;
    /** 0-255 diffuse weight (255 = normal). */
    public final int diffuse;
    /** 0-255 self-illumination pushed into the raytracer. */
    public final int emissive;
    /** Optional coloured light source; {@code emitsLight} is false when all are 0. */
    public final int lightR, lightG, lightB;
    public final boolean emitsLight;
    /** False for slabs/plants/etc. that use the model's AABB instead of a cube. */
    public final boolean fullBlock;
    /** Mining hardness in seconds-ish; negative keeps the engine default. */
    public final float hardness;
    /** Tool tier required to drop the block (0 = hand). */
    public final int miningTier;

    private ModBlock(Builder b) {
        this.name = b.name;
        this.model = b.model == null ? b.name : b.model;
        this.transparency = b.transparency;
        this.reflectivity = b.reflectivity;
        this.diffuse = b.diffuse;
        this.emissive = b.emissive;
        this.lightR = b.lightR;
        this.lightG = b.lightG;
        this.lightB = b.lightB;
        this.emitsLight = b.lightR > 0 || b.lightG > 0 || b.lightB > 0;
        this.fullBlock = b.fullBlock;
        this.hardness = b.hardness;
        this.miningTier = b.miningTier;
    }

    public static Builder of(String name) {
        return new Builder(name);
    }

    /** Fluent builder; every setter returns {@code this}. */
    public static final class Builder {
        private final String name;
        private String model;
        private int transparency = 0;
        private int reflectivity = 0;
        private int diffuse = 255;
        private int emissive = 0;
        private int lightR, lightG, lightB;
        private boolean fullBlock = true;
        private float hardness = -1f;
        private int miningTier = 0;

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("block name is required");
            }
            this.name = name.trim().toLowerCase(java.util.Locale.ROOT);
        }

        /** Model file to resolve instead of {@link #name}. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Vanilla glass uses 150; 255 is fully see-through. */
        public Builder translucent(int transparency) {
            this.transparency = Math.max(0, Math.min(255, transparency));
            return this;
        }

        public Builder reflective(int reflectivity) {
            this.reflectivity = Math.max(0, Math.min(255, reflectivity));
            return this;
        }

        public Builder diffuse(int diffuse) {
            this.diffuse = Math.max(0, Math.min(255, diffuse));
            return this;
        }

        /** Self-illumination in the raytracer (255 = fully lit like glowstone). */
        public Builder emissive(int emissive) {
            this.emissive = Math.max(0, Math.min(255, emissive));
            return this;
        }

        /** Emits coloured light; components are 0-255. */
        public Builder light(int r, int g, int b) {
            this.lightR = clamp(r);
            this.lightG = clamp(g);
            this.lightB = clamp(b);
            return this;
        }

        /** Use the model's AABB instead of a full cube (slabs, plants, signs...). */
        public Builder nonFullBlock() {
            this.fullBlock = false;
            return this;
        }

        /** Mining hardness; values below 0 keep the engine default. */
        public Builder hardness(float hardness) {
            this.hardness = hardness;
            return this;
        }

        /** Tool tier required to drop the block (0 = hand, 4 = diamond). */
        public Builder miningTier(int tier) {
            this.miningTier = Math.max(0, tier);
            return this;
        }

        public ModBlock build() {
            return new ModBlock(this);
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(255, v));
        }
    }
}
