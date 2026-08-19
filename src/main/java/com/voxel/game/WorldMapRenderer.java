package com.voxel.game;

import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE20;
import static org.lwjgl.opengl.GL13.glActiveTexture;

/**
 * Generates the map preview texture: a simplified biome-colored top-down view
 * of UNLOADED chunks. The raytracer renders loaded terrain in full 3D (the map
 * is a top-down camera), but rays that fall through an EMPTY chunk column
 * sample this texture (see u_MapPreview in raytracer.comp) — so the map shows
 * flat biome colors instead of void wherever chunks are missing, and goes void
 * past the world border so the map stops exactly at the world size.
 *
 * The texture is filled ring-by-ring from the center with a bounded chunk
 * budget per call, so opening/panning the map never stalls a tick. Colors are
 * cached per chunk; pans and zooms reuse the cache. When the map center pans
 * at the same zoom, the existing texture SLIDES to the new center — the painted
 * overlap is preserved in place and only the newly-exposed edge strips are
 * filled, so panning never clears and regenerates the whole view. Fill happens
 * on the logic thread (updatePreview), upload on the render thread
 * (uploadIfDirty) — both guarded by a lock, mirroring BiomeManager's
 * biomeLock pattern.
 */
public class WorldMapRenderer {

    /** Must match the shader's MAP_PREVIEW_TEXELS constant. */
    public static final int TEX_SIZE = 256;
    private static final int FILL_CHUNKS_PER_CALL = 256;

    private final ByteBuffer previewPixels = ByteBuffer.allocateDirect(TEX_SIZE * TEX_SIZE * 4);
    private final ByteBuffer slideScratch = ByteBuffer.allocateDirect(TEX_SIZE * TEX_SIZE * 4);
    private final Object lock = new Object();
    private int textureId = 0;
    private boolean needsUpload = true;

