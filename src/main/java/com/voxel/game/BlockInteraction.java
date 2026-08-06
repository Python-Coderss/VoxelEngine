package com.voxel.game;

import org.joml.Vector3f;

import com.voxel.game.GameContext.ActiveUI;
import com.voxel.game.GameContext.CameraMode;
import com.voxel.game.GameContext.GameMode;
import com.voxel.game.ItemDefinitions.ItemStack;

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

    /** Block ID constants */
    private static final int BLOCK_CRAFTING_TABLE = 115;
    private static final int BLOCK_FURNACE = 116;
    private static final int BLOCK_FURNACE_ON = 117;
    private static final int BLOCK_CHEST = 118;
    private static final int BLOCK_TV = 274;
    private static final int BLOCK_WATER = 15;
    private static final int BLOCK_WATER_FLOWING_MIN = 150;
    private static final int BLOCK_WATER_FLOWING_MAX = 156;
    private static final int BLOCK_LAVA = 21;

    /** Opens the furnace UI for the given block position. */
    private void openFurnace(int x, int y, int z) {
        ctx.furnaceBlockX = x;
        ctx.furnaceBlockY = y;
        ctx.furnaceBlockZ = z;
        ctx.furnaceOpen = true;
        ctx.inventoryOpen = true;
        ctx.activeUI = GameContext.ActiveUI.FURNACE;
        ctx.setStatus("Furnace");
    }

    /** Opens the chest UI for the given block position. */
    private void openChest(int x, int y, int z) {
        ctx.chestBlockX = x;
        ctx.chestBlockY = y;
        ctx.chestBlockZ = z;
        ctx.chestOpen = true;
        ctx.inventoryOpen = true;
        ctx.activeUI = GameContext.ActiveUI.CHEST;
        ctx.setStatus("Chest");
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

        // Water and lava can't be broken — use a bucket instead
        if (isWaterBlock(blockId) || blockId == BLOCK_LAVA) {
            resetMining();
            return;
        }

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
        int requiredTier = ctx.blockDataManager.getMiningTier(blockId);
        ItemDefinitions.ItemStack selected = ctx.playerInventory.getSelected();
        ItemDefinitions.ItemDefinition selDef = selected != null ? ctx.itemDefinitions.getDefinition(selected.itemId) : null;
        ItemDefinitions.ToolType activeTool = ItemDefinitions.ToolType.HAND;
        float toolSpeed = 1.0f;
        int toolTier = 0;
        if (selDef != null && selDef.kind == ItemDefinitions.ItemKind.TOOL) {
            activeTool = selDef.toolType;
            toolSpeed = selDef.miningSpeed;
            toolTier = selDef.tier;
        }
        // Check tier requirement: if tool tier is insufficient, mining is very slow and no drop
        if (requiredTier > toolTier) {
            return 0.03f; // Extremely slow - can't effectively mine
        }
        if ("pickaxe".equals(preferredTool)) return activeTool == ItemDefinitions.ToolType.PICKAXE ? toolSpeed : 0.55f;
        if ("shovel".equals(preferredTool))  return activeTool == ItemDefinitions.ToolType.SHOVEL ? toolSpeed : 0.75f;
        if ("axe".equals(preferredTool))     return activeTool == ItemDefinitions.ToolType.AXE ? toolSpeed : 0.85f;
        return activeTool == ItemDefinitions.ToolType.HAND ? 1.2f : Math.max(1.0f, toolSpeed * 0.7f);
    }

    public void breakBlock(int x, int y, int z, int blockId, boolean collectDrop) {
        // Piston base: derive facing direction from directional block ID before clearing
        int pistonDir = getPistonDirection(blockId);
        if (!ctx.chunkManager.setVoxel(x, y, z, 0)) return;
        // Remove an extended piston head left behind by the broken base
        if (pistonDir >= 0) {
            int[][] off = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
            int hx = x + off[pistonDir][0], hy = y + off[pistonDir][1], hz = z + off[pistonDir][2];
            int head = ctx.world.getVoxel(hx, hy, hz);
            if (head == 33 || head == 259) {
                ctx.chunkManager.setVoxel(hx, hy, hz, 0);
                ctx.redstoneManager.onBlockChanged(hx, hy, hz);
                ctx.redstoneManager.notifyNeighbors(hx, hy, hz);
            }
        }
        ctx.redstoneManager.onBlockChanged(x, y, z);
        ctx.redstoneManager.notifyNeighbors(x, y, z);
        if (ctx.encasedFanSystem != null) {
            ctx.encasedFanSystem.onBlockChanged(x, y, z);
        }
        // Notify fluid manager: block removed may open space for fluid to flow into
        if (ctx.fluidManager != null) {
            ctx.fluidManager.notifyBlockChanged(x, y, z);
        }
        // Notify dropped items: if any item was resting on this block, it should fall
        if (ctx.droppedItemManager != null) {
            ctx.droppedItemManager.onBlockDestroyed(x, y, z);
        }

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

        // If breaking a furnace, return items in its slots to the player
        if (blockId == 116 || blockId == 117) {
            FurnaceManager.FurnaceState state = ctx.furnaceManager.removeFurnace(x, y, z);
            if (state != null) {
                if (state.input != null) ctx.playerInventory.addItem(state.input.itemId, state.input.count);
                if (state.fuel != null) ctx.playerInventory.addItem(state.fuel.itemId, state.fuel.count);
                if (state.output != null) ctx.playerInventory.addItem(state.output.itemId, state.output.count);
            }
        }

        // If breaking a chest, return items to the player
        if (blockId == 118) {
            ItemStack[] inv = ctx.chestManager.removeChest(x, y, z);
            if (inv != null) {
                for (int i = 0; i < ChestManager.CHEST_SLOTS; i++) {
                    if (inv[i] != null) {
                        ctx.playerInventory.addItem(inv[i].itemId, inv[i].count);
                    }
                }
            }
        }

        if (collectDrop) {
            // Tier check: if the player's tool tier is insufficient, no drop
            int requiredTier = ctx.blockDataManager.getMiningTier(blockId);
            ItemDefinitions.ItemStack selected = ctx.playerInventory.getSelected();
            int toolTier = 0;
            if (selected != null) {
                ItemDefinitions.ItemDefinition selDef = ctx.itemDefinitions.getDefinition(selected.itemId);
                if (selDef != null && selDef.kind == ItemDefinitions.ItemKind.TOOL) {
                    toolTier = selDef.tier;
                }
            }
            if (requiredTier > toolTier) {
                ctx.setStatus("Need a better tool to mine this");
                return; // No drop if tool tier is insufficient
            }

            String dropItem;
            int dropCount = 1;
            if (blockId == 26) { // redstone_ore -> drop 4 redstone dust
                dropItem = "redstone_wire";
                dropCount = 4;
            } else if (blockId == 85) { // lapis_ore -> drop 4 lapis
                dropItem = "lapis_ore";
                dropCount = 4;
            } else {
                dropItem = ctx.itemDefinitions.getBlockItemByBlockId().get(blockId);
            }
            // Drop into the world; player must walk over to pick up.
            if (dropItem != null && ctx.droppedItemManager != null) {
                ctx.droppedItemManager.spawn(dropItem, dropCount, x, y, z);
            }
        }
    }

    /** Raycast against entities. Returns {entityIndex, hitDistanceBits} or null. */
    public int[] raycastEntity(float maxDist) {
        Vector3f dir = getLookDirection();
        Vector3f pos = getActiveCameraPosition();
        float closestT = maxDist;
        int closestIdx = -1;

        com.voxel.entity.EntityManager em = ctx.entityManager;
        if (em == null) return null;

        for (int i = 0; i < em.getEntityCount(); i++) {
            com.voxel.entity.Entity e = em.getEntity(i);
            if (e == null || e.dimension != ctx.activeDimension) continue;
            if (e instanceof com.voxel.entity.PlayerEntity) continue;
            if (e instanceof com.voxel.entity.EnemyEntity && ((com.voxel.entity.EnemyEntity) e).isDead()) continue;

            Vector3f ePos = e.getPosition();
            float w = 0.3f, h = 0.9f;
            Vector3f bMin = new Vector3f(ePos.x - w, ePos.y, ePos.z - w);
            Vector3f bMax = new Vector3f(ePos.x + w, ePos.y + h * 2, ePos.z + w);

            Vector3f invDir = new Vector3f(1.0f / dir.x, 1.0f / dir.y, 1.0f / dir.z);
            float t1 = (bMin.x - pos.x) * invDir.x, t2 = (bMax.x - pos.x) * invDir.x;
            float t3 = (bMin.y - pos.y) * invDir.y, t4 = (bMax.y - pos.y) * invDir.y;
            float t5 = (bMin.z - pos.z) * invDir.z, t6 = (bMax.z - pos.z) * invDir.z;

            float tMin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
            float tMax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

            if (tMax >= 0 && tMin <= tMax && tMin < closestT) {
                closestT = tMin;
                closestIdx = i;
            }
        }
        if (closestIdx >= 0) return new int[]{closestIdx, Float.floatToRawIntBits(closestT)};
        return null;
    }

    /** Show villager interaction when right-clicked. */
    private void interactWithEntity(com.voxel.entity.Entity e) {
        if (e instanceof com.voxel.entity.VillagerEntity) {
            com.voxel.entity.VillagerEntity v = (com.voxel.entity.VillagerEntity) e;
            String prof = v.getProfession().name().toLowerCase().replace('_', ' ');
            String name = prof.substring(0, 1).toUpperCase() + prof.substring(1);
            String dialogue = ctx.villagerAudioManager != null
                    ? ctx.villagerAudioManager.requestVillagerDialogue(v, ctx.worldTime)
                    : "Hmm...";
            ctx.setStatus("Villager (" + name + ") — \"" + dialogue + "\"");
        }
    }

    public void attemptPlaceBlock() {
        if (ctx.player.isDead()) return;
        int[] hit = raycastBlock(6.0f);

        // Check for entity interaction — if player looks at a villager (even through no block)
        if (!ctx.inventoryOpen && !ctx.commandMode && !ctx.tvCutsceneActive && !ctx.craftingCutsceneActive) {
            int[] entityHit = raycastEntity(6.0f);
            if (entityHit != null) {
                float entDist = Float.intBitsToFloat(entityHit[1]);
                float blockDist = hit != null ? getActiveCameraPosition().distance(
                    new Vector3f(hit[0] + 0.5f, hit[1] + 0.5f, hit[2] + 0.5f)) : Float.MAX_VALUE;
                if (entDist < blockDist) {
                    com.voxel.entity.Entity e = ctx.entityManager.getEntity(entityHit[0]);
                    if (e != null) { interactWithEntity(e); return; }
                }
            } else if (hit == null) {
                // Looking at nothing in range — nothing to interact with
                return;
            }
        }

        if (hit == null) return;

        // Right-click on a crafting table block — start cutscene walk to table
        int hitBlock = ctx.world.getVoxel(hit[0], hit[1], hit[2]);

        // Right-click on villager TV block — start TV cutscene
        if (hitBlock == BLOCK_TV && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.tvCutsceneActive) {
            startTVCutscene(hit);
            return;
        }

        // Right-click on furnace
        if ((hitBlock == BLOCK_FURNACE || hitBlock == BLOCK_FURNACE_ON) && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.furnaceOpen) {
            openFurnace(hit[0], hit[1], hit[2]);
            return;
        }

        // Right-click on chest
        if (hitBlock == BLOCK_CHEST && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.chestOpen) {
            openChest(hit[0], hit[1], hit[2]);
            return;
        }

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

        // ── Bucket fluid interaction ──
        if (def.id.equals("bucket")) {
            // Empty bucket: scoop fluid from the looked-at block
            int scoopBlock = ctx.world.getVoxel(hit[0], hit[1], hit[2]);
            if (isWaterBlock(scoopBlock)) {
                ctx.chunkManager.setVoxel(hit[0], hit[1], hit[2], 0);
                ctx.fluidManager.notifyBlockChanged(hit[0], hit[1], hit[2]);
                ctx.playerInventory.replaceSelected("water_bucket");
                if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
                ctx.setStatus("Filled water bucket");
                return;
            } else if (scoopBlock == BLOCK_LAVA) {
                ctx.chunkManager.setVoxel(hit[0], hit[1], hit[2], 0);
                ctx.fluidManager.notifyBlockChanged(hit[0], hit[1], hit[2]);
                ctx.playerInventory.replaceSelected("lava_bucket");
                if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
                ctx.setStatus("Filled lava bucket");
                return;
            }
            // Not a fluid — fall through to normal item handling
        }
        if (def.id.equals("water_bucket")) {
            // Water bucket: place water source at the adjacent block
            int px = hit[3], py = hit[4], pz = hit[5];
            int existing = ctx.world.getVoxel(px, py, pz);
            if (existing != 0) { ctx.setStatus("Cannot place water here"); return; }
            if (intersectsPlayer(px, py, pz)) return;
            ctx.chunkManager.setVoxel(px, py, pz, BLOCK_WATER);
            ctx.fluidManager.notifyBlockChanged(px, py, pz);
            ctx.playerInventory.replaceSelected("bucket");
            if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
            ctx.setStatus("Placed water");
            return;
        }
        if (def.id.equals("lava_bucket")) {
            // Lava bucket: place lava source at the adjacent block
            int px = hit[3], py = hit[4], pz = hit[5];
            int existing = ctx.world.getVoxel(px, py, pz);
            if (existing != 0) { ctx.setStatus("Cannot place lava here"); return; }
            if (intersectsPlayer(px, py, pz)) return;
            ctx.chunkManager.setVoxel(px, py, pz, BLOCK_LAVA);
            ctx.fluidManager.notifyBlockChanged(px, py, pz);
            ctx.playerInventory.replaceSelected("bucket");
            if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
            ctx.setStatus("Placed lava");
            return;
        }
        // ── End bucket interaction ──

        if (def.kind != ItemDefinitions.ItemKind.BLOCK) {
            ctx.setStatus("Select a block item to place");
            return;
        }

        int px = hit[3], py = hit[4], pz = hit[5];
        int existing = ctx.world.getVoxel(px, py, pz);
        if (existing != 0) return;
        if (intersectsPlayer(px, py, pz)) return;
        int placeBlockId = def.blockId;
        // Orientable logs: choose the axis variant from the clicked face normal
        if (placeBlockId == 5) { // oak_log -> 5 (Y axis), 260 (X axis), 261 (Z axis)
            int ldx = hit[0] - px, ldz = hit[2] - pz;
            if (ldx != 0) placeBlockId = 260;
            else if (ldz != 0) placeBlockId = 261;
        }
        if (placeBlockId == 31 || placeBlockId == 32) {
            // Directional piston: place the correct directional variant block ID
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
            int dirBlockId = getDirectionalPistonId(placeBlockId, direction);
            if (!ctx.chunkManager.setVoxel(px, py, pz, dirBlockId)) return;
        } else if (placeBlockId == 263) {
            // Encased fan: encode facing into extra data
            int dx = hit[0] - px;
            int dy = hit[1] - py;
            int dz = hit[2] - pz;
            int direction;
            if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
                direction = dx > 0 ? 5 : 4;
            } else if (Math.abs(dy) >= Math.abs(dz)) {
                direction = dy > 0 ? 1 : 0;
            } else {
                direction = dz > 0 ? 3 : 2;
            }
            if (!ctx.chunkManager.setVoxelWithData(px, py, pz, placeBlockId, direction)) return;
        } else {
            if (!ctx.chunkManager.setVoxel(px, py, pz, placeBlockId)) return;
        }

        ctx.redstoneManager.onBlockChanged(px, py, pz);
        ctx.redstoneManager.notifyNeighbors(px, py, pz);
        if (ctx.encasedFanSystem != null) {
            ctx.encasedFanSystem.onBlockChanged(px, py, pz);
        }
        // Notify fluid manager: block placed next to fluids may affect flow
        if (ctx.fluidManager != null) {
            ctx.fluidManager.notifyBlockChanged(px, py, pz);
        }

        if (ctx.gameMode == GameMode.SURVIVAL) {
            selected.count--;
            if (selected.count <= 0) ctx.playerInventory.setSlot(ctx.playerInventory.getSelectedSlot(), null);
        }
        if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
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

    /**
     * Checks if a block ID is any water variant (source or flowing).
     */
    private boolean isWaterBlock(int blockId) {
        return blockId == BLOCK_WATER || (blockId >= BLOCK_WATER_FLOWING_MIN && blockId <= BLOCK_WATER_FLOWING_MAX);
    }

    /**
     * Maps a directional piston block ID (31/32 + direction variants 264-273) back
     * to the direction constant. Returns -1 if not a piston base block.
     * Direction: 0=down, 1=up, 2=north, 3=south, 4=west, 5=east.
     */
    public static int getPistonDirection(int blockId) {
        switch (blockId) {
            case 31: case 32:  return 1;  // default facing up
            case 264: case 269: return 0;  // down
            case 265: case 270: return 2;  // north
            case 266: case 271: return 3;  // south
            case 267: case 272: return 4;  // west
            case 268: case 273: return 5;  // east
            default: return -1;
        }
    }

    /**
     * Returns the directional block ID for placing a piston at the given direction.
     * Maps base piston ID + direction (0=down,1=up,2=north,3=south,4=west,5=east) → directional block ID.
     */
    public static int getDirectionalPistonId(int baseBlockId, int direction) {
        boolean isSticky = (baseBlockId == 32);
        // dirMap[0]=normal piston, dirMap[1]=sticky piston
        // Indexed by direction: 0=down, 1=up, 2=north, 3=south, 4=west, 5=east
        int[][] dirMap = {
            {264, 31, 265, 266, 267, 268},  // normal: down, up, north, south, west, east
            {269, 32, 270, 271, 272, 273}   // sticky
        };
        return dirMap[isSticky ? 1 : 0][direction];
    }

    private boolean intersectsPlayer(int x, int y, int z) {
        Vector3f pos = ctx.player.getPosition();
        float pMinX = pos.x - PLAYER_HALF_WIDTH, pMaxX = pos.x + PLAYER_HALF_WIDTH;
        float pMinY = pos.y, pMaxY = pos.y + PLAYER_HEIGHT;
        float pMinZ = pos.z - PLAYER_HALF_WIDTH, pMaxZ = pos.z + PLAYER_HALF_WIDTH;
        return pMaxX > x && pMinX < x + 1 && pMaxY > y && pMinY < y + 1 && pMaxZ > z && pMinZ < z + 1;
    }

    /** Start the TV watching cutscene. */
    private void startTVCutscene(int[] hit) {
        ctx.tvBlockX = hit[0];
        ctx.tvBlockY = hit[1];
        ctx.tvBlockZ = hit[2];

        // Get current channel from TV system
        if (ctx.tvSystem != null) {
            ctx.tvChannel = ctx.tvSystem.getChannel(hit[0], hit[1], hit[2]);
            // Gather nearby villagers to watch TV
            if (ctx.villageManager != null) {
                com.voxel.game.VillagerVillageManager.Village village = 
                    ctx.villageManager.findVillageAt(hit[0], hit[2]);
                if (village != null) {
                    ctx.villageManager.gatherVillagersAtTV(village, hit[0], hit[1], hit[2], 
                        ctx.tvSystem, ctx.tvChannel);
                }
            }
        }

        // Compute camera position: zoom in front of TV with slight angle
        Vector3f playerPos = ctx.player.getPosition();
        float tvCX = hit[0] + 0.5f;
        float tvCY = hit[1] + 0.8f;
        float tvCZ = hit[2] + 0.5f;

        // Camera: position 2 blocks away from TV's front face
        float cx = tvCX;
        float cy = tvCY + 0.3f;
        float cz = tvCZ + 2.5f; // In front of the TV

        ctx.tvCutsceneCameraStart.set(playerPos.x, playerPos.y + 1.6f, playerPos.z);
        ctx.tvCutsceneCameraTarget.set(cx, cy, cz);
        ctx.tvCutsceneStartYaw = ctx.yaw;
        ctx.tvCutsceneStartPitch = ctx.pitch;
        ctx.tvCutsceneTargetYaw = -90;
        ctx.tvCutsceneTargetPitch = -10;

        ctx.tvCutsceneActive = true;
        ctx.tvCutsceneTimer = 0.0f;
        ctx.tvWatching = false;
        ctx.setStatus("Watching TV - Channel: " + 
            (ctx.tvSystem != null ? ctx.tvSystem.getChannelName(ctx.tvChannel) : "VNN"));
    }

    /** Cycle the TV channel. Called when player presses a key while watching. */
    public void cycleTVChannel() {
        if (ctx.tvSystem != null) {
            int newChannel = ctx.tvSystem.nextChannel(ctx.tvBlockX, ctx.tvBlockY, ctx.tvBlockZ);
            ctx.tvChannel = newChannel;
            ctx.setStatus("TV: " + ctx.tvSystem.getChannelName(newChannel));
        }
    }

    /** Stop watching TV. */
    public void stopWatchingTV() {
        ctx.tvCutsceneActive = false;
        ctx.tvWatching = false;
        
        // Dismiss villagers
        if (ctx.villageManager != null && ctx.tvSystem != null) {
            com.voxel.game.VillagerVillageManager.Village village = 
                ctx.villageManager.findVillageAt(ctx.tvBlockX, ctx.tvBlockZ);
            if (village != null) {
                ctx.villageManager.dismissVillagersFromTV(village, 
                    ctx.tvBlockX, ctx.tvBlockY, ctx.tvBlockZ, ctx.tvSystem);
            }
        }
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
