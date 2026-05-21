package com.voxel.game;

import org.joml.Vector3f;

import com.voxel.game.GameContext.CameraMode;
import com.voxel.game.GameContext.GameMode;

/**
 * Handles mining, block breaking, block placement, and raycasting.
 */
public class BlockInteraction {
    private static final float PLAYER_HALF_WIDTH = 0.3f;
    private static final float PLAYER_HEIGHT = 1.8f;

    private final GameContext ctx;

    public BlockInteraction(GameContext ctx) {
        this.ctx = ctx;
    }

    public void updateMining(float dt) {
        if (ctx.inventoryOpen || ctx.commandMode || !ctx.leftMouseHeld || ctx.player.isDead()) {
            resetMining();
            return;
        }
        int[] hit = raycastBlock(6.0f);
        if (hit == null) { resetMining(); return; }

        int blockId = ctx.world.getVoxel(hit[0], hit[1], hit[2]);
        if (blockId == 0) { resetMining(); return; }

        if (ctx.gameMode == GameMode.CREATIVE) {
            if (ctx.leftMousePressedThisFrame) breakBlock(hit[0], hit[1], hit[2], blockId, false);
            return;
        }

        if (hit[0] != ctx.breakTargetX || hit[1] != ctx.breakTargetY || hit[2] != ctx.breakTargetZ) {
            ctx.breakTargetX = hit[0]; ctx.breakTargetY = hit[1]; ctx.breakTargetZ = hit[2]; ctx.breakProgress = 0.0f;
        }
        ctx.breakProgress += dt * getMiningSpeed(blockId);
        if (ctx.breakProgress >= ctx.blockDataManager.getHardness(blockId)) {
            breakBlock(hit[0], hit[1], hit[2], blockId, true);
            resetMining();
        }
    }

    private float getMiningSpeed(int blockId) {
        String preferredTool = ctx.blockDataManager.getPreferredTool(blockId);
        ItemDefinitions.ItemStack selected = ctx.playerInventory.getSelected();
        ItemDefinitions.ItemDefinition selDef = selected != null ? ctx.itemDefinitions.getDefinition(selected.itemId) : null;
        ItemDefinitions.ToolType activeTool = ItemDefinitions.ToolType.HAND;
        float toolSpeed = 1.0f;
        if (selDef != null && selDef.kind == ItemDefinitions.ItemKind.TOOL) {
            activeTool = selDef.toolType;
            toolSpeed = selDef.miningSpeed;
        }
        if ("pickaxe".equals(preferredTool)) return activeTool == ItemDefinitions.ToolType.PICKAXE ? toolSpeed : 0.55f;
        if ("shovel".equals(preferredTool))  return activeTool == ItemDefinitions.ToolType.SHOVEL ? toolSpeed : 0.75f;
        if ("axe".equals(preferredTool))     return activeTool == ItemDefinitions.ToolType.AXE ? toolSpeed : 0.85f;
        return activeTool == ItemDefinitions.ToolType.HAND ? 1.2f : Math.max(1.0f, toolSpeed * 0.7f);
    }

    public void breakBlock(int x, int y, int z, int blockId, boolean collectDrop) {
        if (!ctx.chunkManager.setVoxel(x, y, z, 0)) return;
        ctx.redstoneManager.onBlockChanged(x, y, z);
        if (collectDrop) {
            String dropItem;
            int dropCount = 1;
            if (blockId == 26) { // redstone_ore -> drop 4 redstone dust
                dropItem = "redstone_wire";
                dropCount = 4;
            } else {
                dropItem = ctx.itemDefinitions.getBlockItemByBlockId().get(blockId);
            }
            if (dropItem != null) ctx.playerInventory.addItem(dropItem, dropCount);
        }
    }

