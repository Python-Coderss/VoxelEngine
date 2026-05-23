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
            if (ctx.leftMousePressedThisFrame) {
                ctx.leftMousePressedThisFrame = false; // consume the flag
                breakBlock(hit[0], hit[1], hit[2], blockId, false);
            }
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
        ctx.redstoneManager.notifyNeighbors(x, y, z);

        // If breaking a crafting table, return items in its grid to the player
        if (blockId == 115) {
            String[][] grid = ctx.craftingTableManager.removeGrid(x, y, z);
            if (grid != null) {
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        if (grid[r] != null && grid[r][c] != null) {
                            ctx.playerInventory.addItem(grid[r][c], 1);
                        }
                    }
                }
            }
        }

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

        // Right-click on a crafting table block — start cutscene walk to table
        int hitBlock = ctx.world.getVoxel(hit[0], hit[1], hit[2]);
        if (hitBlock == 115 && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.craftingTableOpen) {
            // If the table has items and player isn't holding a block, extract items directly
            String[][] existingGrid = ctx.craftingTableManager.getGrid(hit[0], hit[1], hit[2]);
            if (existingGrid != null && !CraftingTableManager.isGridEmpty(existingGrid)) {
                // Check if the player is holding a block item to place, vs a tool to extract
                ItemDefinitions.ItemStack held = ctx.playerInventory.getSelected();
                boolean holdingBlock = held != null && ctx.itemDefinitions.getDefinition(held.itemId) != null
                    && ctx.itemDefinitions.getDefinition(held.itemId).kind == ItemDefinitions.ItemKind.BLOCK;
                if (!holdingBlock) {
                    // Extract items: right-click on table with items pops them out
                    for (int r = 0; r < 3; r++) {
                        for (int c = 0; c < 3; c++) {
                            if (existingGrid[r] != null && existingGrid[r][c] != null) {
                                if (ctx.playerInventory.addItem(existingGrid[r][c], 1)) {
                                    existingGrid[r][c] = null;
                                }
                            }
                        }
                    }
                    ctx.craftingTableManager.setGrid(hit[0], hit[1], hit[2], existingGrid);
                    ctx.setStatus("Extracted items from crafting table");
                    return;
                }
            }

            ctx.craftingTableBlockX = hit[0];
            ctx.craftingTableBlockY = hit[1];
            ctx.craftingTableBlockZ = hit[2];



            // Compute target position: snap to the nearest walkable side of the table
            Vector3f tableCenter = new Vector3f(hit[0] + 0.5f, hit[1], hit[2] + 0.5f);
            Vector3f playerPos = ctx.player.getPosition();

            float targetX, targetZ, targetY = hit[1];
            // If the clicked face is top/bottom (adjacent block has same x/z as table),
            // fall back to using the player's approach direction to find the nearest side
            if (hit[3] == hit[0] && hit[5] == hit[2]) {
                // Determine which side of the table the player is approaching from
                float dx = playerPos.x - tableCenter.x;
                float dz = playerPos.z - tableCenter.z;
                if (Math.abs(dx) >= Math.abs(dz)) {
                    targetX = hit[0] + (dx >= 0 ? 1.5f : -0.5f);
                    targetZ = tableCenter.z;
                } else {
                    targetX = tableCenter.x;
                    targetZ = hit[2] + (dz >= 0 ? 1.5f : -0.5f);
                }
            } else {
                // Side face click: walk to the center of the adjacent block
                targetX = hit[3] + 0.5f;
                targetZ = hit[5] + 0.5f;
            }
            ctx.cutsceneTargetPos.set(targetX, targetY, targetZ);

            // Store starting state
            ctx.cutsceneStartPos.set(playerPos);
            ctx.cutsceneStartYaw = ctx.yaw;
            ctx.cutsceneStartPitch = ctx.pitch;

            // 45° isometric-like camera looking down at the table
            float roundedYaw = Math.round(ctx.yaw / 90.0f) * 90.0f;
            ctx.cutsceneTargetPitch = CraftingTableConstants.CRAFTING_TABLE_PITCH;
            ctx.cutsceneTargetYaw = roundedYaw;

            // Compute camera position: offset behind the look direction at 45° pitch
            double ry = Math.toRadians(roundedYaw);
            double rp = Math.toRadians(CraftingTableConstants.CRAFTING_TABLE_PITCH);
            float cosPitch = (float)Math.cos(rp);
            float sinPitch = (float)Math.sin(rp);
            float fx = (float)Math.cos(ry) * cosPitch;
            float fy = sinPitch;
            float fz = (float)Math.sin(ry) * cosPitch;

            float tableCX = hit[0] + 0.5f;
            float tableCY = hit[1] + 1.0f; // Top of table
            float tableCZ = hit[2] + 0.5f;

            // Try increasing distances until we find a clear spot for the camera
            float[] distances = {1.0f, 1.5f, 1.75f, 2.0f, 2.5f, 3f, 4f, 5f, 6f, 7f, 8f};
            float camX = tableCX, camY = tableCY, camZ = tableCZ;
            boolean foundSpot = false;

            for (int di = 0; di < distances.length; di++) {
                float dist = distances[di];
                float cx = tableCX - dist * fx;
                float cy = tableCY - dist * fy;
                float cz = tableCZ - dist * fz;

                int voxel = ctx.world.getVoxel((int)Math.floor(cx), (int)Math.floor(cy), (int)Math.floor(cz));
                if (voxel == 0 || !ctx.blockDataManager.isFullBlock(voxel)) {
                    camX = cx; camY = cy; camZ = cz;
                    foundSpot = true;
                    break;
                }
            }

            if (!foundSpot) {
                // Fallback: just use the furthest distance even if blocked
                camX = tableCX - distances[distances.length-1] * fx;
                camY = tableCY - distances[distances.length-1] * fy;
                camZ = tableCZ - distances[distances.length-1] * fz;
            }

            ctx.cutsceneCameraStartPos.set(playerPos.x, playerPos.y + 1.6f, playerPos.z);
            ctx.cutsceneCameraTargetPos.set(camX, camY, camZ);

            ctx.craftingCutsceneActive = true;
            ctx.craftingCutsceneTimer = 0.0f;
            ctx.setStatus("Walking to crafting table...");
            return;
        }

        ItemDefinitions.ItemStack selected = ctx.playerInventory.getSelected();
        if (selected == null) { ctx.setStatus("Selected slot is empty"); return; }
        ItemDefinitions.ItemDefinition def = ctx.itemDefinitions.getDefinition(selected.itemId);
        if (def == null) return;
        if (def.kind != ItemDefinitions.ItemKind.BLOCK) {
            ctx.setStatus("Select a block item to place");
            return;
        }

        int px = hit[3], py = hit[4], pz = hit[5];
        int existing = ctx.world.getVoxel(px, py, pz);
        if (existing != 0) return;
        if (intersectsPlayer(px, py, pz)) return;
        if (def.blockId == 31 || def.blockId == 32) {
            // Piston blocks: encode facing direction into extra data
            int dx = hit[0] - px;
            int dy = hit[1] - py;
            int dz = hit[2] - pz;
            int direction;
            if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
                direction = dx > 0 ? 5 : 4;  // east or west
            } else if (Math.abs(dy) >= Math.abs(dz)) {
                direction = dy > 0 ? 1 : 0;  // up or down
            } else {
                direction = dz > 0 ? 3 : 2;  // south or north
            }
            if (!ctx.chunkManager.setVoxelWithData(px, py, pz, def.blockId, direction)) return;
        } else {
            if (!ctx.chunkManager.setVoxel(px, py, pz, def.blockId)) return;
        }

        ctx.redstoneManager.onBlockChanged(px, py, pz);
        ctx.redstoneManager.notifyNeighbors(px, py, pz);

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
                if (blockId != 0) {
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
