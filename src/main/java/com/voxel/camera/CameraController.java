package com.voxel.camera;

import com.voxel.World;
import com.voxel.Player;
import com.voxel.game.GameContext;
import com.voxel.game.GameContext.ActiveUI;
import com.voxel.game.GameContext.CameraMode;
import com.voxel.utils.BlockDataManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Centralised camera math, raycasting, and collision-resolution logic.
 * Replaces the camera methods that formerly lived in Main.java.
 *
 * Reads its state from Main (yaw, pitch, camera mode, crafting camera state)
 * and the shared GameContext (player, world, block data, crafting cutscene progress).
 */
public class CameraController {
    // Player physics constants — also referenced from Main; kept public so existing code keeps working.
    public static final float PLAYER_HALF_WIDTH = 0.3f;
    public static final float PLAYER_HEIGHT = 1.8f;
    public static final float PLAYER_EYE_HEIGHT = 1.6f;
    public static final float THIRD_PERSON_DISTANCE = 4.0f;
    public static final float THIRD_PERSON_TARGET_HEIGHT = 1.35f;
    public static final float CAMERA_COLLISION_STEP = 0.1f;

    // Crafting table grid layout (relative to texture cells)
    public static final float CT_MARGIN = 4.0f / 16.0f;     // 0.25
    public static final float CT_CELL = 2.0f / 16.0f;       // 0.125
    public static final float CT_GAP = 1.0f / 16.0f;        // 0.0625
    public static final float CT_STEP = CT_CELL + CT_GAP;   // 0.1875
    public static final float CT_HALF_CELL = CT_CELL / 2.0f; // 0.0625

    private final GameContext ctx;
    private final com.voxel.Main main;

    public CameraController(GameContext ctx, com.voxel.Main main) {
        this.ctx = ctx;
        this.main = main;
    }

    /** Forward unit vector based on Main's yaw/pitch fields. */
    public Vector3f getLookDirection() {
        return new Vector3f(
            (float) (Math.cos(Math.toRadians(main.yaw)) * Math.cos(Math.toRadians(main.pitch))),
            (float) Math.sin(Math.toRadians(main.pitch)),
            (float) (Math.sin(Math.toRadians(main.yaw)) * Math.cos(Math.toRadians(main.pitch)))
        ).normalize();
    }

    /** Player position + PLAYER_EYE_HEIGHT. */
    public Vector3f getPlayerEyePosition() {
        Vector3f pos = ctx.player.getPosition();
        return new Vector3f(pos.x, pos.y + PLAYER_EYE_HEIGHT, pos.z);
    }

    /**
     * Active camera position depends on game mode:
     *  1. Crafting cutscene (smoothstep lerp)
     *  2. Crafting table open (fixed target)
     *  3. First person
     *  4. Third-person over-the-shoulder with collision resolution
     */
    public Vector3f getActiveCameraPosition() {
        Vector3f eye = getPlayerEyePosition();

        // Crafting cutscene progress
        if (ctx.craftingCutsceneActive) {
            float t = Math.min(1.0f, ctx.craftingCutsceneTimer / GameContext.CRAFTING_CUTSCENE_DURATION);
            float smoothT = t * t * (3.0f - 2.0f * t);
            Vector3f cam = new Vector3f();
            cam.set(
                ctx.cutsceneCameraStartPos.x + (ctx.cutsceneCameraTargetPos.x - ctx.cutsceneCameraStartPos.x) * smoothT,
                ctx.cutsceneCameraStartPos.y + (ctx.cutsceneCameraTargetPos.y - ctx.cutsceneCameraStartPos.y) * smoothT,
                ctx.cutsceneCameraStartPos.z + (ctx.cutsceneCameraTargetPos.z - ctx.cutsceneCameraStartPos.z) * smoothT
            );
            return cam;
        }

        // Crafting table open (use the cutscene target position we already computed)
        if (ctx.craftingTableOpen) {
            return new Vector3f(ctx.cutsceneCameraTargetPos);
        }

        // First person
        if (main.cameraMode == CameraMode.FIRST_PERSON) return eye;

        // Third person over-the-shoulder
        Vector3f look = getLookDirection();
        Vector3f right = new Vector3f(look).cross(new Vector3f(0, 1, 0)).normalize();
        Vector3f target = ctx.player.getPosition().add(0, THIRD_PERSON_TARGET_HEIGHT, 0, new Vector3f());
        target.add(right.mul(0.6f, new Vector3f()));
        Vector3f desired = new Vector3f(target).sub(new Vector3f(look).mul(THIRD_PERSON_DISTANCE));
        return resolveCameraCollision(target, desired);
    }

