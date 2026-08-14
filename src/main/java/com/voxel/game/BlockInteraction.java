package com.voxel.game;

import org.joml.Matrix4f;
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
    private static final float SURFACE_CRAFTING_REACH = 6.0f;

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
    private static final int BLOCK_HAND_CRANK = com.voxel.game.CreateMachineManager.BLOCK_HAND_CRANK;
    private static final int BLOCK_MECHANICAL_PRESS = com.voxel.game.CreateMachineManager.BLOCK_MECHANICAL_PRESS;
    private static final int BLOCK_MILLSTONE = com.voxel.game.CreateMachineManager.BLOCK_MILLSTONE;
    private static final int BLOCK_CRUSHING_WHEEL = com.voxel.game.CreateMachineManager.BLOCK_CRUSHING_WHEEL;
    private static final int BLOCK_DEPLOYER = com.voxel.game.CreateMachineManager.BLOCK_DEPLOYER;
    private static final int BLOCK_ITEM_VAULT = com.voxel.game.CreateMachineManager.BLOCK_ITEM_VAULT;
    private static final int BLOCK_COMMAND = CommandBlockManager.BLOCK_COMMAND;
    private static final int BLOCK_CHAIN_COMMAND = CommandBlockManager.BLOCK_CHAIN_COMMAND;
    private static final int BLOCK_REPEATING_COMMAND = CommandBlockManager.BLOCK_REPEATING_COMMAND;
    private static final int BLOCK_WATER = 15;
    private static final int BLOCK_WATER_FLOWING_MIN = 150;
    private static final int BLOCK_WATER_FLOWING_MAX = 156;
    private static final int BLOCK_LAVA = 21;

    /** Opens the furnace UI for the given block position. */
    public void openFurnace(int x, int y, int z) {
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
        // The ancient-builder facility's sealed test chest is the lore source of
        // power fragments. Populate it lazily so it persists through the normal
        // ChestManager save/load path without coupling world generation to UI state.
        if (com.voxel.world.AncientBuilderFacility.isPowerFragmentChest(x, y, z)
                && ctx.chestManager.getInventory(x, y, z) == null) {
            ItemStack[] inv = new ItemStack[ChestManager.CHEST_SLOTS];
            inv[0] = new ItemStack(CommandBlockManager.POWER_FRAGMENT, 4);
            ctx.chestManager.setInventory(x, y, z, inv);
            ctx.setStatus("Testing-facility archive: power fragments recovered");
        }
        ctx.chestOpen = true;
        ctx.inventoryOpen = true;
        ctx.activeUI = GameContext.ActiveUI.CHEST;
        ctx.setStatus("Chest");
    }

    /** Opens the item-vault UI (shares the ChestManager storage keyed by position). */
    private void openVault(int x, int y, int z) {
        ctx.chestBlockX = x;
        ctx.chestBlockY = y;
        ctx.chestBlockZ = z;
        ctx.chestOpen = true;
        ctx.inventoryOpen = true;
        ctx.activeUI = GameContext.ActiveUI.CHEST;
        ctx.setStatus("Item Vault");
    }

    /** Directional machines whose facing is encoded in extra-data bits 16-18. */
    private static boolean isDirectionalMachine(int block) {
        return block == 263 || block == 409 || block == 410 || block == 411 || block == 412 || block == 413;
    }

    /**
     * Start the furnace walk-up cutscene. The player steps toward the side of the
     * furnace they clicked while the camera pans to frame it; when the animation
     * finishes, Main.tick() calls openFurnace() to show the UI.
     */
    private void startFurnaceCutscene(int[] hit) {
        int fx = hit[0], fy = hit[1], fz = hit[2];
        ctx.furnaceBlockX = fx;
        ctx.furnaceBlockY = fy;
        ctx.furnaceBlockZ = fz;

        Vector3f playerPos = ctx.player.getPosition();

        // Approach direction: the adjacent block on the clicked face (hit[3]/hit[5]).
        // If the top/bottom face was clicked, fall back to the player's approach.
        int dx = hit[3] - fx;
        int dz = hit[5] - fz;
        if (dx == 0 && dz == 0) {
            dx = (int) Math.signum(playerPos.x - (fx + 0.5f));
            dz = (int) Math.signum(playerPos.z - (fz + 0.5f));
            if (dx == 0 && dz == 0) dz = 1;
        }

        // Walk target: one block away from the furnace on the approach side
        ctx.furnaceCutsceneStartPos.set(playerPos);
        ctx.furnaceCutsceneTargetPos.set(fx + 0.5f + dx, fy, fz + 0.5f + dz);

        // Camera: behind the walk target, slightly elevated, looking at the furnace
        float camX = fx + 0.5f + dx * 2.0f;
        float camY = fy + 0.8f;
        float camZ = fz + 0.5f + dz * 2.0f;

        // Step the camera further back until it clears any solid blocks
        float[] distances = {2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
        for (float dist : distances) {
            float cx = fx + 0.5f + dx * dist;
            float cz = fz + 0.5f + dz * dist;
            int voxel = ctx.world.getVoxel((int) Math.floor(cx), (int) Math.floor(camY), (int) Math.floor(cz));
            if (voxel == 0 || !ctx.blockDataManager.isFullBlock(voxel)) {
                camX = cx;
                camZ = cz;
                break;
            }
        }

        ctx.furnaceCutsceneCameraStart.set(playerPos.x, playerPos.y + 1.6f, playerPos.z);
        ctx.furnaceCutsceneCameraTarget.set(camX, camY, camZ);
        ctx.furnaceCutsceneStartYaw = ctx.yaw;
        ctx.furnaceCutsceneStartPitch = ctx.pitch;
        // Look from the camera back toward the furnace center. Yaw convention is
        // look.x = cos(yaw), look.z = sin(yaw), so yaw = atan2(dz, dx) — the
        // previous atan2(dx, dz) framed the furnace 90° off.
        float targetYaw = (float) Math.toDegrees(Math.atan2(fz + 0.5f - camZ, fx + 0.5f - camX));
        // Take the shortest angular path so the camera pans directly to face the
        // furnace instead of spinning the long way across the ±180° seam.
        float dYaw = ((targetYaw - ctx.furnaceCutsceneStartYaw + 540.0f) % 360.0f) - 180.0f;
        ctx.furnaceCutsceneTargetYaw = ctx.furnaceCutsceneStartYaw + dYaw;
        ctx.furnaceCutsceneTargetPitch = -15;

        ctx.furnaceCutsceneActive = true;
        ctx.furnaceCutsceneTimer = 0.0f;
        ctx.setStatus("Walking to furnace...");
    }

    public void updateMining(float dt) {
        if (ctx.inventoryOpen || ctx.commandMode || !ctx.leftMouseHeld || ctx.player.isDead()
                || ctx.craftingCutsceneActive || ctx.furnaceCutsceneActive || ctx.tvCutsceneActive) {
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
        if (ctx.kineticManager != null) {
            ctx.kineticManager.onBlockChanged(x, y, z);
        }
        if (ctx.encasedFanSystem != null) {
            ctx.encasedFanSystem.onBlockChanged(x, y, z);
        }
        if (ctx.machineManager != null) {
            // Deployer contents are returned to the player before the position is
            // untracked, so the item can be unloaded first.
            if (blockId == BLOCK_DEPLOYER) {
                ctx.machineManager.unloadDeployerToInventory(x, y, z, ctx.playerInventory);
            }
            ctx.machineManager.onBlockChanged(x, y, z);
        }
        // Notify fluid manager: block removed may open space for fluid to flow into
        if (ctx.fluidManager != null) {
            ctx.fluidManager.notifyBlockChanged(x, y, z);
        }
        // Notify dropped items: if any item was resting on this block, it should fall
        if (ctx.droppedItemManager != null) {
            ctx.droppedItemManager.onBlockDestroyed(x, y, z);
        }

        // Surface-crafting ingredients belong to any block, not only crafting tables.
        String[][] surfaceGrid = ctx.surfaceCraftingManager.removeGrid(x, y, z);
        if (surfaceGrid != null) {
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    if (surfaceGrid[r] != null && surfaceGrid[r][c] != null) {
                        ctx.playerInventory.addItem(surfaceGrid[r][c], 1);
                    }
                }
            }
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

        // If breaking a chest or item vault, return items to the player
        if (blockId == 118 || blockId == BLOCK_ITEM_VAULT) {
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
            dropItem = dropItemForBlock(blockId);
            if (dropItem == null) {
                dropItem = ctx.itemDefinitions.getBlockItemByBlockId().get(blockId);
            }
            if (blockId == 26 || blockId == 85) {
                dropCount = 4;
            }
            // Drop into the world; player must walk over to pick up.
            if (dropItem != null && ctx.droppedItemManager != null) {
                ctx.droppedItemManager.spawn(dropItem, dropCount, x, y, z);
            }
        }
    }

    /**
     * Returns a special mined item for blocks whose drop differs from their placed item.
     * Stone remains the placeable stone block (ID 2), but mining it yields cobblestone.
     *
     * Package-private so the drop rule can be regression-tested without constructing the
     * full OpenGL-backed game context.
     */
    static String dropItemForBlock(int blockId) {
        if (blockId == 2) return "cobblestone";
        if (blockId == 26) return "redstone_wire";
        if (blockId == 85) return "lapis_ore";
        // Proper progression: coal and diamond ores drop their material, not the block
        if (blockId == 61) return "coal";
        if (blockId == 83) return "diamond";
        return null;
    }

    /**
     * Picks the rail orientation (RAIL_NS vs RAIL_EW) for a rail being placed at
     * (px, py, pz). Existing rail neighbours take precedence; a free-standing
     * rail runs perpendicular to the player's dominant look axis.
     * Public static so it can be regression-tested without GL.
     */
    public static int chooseRailAxis(com.voxel.World world, int px, int py, int pz, float lookDx, float lookDz) {
        boolean ns = isRailAt(world, px, py, pz - 1) || isRailAt(world, px, py, pz + 1);
        boolean ew = isRailAt(world, px - 1, py, pz) || isRailAt(world, px + 1, py, pz);
        if (ns != ew) {
            return ns ? com.voxel.entity.MinecartEntity.RAIL_NS : com.voxel.entity.MinecartEntity.RAIL_EW;
        }
        // No rail neighbours (or a cross): align with the player's look.
        return Math.abs(lookDz) >= Math.abs(lookDx)
                ? com.voxel.entity.MinecartEntity.RAIL_NS
                : com.voxel.entity.MinecartEntity.RAIL_EW;
    }

    private static boolean isRailAt(com.voxel.World world, int x, int y, int z) {
        int b = world.getVoxel(x, y, z);
        return com.voxel.entity.MinecartEntity.isRail(b);
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
        } else if (e instanceof com.voxel.entity.MinecartEntity) {
            // Right-click a cart: ride it, or get off if already riding.
            if (ctx.ridingMinecart == e) {
                if (ctx.dismountMinecart != null) ctx.dismountMinecart.run();
                ctx.setStatus("Dismounted minecart");
            } else {
                ctx.ridingMinecart = e;
                ctx.setStatus("Riding minecart — W/S to move, E to dismount");
            }
        }
    }

    /**
     * Starts the 2x2 surface-crafting overlay on the block under the cursor.
     * The target must be a full block with an air-exposed top and a clear,
     * reachable line from the player's eye to the top surface.
     */
    public void attemptSurfaceCrafting() {
        if (ctx.player.isDead() || ctx.inventoryOpen || ctx.commandMode
                || ctx.craftingCutsceneActive || ctx.tvCutsceneActive) return;

        int[] hit = raycastBlock(SURFACE_CRAFTING_REACH);
        if (hit == null) {
            ctx.setStatus("No reachable block for surface crafting");
            return;
        }
        int blockId = ctx.world.getVoxel(hit[0], hit[1], hit[2]);
        if (!isReachableExposedTop(hit[0], hit[1], hit[2], blockId)) {
            ctx.setStatus("The block's top is not exposed or reachable");
            return;
        }

        ctx.surfaceCraftingOpen = true;
        ctx.surfaceCraftingBlockX = hit[0];
        ctx.surfaceCraftingBlockY = hit[1];
        ctx.surfaceCraftingBlockZ = hit[2];
        ctx.inventoryOpen = true;
        ctx.activeUI = ActiveUI.SURFACE_CRAFTING;
        ctx.playerInventory.loadSurfaceCraftingGrid(hit[0], hit[1], hit[2]);
        ctx.setStatus("Surface crafting — use the four top-face quadrants");
    }

    /** Returns true only when the target is a full block with a clear top and eye ray. */
    private boolean isReachableExposedTop(int x, int y, int z, int blockId) {
        if (blockId <= 0 || !ctx.blockDataManager.isFullBlock(blockId)) return false;
        int above = ctx.world.getVoxel(x, y + 1, z);
        if (above != 0) return false;

        Vector3f eye = getActiveCameraPosition().set(
            ctx.player.getPosition().x,
            ctx.player.getPosition().y + 1.6f,
            ctx.player.getPosition().z
        );
        Vector3f top = new Vector3f(x + 0.5f, y + 1.01f, z + 0.5f);
        if (eye.distance(top) > SURFACE_CRAFTING_REACH) return false;

        Vector3f delta = new Vector3f(top).sub(eye);
        float length = delta.length();
        if (length <= 0.001f) return true;
        Vector3f dir = delta.normalize();
        for (float d = 0.05f; d < length - 0.05f; d += 0.05f) {
            Vector3f sample = new Vector3f(eye).fma(d, dir);
            int voxel = ctx.world.getVoxel(
                (int) Math.floor(sample.x),
                (int) Math.floor(sample.y),
                (int) Math.floor(sample.z)
            );
            if (voxel != 0 && !(voxel == blockId
                    && Math.floor(sample.x) == x
                    && Math.floor(sample.y) == y
                    && Math.floor(sample.z) == z)) {
                return false;
            }
        }
        return true;
    }

    /** Raycasts the mouse cursor onto the active block's top face and returns quadrant 0..3. */
    public int raycastSurfaceCraftingCell() {
        if (!ctx.surfaceCraftingOpen) return -1;
        Vector3f origin = getActiveCameraPosition();
        Vector3f lookTarget = new Vector3f(origin).add(getLookDirection());
        Matrix4f projection = new Matrix4f().perspective(
            (float) Math.toRadians(70.0),
            (float) ctx.width / Math.max(1, ctx.height),
            0.1f, 2048.0f
        );
        Matrix4f view = new Matrix4f().lookAt(origin, lookTarget, new Vector3f(0, 1, 0));
        Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
        float ndcX = (ctx.lastMouseX / Math.max(1, ctx.width)) * 2.0f - 1.0f;
        float ndcY = 1.0f - (ctx.lastMouseY / Math.max(1, ctx.height)) * 2.0f;
        Vector3f near = new Vector3f(ndcX, ndcY, -1.0f).mulProject(inverse);
        Vector3f far = new Vector3f(ndcX, ndcY, 1.0f).mulProject(inverse);
        Vector3f direction = new Vector3f(far).sub(near).normalize();

        float topY = ctx.surfaceCraftingBlockY + 1.0f;
        if (Math.abs(direction.y) < 1e-6f) return -1;
        float t = (topY - near.y) / direction.y;
        if (t <= 0.0f || t > SURFACE_CRAFTING_REACH) return -1;
        float x = near.x + direction.x * t;
        float z = near.z + direction.z * t;
        float u = x - ctx.surfaceCraftingBlockX;
        float v = z - ctx.surfaceCraftingBlockZ;
        if (u < 0.0f || u >= 1.0f || v < 0.0f || v >= 1.0f) return -1;

        int col = Math.min(1, (int) (u * 2.0f));
        int row = Math.min(1, (int) (v * 2.0f));
        return row * 2 + col;
    }

    private void openCommandBlockEditor(int x, int y, int z) {
        CommandBlockManager.CommandBlockState state = ctx.commandBlockManager.getOrCreate(x, y, z);
        if (state.command == null || state.command.trim().isEmpty()) {
            state.command = com.voxel.world.AncientBuilderFacility.defaultCommandAt(x, y, z);
        }
        ctx.commandBlockEditorX = x;
        ctx.commandBlockEditorY = y;
        ctx.commandBlockEditorZ = z;
        ctx.commandBlockEditorCommand = state.command == null ? "" : state.command;
        ctx.commandBlockEditorOpen = true;
        ctx.commandMode = true;
        ctx.inventoryOpen = true;
        ctx.activeUI = ActiveUI.COMMAND_BLOCK;
        if (ctx.updateCursorMode != null) ctx.updateCursorMode.run();
        ctx.setStatus("Ancient-builder command console — power fragments enable survival programming");
    }

    public boolean saveCommandBlockEditor() {
        if (!ctx.commandBlockEditorOpen) return false;
        String status = ctx.commandBlockManager.program(ctx,
            ctx.commandBlockEditorX, ctx.commandBlockEditorY, ctx.commandBlockEditorZ,
            ctx.commandBlockEditorCommand);
        ctx.setStatus(status);
        boolean saved = status.equals("Command block programmed.") || status.equals("Command block cleared.");
        if (saved && ctx.worldSaveManager != null) {
            ctx.worldSaveManager.saveCommandBlockData(ctx.activeDimension, ctx.commandBlockManager);
        }
        return saved;
    }

    public void attemptPlaceBlock() {
        if (ctx.player.isDead()) return;
        int[] hit = raycastBlock(6.0f);

        // Check for entity interaction — if player looks at a villager (even through no block)
        if (!ctx.inventoryOpen && !ctx.commandMode && !ctx.tvCutsceneActive && !ctx.craftingCutsceneActive && !ctx.furnaceCutsceneActive) {
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

        // Right-click on a command block opens the ancient-builder program editor.
        if (CommandBlockManager.isCommandBlock(hitBlock) && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.tvCutsceneActive) {
            openCommandBlockEditor(hit[0], hit[1], hit[2]);
            return;
        }

        // Right-click on villager TV block — start TV cutscene
        if (hitBlock == BLOCK_TV && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.tvCutsceneActive) {
            startTVCutscene(hit);
            return;
        }

        // Right-click on furnace — walk-up cutscene, then the furnace UI opens
        if ((hitBlock == BLOCK_FURNACE || hitBlock == BLOCK_FURNACE_ON) && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.tvCutsceneActive && !ctx.furnaceCutsceneActive && !ctx.furnaceOpen) {
            startFurnaceCutscene(hit);
            return;
        }

        // Right-click on blaze burner: add fuel (coal, blaze rod, blaze powder)
        if (com.voxel.world.BlazeBurnerManager.isBlazeBurner(hitBlock) && !ctx.inventoryOpen && !ctx.craftingCutsceneActive) {
            ItemDefinitions.ItemStack held = ctx.playerInventory.getSelected();
            if (held != null) {
                int fuelTicks = 0;
                if (held.itemId.equals("coal")) fuelTicks = 1600;
                else if (held.itemId.equals("blaze_rod")) fuelTicks = 2400;
                else if (held.itemId.equals("blaze_powder")) fuelTicks = 800;
                if (fuelTicks > 0 && ctx.blazeBurnerManager != null) {
                    ctx.blazeBurnerManager.addFuel(hit[0], hit[1], hit[2], fuelTicks);
                    held.count--;
                    if (held.count <= 0) ctx.playerInventory.clearSlot(ctx.playerInventory.getSelectedSlot());
                    ctx.setStatus("Added fuel to blaze burner");
                    return;
                }
            }
        }

        // Right-click copper tank with bucket: fill/drain
        if (com.voxel.world.CopperTankManager.isCopperTank(hitBlock) && !ctx.inventoryOpen && !ctx.craftingCutsceneActive) {
            ItemDefinitions.ItemStack held = ctx.playerInventory.getSelected();
            if (held != null && ctx.copperTankManager != null) {
                if (held.itemId.equals("water_bucket")) {
                    if (ctx.copperTankManager.fill(hit[0], hit[1], hit[2])) {
                        ctx.playerInventory.replaceSelected("bucket");
                        ctx.setStatus("Filled copper tank");
                        return;
                    }
                } else if (held.itemId.equals("bucket")) {
                    if (ctx.copperTankManager.drain(hit[0], hit[1], hit[2])) {
                        ctx.playerInventory.replaceSelected("water_bucket");
                        ctx.setStatus("Drained copper tank");
                        return;
                    }
                }
            }
        }

        // Right-click hand crank: wind it up for 5 seconds of rotation
        if (hitBlock == BLOCK_HAND_CRANK && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && ctx.machineManager != null) {
            ctx.machineManager.spinCrank(hit[0], hit[1], hit[2]);
            ctx.setStatus("Cranked — the network spins for 5 seconds");
            return;
        }

        // Right-click windmill bearing: report the sail setup
        if (hitBlock == com.voxel.game.CreateMachineManager.BLOCK_WINDMILL_BEARING
                && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && ctx.machineManager != null) {
            int sails = ctx.machineManager.windmillSailCount(hit[0], hit[1], hit[2]);
            boolean spinning = ctx.machineManager.isWindmillSpinning(hit[0], hit[1], hit[2]);
            ctx.setStatus("Windmill bearing: " + sails + " sails, " + (spinning ? "spinning" : "needs 2+ exposed sails"));
            return;
        }

        // Right-click item vault: opens like a chest
        if (hitBlock == BLOCK_ITEM_VAULT && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.chestOpen) {
            openVault(hit[0], hit[1], hit[2]);
            return;
        }

        // Right-click on chest
        if (hitBlock == BLOCK_CHEST && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && !ctx.chestOpen) {
            openChest(hit[0], hit[1], hit[2]);
            return;
        }

        // Right-click on a repeater cycles its delay; on a comparator toggles mode
        if (com.voxel.world.RedstoneManager.isRepeater(hitBlock) || com.voxel.world.RedstoneManager.isComparator(hitBlock)) {
            if (ctx.inventoryOpen || ctx.craftingCutsceneActive || ctx.tvCutsceneActive || ctx.furnaceCutsceneActive || ctx.chestOpen) {
                return;
            }
            int raw = ctx.world.getRawVoxel(hit[0], hit[1], hit[2]);
            if (com.voxel.world.RedstoneManager.isRepeater(hitBlock)) {
                int delay = ((raw >> 16) & 0xF);
                if (delay < 1 || delay > 4) delay = 1;
                delay = (delay % 4) + 1;
                ctx.chunkManager.setVoxelWithData(hit[0], hit[1], hit[2], hitBlock, delay);
                ctx.setStatus("Repeater delay set to " + delay + (delay == 1 ? " tick" : " ticks"));
            } else {
                int newId = hitBlock ^ 8;
                ctx.chunkManager.setVoxelWithData(hit[0], hit[1], hit[2], newId, 0);
                ctx.setStatus(newId >= 345 ? "Comparator mode: subtract" : "Comparator mode: compare");
            }
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

        // ── Wrench: rotate a directional machine's facing, or read power state ──
        if ("wrench".equals(selected.itemId) && !ctx.inventoryOpen && !ctx.craftingCutsceneActive) {
            if (isDirectionalMachine(hitBlock)) {
                int raw = ctx.world.getRawVoxel(hit[0], hit[1], hit[2]);
                int dir = (raw >> 16) & 0x7;
                int nd = (dir > 5) ? 1 : (dir + 1) % 6;
                ctx.chunkManager.setVoxelWithData(hit[0], hit[1], hit[2], hitBlock, nd);
                ctx.setStatus("Rotated facing " + com.voxel.game.CreateMachineManager.directionName(nd));
                return;
            }
            if (ctx.machineManager != null && com.voxel.game.CreateMachineManager.isMachineBlock(hitBlock)) {
                boolean powered = ctx.machineManager.isMachinePowered(hit[0], hit[1], hit[2]);
                ctx.setStatus(powered ? "Machine is Powered" : "Machine is Idle — connect rotation");
                return;
            }
        }

        // ── Deployer: load with a block item, or report contents ──
        if (hitBlock == BLOCK_DEPLOYER && !ctx.inventoryOpen && !ctx.craftingCutsceneActive && ctx.machineManager != null) {
            if (def.kind == ItemDefinitions.ItemKind.BLOCK && def.blockId > 0) {
                if (ctx.machineManager.loadDeployer(hit[0], hit[1], hit[2], selected.itemId)) {
                    if (ctx.gameMode == GameMode.SURVIVAL) {
                        selected.count--;
                        if (selected.count <= 0) ctx.playerInventory.setSlot(ctx.playerInventory.getSelectedSlot(), null);
                    }
                    if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
                    ctx.setStatus("Deployer loaded: " + selected.itemId.replace('_', ' '));
                    return;
                }
                ctx.setStatus("Deployer is full or holds another item");
                return;
            }
            ctx.setStatus("Deployer holds: " + ctx.machineManager.deployerStatus(hit[0], hit[1], hit[2]));
            return;
        }

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

        // ── Minecart item: spawn a cart entity on the target rail (not a block) ──
        if (def.id.equals("minecart")) {
            int px = hit[3], py = hit[4], pz = hit[5];
            int target = ctx.world.getVoxel(px, py, pz);
            if (!com.voxel.entity.MinecartEntity.isRail(target)) {
                ctx.setStatus("Place minecarts on rails");
                return;
            }
            if (ctx.minecartSpawnQueue != null) {
                ctx.minecartSpawnQueue.add(new Vector3f(
                        px + 0.5f, py + com.voxel.entity.MinecartEntity.RAIL_TOP, pz + 0.5f));
            }
            if (ctx.gameMode == GameMode.SURVIVAL) {
                selected.count--;
                if (selected.count <= 0) ctx.playerInventory.setSlot(ctx.playerInventory.getSelectedSlot(), null);
            }
            if (ctx.uiDirtyMarker != null) ctx.uiDirtyMarker.run();
            ctx.setStatus("Placed minecart");
            return;
        }

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
        // Orientable shafts: same rule -> 291 (Y axis), 292 (X axis), 293 (Z axis)
        if (placeBlockId == 291) {
            int ldx = hit[0] - px, ldz = hit[2] - pz;
            if (ldx != 0) placeBlockId = 292;
            else if (ldz != 0) placeBlockId = 293;
        }
        // Rails: need a full solid block underneath; choose N-S or E-W axis from
        // neighbouring rails (or the player's look direction when free-standing).
        if (placeBlockId == com.voxel.entity.MinecartEntity.RAIL_NS) {
            int below = ctx.world.getVoxel(px, py - 1, pz);
            if (below == 0 || ctx.blockDataManager.isLiquid(below) || !ctx.blockDataManager.isFullBlock(below)) {
                ctx.setStatus("Rails need a solid block below");
                return;
            }
            Vector3f look = getLookDirection();
            placeBlockId = chooseRailAxis(ctx.world, px, py, pz, look.x, look.z);
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
        } else if (placeBlockId == 263 || (placeBlockId >= 409 && placeBlockId <= 413)) {
            // Encased fan + directional Create machines: encode facing into extra data
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
        } else if (placeBlockId >= 329 && placeBlockId <= 336) {
            // Repeater: horizontal facing from the clicked face, default 1-tick delay
            int dir = horizontalFacing(hit, px, py, pz);
            if (!ctx.chunkManager.setVoxelWithData(px, py, pz, 329 + dir - 2, 1)) return;
        } else if (placeBlockId >= 337 && placeBlockId <= 352) {
            // Comparator: horizontal facing from the clicked face, compare mode
            int dir = horizontalFacing(hit, px, py, pz);
            if (!ctx.chunkManager.setVoxelWithData(px, py, pz, 337 + dir - 2, 0)) return;
        } else {
            if (!ctx.chunkManager.setVoxel(px, py, pz, placeBlockId)) return;
        }

        ctx.redstoneManager.onBlockChanged(px, py, pz);
        ctx.redstoneManager.notifyNeighbors(px, py, pz);
        if (ctx.kineticManager != null) {
            ctx.kineticManager.onBlockChanged(px, py, pz);
        }
        if (ctx.encasedFanSystem != null) {
            ctx.encasedFanSystem.onBlockChanged(px, py, pz);
        }
        if (ctx.machineManager != null) {
            ctx.machineManager.onBlockChanged(px, py, pz);
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

    /**
     * Horizontal facing for repeater/comparator placement from the clicked face
     * normal: 2=north, 3=south, 4=west, 5=east. Clicking a top/bottom face
     * defaults to north (like Minecraft).
     */
    private int horizontalFacing(int[] hit, int px, int py, int pz) {
        int dx = hit[0] - px, dz = hit[2] - pz;
        if (dx > 0) return 5;
        if (dx < 0) return 4;
        if (dz > 0) return 3;
        if (dz < 0) return 2;
        return 2;
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
