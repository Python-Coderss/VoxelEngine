package com.voxel.game;

import com.voxel.Player;
import com.voxel.World;
import com.voxel.crafting.CraftingManager;
import com.voxel.entity.EntityManager;
import com.voxel.entity.PlayerEntity;
import com.voxel.utils.BiomeManager;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.TextureManager;
import com.voxel.world.ChunkManager;
import com.voxel.world.DimensionManager;
import com.voxel.world.DimensionType;
import com.voxel.world.RedstoneManager;
import org.joml.Vector2i;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared mutable game state passed to extracted subsystems.
 * This is a simple data holder - not a god class with behavior.
 */
public class GameContext {
    public enum GameMode { SURVIVAL, CREATIVE }
    public enum CameraMode { FIRST_PERSON, THIRD_PERSON }

    // --- World / Dimension ---
    public World world;
    public ChunkManager chunkManager;
    public DimensionManager dimensionManager;
    public DimensionType activeDimension = DimensionType.OVERWORLD;
    public RedstoneManager redstoneManager;

    // --- Entity ---
    public EntityManager entityManager;
    public PlayerEntity playerEntity;

    // --- Core managers ---
    public ItemDefinitions itemDefinitions;
    public PlayerInventory playerInventory;
    public BlockDataManager blockDataManager;
    public BiomeManager biomeManager;
    public TextureManager textureManager;
    public CraftingManager craftingManager;

    // --- Player ---
    public Player player;

    // --- Mutable game state ---
    public GameMode gameMode = GameMode.SURVIVAL;
    public CameraMode cameraMode = CameraMode.FIRST_PERSON;
    public float worldTime = 720.0f;
    public boolean combatMode = false;
    public boolean inventoryOpen = false;

    // --- Camera ---
    public float yaw = -90, pitch = 0;
    public float playerYaw = -90;
    public float lastMouseX = 640, lastMouseY = 360;
    public boolean firstMouse = true;
    public float cameraShake = 0.0f;
    public double lastAttackTime = 0;
    public double lastRollTime = 0;

    // --- UI state ---
    public int uiTextureId = 0;
    public Vector2i uiTextureSize = new Vector2i(1, 1);
    public int fontTextureId = 0;
    public Vector2i fontTextureSize = new Vector2i(1, 1);
    public Vector4f uvHeartFull = new Vector4f(70, 0, 9, 9);
    public Vector4f uvHeartHalf = new Vector4f(61, 0, 9, 9);
    public Vector4f uvHeartEmpty = new Vector4f(52, 0, 9, 9);

    // --- Status / Command ---
    public boolean commandMode = false;
    public StringBuilder commandBuffer = new StringBuilder();
    public String statusMessage = "";
    public double statusUntil = 0.0;
    public int lastMeasuredFps = 0;

    // --- Render ---
    public int width = 1280, height = 720;
    public int lastBiomeOffsetX = 0, lastBiomeOffsetZ = 0;

    // --- Mining ---
    public int breakTargetX = Integer.MIN_VALUE;
    public int breakTargetY = Integer.MIN_VALUE;
    public int breakTargetZ = Integer.MIN_VALUE;
    public float breakProgress = 0.0f;
    public boolean leftMouseHeld = false;
    public boolean leftMousePressedThisFrame = false;
    public double lastPortalTeleportTime = 0;

    // --- Runnables passed by Main for dimension switching ---
    public Runnable uploadWorldToGpu;
    public Runnable updateCursorMode;
    public java.util.function.Consumer<String> statusConsumer;

    public void setStatus(String msg) {
        statusMessage = msg;
        statusUntil = System.currentTimeMillis() / 1000.0 + 3.0;
        System.out.println(msg);
        if (statusConsumer != null) statusConsumer.accept(msg);
    }

    public void switchToDimension(DimensionType target) {
        DimensionType previous = activeDimension;
        int renderDistance = target == DimensionType.OVERWORLD ? 8 : 6;
        dimensionManager.ensureDimension(target, renderDistance);
        dimensionManager.switchTo(target);
        activeDimension = target;
        world = dimensionManager.getActiveWorld();
        chunkManager = dimensionManager.getActiveChunkManager();
        redstoneManager = new RedstoneManager(world, chunkManager);
        com.voxel.world.RedstoneLogger.log("DIMENSION SWITCH: created new RedstoneManager for " + target.name + " (was " + previous.name + ")");
        if (previous != target) dimensionManager.unloadDimension(previous);
        int spawnY = target.baseHeight + 3;
        player.getPosition().set(1024, spawnY, 1024);
        if (uploadWorldToGpu != null) uploadWorldToGpu.run();
        setStatus("Switched to " + target.name);
    }
}
