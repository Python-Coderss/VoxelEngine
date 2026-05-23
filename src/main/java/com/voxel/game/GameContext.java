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
import com.voxel.world.WorldSaveManager;
import org.joml.Vector2i;
import org.joml.Vector3f;
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
    public Vector4f uvHeartFull = new Vector4f(99, 2, 7, 7);
    public Vector4f uvHeartHalf = new Vector4f(108, 2, 7, 7);
    public Vector4f uvHeartEmpty = new Vector4f(90, 2, 7, 7);
    public Vector4f uvHeartBase = new Vector4f(62, 1, 9, 9);

    // --- Status / Command ---
    public boolean commandMode = false;
    public StringBuilder commandBuffer = new StringBuilder();
    public String statusMessage = "";
    public double statusUntil = 0.0;
    public int lastMeasuredFps = 0;

    // --- Render ---
    public int width = 1280, height = 720;
    public int lastBiomeOffsetX = 0, lastBiomeOffsetZ = 0;

    // --- Crafting ---
    public CraftingTableManager craftingTableManager = new CraftingTableManager();
    public boolean craftingTableOpen = false;
    public int craftingTableBlockX, craftingTableBlockY, craftingTableBlockZ;
    // Crafting grid item texture layers for 3D rendering (-1 = empty slot)
    public int[] craftingItemLayers = new int[]{-1,-1,-1,-1,-1,-1,-1,-1,-1};

    // --- Crafting cutscene ---
    public boolean craftingCutsceneActive = false;
    public float craftingCutsceneTimer = 0.0f;
    public static final float CRAFTING_CUTSCENE_DURATION = 0.8f;
    public Vector3f cutsceneStartPos = new Vector3f();
    public Vector3f cutsceneTargetPos = new Vector3f();
    public Vector3f cutsceneCameraStartPos = new Vector3f();
    public Vector3f cutsceneCameraTargetPos = new Vector3f();
    public float cutsceneStartYaw = -90, cutsceneStartPitch = 0;
    public float cutsceneTargetYaw, cutsceneTargetPitch = -60;

    // --- Mining ---
    public int breakTargetX = Integer.MIN_VALUE;
    public int breakTargetY = Integer.MIN_VALUE;
    public int breakTargetZ = Integer.MIN_VALUE;
    public float breakProgress = 0.0f;
    public boolean leftMouseHeld = false;
    public boolean leftMousePressedThisFrame = false;
    public double lastPortalTeleportTime = 0;

    // --- Combat ---
    public int lockedEntityIndex = -1;
    public int comboCount = 0;
    public float comboTimer = 0.0f;
    public float chargeTime = 0.0f;
    public boolean isCharging = false;
    public float lastAttackDamage = 0.0f;
    public boolean invincible = false;
    public float iFrameTimer = 0.0f;
    public int rollDirectionX = 0; // -1, 0, or 1
    public int rollDirectionZ = 0;
    // Damage numbers list (world position, damage, lifetime)
    public final java.util.List<DamageNumber> damageNumbers = new java.util.ArrayList<>();

    // --- World Save ---
    public WorldSaveManager worldSaveManager;

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

        // Save crafting data for the current dimension before switching
        if (worldSaveManager != null && previous != null) {
            worldSaveManager.saveCraftingData(previous, craftingTableManager);
        }

        dimensionManager.ensureDimension(target, renderDistance);
        dimensionManager.switchTo(target);
        activeDimension = target;
        world = dimensionManager.getActiveWorld();
        chunkManager = dimensionManager.getActiveChunkManager();
        redstoneManager = new RedstoneManager(world, chunkManager);
        com.voxel.world.RedstoneLogger.log("DIMENSION SWITCH: created new RedstoneManager for " + target.name + " (was " + previous.name + ")");
        if (previous != target) dimensionManager.unloadDimension(previous);
        // Find a safe landing spot: full area scan in expanding squares around spawn
        int spawnY = target.baseHeight + 3;
        if (target == DimensionType.AETHER || target == DimensionType.END) {
            boolean foundSurface = false;
            // Scan increasingly larger squares centered at (1024, 1024)
            // Max scan radius: 24 blocks (covers 49x49 area)
            int maxScanRadius = 24;
            for (int ox = -maxScanRadius; ox <= maxScanRadius && !foundSurface; ox += 3) {
                for (int oz = -maxScanRadius; oz <= maxScanRadius && !foundSurface; oz += 3) {
                    int sx = 1024 + ox, sz = 1024 + oz;
                    for (int sy = target.baseHeight + 3; sy < target.baseHeight + 28; sy++) {
                        int block = world.getVoxel(sx, sy, sz);
                        if (block != 0 && block != 106) {
                            spawnY = sy + 1;
                            foundSurface = true;
                            break;
                        }
                    }
                }
            }
        }
        player.getPosition().set(1024, spawnY, 1024);
        // Load crafting data for the new dimension
        if (worldSaveManager != null) {
            worldSaveManager.loadCraftingData(target, craftingTableManager);
        }

        if (uploadWorldToGpu != null) uploadWorldToGpu.run();
        setStatus("Switched to " + target.name);
    }

    /** Floating damage number that appears at an entity's position and fades out. */
    public static class DamageNumber {
        public float worldX, worldY, worldZ;
        public float damage;
        public float lifetime = 1.5f;
        public float maxLifetime = 1.5f;

        public DamageNumber(float worldX, float worldY, float worldZ, float damage) {
            this.worldX = worldX;
            this.worldY = worldY;
            this.worldZ = worldZ;
            this.damage = damage;
        }

        public void update(float dt) {
            worldY += dt * 1.2f; // Float upward
            lifetime -= dt;
        }

        public boolean isExpired() { return lifetime <= 0; }
        public float getAlpha() { return Math.max(0, lifetime / maxLifetime); }
    }
}
