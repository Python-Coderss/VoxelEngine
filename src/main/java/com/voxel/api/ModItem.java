package com.voxel.api;

/**
 * A standalone mod item (not a placeable block).
 *
 * <p>Items are inventory-only for now: they show the same 16x16 icon and stack
 * to 64, and recipes/commands can refer to them by {@link #id}. Block items
 * should use {@link ModContext#registerBlockItem(String, String)} instead, so
 * they keep their 3D block model when dropped or placed.</p>
 */
public final class ModItem {

    public final String id;
    public final String displayName;
    /** Texture name (no extension) under {@code assets/<modid>/textures}. */
    public final String iconTexture;

    private ModItem(String id, String displayName, String iconTexture) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("item id is required");
        this.id = id.trim().toLowerCase(java.util.Locale.ROOT);
        this.displayName = displayName == null || displayName.isEmpty() ? id : displayName;
        this.iconTexture = iconTexture == null || iconTexture.isEmpty() ? this.id : iconTexture;
    }

    public static ModItem of(String id, String displayName, String iconTexture) {
        return new ModItem(id, displayName, iconTexture);
    }

    public static ModItem of(String id, String displayName) {
        return new ModItem(id, displayName, id);
    }
}
