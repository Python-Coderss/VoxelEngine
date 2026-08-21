package com.voxel.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.voxel.Main;
import com.voxel.entity.Entity;
import com.voxel.entity.EntityManager;
import com.voxel.entity.VillagerEntity;

/**
 * MCSM (Story Mode)-style interaction billboards: floating "click here"
 * markers that appear over interactable objects within reach, so the player
 * always knows where to point the cursor to do something.
 *
 * Markers are screen-space UI quads (drawn by HudUI). This class only
 * detects interactables, projects their world position to screen space, and
 * publishes the result (a list of {screenX, screenY, label, highlighted}).
 * Detection runs on the render thread but is throttled to ~8 Hz so the O(n)
 * entity scan never tanks the frame rate.
 *
 * Interactable set:
 *  - Functional blocks: crafting table (115), furnace (116/117), chest (118),
 *    nether portal (19), aether portal (106). Scanned by a small raycast
 *    grid around the player rather than a full world walk.
 *  - Villager entities within range (the MCSM "talk to" targets).
 */
public class InteractionBillboardSystem {
    private final GameContext ctx;
    private final Main main;

    // Reusable output buffer — HUD reads this each frame. Each entry is
    // {screenX, screenY, labelAlpha, 0}. Using a flat array avoids per-frame
    // allocation and keeps the publish path allocation-free.
    private float[] markers = new float[MAX_MARKERS * 4];
    // Last scan's world-space anchors {x, y, z} per marker — markers[] holds
    // screen-space results after projection and must never be fed back in.
    private float[] markerWorld = new float[MAX_MARKERS * 3];
    private String[] labels = new String[MAX_MARKERS];
    /** Display name of the interactable (block/structure/mob), e.g. "Crafting Table". */
    private String[] names = new String[MAX_MARKERS];
    /** Actions the player can take, e.g. {"Craft"}. Usually exactly one. */
    private String[][] actions = new String[MAX_MARKERS][];
    private boolean[] highlighted = new boolean[MAX_MARKERS];
    private int markerCount = 0;

    private static final int MAX_MARKERS = 16;
    private static final float REACH = 6.0f;          // max distance for a billboard
    private static final double SCAN_INTERVAL = 0.12; // throttle (seconds)
    private double lastScanTime = -1.0;

    // Interactable block IDs (see BlockInteraction / PortalSystem).
    private static final int B_CRAFTING_TABLE = 115;
    private static final int B_FURNACE = 116;
    private static final int B_FURNACE_ON = 117;
    private static final int B_CHEST = 118;
    private static final int B_PORTAL_NETHER = 19;
    private static final int B_PORTAL_AETHER = 106;

    public InteractionBillboardSystem(GameContext ctx, Main main) {
        this.ctx = ctx;
        this.main = main;
    }

    /** Number of visible billboards this frame. */
    public int getMarkerCount() { return markerCount; }
    public float getMarkerX(int i) { return markers[i * 4]; }
    public float getMarkerY(int i) { return markers[i * 4 + 1]; }
    public float getMarkerAlpha(int i) { return markers[i * 4 + 2]; }
    public String getMarkerLabel(int i) { return labels[i]; }
    public String getMarkerName(int i) { return names[i]; }
    public int getMarkerActionCount(int i) { return actions[i] == null ? 0 : actions[i].length; }
    public String getMarkerAction(int i, int j) { return actions[i][j]; }
    public boolean isMarkerHighlighted(int i) { return highlighted[i]; }

    /**
     * Per-frame update. Re-scans at most SCAN_INTERVAL seconds; otherwise
     * re-projects the last scan's world positions (cheap) so markers track
     * camera motion smoothly between scans.
     */
    public void update(double time) {
        boolean active = main.pointAndClickMode
                && ctx != null && ctx.player != null && !ctx.player.isDead()
                && ctx.menuScreen == GameContext.MenuScreen.IN_GAME
                && !ctx.initializing && ctx.world != null && ctx.entityManager != null
                && !main.inventoryOpen && !main.commandMode && !ctx.mapOpen && !ctx.pauseMenuOpen
                && (ctx.cinematic == null || !ctx.cinematic.cameraActive());
        if (!active) {
            if (markerCount != 0) markerCount = 0;
            return;
        }

        if (lastScanTime < 0 || time - lastScanTime >= SCAN_INTERVAL) {
            lastScanTime = time;
            scanInteractables();
        }
        // Always re-project (camera moves between scans).
        projectMarkers(time);
    }

