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
    public enum CameraMode {
        FIRST_PERSON,
        THIRD_PERSON_FOLLOW,
        THIRD_PERSON_ORBIT,
        THIRD_PERSON_FIXED,
        CINEMATIC
    }

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
    public com.voxel.camera.CutsceneManager cutsceneManager = new com.voxel.camera.CutsceneManager();
    public boolean cutsceneActive = false;
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

    // --- Furnace ---
    public FurnaceManager furnaceManager = new FurnaceManager();
    public boolean furnaceOpen = false;
    public int furnaceBlockX, furnaceBlockY, furnaceBlockZ;

    // --- Chest ---
    public ChestManager chestManager = new ChestManager();
    public boolean chestOpen = false;
    public int chestBlockX, chestBlockY, chestBlockZ;
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
    public boolean breakOverlayDirty = false;
    public float breakOverlayProgress = 0.0f;
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

    // --- Active UI state (which overlay is shown) ---
    public enum ActiveUI { NONE, INVENTORY, CHEST, FURNACE, CRAFTING_TABLE }
    public ActiveUI activeUI = ActiveUI.NONE;

    // --- Pending spawn adjustment (deferred until spawn chunks are loaded) ---
    public int pendingSpawnX = Integer.MIN_VALUE;
    public int pendingSpawnZ = Integer.MIN_VALUE;

    // --- Runnables passed by Main for dimension switching ---
    public Runnable uploadWorldToGpu;
    public Runnable updateCursorMode;
    public java.util.function.Consumer<String> statusConsumer;

    // Scale factors relative to Overworld (1.0). Aether: 8 blocks = 1 overworld block. Nether: 8 overworld blocks = 1 nether block.
    private static final float SCALE_AETHER = 8.0f;
    private static final float SCALE_NETHER = 0.125f;
    private static final float SCALE_OVERWORLD = 1.0f;
    private static final float SCALE_END = 0.0f; // End uses fixed spawn point, not scaled translation

    private static float getScaleFactor(DimensionType dim) {
        switch (dim) {
            case AETHER: return SCALE_AETHER;
            case NETHER: return SCALE_NETHER;
            case END:    return SCALE_END;
            default:     return SCALE_OVERWORLD;
        }
    }

    /**
     * Translates a coordinate from the source dimension to the target dimension.
     * Scale factors: Aether=8x, Nether=0.125x relative to Overworld.
     * For END dimensions, coordinate translation is not used (fixed spawn instead).
     */
    private static float translateCoordinate(float value, DimensionType source, DimensionType target) {
        float sourceScale = getScaleFactor(source);
        float targetScale = getScaleFactor(target);
        // If either is End, translation is handled separately via fixed spawn
        if (sourceScale == 0.0f || targetScale == 0.0f) return value;
        return value * targetScale / sourceScale;
    }

    public void setStatus(String msg) {
        statusMessage = msg;
        statusUntil = System.currentTimeMillis() / 1000.0 + 3.0;
        System.out.println(msg);
        if (statusConsumer != null) statusConsumer.accept(msg);
    }

    /** Legacy overload: translates from the player's current position. */
    public void switchToDimension(DimensionType target) {
        switchToDimension(target, new Vector3f(player.getPosition()));
    }

    public void switchToDimension(DimensionType target, Vector3f sourcePosition) {
        DimensionType previous = activeDimension;
        int renderDistance = target == DimensionType.OVERWORLD ? 8 : 6;

        // Save crafting/furnace/chest data for the current dimension before switching
        if (worldSaveManager != null && previous != null) {
            worldSaveManager.saveCraftingData(previous, craftingTableManager);
            worldSaveManager.saveFurnaceData(previous, furnaceManager);
            worldSaveManager.saveChestData(previous, chestManager);
        }

        dimensionManager.ensureDimension(target, renderDistance);
        dimensionManager.switchTo(target);
        activeDimension = target;
        world = dimensionManager.getActiveWorld();
        chunkManager = dimensionManager.getActiveChunkManager();
        redstoneManager = new RedstoneManager(world, chunkManager);
        com.voxel.world.RedstoneLogger.log("DIMENSION SWITCH: created new RedstoneManager for " + target.name + " (was " + previous.name + ")");
        if (previous != target) dimensionManager.unloadDimension(previous);

        // --- Determine spawn position with coordinate translation ---
        float tx = translateCoordinate(sourcePosition.x, previous, target);
        float tz = translateCoordinate(sourcePosition.z, previous, target);
        int spawnX = Math.round(tx);
        int spawnZ = Math.round(tz);
        int spawnY;

        if (target == DimensionType.END) {
            // End: island dimension — search for an island near the translated position
            spawnY = findIslandSurface(spawnX, spawnZ);
            pendingSpawnX = spawnX;
            pendingSpawnZ = spawnZ;
        } else if (target == DimensionType.AETHER) {
            // Aether: island dimension — search for an island near the translated position
            spawnY = findIslandSurface(spawnX, spawnZ);
            pendingSpawnX = spawnX;
            pendingSpawnZ = spawnZ;
        } else if (target == DimensionType.NETHER) {
            // Nether: cave dimension, scan downward from ceiling
            spawnY = findNetherSpawn(spawnX, spawnZ);
        } else {
            // Overworld: find surface near translated position
            spawnY = findSurfaceNear(spawnX, spawnZ, 1, 255, 32);
        }

        player.getPosition().set(spawnX + 0.5f, spawnY, spawnZ + 0.5f);
        player.getVelocity().set(0);
        player.setDimension(target);
        // Sync playerEntity dimension for entity visibility filtering
        if (playerEntity != null) {
            playerEntity.dimension = target;
        }
        // Load crafting/furnace/chest data for the new dimension
        if (worldSaveManager != null) {
            worldSaveManager.loadCraftingData(target, craftingTableManager);
            worldSaveManager.loadFurnaceData(target, furnaceManager);
            worldSaveManager.loadChestData(target, chestManager);
        }

        if (uploadWorldToGpu != null) uploadWorldToGpu.run();
        // Lighting builds incrementally as chunks load in the new dimension.
        setStatus("Switched to " + target.name);
    }

    /** Scans an area around (cx, cz) for the highest solid block, returns y+2 (air above). */
    private int findSurfaceNear(int cx, int cz, int yMin, int yMax, int maxRadius) {
        for (int r = 0; r <= maxRadius; r += 3) {
            for (int ox = -r; ox <= r; ox += 3) {
                for (int oz = -r; oz <= r; oz += 3) {
                    int sx = cx + ox, sz = cz + oz;
                    for (int sy = yMax - 1; sy >= yMin; sy--) {
                        int block = world.getVoxel(sx, sy, sz);
                        if (block != 0 && block != 106) { // 106 = aether portal (not solid ground)
                            return sy + 2;
                        }
                    }
                }
            }
        }
        // Fallback
        if (activeDimension == DimensionType.AETHER) return 96;
        if (activeDimension == DimensionType.NETHER) return 32;
        return activeDimension.baseHeight + 3;
    }

    /**
     * Called each tick after a dimension switch. Once the spawn chunks are loaded,
     * re-scans for the island surface and adjusts the player's y to 2 blocks above it.
     * This prevents fall damage from spawning at a wrong y when chunks weren't ready.
     */
    public void adjustSpawnYAfterChunkLoad() {
        if (pendingSpawnX == Integer.MIN_VALUE) return;
        int surfaceY = findIslandSurface(pendingSpawnX, pendingSpawnZ);
        float currentY = player.getPosition().y;
        // Only adjust if we found a different (real) surface — don't adjust if still fallback
        if (Math.abs(surfaceY - currentY) > 0.5f && surfaceY > 10 && surfaceY < 200) {
            player.getPosition().y = surfaceY;
        }
        pendingSpawnX = Integer.MIN_VALUE;
    }

    /**
     * Island spawn finder: searches within a 6-chunk (96-block) radius for a solid terrain
     * block (full block) with air above. Used by Aether and End dimensions.
     * Excludes aerclouds, leaves, portals, and other non-full blocks.
     */
    private int findIslandSurface(int cx, int cz) {
        int radius = 6 * 16; // 6 chunks = 96 blocks
        for (int r = 0; r <= radius; r += 4) {
            for (int ox = -r; ox <= r; ox += 4) {
                for (int oz = -r; oz <= r; oz += 4) {
                    int sx = cx + ox, sz = cz + oz;
                    for (int sy = 120; sy >= 20; sy--) {
                        int block = world.getVoxel(sx, sy, sz);
                        // Only count full blocks (terrain) — excludes aerclouds, leaves, portals, etc.
                        if (block != 0 && blockDataManager.isFullBlock(block)) {
                            int above = world.getVoxel(sx, sy + 1, sz);
                            if (above == 0) {
                                return sy + 2;
                            }
                        }
                    }
                }
            }
        }
        // Fallback: try coarser scan for any solid block
        return findSurfaceNear(cx, cz, 20, 120, 48);
    }

    /** Nether-specific spawn finder: scan downward from ceiling for a cave floor. */
    private int findNetherSpawn(int cx, int cz) {
        // Scan around for a cave floor: solid block with air above
        for (int r = 0; r <= 16; r += 4) {
            for (int ox = -r; ox <= r; ox += 4) {
                for (int oz = -r; oz <= r; oz += 4) {
                    int sx = cx + ox, sz = cz + oz;
                    for (int sy = 110; sy >= 4; sy--) {
                        int below = world.getVoxel(sx, sy, sz);
                        int above = world.getVoxel(sx, sy + 1, sz);
                        int above2 = world.getVoxel(sx, sy + 2, sz);
                        if (below != 0 && above == 0 && above2 == 0) {
                            return sy + 2;
                        }
                    }
                }
            }
        }
        return 32; // Fallback: middle of nether
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
