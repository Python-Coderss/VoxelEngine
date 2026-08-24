package com.voxel.game;

import org.joml.Vector3f;

import com.voxel.world.DimensionType;

/**
 * Portal detection, activation (lighting), and teleportation between dimensions.
 */
public class PortalSystem {
    // Portals come in two orientations, matching the vanilla blockstate
    // convention (axis=x walls show their north/south faces, axis=z walls
    // show east/west): a frame spanning X takes the *_ns block, a frame
    // spanning Z takes the *_ew block.
    private static final int PORTAL_NETHER = 19;      // nether portal, ns faces
    private static final int PORTAL_NETHER_EW = 128;  // nether portal, ew faces
    private static final int PORTAL_AETHER = 106;     // aether portal, ns faces
    private static final int PORTAL_AETHER_EW = 127;  // aether portal, ew faces

    private final GameContext ctx;
    private final BlockInteraction blockInteraction;

    public PortalSystem(GameContext ctx, BlockInteraction blockInteraction) {
        this.ctx = ctx;
        this.blockInteraction = blockInteraction;
    }

    private static boolean isNetherPortal(int id) {
        return id == PORTAL_NETHER || id == PORTAL_NETHER_EW;
    }

    private static boolean isAetherPortal(int id) {
        return id == PORTAL_AETHER || id == PORTAL_AETHER_EW;
    }

    public void checkTeleport() {
        if (ctx.player.isDead()) return;
        Vector3f pos = ctx.player.getPosition();
        int px = (int) Math.floor(pos.x), py = (int) Math.floor(pos.y), pz = (int) Math.floor(pos.z);
        int vFeet = ctx.world.getVoxel(px, py, pz);
        int vBody = ctx.world.getVoxel(px, py + 1, pz);
        int voxel = isNetherPortal(vFeet) || isAetherPortal(vFeet) ? vFeet :
                    isNetherPortal(vBody) || isAetherPortal(vBody) ? vBody : 0;
        if (voxel == 0) return;

        double now = System.currentTimeMillis() / 1000.0;
        if (now - ctx.lastPortalTeleportTime < 1.0) return;

        DimensionType target;
        if (ctx.activeDimension == DimensionType.PORTAL_HALL) {
            // Portal Hall endpoints are laid out west-to-east: Nether, Aether,
            // End, and Overworld. Their voxel IDs intentionally reuse the normal
            // portal textures, so the hall coordinate selects the destination.
            if (px < -14) target = DimensionType.NETHER;
            else if (px < 0) target = DimensionType.AETHER;
            else if (px < 14) target = DimensionType.END;
            else target = DimensionType.OVERWORLD;
        } else if (isNetherPortal(voxel)) {
            target = ctx.activeDimension == DimensionType.NETHER ? DimensionType.OVERWORLD : DimensionType.NETHER;
        } else {
            target = ctx.activeDimension == DimensionType.AETHER ? DimensionType.OVERWORLD : DimensionType.AETHER;
        }
        if (target == ctx.activeDimension) return;

        ctx.switchToDimension(target);
        ctx.lastPortalTeleportTime = now;
        if (ctx.cinematic != null) ctx.cinematic.playPortalTravel();
        ctx.setStatus("Teleported to " + target.name);
    }

    /** True while teleport is on cooldown (shared with walk-in detection). */
    private boolean onCooldown(double now) {
        return now - ctx.lastPortalTeleportTime < 1.0;
    }

    /**
     * Click-to-enter: switch dimensions as if the player had walked into the
     * portal block they just clicked. Used by the point-and-click billboard
     * action; hall portals are position-keyed and are ignored here.
     */
    public void enterPortal(int portalBlockId) {
        if (!(isNetherPortal(portalBlockId) || isAetherPortal(portalBlockId))) return;
        if (ctx.activeDimension == DimensionType.PORTAL_HALL) return;
        double now = System.currentTimeMillis() / 1000.0;
        if (onCooldown(now)) return;

        DimensionType target;
        if (isNetherPortal(portalBlockId)) {
            target = ctx.activeDimension == DimensionType.NETHER ? DimensionType.OVERWORLD : DimensionType.NETHER;
        } else {
            target = ctx.activeDimension == DimensionType.AETHER ? DimensionType.OVERWORLD : DimensionType.AETHER;
        }
        if (target == ctx.activeDimension) return;

        ctx.switchToDimension(target);
        ctx.lastPortalTeleportTime = now;
        if (ctx.cinematic != null) ctx.cinematic.playPortalTravel();
        ctx.setStatus("Teleported to " + target.name);
    }