    public void attemptPlaceBlock() {
        if (ctx.player.isDead()) return;
        int[] hit = raycastBlock(6.0f);
        if (hit == null) return;

        ItemDefinitions.ItemStack selected = ctx.playerInventory.getSelected();
        if (selected == null) { ctx.setStatus("Selected slot is empty"); return; }
        ItemDefinitions.ItemDefinition def = ctx.itemDefinitions.getDefinition(selected.itemId);
        if (def == null || def.kind != ItemDefinitions.ItemKind.BLOCK) {
            ctx.setStatus("Select a block item to place"); return;
        }

        int px = hit[3], py = hit[4], pz = hit[5];
        if (ctx.world.getVoxel(px, py, pz) != 0) return;
        if (intersectsPlayer(px, py, pz)) return;
        if (!ctx.chunkManager.setVoxel(px, py, pz, def.blockId)) return;
        ctx.redstoneManager.onBlockChanged(px, py, pz);

        if (ctx.gameMode == GameMode.SURVIVAL) {
            selected.count--;
            if (selected.count <= 0) ctx.playerInventory.setSlot(ctx.playerInventory.getSelectedSlot(), null);
        }
    }

    public void resetMining() {
        ctx.breakTargetX = Integer.MIN_VALUE;
        ctx.breakTargetY = Integer.MIN_VALUE;
        ctx.breakTargetZ = Integer.MIN_VALUE;
        ctx.breakProgress = 0.0f;
    }

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
                if (blockId != 0 && ctx.blockDataManager.isFullBlock(blockId)) {
                    return new int[]{cx, cy, cz, lastX, lastY, lastZ};
                }
                lastX = cx; lastY = cy; lastZ = cz;
            }
        }
        return null;
    }

    private boolean intersectsPlayer(int x, int y, int z) {
        Vector3f pos = ctx.player.getPosition();
        float pMinX = pos.x - PLAYER_HALF_WIDTH, pMaxX = pos.x + PLAYER_HALF_WIDTH;
        float pMinY = pos.y, pMaxY = pos.y + PLAYER_HEIGHT;
        float pMinZ = pos.z - PLAYER_HALF_WIDTH, pMaxZ = pos.z + PLAYER_HALF_WIDTH;
        return pMaxX > x && pMinX < x + 1 && pMaxY > y && pMinY < y + 1 && pMaxZ > z && pMinZ < z + 1;
    }

    Vector3f getLookDirection() {
        return new Vector3f(
            (float)(Math.cos(Math.toRadians(ctx.yaw)) * Math.cos(Math.toRadians(ctx.pitch))),
            (float)Math.sin(Math.toRadians(ctx.pitch)),
            (float)(Math.sin(Math.toRadians(ctx.yaw)) * Math.cos(Math.toRadians(ctx.pitch)))
        ).normalize();
    }

    Vector3f getActiveCameraPosition() {
        Vector3f eye = new Vector3f(ctx.player.getPosition()).add(0, 1.6f, 0);
        if (ctx.cameraMode == CameraMode.FIRST_PERSON) return eye;

        Vector3f look = getLookDirection();
        Vector3f right = new Vector3f(look).cross(new Vector3f(0, 1, 0)).normalize();
        Vector3f target = new Vector3f(ctx.player.getPosition()).add(0, 1.35f, 0);
        target.add(right.mul(0.6f));
        Vector3f desired = new Vector3f(target).sub(new Vector3f(look).mul(4.0f));
        return resolveCameraCollision(target, desired);
    }

    private Vector3f resolveCameraCollision(Vector3f origin, Vector3f desired) {
        Vector3f delta = new Vector3f(desired).sub(origin);
        float length = delta.length();
        if (length <= 0.0001f) return new Vector3f(origin);
        Vector3f dir = delta.div(length);
        Vector3f lastFree = new Vector3f(origin);
        for (float traveled = 0.1f; traveled <= length; traveled += 0.1f) {
            Vector3f sample = new Vector3f(origin).fma(traveled, dir);
            int v = ctx.world.getVoxel((int)Math.floor(sample.x), (int)Math.floor(sample.y), (int)Math.floor(sample.z));
            if (v > 0 && ctx.blockDataManager.isFullBlock(v)) return lastFree;
            lastFree.set(sample);
        }
        return desired;
    }
}
