package com.voxel.player;

import com.voxel.Main;
import com.voxel.Player;
import com.voxel.camera.CameraController;
import com.voxel.game.GameContext;
import com.voxel.game.GameContext.CameraMode;
import com.voxel.game.GameContext.GameMode;
import com.voxel.game.ItemDefinitions.ItemStack;
import com.voxel.game.PlayerInventory;
import com.voxel.utils.BlockDataManager;
import com.voxel.world.DimensionType;
import com.voxel.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Encapsulates per-tick player input (movement, jumping, flying, parachutes) and the
 * player.update() and playerEntity.sync() calls previously embedded in Main.tick().
 */
public class PlayerController {
    private static final float FLY_MOVE_SPEED = 1.5f;
    private static final float WALK_MOVE_SPEED = 0.4f;
    private static final float AETHER_PARACHUTE_FALL_THRESHOLD = -8.0f;

    private final GameContext ctx;
    private final Main main;
    private final CameraController camera;
    private final BlockDataManager blockDataManager;
    private final World world;
    private final PlayerInventory playerInventory;

    public PlayerController(GameContext ctx, Main main, CameraController camera,
                            BlockDataManager blockDataManager, World world,
                            PlayerInventory playerInventory) {
        this.ctx = ctx;
        this.main = main;
        this.camera = camera;
        this.blockDataManager = blockDataManager;
        this.world = world;
        this.playerInventory = playerInventory;
    }

    /**
     * Per-frame input handling moved from Main.handleInput(dt).
     * Skips if inventory / command / death / crafting-cutscene is active.
     */
    public void handleInput(float dt) {
        if (main.inventoryOpen || main.commandMode || main.player.isDead() || ctx.craftingCutsceneActive) return;

        // Forward / right vectors for WASD
        double ry = Math.toRadians(main.yaw);
        float fx = (float) Math.cos(ry), fz = (float) Math.sin(ry);
        float rx = -fz, rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl > 0) { rx /= rl; rz /= rl; }

        // Direction-aware dodge roll (handled inside Main.handleInput via the existing
        // dodge branch; PlayerController keeps the architectural slot for future migration).
        if (glfwGetKey(main.window, GLFW_KEY_LEFT_ALT) == GLFW_PRESS && main.combatMode) {
            // Reserved: dodge roll handled by Main.handleInput directly.
        }

        // Combat charge / normal attack input remains in Main.handleInput.
        if (main.combatMode && !main.inventoryOpen) {
            // Reserved: charge handling in Main.handleInput.
        } else if (!main.combatMode && !main.inventoryOpen) {
            // Reserved: instant attack in Main.handleInput.
        }

        // Movement (WASD)
        float speed = main.player.isFlying() ? FLY_MOVE_SPEED : WALK_MOVE_SPEED;

        float dx = 0, dz = 0;
        if (glfwGetKey(main.window, GLFW_KEY_W) == GLFW_PRESS) { dx += fx; dz += fz; }
        if (glfwGetKey(main.window, GLFW_KEY_S) == GLFW_PRESS) { dx -= fx; dz -= fz; }
        if (glfwGetKey(main.window, GLFW_KEY_A) == GLFW_PRESS) { dx -= rx; dz -= rz; }
        if (glfwGetKey(main.window, GLFW_KEY_D) == GLFW_PRESS) { dx += rx; dz += rz; }

        float mvLen = (float) Math.sqrt(dx * dx + dz * dz);
        if (mvLen > 0) {
            dx /= mvLen;
            dz /= mvLen;
            if (main.combatMode) {
                if (Math.abs(dx) > Math.abs(dz)) dz = 0; else dx = 0;
            }
            if (main.cameraMode == CameraMode.THIRD_PERSON_FOLLOW) {
                main.playerYaw = (float) Math.toDegrees(Math.atan2(dz, dx));
            }
        }
        main.player.move(dx * speed, 0, dz * speed, speed * 2.0f);

        if (glfwGetKey(main.window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            if (main.player.isFlying()) main.player.move(0, speed, 0, speed * 2.0f);
            else main.player.jump(world, blockDataManager);
        }
        if (glfwGetKey(main.window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            if (main.player.isFlying()) main.player.move(0, -speed, 0, speed * 2.0f);
        }

        if (main.gameMode == GameMode.CREATIVE) {
            if (glfwGetKey(main.window, GLFW_KEY_F) == GLFW_PRESS) main.player.setFlying(true);
            if (glfwGetKey(main.window, GLFW_KEY_G) == GLFW_PRESS) main.player.setFlying(false);
        }
    }

    /**
     * Per-tick player updates (player.update, parachute deploy/land, playerEntity.sync)
     * moved from Main.tick().
     */
    public void tickPlayer(float dt) {
        // First-person → playerYaw tracks yaw directly
        if (main.cameraMode == CameraMode.FIRST_PERSON) {
            main.playerYaw = main.yaw;
        }

        main.player.setYaw(main.playerYaw);
        main.player.setPitch(main.pitch);
        if (main.playerEntity != null) {
            main.playerEntity.syncFromPlayer(main.player, main.playerYaw, main.pitch,
                main.cameraMode != CameraMode.FIRST_PERSON, dt);
        }

        // Aether parachute deploy (auto when falling fast)
        if (main.activeDimension == DimensionType.AETHER
                && !main.player.isOnGround()
                && !main.player.isParachuteDeployed()
                && main.player.getVelocity().y < AETHER_PARACHUTE_FALL_THRESHOLD
                && main.player.getPosition().y > 0) {
            for (int i = 0; i < main.playerInventory.getInventorySize(); i++) {
                ItemStack stack = main.playerInventory.getSlot(i);
                if (stack != null && (stack.itemId.equals("cold_parachute") || stack.itemId.equals("golden_parachute"))) {
                    if (stack.durability == 0 && stack.itemId.equals("golden_parachute")) {
                        stack.durability = 20;
                    }
                    main.player.deployParachute(stack.itemId, i);
                    main.setStatus("Parachute deployed!");
                    break;
                }
            }
        }

        // Parachute landing: consume durability when touching ground
        if (main.player.isOnGround() && main.player.getParachuteItemId() != null) {
            String itemId = main.player.getParachuteItemId();
            int slotIdx = main.player.getParachuteSlotIndex();
            main.player.resetParachute();
            if (slotIdx >= 0 && slotIdx < playerInventory.getInventorySize()) {
                ItemStack stack = playerInventory.getSlot(slotIdx);
                if (stack != null && stack.itemId.equals(itemId)) {
                    if (itemId.equals("golden_parachute")) {
                        stack.durability--;
                        if (stack.durability <= 0) {
                            stack.count--;
                            if (stack.count <= 0) playerInventory.clearSlot(slotIdx);
                            main.setStatus("Golden parachute worn out!");
                        } else {
                            main.setStatus("Parachute landed (" + stack.durability + " uses left)");
                        }
                    } else {
                        stack.count--;
                        if (stack.count <= 0) playerInventory.clearSlot(slotIdx);
                        main.setStatus("Parachute used up!");
                    }
                }
            }
        }
    }

    /** player.update() invocation (separate so the parachute tick can sit on top). */
    public void updatePlayerAndEntity(float dt) {
        main.player.update(dt, world, blockDataManager);
    }

    public static Vector3f computeForwardRightFlag(float yaw) {
        double ry = Math.toRadians(yaw);
        float fx = (float) Math.cos(ry), fz = (float) Math.sin(ry);
        Vector3f result = new Vector3f(fx, 0, fz);
        return result;
    }
}