    public void attemptActivate() {
        if (ctx.player.isDead()) return;
        int[] hit = blockInteraction.raycastBlock(blockInteraction.blockReachDistance());
        if (hit == null) return;

        int hitX = hit[0], hitY = hit[1], hitZ = hit[2];
        int hitBlock = ctx.world.getVoxel(hitX, hitY, hitZ);

        ItemDefinitions.ItemStack selected = ctx.playerInventory.getSelected();
        if (selected == null) { ctx.setStatus("Select flint & steel, water bucket, or eye of ender"); return; }

        String itemId = selected.itemId;
        int frameBlockId, portalNsId, portalEwId;
        String activationMsg;

        if (itemId.equals("flint_and_steel")) {
            frameBlockId = 16; portalNsId = PORTAL_NETHER; portalEwId = PORTAL_NETHER_EW;
            activationMsg = "Nether Portal";
        } else if (itemId.equals("water_bucket")) {
            frameBlockId = 17; portalNsId = PORTAL_AETHER; portalEwId = PORTAL_AETHER_EW;
            activationMsg = "Aether Portal";
        } else if (itemId.equals("eye_of_ender")) {
            frameBlockId = 18; portalNsId = PORTAL_NETHER; portalEwId = PORTAL_NETHER_EW;
            activationMsg = "End Portal";
        } else {
            ctx.setStatus("Shift+right-click with flint & steel, water bucket, or eye of ender");
            return;
        }

        if (hitBlock != frameBlockId) return;
        if (tryLightPortal(hitX, hitY, hitZ, frameBlockId, portalNsId, portalEwId)) {
            ctx.setStatus("Lit " + activationMsg);
        }
    }

    private boolean tryLightPortal(int hx, int hy, int hz, int frameId, int nsId, int ewId) {
        // A frame spanning X shows its opening to north/south -> *_ns block;
        // a frame spanning Z opens east/west -> *_ew block.
        return tryOrientation(hx, hy, hz, 1, 0, frameId, nsId) ||
               tryOrientation(hx, hy, hz, 0, 1, frameId, ewId);
    }

    private boolean tryOrientation(int hx, int hy, int hz, int dx, int dz, int frameId, int portalId) {
        for (int dw = -3; dw <= 0; dw++) {
            for (int dh = -4; dh <= 0; dh++) {
                int sx = (dx == 1) ? hx + dw : hx;
                int sy = hy + dh;
                int sz = (dz == 1) ? hz + dw : hz;
                if (isValidFrame(sx, sy, sz, dx, dz, frameId)) {
                    for (int px = 1; px <= 2; px++)
                        for (int py = 1; py <= 3; py++)
                            ctx.chunkManager.setVoxel(sx + px * dx, sy + py, sz + px * dz, portalId);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isValidFrame(int sx, int sy, int sz, int dx, int dz, int frameId) {
        int w = 4, h = 5;
        // Bottom & top
        for (int i = 0; i < w; i++) {
            if (ctx.world.getVoxel(sx + i * dx, sy, sz + i * dz) != frameId) return false;
            if (ctx.world.getVoxel(sx + i * dx, sy + h - 1, sz + i * dz) != frameId) return false;
        }
        // Left & right columns
        for (int i = 1; i < h - 1; i++) {
            if (ctx.world.getVoxel(sx, sy + i, sz) != frameId) return false;
            if (ctx.world.getVoxel(sx + (w - 1) * dx, sy + i, sz + (w - 1) * dz) != frameId) return false;
        }
        // Interior empty
        for (int px = 1; px < w - 1; px++)
            for (int py = 1; py < h - 1; py++)
                if (ctx.world.getVoxel(sx + px * dx, sy + py, sz + px * dz) != 0) return false;
        return true;
    }
}