    // Per-chunk RGBA color cache (world chunk coords → packed int). Access-order
    // LRU: pans reuse visited chunks, and the cache never grows unboundedly.
    private final Map<Long, Integer> chunkColors = new LinkedHashMap<Long, Integer>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > 32768;
        }
    };

    // ── Fill state: the world-space region currently being baked ──
    private float regionCenterX, regionCenterZ;   // world coords of the texture center
    private int regionBlocksPerTexel = -1;         // -1 = uninitialized
    private float regionBorder = -1f;              // world border radius at bake time
    private int fillRing = 0;                      // next chunk ring to fill (0 = center)
    private int fillCell = 0;                      // next perimeter cell on that ring
    private int maxRing = 0;
    private boolean fillDone = true;

    /** World X/Z of texel (0,0) for the current region (shader origin uniform). */
    public float getOriginX() { return regionCenterX - (TEX_SIZE / 2f) * regionBlocksPerTexel; }
    public float getOriginZ() { return regionCenterZ - (TEX_SIZE / 2f) * regionBlocksPerTexel; }
    public int getBlocksPerTexel() { return Math.max(1, regionBlocksPerTexel); }

    /**
     * Advance the preview bake for the map region centered at (centerX, centerZ).
     * Called from the logic thread while the map is open.
     *
     * Zoom or border changes re-bake from scratch (clear + ring fill). A pan of
     * more than half a texel at the same zoom SLIDES the existing texture to the
     * new center — the painted overlap is kept, only the newly-exposed edge
     * chunks are filled — so panning never clears and regenerates the view.
     */
    public void updatePreview(BiomeProvider biomes, float centerX, float centerZ, float zoom, float border) {
        synchronized (lock) {
            // Viewport height at this zoom ≈ 2 * camY; add margin for the frustum + pan.
            float camY = 120f * zoom + 20f;
            int bpt = Math.max(1, (int) Math.ceil(2f * camY * 1.35f / TEX_SIZE));

            boolean zoomChanged = regionBlocksPerTexel != bpt || regionBorder != border;
            float dxTex = (centerX - regionCenterX) / bpt;   // in texels
            float dzTex = (centerZ - regionCenterZ) / bpt;
            // Pan only when the center moved more than half a texel (the slide
            // granularity); smaller drifts keep the current region.
            boolean panned = !zoomChanged && (Math.abs(dxTex) > 0.5f || Math.abs(dzTex) > 0.5f);

            if (zoomChanged) {
                regionCenterX = centerX;
                regionCenterZ = centerZ;
                regionBlocksPerTexel = bpt;
                regionBorder = border;
                clearTexture();
                restartFill(bpt);
            } else if (panned) {
                // Slide the painted content to the new center; the exposed strips
                // (cleared by the slide) are filled by the ring scan below.
                slideTexture(Math.round(dxTex), Math.round(dzTex));
                regionCenterX = centerX;
                regionCenterZ = centerZ;
                restartFill(bpt);
            }
            if (fillDone) return;

            // Ring-by-ring scan (perimeter of the [-r..r]² square around the
            // center chunk), bounded budget. Chunks already painted (the slid
            // overlap) are skipped via chunkNeedsFill, so a pan only fills the
            // newly-exposed area.
            int centerCX = (int) Math.floor(regionCenterX / 16f);
            int centerCZ = (int) Math.floor(regionCenterZ / 16f);
            int budget = FILL_CHUNKS_PER_CALL;
            while (budget > 0 && fillRing <= maxRing) {
                int r = fillRing;
                int perim = r == 0 ? 1 : 8 * r;
                while (budget > 0 && fillCell < perim) {
                    int cx, cz;
                    if (r == 0) { cx = 0; cz = 0; }
                    else {
                        int side = fillCell / (2 * r);       // 0 top, 1 right, 2 bottom, 3 left
                        int pos = fillCell % (2 * r);
                        if (side == 0)      { cx = -r + pos;      cz = -r; }
                        else if (side == 1) { cx = r;             cz = -r + pos; }
                        else if (side == 2) { cx = r - pos;       cz = r; }
                        else                { cx = -r;            cz = r - pos; }
                    }
                    int wcx = centerCX + cx;
                    int wcz = centerCZ + cz;
                    if (chunkNeedsFill(wcx, wcz)) {
                        fillChunkTexels(biomes, wcx, wcz);
                        budget--;
                    }
                    fillCell++;
                }
                if (fillCell >= perim) { fillRing++; fillCell = 0; }
            }
            if (fillRing > maxRing) fillDone = true;
            needsUpload = true;
        }
    }

    private void restartFill(int bpt) {
        maxRing = (int) Math.ceil((TEX_SIZE * bpt / 2) / 16f);
        fillRing = 0;
        fillCell = 0;
        fillDone = false;
    }

    private void clearTexture() {
        for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) {
            int idx = i * 4;
            previewPixels.put(idx, (byte) 0);
            previewPixels.put(idx + 1, (byte) 0);
            previewPixels.put(idx + 2, (byte) 0);
            previewPixels.put(idx + 3, (byte) 0);
        }
    }

    /**
     * Shifts the painted content by (idx, idz) texels so the texture is centered
     * on the new region center. New texel (tx,tz) shows the old texel (tx+idx,
     * tz+idz) — the world-block-to-texel mapping is preserved, so the overlap
     * keeps its correct colors. The exposed strips (no source texel) are cleared
     * to void and filled by the ring scan.
     */
    private void slideTexture(int idx, int idz) {
        for (int i = 0; i < TEX_SIZE * TEX_SIZE * 4; i++) slideScratch.put(i, previewPixels.get(i));
        clearTexture();
        int tx0 = Math.max(0, -idx), tx1 = Math.min(TEX_SIZE - 1, TEX_SIZE - 1 - idx);
        int tz0 = Math.max(0, -idz), tz1 = Math.min(TEX_SIZE - 1, TEX_SIZE - 1 - idz);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int dst = (tz * TEX_SIZE + tx) * 4;
                int src = ((tz + idz) * TEX_SIZE + (tx + idx)) * 4;
                previewPixels.put(dst, slideScratch.get(src));
                previewPixels.put(dst + 1, slideScratch.get(src + 1));
                previewPixels.put(dst + 2, slideScratch.get(src + 2));
                previewPixels.put(dst + 3, slideScratch.get(src + 3));
            }
        }
    }

    /**
     * True when the chunk still has void texels inside the current region (and
     * is inside the world border). Beyond-border chunks are intentionally void
     * and are never re-filled. This is what makes pans cheap: after a slide, the
     * overlap chunks are painted (returns false) and only the exposed chunks get
     * filled.
     */
    private boolean chunkNeedsFill(int chunkX, int chunkZ) {
        int wx = chunkX * 16 + 8, wz = chunkZ * 16 + 8;
        if (Math.abs(wx) > regionBorder || Math.abs(wz) > regionBorder) return false;
        int bpt = regionBlocksPerTexel;
        float originX = getOriginX(), originZ = getOriginZ();
        int tx0 = (int) Math.ceil((chunkX * 16 - originX) / (double) bpt - 0.5);
        int tz0 = (int) Math.ceil((chunkZ * 16 - originZ) / (double) bpt - 0.5);
        int tx1 = (int) Math.ceil((chunkX * 16 + 16 - originX) / (double) bpt - 0.5) - 1;
        int tz1 = (int) Math.ceil((chunkZ * 16 + 16 - originZ) / (double) bpt - 0.5) - 1;
        tx0 = Math.max(0, tx0); tz0 = Math.max(0, tz0);
        tx1 = Math.min(TEX_SIZE - 1, tx1); tz1 = Math.min(TEX_SIZE - 1, tz1);
        if (tx0 > tx1 || tz0 > tz1) return false;
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if ((previewPixels.get((tz * TEX_SIZE + tx) * 4 + 3) & 0xFF) == 0) return true;
            }
        }
        return false;
    }

    /**
     * Writes the texels whose CENTER block falls inside the given world chunk
     * (16×16 blocks). Assigning each texel to exactly one chunk (its center's
     * chunk) makes the fill gap-free AND overlap-free: a texel straddling a
     * chunk border belongs to one chunk only, so a later ring can never
     * overwrite an earlier chunk's boundary texel with a different color.
     */
    private void fillChunkTexels(BiomeProvider biomes, int chunkX, int chunkZ) {
        int color = chunkColor(biomes, chunkX, chunkZ);
        int bpt = regionBlocksPerTexel;
        float originX = getOriginX(), originZ = getOriginZ();
        // Texel tx covers block center originX + (tx + 0.5) * bpt; it belongs to
        // this chunk when that center is in [chunk*16, chunk*16 + 16).
        int tx0 = (int) Math.ceil((chunkX * 16 - originX) / (double) bpt - 0.5);
        int tz0 = (int) Math.ceil((chunkZ * 16 - originZ) / (double) bpt - 0.5);
        int tx1 = (int) Math.ceil((chunkX * 16 + 16 - originX) / (double) bpt - 0.5) - 1;
        int tz1 = (int) Math.ceil((chunkZ * 16 + 16 - originZ) / (double) bpt - 0.5) - 1;
        tx0 = Math.max(0, tx0); tz0 = Math.max(0, tz0);
        tx1 = Math.min(TEX_SIZE - 1, tx1); tz1 = Math.min(TEX_SIZE - 1, tz1);
        if (tx0 > tx1 || tz0 > tz1) return;
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int idx = (tz * TEX_SIZE + tx) * 4;
                previewPixels.put(idx, (byte) (color >> 16));
                previewPixels.put(idx + 1, (byte) (color >> 8));
                previewPixels.put(idx + 2, (byte) color);
                previewPixels.put(idx + 3, (byte) (color >>> 24));
            }
        }
    }

    /** Biome color for a chunk (cached); 0 (void, alpha 0) beyond the world border. */
    private int chunkColor(BiomeProvider biomes, int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        Integer cached = chunkColors.get(key);
        if (cached != null) return cached;
        int color;
        float wx = cx * 16 + 8, wz = cz * 16 + 8;
        if (Math.abs(wx) > regionBorder || Math.abs(wz) > regionBorder) {
            color = 0; // past the world size — the map stops here (alpha 0 = void)
        } else if (biomes == null) {
            color = 0xFF6E7378; // no biome provider: neutral unexplored grey
        } else {
            color = biomeColor(biomes.getBiome((int) wx, (int) wz)) | 0xFF000000;
        }
        chunkColors.put(key, color);
        return color;
    }

    /** Simplified muted color per biome name (the "paper map" palette). */
    private static int biomeColor(Biome b) {
        String n = b == null ? "" : b.name;
        float r, g, bl;
        if (n.contains("Ocean"))          { r = 0.08f; g = 0.30f; bl = 0.62f; }
        else if (n.contains("River"))     { r = 0.15f; g = 0.42f; bl = 0.72f; }
        else if (n.contains("Swamp"))     { r = 0.30f; g = 0.40f; bl = 0.22f; }
        else if (n.contains("Ice") || n.contains("Frozen") || n.contains("Taiga")
                || n.contains("Tundra"))  { r = 0.66f; g = 0.70f; bl = 0.74f; }
        else if (n.contains("Jungle"))    { r = 0.13f; g = 0.42f; bl = 0.10f; }
        else if (n.contains("Rainforest")){ r = 0.10f; g = 0.36f; bl = 0.08f; }
        else if (n.contains("Forest"))    { r = 0.20f; g = 0.50f; bl = 0.15f; }
        else if (n.contains("Hills") || n.contains("Extreme")
                || n.contains("Stone"))   { r = 0.40f; g = 0.40f; bl = 0.40f; }
        else if (n.contains("Desert") || n.contains("Mesa")
                || n.contains("Beach"))   { r = 0.72f; g = 0.66f; bl = 0.40f; }
        else if (n.contains("Mushroom"))  { r = 0.62f; g = 0.50f; bl = 0.52f; }
        else if (n.contains("Hell"))      { r = 0.45f; g = 0.20f; bl = 0.12f; }
        else if (n.contains("Sky") || n.contains("End")) { r = 0.47f; g = 0.41f; bl = 0.56f; }
        else if (n.contains("Savanna"))   { r = 0.47f; g = 0.56f; bl = 0.22f; }
        else if (n.contains("Shrubland")) { r = 0.50f; g = 0.44f; bl = 0.28f; }
        else                              { r = 0.43f; g = 0.45f; bl = 0.47f; } // plains/default
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (bl * 255);
    }

    /**
     * Uploads the preview texture if the logic thread has finished a fill batch.
     * Called from the render thread (bindTextures). The GL call happens inside
     * the lock so it never races the in-progress fill. Binds on texture unit 20
     * (the same dedicated unit the shader samples) so the upload never clobbers
     * another unit's binding (e.g. unit 14's foliage colormap).
     */
    public void uploadIfDirty() {
        synchronized (lock) {
            if (!needsUpload || textureId == 0) return;
            ByteBuffer px = previewPixels.duplicate();
            px.position(0);
            px.limit(TEX_SIZE * TEX_SIZE * 4);
            glActiveTexture(GL_TEXTURE20);
            glBindTexture(GL_TEXTURE_2D, textureId);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, TEX_SIZE, TEX_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
            needsUpload = false;
        }
    }

    public int getTextureId() { return textureId; }
    public void setTextureId(int id) { this.textureId = id; }
    public int getTexSize() { return TEX_SIZE; }

    /** Force a full re-bake next update (e.g. after a dimension switch). */
    public void markDirty() {
        synchronized (lock) {
            regionBlocksPerTexel = -1;
            regionBorder = -1f;
        }
    }

    /** Package-private readback for tests: RGBA bytes of one texel. */
    byte[] getTexelBytes(int tx, int tz) {
        synchronized (lock) {
            if (tx < 0 || tz < 0 || tx >= TEX_SIZE || tz >= TEX_SIZE) return new byte[]{0, 0, 0, 0};
            int idx = (tz * TEX_SIZE + tx) * 4;
            return new byte[]{previewPixels.get(idx), previewPixels.get(idx + 1),
                    previewPixels.get(idx + 2), previewPixels.get(idx + 3)};
        }
    }

    /** Package-private test helper: are all texels filled (non-zero alpha) within the region? */
    boolean isFullyFilled() {
        synchronized (lock) {
            return fillDone;
        }
    }
}
