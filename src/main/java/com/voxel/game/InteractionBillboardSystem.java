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
 *    villager TV (274), nether portal (19/128), aether portal (106/127).
 *    Scanned by a small raycast grid around the player rather than a full
 *    world walk.
 *  - Talking entities within range: villagers and the ancient builders.
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
    /** Markers hidden by last projection: behind geometry / outside the view. */
    private int culledOccluded = 0;
    private int culledOffscreen = 0;
    /** Keyboard-selected marker (arrow-key navigation), or -1 when none. */
    private int selectedMarker = -1;

    private static final int MAX_MARKERS = 16;
    private static final float REACH = 6.0f;          // max distance for a billboard
    private static final double SCAN_INTERVAL = 0.12; // throttle (seconds)
    private double lastScanTime = -1.0;

    /**
     * Unattended HUD capture: {@code VOXEL_PAC_PROBE} publishes a fixed prompt
     * layout at known screen positions instead of scanning the world, so a
     * screenshot always contains a target under the cursor, a multi-action
     * prompt and idle prompts. Inert otherwise.
     */
    private static final boolean PROBE_LAYOUT =
            System.getenv("VOXEL_PAC_PROBE") != null;
    /**
     * A/B switch for the occlusion test ({@code VOXEL_PAC_NO_CULL}): a capture
     * with and without culling from the same camera proves which prompts a
     * wall was hiding. Inert otherwise.
     */
    private static final boolean PROBE_NO_CULL =
            System.getenv("VOXEL_PAC_NO_CULL") != null;

    // Interactable block IDs (see BlockInteraction / PortalSystem).
    private static final int B_CRAFTING_TABLE = 115;
    private static final int B_FURNACE = 116;
    private static final int B_FURNACE_ON = 117;
    private static final int B_CHEST = 118;
    private static final int B_PORTAL_NETHER = 19;
    private static final int B_PORTAL_NETHER_EW = 128;
    private static final int B_PORTAL_AETHER = 106;
    private static final int B_PORTAL_AETHER_EW = 127;
    private static final int B_VILLAGER_TV = 274;
    // Create-mod machines with a real right-click handler in tryUseTarget.
    private static final int B_HAND_CRANK = 404;
    private static final int B_WINDMILL_BEARING = 405;
    private static final int B_ITEM_VAULT = 414;
    // The Sift (§Sift): the sifting block and the rift portal.
    private static final int B_SIFT_BED = 932;
    private static final int B_SIFT_PORTAL = 934;
    // The Ancient City's tuning stone (§Melody rift).
    private static final int B_NOTE_BLOCK = 937;

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

    /** Markers hidden by the last projection pass because a wall intervened. */
    public int getCulledOccludedCount() { return culledOccluded; }
    /** Markers hidden by the last projection pass because they were off-screen. */
    public int getCulledOffscreenCount() { return culledOffscreen; }

    /** Index of the marker currently under the centre cursor, or -1. */
    public int getHighlightedMarker() {
        for (int i = 0; i < markerCount; i++) if (highlighted[i]) return i;
        return -1;
    }
    public boolean hasHighlightedMarker() { return getHighlightedMarker() >= 0; }

    /** Index of the keyboard-selected marker, or -1 when none is selected. */
    public int getSelectedMarker() { return selectedMarker; }

    /** Clears the keyboard selection so the cursor hover takes over again. */
    public void clearSelection() { selectedMarker = -1; }

    /**
     * Steps the keyboard selection to the next ({@code dir > 0}) or previous
     * ({@code dir < 0}) on-screen prompt, wrapping around. With nothing
     * selected the first step lands on the first (or last) visible marker.
     * Returns the selected index, or -1 when no marker is selectable.
     */
    public int selectNext(int dir) {
        boolean[] visible = new boolean[markerCount];
        for (int i = 0; i < markerCount; i++) visible[i] = markers[i * 4 + 2] > 0.01f;
        int idx = nextSelectable(selectedMarker, dir, markerCount, visible);
        selectedMarker = idx;
        return idx;
    }

    /**
     * Pure selection step: the next selectable index in {@code dir} from
     * {@code current}, wrapping modulo {@code count} and skipping markers whose
     * {@code visible} flag is false. Returns -1 when nothing is selectable.
     */
    static int nextSelectable(int current, int dir, int count, boolean[] visible) {
        if (count <= 0) return -1;
        if (dir == 0) dir = 1;
        int start = current >= 0 ? current : (dir > 0 ? -1 : 0);
        for (int step = 1; step <= count; step++) {
            int idx = Math.floorMod(start + dir * step, count);
            if (visible == null || idx >= visible.length || visible[idx]) return idx;
        }
        return -1;
    }

    // Per-marker source: block cell for functional-block markers (the
    // interactable itself, not the floating anchor), or an entity id with
    // blockX < 0 for entity markers ("Talk").
    private final int[] markerBlockX = new int[MAX_MARKERS];
    private final int[] markerBlockY = new int[MAX_MARKERS];
    private final int[] markerBlockZ = new int[MAX_MARKERS];
    private final int[] markerEntityId = new int[MAX_MARKERS];

    public int getMarkerBlockX(int i) { return markerBlockX[i]; }
    public int getMarkerBlockY(int i) { return markerBlockY[i]; }
    public int getMarkerBlockZ(int i) { return markerBlockZ[i]; }
    public int getMarkerEntityId(int i) { return markerEntityId[i]; }

    /**
     * Per-frame update. Re-scans at most SCAN_INTERVAL seconds; otherwise
     * re-projects the last scan's world positions (cheap) so markers track
     * camera motion smoothly between scans.
     */
    public void update(double time) {
        if (PROBE_LAYOUT) {
            publishProbeLayout(main.width, main.height);
            return;
        }
        boolean active = main.pointAndClickMode
                && ctx != null && ctx.player != null && !ctx.player.isDead()
                && ctx.menuScreen == GameContext.MenuScreen.IN_GAME
                && !ctx.initializing && ctx.world != null && ctx.entityManager != null
                && !main.inventoryOpen && !main.commandMode && !ctx.mapOpen && !ctx.pauseMenuOpen
                && (ctx.cinematic == null || !ctx.cinematic.cameraActive());
        if (!active) {
            if (markerCount != 0) markerCount = 0;
            selectedMarker = -1;
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

    /**
     * Fixed screen-space prompt set for {@code VOXEL_PAC_PROBE}: one hovered
     * multi-action target at the reticle, plus idle prompts around it. Screen
     * coordinates are already final, so the normal projection pass is skipped.
     */
    private void publishProbeLayout(int w, int h) {
        markerCount = 0;
        selectedMarker = -1;
        culledOccluded = 0;
        culledOffscreen = 0;
        addProbeMarker(0.50f, 0.46f, "Mason the Villager",
                new String[]{"Talk", "Trade"}, true, w, h);
        addProbeMarker(0.28f, 0.34f, "Crafting Table", new String[]{"Craft"}, false, w, h);
        addProbeMarker(0.74f, 0.33f, "Chest", new String[]{"Open"}, false, w, h);
        addProbeMarker(0.68f, 0.68f, "Nether Portal", new String[]{"Enter"}, false, w, h);
        addProbeMarker(0.24f, 0.70f, "Furnace", new String[]{"Smelt"}, false, w, h);
    }

    private void addProbeMarker(float fx, float fy, String name, String[] actionList,
                                boolean highlightedMarker, int w, int h) {
        int i = markerCount;
        if (i >= MAX_MARKERS) return;
        markers[i * 4] = fx * w;
        markers[i * 4 + 1] = fy * h;
        markers[i * 4 + 2] = 1f;
        markers[i * 4 + 3] = 0f;
        names[i] = name;
        actions[i] = actionList.clone();
        labels[i] = actions[i][0];
        highlighted[i] = highlightedMarker;
        markerBlockX[i] = -1;
        markerBlockY[i] = -1;
        markerBlockZ[i] = -1;
        markerEntityId[i] = -1;
        markerCount++;
    }

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
                    addMarker(wx, wy + 0.8f, wz, blockName(id), blockAction(id), bx, by, bz, -1); // hover slightly above
                }
            }
        }

        // --- Villager entities: the MCSM "talk to" targets. ---
        EntityManager em = ctx.entityManager;
        if (em == null) return;
        java.util.List<Entity> snap = em.getEntitiesSnapshot();
        for (Entity e : snap) {
            if (markerCount >= MAX_MARKERS) break;
            Vector3f ep = e.getPosition();
            float distSq = distanceSquared(p.x, p.y + 1.6f, p.z, ep.x, ep.y, ep.z);
            if (distSq > REACH * REACH) continue;
            if (e instanceof VillagerEntity) {
                VillagerEntity v = (VillagerEntity) e;
                // A villager with a trade table supports a second action; the
                // HUD lists both under the name (MCSM multi-choice prompt).
                String[] acts = com.voxel.game.VillagerTrades.trades(v.getProfession())
                        ? new String[]{"Talk", "Trade"} : new String[]{"Talk"};
                String display = v.aiDisplayName();
                addMarker(ep.x, ep.y + 2.0f, ep.z,
                        display == null || display.trim().isEmpty() ? "Villager" : display,
                        acts, -1, -1, -1, e.id);
            } else if (e instanceof com.voxel.entity.AncientBuilderEntity) {
                com.voxel.entity.AncientBuilderEntity builder =
                        (com.voxel.entity.AncientBuilderEntity) e;
                addMarker(ep.x, ep.y + 2.2f, ep.z, builder.getDisplayName(), "Talk",
                        -1, -1, -1, e.id);
            }
        }
    }

    private void addMarker(float wx, float wy, float wz, String name, String action,
                           int bx, int by, int bz, int entityId) {
        addMarker(wx, wy, wz, name, new String[]{action}, bx, by, bz, entityId);
    }

    private void addMarker(float wx, float wy, float wz, String name, String[] actionList,
                           int bx, int by, int bz, int entityId) {
        int i = markerCount;
        if (i >= MAX_MARKERS) return;
        markers[i * 4] = 0f;       // filled by projectMarkers (screen space)
        markerWorld[i * 3] = wx;
        markerWorld[i * 3 + 1] = wy;
        markerWorld[i * 3 + 2] = wz;
        markers[i * 4 + 1] = 0f;
        markers[i * 4 + 2] = 0f;
        names[i] = name;
        actions[i] = (actionList == null || actionList.length == 0)
                ? new String[]{""} : actionList.clone();
        labels[i] = actions[i][0];
        highlighted[i] = false;
        markerBlockX[i] = bx;
        markerBlockY[i] = by;
        markerBlockZ[i] = bz;
        markerEntityId[i] = entityId;
        markerCount++;
    }

    public static boolean isInteractableBlock(int id) {
        return id == B_CRAFTING_TABLE || id == B_FURNACE || id == B_FURNACE_ON
            || id == B_CHEST || id == B_VILLAGER_TV
            || id == B_HAND_CRANK || id == B_WINDMILL_BEARING || id == B_ITEM_VAULT
            || id == B_SIFT_BED || id == B_SIFT_PORTAL
            || id == B_NOTE_BLOCK
            || id == B_PORTAL_NETHER || id == B_PORTAL_AETHER
            || id == B_PORTAL_NETHER_EW || id == B_PORTAL_AETHER_EW
            // Levers/buttons are runtime-configured; before configure() this is
            // false, which keeps tests and early startup safe.
            || com.voxel.game.RedstoneSwitches.isLever(id)
            || com.voxel.game.RedstoneSwitches.isButton(id);
    }

    /**
     * The one-line reticle prompt for a target: {@code CLICK   Craft   ·   
     * Crafting Table} for a single action, or the key-hinted list {@code
     * Villager    [1] Talk    [2] Trade} when there is a choice. Pure, so the
     * wording is unit-tested without a GL context.
     */
    public static String promptLine(String name, String[] actions) {
        String target = name == null ? "" : name;
        int n = actions == null ? 0 : actions.length;
        if (n <= 1) {
            String verb = n == 0 || actions[0] == null ? "" : actions[0].trim();
            if (verb.isEmpty()) return target;
            return target.isEmpty() ? "CLICK   " + verb
                    : "CLICK   " + verb + "   \u00b7   " + target;
        }
        StringBuilder line = new StringBuilder(target);
        for (int j = 0; j < n; j++) {
            line.append(line.length() == 0 ? "" : "    ")
                .append('[').append(j + 1).append("] ")
                .append(actions[j] == null ? "" : actions[j]);
        }
        return line.toString();
    }

    /** Display name of an interactable block (shown in the MCSM prompt). */
    static String blockName(int id) {
        switch (id) {
            case 115: return "Crafting Table";
            case 116:
            case 117: return "Furnace";
            case 118: return "Chest";
            case 274: return "Villager TV";
            case 404: return "Hand Crank";
            case 405: return "Windmill Bearing";
            case 414: return "Item Vault";
            case 932: return "Sift Bed";
            case 934: return "Sift Rift";
            case 937: return "Tuning Stone";
            case 19:
            case 128: return "Nether Portal";
            case 106:
            case 127: return "Aether Portal";
            default:
                if (com.voxel.game.RedstoneSwitches.isLever(id)) return "Lever";
                if (com.voxel.game.RedstoneSwitches.isButton(id)) return "Button";
                return "";
        }
    }

    /** The single action a block supports (the verb shown under its name). */
    static String blockAction(int id) {
        switch (id) {
            case 115: return "Craft";
            case 116:
            case 117: return "Smelt";
            case 118: return "Open";
            case 274: return "Watch";
            case 404: return "Crank";
            case 405: return "Inspect";
            case 414: return "Open";
            case 932: return "Sift";
            case 934: return "Enter";
            case 937: return "Play";
            case 19:
            case 106:
            case 127:
            case 128: return "Enter";
            default:
                if (com.voxel.game.RedstoneSwitches.isLever(id)) return "Flip";
                if (com.voxel.game.RedstoneSwitches.isButton(id)) return "Press";
                return "";
        }
    }

    // ── Line of sight (raycast culling) ───────────────────────────────────────

    /** Minimal voxel access for the occlusion walk, so it is testable. */
    public interface BlockOccluder {
        /** Block id at a cell, or 0 when the cell is outside the loaded world. */
        int voxelAt(int x, int y, int z);

        /** True when the id blocks sight completely (a full opaque block). */
        boolean isOpaque(int blockId);
    }

    private final BlockOccluder worldOccluder = new BlockOccluder() {
        @Override
        public int voxelAt(int x, int y, int z) {
            return ctx == null || ctx.world == null ? 0 : ctx.world.getVoxel(x, y, z);
        }

        @Override
        public boolean isOpaque(int blockId) {
            if (blockId == 0) return false;
            // Full blocks only: glass, leaves, grass, torches and fluids must
            // not hide a prompt, because the player can still click through
            // them. isFullBlockFast is the same flag the mesher treats as solid.
            return ctx != null && ctx.blockDataManager != null
                    && ctx.blockDataManager.isFullBlockFast(blockId);
        }
    };

    /**
     * Amanatides &amp; Woo walk from a camera to a prompt anchor, stopping at the
     * first opaque cell: {@code true} when the target is behind a wall. The
     * prompt must then be hidden — a floating "click here" card glowing through
     * bedrock tells the player there is something to click when there is not.
     *
     * <p>Pure and allocation-free so the culling is unit-tested with a synthetic
     * world. The anchor's own cell is never tested (the walk stops just short of
     * it), so a marker anchored on a block face is not occluded by its block.</p>
     */
    public static boolean segmentOccluded(float ax, float ay, float az,
                                          float bx, float by, float bz,
                                          BlockOccluder world) {
        if (world == null) return false;
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        double length = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
        if (length < 1e-4) return false;
        float nx = (float) (dx / length);
        float ny = (float) (dy / length);
        float nz = (float) (dz / length);
        float reach = (float) length - 0.05f; // stay out of the anchor's cell

        int x = (int) Math.floor(ax), y = (int) Math.floor(ay), z = (int) Math.floor(az);
        int stepX = nx > 0f ? 1 : -1;
        int stepY = ny > 0f ? 1 : -1;
        int stepZ = nz > 0f ? 1 : -1;
        float tDeltaX = nx == 0f ? Float.MAX_VALUE : Math.abs(1f / nx);
        float tDeltaY = ny == 0f ? Float.MAX_VALUE : Math.abs(1f / ny);
        float tDeltaZ = nz == 0f ? Float.MAX_VALUE : Math.abs(1f / nz);
        float tMaxX = nx == 0f ? Float.MAX_VALUE
                : ((nx > 0f ? (x + 1 - ax) : (ax - x)) / Math.abs(nx));
        float tMaxY = ny == 0f ? Float.MAX_VALUE
                : ((ny > 0f ? (y + 1 - ay) : (ay - y)) / Math.abs(ny));
        float tMaxZ = nz == 0f ? Float.MAX_VALUE
                : ((nz > 0f ? (z + 1 - az) : (az - z)) / Math.abs(nz));

        // Bounded walk: each step crosses exactly one cell boundary, and a
        // diagonal ray can cross up to three cells per unit of length, so the
        // guard scales with that worst case (it exists only to rule out an
        // infinite loop, not to limit the search).
        int guard = (int) (length * 3f) + 4;
        for (int step = 0; step < guard; step++) {
            float t;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX; x += stepX; tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY; y += stepY; tMaxY += tDeltaY;
            } else {
                t = tMaxZ; z += stepZ; tMaxZ += tDeltaZ;
            }
            if (t > reach) return false;
            if (world.isOpaque(world.voxelAt(x, y, z))) return true;
        }
        return false;
    }

    // ── Projection (world → screen) ───────────────────────────────────────────

    private void projectMarkers(double time) {
        if (markerCount == 0) return;
        if (selectedMarker >= markerCount) selectedMarker = -1;
        // Same view/projection as the cursor ray in Main.updatePointAndClick.
        Vector3f camPos = main.cameraController.getActiveCameraPosition();
        Vector3f dir = main.getLookDirection();
        // Canonical lens: vertical FOV = 2*atan(tanHalfFov), matching the shader.
        float tanHalfFov = main.cameraController.getTanHalfFov();
        float fovRad = 2.0f * (float) Math.atan(tanHalfFov);
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
        // Only the nearest marker under the cursor is highlighted; without
        // this, overlapping prompts all lit up and the HUD could not say which
        // one a click would use.
        int bestHover = -1;
        float bestHoverDistance = Float.MAX_VALUE;
        culledOccluded = 0;
        culledOffscreen = 0;
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
            // Off-screen: hide instead of letting the HUD clamp the card onto
            // the nearest screen edge, where it would point at nothing.
            if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) {
                markers[i * 4 + 2] = 0f;
                highlighted[i] = false;
                culledOffscreen++;
                continue;
            }
            float screenX = (ndcX * 0.5f + 0.5f) * main.width;
            float screenY = (1.0f - (ndcY * 0.5f + 0.5f)) * main.height;
            markers[i * 4] = screenX;
            markers[i * 4 + 1] = screenY;
            markers[i * 4 + 3] = 0f;

            // Raycast culling: a prompt behind a wall is a lie. Walk the voxels
            // between the camera and the anchor and drop the marker when a full
            // block intervenes (glass and leaves stay transparent).
            if (!PROBE_NO_CULL
                    && segmentOccluded(camPos.x, camPos.y, camPos.z, wx, wy, wz, worldOccluder)) {
                markers[i * 4 + 2] = 0f;
                highlighted[i] = false;
                culledOccluded++;
                continue;
            }

            // Distance-based alpha: fade out near the reach edge.
            float distToCam = (float) Math.sqrt(distanceSquared(camPos.x, camPos.y, camPos.z, wx, wy, wz));
            float a = 1.0f - Math.max(0f, (distToCam - (REACH - 1.5f)) / 1.5f);
            a = Math.max(0f, Math.min(1f, a));
            markers[i * 4 + 2] = a;

            // A keyboard selection owns the highlight; with none, the cursor
            // ray passing close to this marker's anchor drives it.
            if (selectedMarker < 0 && rayO != null && rayD != null) {
                float distance = pointRayDistance(rayO, rayD, worldPos);
                if (distance < 0.6f && distance < bestHoverDistance) {
                    bestHoverDistance = distance;
                    bestHover = i;
                }
            }
            highlighted[i] = selectedMarker >= 0 && i == selectedMarker;
        }
        if (selectedMarker < 0 && bestHover >= 0) {
            highlighted[bestHover] = true;
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
