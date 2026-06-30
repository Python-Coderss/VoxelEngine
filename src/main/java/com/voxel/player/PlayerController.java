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
    private static final float FLY_MOVE_SPEED = 0.05f;
    private static final float AETHER_PARACHUTE_FALL_THRESHOLD = -8.0f;

    // Sprint double-tap W detection
    private double lastWPressTime = 0;
    private boolean wWasPressed = false;

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

        // Standard WASD: W=forward, A=left, S=backward, D=right
        float strafe = 0, forward = 0;
        if (glfwGetKey(main.window, GLFW_KEY_W) == GLFW_PRESS) forward += 1.0f;
        if (glfwGetKey(main.window, GLFW_KEY_S) == GLFW_PRESS) forward -= 1.0f;
        if (glfwGetKey(main.window, GLFW_KEY_A) == GLFW_PRESS) strafe += 1.0f;
        if (glfwGetKey(main.window, GLFW_KEY_D) == GLFW_PRESS) strafe -= 1.0f;

        // Sprint: Left Control key OR double-tap W while on ground and moving forward
        boolean ctrlDown = glfwGetKey(main.window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS;
        boolean wDown = glfwGetKey(main.window, GLFW_KEY_W) == GLFW_PRESS;
        boolean sprintByCtrl = ctrlDown && !main.player.isFlying() && main.player.isOnGround();

        // Double-tap W: measure gap between release and re-press (<300ms)
        boolean sprintByDoubleTap = false;
        if (!wDown && wWasPressed) {
            lastWPressTime = glfwGetTime();  // record release time
        }
        if (wDown && !wWasPressed && forward > 0.1f && !main.player.isFlying() && main.player.isOnGround()) {
            if (glfwGetTime() - lastWPressTime < 0.3) {
                sprintByDoubleTap = true;
            }
        }
        wWasPressed = wDown;

        main.player.setSprinting(sprintByCtrl || sprintByDoubleTap);

        // Normalize for diagonals
        float mvLen = (float) Math.sqrt(strafe * strafe + forward * forward);
        if (mvLen > 0) {
            strafe /= mvLen;
            forward /= mvLen;
            if (main.combatMode) {
                // Axis-aligned restriction
                if (Math.abs(strafe) > Math.abs(forward)) forward = 0; else strafe = 0;
            }
            if (main.cameraMode == CameraMode.THIRD_PERSON_FOLLOW) {
                // Compute world-space direction matching tick() rotation convention
                // forward → (cos, sin), left strafe → (-sin, cos)
                float wx = forward * fx + strafe * fz;
                float wz = forward * fz - strafe * fx;
                main.playerYaw = (float) Math.toDegrees(Math.atan2(wz, wx));
            }
        }
        // Pass raw strafe/forward to move() — tick() handles yaw rotation + acceleration
        // Note: move(dx=strafing, dy, dz=forward, speed) — strafe→dx, forward→dz
        main.player.move(strafe, 0, forward, 0);

        if (glfwGetKey(main.window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            if (main.gameMode == GameMode.CREATIVE) {
                // Creative: auto-fly, no isFlying gate needed
                main.player.setFlying(true);
                main.player.move(0, 0.05f, 0, 0);
            } else if (main.player.isFlying()) {
                main.player.move(0, FLY_MOVE_SPEED, 0, 0);
            } else {
                main.player.jump(world, blockDataManager);
            }
        }
        if (glfwGetKey(main.window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            if (main.gameMode == GameMode.CREATIVE) {
                // Creative: shift = fly down, not sneak
                main.player.setFlying(true);
                main.player.setSneaking(false);
                main.player.move(0, -FLY_MOVE_SPEED, 0, 0);
            } else if (main.player.isFlying()) {
                main.player.move(0, -FLY_MOVE_SPEED, 0, 0);
            }
            // Survival: shift is handled by setSneaking() call above
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