    // ── Detection ────────────────────────────────────────────────────────────

    /** Blocks already claimed by a merged structure during the current scan. */
    private final java.util.HashSet<Long> claimedBlocks = new java.util.HashSet<>();

    private static long packKey(int x, int y, int z) {
        return ((long)(x & 0x3FFFFF) << 42) | ((long)(z & 0x3FFFFF) << 21) | (y & 0x1FFFFF);
    }

    private void scanInteractables() {
        markerCount = 0;
        if (ctx.world == null || ctx.player == null) return;
        Vector3f p = ctx.player.getPosition();
        claimedBlocks.clear();

        // --- Functional blocks: scan a small voxel box around the player. ---
        // A full world walk is infeasible; a ±5 radius box covers every block
        // the player could plausibly click (reach is ~6 blocks) at low cost.
        // Connected same-type blocks (e.g. a portal frame's interior columns)
        // are merged into ONE structure marker at the centroid instead of one
        // marker per block.
        int r = 5;
        int ox = (int) Math.floor(p.x), oy = (int) Math.floor(p.y), oz = (int) Math.floor(p.z);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (markerCount >= MAX_MARKERS) return;
                    int bx = ox + dx, by = oy + dy, bz = oz + dz;
                    int id = ctx.world.getVoxel(bx, by, bz);
                    if (!isInteractableBlock(id)) continue;
                    if (!claimedBlocks.add(packKey(bx, by, bz))) continue;
                    // Flood-fill the whole connected structure of this type.
                    java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
                    queue.add(new long[]{bx, by, bz});
                    float sumX = 0, sumY = 0, sumZ = 0;
                    int n = 0;
                    while (!queue.isEmpty()) {
                        long[] c = queue.poll();
                        int cx = (int) c[0], cy = (int) c[1], cz = (int) c[2];
                        sumX += cx; sumY += cy; sumZ += cz;
                        n++;
                        int[][] nb = {{cx + 1, cy, cz}, {cx - 1, cy, cz},
                                      {cx, cy + 1, cz}, {cx, cy - 1, cz},
                                      {cx, cy, cz + 1}, {cx, cy, cz - 1}};
                        for (int[] q : nb) {
                            if (Math.abs(q[0] - ox) > r || q[1] < oy - 2 || q[1] > oy + 3
                                    || Math.abs(q[2] - oz) > r) continue;
                            if (ctx.world.getVoxel(q[0], q[1], q[2]) != id) continue;
                            if (!claimedBlocks.add(packKey(q[0], q[1], q[2]))) continue;
                            queue.add(new long[]{q[0], q[1], q[2]});
                        }
                    }
                    float wx = sumX / n + 0.5f, wy = sumY / n + 0.5f, wz = sumZ / n + 0.5f;
                    float distSq = distanceSquared(p.x, p.y + 1.6f, p.z, wx, wy, wz);
                    if (distSq > REACH * REACH) continue;
                    addMarker(wx, wy + 0.8f, wz, blockName(id), blockAction(id)); // hover slightly above
                }
            }
        }

        // --- Villager entities: the MCSM "talk to" targets. ---
        EntityManager em = ctx.entityManager;
        if (em == null) return;
        java.util.List<Entity> snap = em.getEntitiesSnapshot();
        for (Entity e : snap) {
            if (markerCount >= MAX_MARKERS) break;
            if (!(e instanceof VillagerEntity)) continue;
            Vector3f ep = e.getPosition();
            float distSq = distanceSquared(p.x, p.y + 1.6f, p.z, ep.x, ep.y, ep.z);
            if (distSq > REACH * REACH) continue;
            addMarker(ep.x, ep.y + 2.0f, ep.z, "Villager", "Talk"); // above villager head
        }
    }

    private void addMarker(float wx, float wy, float wz, String name, String action) {
        int i = markerCount;
        if (i >= MAX_MARKERS) return;
        markers[i * 4] = 0f;       // filled by projectMarkers (screen space)
        markerWorld[i * 3] = wx;
        markerWorld[i * 3 + 1] = wy;
        markerWorld[i * 3 + 2] = wz;
        markers[i * 4 + 1] = 0f;
        markers[i * 4 + 2] = 0f;
        names[i] = name;
        actions[i] = new String[]{action};
        labels[i] = action;
        highlighted[i] = false;
        markerCount++;
    }

    private static boolean isInteractableBlock(int id) {
        return id == B_CRAFTING_TABLE || id == B_FURNACE || id == B_FURNACE_ON
            || id == B_CHEST || id == B_PORTAL_NETHER || id == B_PORTAL_AETHER;
    }

    /** Display name of an interactable block (shown in the MCSM prompt). */
    private static String blockName(int id) {
        switch (id) {
            case 115: return "Crafting Table";
            case 116:
            case 117: return "Furnace";
            case 118: return "Chest";
            case 19:  return "Nether Portal";
            case 106: return "Aether Portal";
            default: return "";
        }
    }

    /** The single action a block supports (the verb shown under its name). */
    private static String blockAction(int id) {
        switch (id) {
            case 115: return "Craft";
            case 116:
            case 117: return "Smelt";
            case 118: return "Open";
            case 19:
            case 106: return "Enter";
            default: return "";
        }
    }

    // ── Projection (world → screen) ───────────────────────────────────────────

    private void projectMarkers(double time) {
        if (markerCount == 0) return;
        // Same view/projection as the cursor ray in Main.updatePointAndClick.
        Vector3f camPos = main.cameraController.getActiveCameraPosition();
        Vector3f dir = main.getLookDirection();
        float fovRad = (float) Math.toRadians(70.0);
        float aspect = (float) main.width / (float) main.height;
        Matrix4f proj = new Matrix4f().perspective(fovRad, aspect, 0.1f, 2048.0f);
        Matrix4f view = new Matrix4f().lookAt(camPos, new Vector3f(camPos).add(dir), new Vector3f(0, 1, 0));
        Matrix4f vp = new Matrix4f(proj).mul(view);

        // The cursor's world-space ray, to decide whether a marker is the one
        // currently under the cursor (highlighted vs. default).
        float[] cursorRay = ctx.cursorRayOverride;
        Vector3f rayO = cursorRay != null ? new Vector3f(cursorRay[0], cursorRay[1], cursorRay[2]) : null;
        Vector3f rayD = cursorRay != null ? new Vector3f(cursorRay[3], cursorRay[4], cursorRay[5]) : null;

        Vector4f clip = new Vector4f();
        Vector3f worldPos = new Vector3f();
        for (int i = 0; i < markerCount; i++) {
            float wx = markerWorld[i * 3], wy = markerWorld[i * 3 + 1], wz = markerWorld[i * 3 + 2];
            worldPos.set(wx, wy, wz);
            clip.set(worldPos.x, worldPos.y, worldPos.z, 1f);
            vp.transform(clip);
            if (clip.w <= 0.01f) {
                // Behind camera or clipped — hide.
                markers[i * 4 + 2] = 0f;
                highlighted[i] = false;
                continue;
            }
            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            float screenX = (ndcX * 0.5f + 0.5f) * main.width;
            float screenY = (1.0f - (ndcY * 0.5f + 0.5f)) * main.height;
            markers[i * 4] = screenX;
            markers[i * 4 + 1] = screenY;
            markers[i * 4 + 3] = 0f;

            // Distance-based alpha: fade out near the reach edge.
            float distToCam = (float) Math.sqrt(distanceSquared(camPos.x, camPos.y, camPos.z, wx, wy, wz));
            float a = 1.0f - Math.max(0f, (distToCam - (REACH - 1.5f)) / 1.5f);
            a = Math.max(0f, Math.min(1f, a));
            markers[i * 4 + 2] = a;

            // Highlight if the cursor ray passes close to this marker's anchor.
            highlighted[i] = rayO != null && rayD != null
                && pointRayDistance(rayO, rayD, worldPos) < 0.6f;
        }
    }

    /** Shortest distance from point p to the ray (origin o, normalized dir d). */
    private static float pointRayDistance(Vector3f o, Vector3f d, Vector3f p) {
        Vector3f op = new Vector3f(p).sub(o);
        float t = op.dot(d);
        if (t < 0) return Float.MAX_VALUE; // behind the camera
        Vector3f closest = new Vector3f(d).mul(t).add(o);
        return closest.distance(p);
    }

    private static float distanceSquared(float ax, float ay, float az, float bx, float by, float bz) {
        float dx = ax - bx, dy = ay - by, dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }
}