    /** Step-based raymarch from origin toward desired, stopping just before a solid voxel. */
    public Vector3f resolveCameraCollision(Vector3f origin, Vector3f desired) {
        Vector3f delta = new Vector3f(desired).sub(origin);
        float length = delta.length();
        if (length <= 0.0001f) return new Vector3f(origin);
        Vector3f dir = delta.div(length);
        Vector3f lastFree = new Vector3f(origin);
        for (float traveled = CAMERA_COLLISION_STEP; traveled <= length; traveled += CAMERA_COLLISION_STEP) {
            Vector3f sample = new Vector3f(origin).fma(traveled, dir);
            if (isSolidCameraSample(sample)) return lastFree;
            lastFree.set(sample);
        }
        return desired;
    }

    /** Returns true if the voxel at sample's floored coords is a "full block". */
    public boolean isSolidCameraSample(Vector3f sample) {
        int v = ctx.world.getVoxel(
            (int) Math.floor(sample.x),
            (int) Math.floor(sample.y),
            (int) Math.floor(sample.z)
        );
        return v > 0 && ctx.blockDataManager.isFullBlock(v);
    }

    /**
     * Perspective raycast from the mouse cursor to identify which slot index (0..8) of the
     * 3x3 crafting table is currently under the cursor while ctx.craftingTableOpen is true.
     * Returns -1 if no slot is hit.
     */
    public int raycastCraftingCell() {
        // Build projection/view matrices using the active camera
        Vector3f pos = getActiveCameraPosition();
        Vector3f dir = getLookDirection();
        Vector3f lookTarget = new Vector3f(pos).add(dir);

        float fovRad = (float) Math.toRadians(70.0);
        float aspect = (float) main.width / main.height;
        Matrix4f proj = new Matrix4f().perspective(fovRad, aspect, 0.1f, 2048.0f);
        Matrix4f view = new Matrix4f().lookAt(pos, lookTarget, new Vector3f(0, 1, 0));

        // Convert mouse pixel → NDC
        float ndcX = (float) ((main.lastMouseX / main.width) * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (main.lastMouseY / main.height) * 2.0);

        // Unproject near + far points → world-space ray
        Matrix4f inv = new Matrix4f(proj).mul(view).invert();
        Vector3f nearW = new Vector3f(ndcX, ndcY, -1.0f).mulProject(inv);
        Vector3f farW = new Vector3f(ndcX, ndcY, 1.0f).mulProject(inv);
        Vector3f rayOrigin = new Vector3f(nearW);
        Vector3f rayDir = new Vector3f(farW).sub(nearW).normalize();

        // The crafting table is one block tall; intersect with top of the block at y = tableY + 1
        int tableX = ctx.craftingTableBlockX;
        int tableY = ctx.craftingTableBlockY;
        int tableZ = ctx.craftingTableBlockZ;
        float topY = tableY + 1.0f;
        if (Math.abs(rayDir.y) < 1e-6f) return -1;
        float t = (topY - rayOrigin.y) / rayDir.y;
        if (t <= 0) return -1;
        float hitX = rayOrigin.x + t * rayDir.x;
        float hitZ = rayOrigin.z + t * rayDir.z;

        // Local UV on table top face (origin = top-front-left)
        float cellU = (hitX - tableX) / 1.0f;
        float cellV = (hitZ - tableZ) / 1.0f;
        if (cellU < 0 || cellU > 1 || cellV < 0 || cellV > 1) return -1;

        // Map to 3x3 grid (texture layout: margin 4/16, cell 2/16, gap 1/16, step 0.1875)
        float uRel = cellU - CT_MARGIN;
        float vRel = cellV - CT_MARGIN;
        int col = (int) Math.floor(uRel / CT_STEP);
        int row = (int) Math.floor(vRel / CT_STEP);
        if (col < 0 || col > 2 || row < 0 || row > 2) return -1;
        // Center slot is the result slot (index 4) — return -1 to prevent direct placement
        int idx = row * 3 + col;
        if (idx == 4) return -1;
        return idx;
    }

    /**
     * Marches the look ray by step until hitting a non-air voxel, or reaching maxDist.
     * Returns int[]{cx,cy,cz, lastX,lastY,lastZ} — last-known empty block for placing.
     * Returns null if nothing was hit.
     */
    public int[] raycastBlock(float maxDist) {
        Vector3f dir = getLookDirection();
        Vector3f pos = getActiveCameraPosition();
        float step = 0.05f;
        int lastX = (int) Math.floor(pos.x);
        int lastY = (int) Math.floor(pos.y);
        int lastZ = (int) Math.floor(pos.z);
        Vector3f cur = new Vector3f(pos);
        for (float d = 0; d <= maxDist; d += step) {
            cur.set(pos).fma(d, dir);
            int cx = (int) Math.floor(cur.x);
            int cy = (int) Math.floor(cur.y);
            int cz = (int) Math.floor(cur.z);
            if (cx != lastX || cy != lastY || cz != lastZ) {
                int blockId = ctx.world.getVoxel(cx, cy, cz);
                if (blockId != 0) {
                    return new int[]{cx, cy, cz, lastX, lastY, lastZ};
                }
                lastX = cx; lastY = cy; lastZ = cz;
            }
        }
        return null;
    }
}
