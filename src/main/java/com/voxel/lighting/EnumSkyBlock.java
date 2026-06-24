package com.voxel.lighting;

/**
 * Mirrors Minecraft's EnumSkyBlock: two separate light channels.
 * SKY light propagates top-down from the world ceiling.
 * BLOCK light flood-fills from emissive block sources.
 */
public enum EnumSkyBlock {
    /** Sunlight / sky light, propagates downward from world top. Default value at sky-exposed positions is 15. */
    SKY(15),

    /** Block light from emissive sources (torches, glowstone, etc.). Default value with no sources is 0. */
    BLOCK(0);

    /** The default light value for this type at a position with no obstruction/source. */
    public final int defaultLightValue;

    EnumSkyBlock(int defaultLightValue) {
        this.defaultLightValue = defaultLightValue;
    }
}
