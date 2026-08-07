package com.voxel.game;

import com.voxel.Player;
import com.voxel.World;
import com.voxel.crafting.CraftingManager;
import com.voxel.entity.EntityManager;
import com.voxel.entity.PlayerEntity;
import com.voxel.utils.BiomeManager;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.BlockRegistry;
import com.voxel.utils.ShaderBlockRegistry;
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
        THIRD_PERSON_FIXED
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
    /** Deduplicated canonical block/item registry — one entry per logical item. */
    public CanonicalRegistry canonicalRegistry;
    public PlayerInventory playerInventory;
    public BlockDataManager blockDataManager;
    public BlockRegistry blockRegistry;
    public ShaderBlockRegistry shaderBlockRegistry;
    public BiomeManager biomeManager;
    public TextureManager textureManager;
    public CraftingManager craftingManager;
    /** Tracks items dropped in the world from block breaks. Hover + auto-pickup. */
    public DroppedItemManager droppedItemManager;
    /** Create-inspired encased fans: push dropped items along their facing when powered. */
    public EncasedFanSystem encasedFanSystem;
    /** Optional asynchronous villager voice and OpenAL playback bridge. */
    public com.voxel.audio.VillagerAudioManager villagerAudioManager;

    // --- Dynamic point lights (added via /light) ---
    /** Max point lights supported by the pointLightSSBO. */
    public static final int MAX_POINT_LIGHTS = 16;
    /** Packed light data: 8 floats per light = x,y,z,radius, r,g,b,intensity. World (absolute) coords. */
    public final float[] pointLightData = new float[MAX_POINT_LIGHTS * 8];
    public volatile int numPointLights = 0;

    // --- Player ---
    public Player player;

    // --- Mutable game state ---
    public GameMode gameMode = GameMode.SURVIVAL;
    public CameraMode cameraMode = CameraMode.FIRST_PERSON;
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
    public boolean screenshotRequested = false;

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

    // --- Fluid ---
    public com.voxel.world.FluidManager fluidManager;

    // --- Active UI state (which overlay is shown) ---
    public enum ActiveUI { NONE, INVENTORY, CHEST, FURNACE, CRAFTING_TABLE, TV }
    public ActiveUI activeUI = ActiveUI.NONE;

    // --- Villager TV ---
    public VillagerTVSystem tvSystem;
    public VillagerVillageManager villageManager;
    public boolean tvCutsceneActive = false;
    public float tvCutsceneTimer = 0.0f;
    public static final float TV_CUTSCENE_DURATION = 1.0f;
    public int tvBlockX, tvBlockY, tvBlockZ;
    public int tvChannel = 3; // Default to VNN
    public Vector3f tvCutsceneCameraStart = new Vector3f();
    public Vector3f tvCutsceneCameraTarget = new Vector3f();
    public float tvCutsceneStartYaw, tvCutsceneStartPitch;
    public float tvCutsceneTargetYaw = -90, tvCutsceneTargetPitch = -15;
    public boolean tvWatching = false;

    // --- Spawn resolution (deferred until spawn chunks are generated) ---
    public int pendingSpawnX = Integer.MIN_VALUE;
    public int pendingSpawnZ = Integer.MIN_VALUE;
    private int pendingSpawnY = Integer.MIN_VALUE;
    public volatile boolean spawnLoading = true;
    public volatile String spawnLoadingMessage = "Generating spawn chunks...";

    // --- Heavy init phase (deferred from Main.init() to the loading screen) ---
    // True while Main hasn't yet created the Overworld dimension / chunkManager /
    // redstoneManager / fluidManager / playerEntity / initial enemies. The
    // Main logic thread runs Main.initializeWorldPhase() once on its first tick;
    // until that completes, the render loop just shows the spawn-loading overlay.
    public volatile boolean initializing = true;

    // --- Runnables passed by Main for dimension switching ---
    public Runnable uploadWorldToGpu;
    public Runnable updateCursorMode;
    public java.util.function.Consumer<String> statusConsumer;
    /** Called by subsystems when inventory changes — triggers hotbar rerender. */
    public Runnable uiDirtyMarker;

    // Scale factors relative to Overworld (1.0). Aether: 8 blocks = 1 overworld block. Nether: 8 overworld blocks = 1 nether block.
    private static final float SCALE_AETHER = 8.0f;
    private static final float SCALE_NETHER = 0.125f;
    private static final float SCALE_OVERWORLD = 1.0f;
    private static final float SCALE_END = 0.0f; // End uses fixed spawn point, not scaled translation
    private static final float SCALE_ERROR502 = 1.0f; // Isolated Beta world with normal coordinate scale

    private static float getScaleFactor(DimensionType dim) {
        switch (dim) {
            case AETHER: return SCALE_AETHER;
            case NETHER: return SCALE_NETHER;
            case END:    return SCALE_END;
            case ERROR502:return SCALE_ERROR502;
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

    /** Starts deferred spawn resolution for a newly selected world position. */
    public void beginSpawnResolution(int x, int z) {
        pendingSpawnX = x;
        pendingSpawnZ = z;
        pendingSpawnY = player == null
            ? activeDimension.baseHeight + 3
            : (int) Math.floor(player.getPosition().y);
        spawnLoading = true;
        spawnLoadingMessage = "Generating spawn chunks...";
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

        // Drop in-world drops from the previous dimension — they're per-dimension and would
        // otherwise continue rendering against the new dimension's chunks.
        if (previous != null && droppedItemManager != null) {
            droppedItemManager.clearAll();
        }

        dimensionManager.ensureDimension(target, renderDistance);
        dimensionManager.switchTo(target);
        activeDimension = target;
        world = dimensionManager.getActiveWorld();
        chunkManager = dimensionManager.getActiveChunkManager();
        redstoneManager = new RedstoneManager(world, chunkManager);
        com.voxel.world.RedstoneLogger.log("DIMENSION SWITCH: created new RedstoneManager for " + target.name + " (was " + previous.name + ")");

        // Recreate fluid manager for the new dimension
        fluidManager = new com.voxel.world.FluidManager(world, chunkManager, blockDataManager, target == DimensionType.NETHER);
        chunkManager.setFluidManager(fluidManager);

        if (previous != target) dimensionManager.unloadDimension(previous);

        // --- Determine spawn position with coordinate translation ---
        float tx = translateCoordinate(sourcePosition.x, previous, target);
        float tz = translateCoordinate(sourcePosition.z, previous, target);
        int spawnX = Math.round(tx);
        int spawnZ = Math.round(tz);
        // Do not scan terrain here: the target world's spawn chunks may not exist yet.
        // Use a harmless fallback while the loading overlay is shown, then resolve the
        // actual surface after ChunkManager confirms the spawn area is generated.
        int spawnY;
        if (target == DimensionType.NETHER) {
            spawnY = 32;
        } else if (target == DimensionType.AETHER) {
            // Start near the configured Aether terrain height so the immediate
            // generated sections include the island surface for detection.
            spawnY = target.baseHeight + 3;
        } else {
            spawnY = target.baseHeight + 3;
        }

        player.setPosition(spawnX + 0.5, spawnY, spawnZ + 0.5);
        player.resetVelocity();
        player.setDimension(target);
        beginSpawnResolution(spawnX, spawnZ);
        // Keep /spawn and death aligned with the safe location resolved for the
        // dimension the player just entered.
        player.setSpawnPoint(new Vector3f(player.getPosition()));
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

    /**
     * The spawn loader generates three vertical sections around the fallback
     * player position. Keep all surface probes inside that generated range;
     * reading higher unloaded sections would look like air and produce a bad
     * spawn height.
     */
    private int spawnLoadedMinY() {
        int playerCy = Math.floorDiv(pendingSpawnY, 16);
        return (playerCy - 1) * 16;
    }

    private int spawnLoadedMaxY() {
        int playerCy = Math.floorDiv(pendingSpawnY, 16);
        return (playerCy + 2) * 16 - 1;
    }

    /** Scans an area around (cx, cz) for the highest solid block, returns y+2 (air above). */
    private int findSurfaceNear(int cx, int cz, int yMin, int yMax, int maxRadius) {
        for (int r = 0; r <= maxRadius; r += 3) {
            for (int ox = -r; ox <= r; ox += 3) {
                for (int oz = -r; oz <= r; oz += 3) {
                    int sx = cx + ox, sz = cz + oz;
                    for (int sy = yMax - 1; sy >= yMin; sy--) {
                        int block = world.getVoxel(sx, sy, sz);
                        if (block != 0 && block != 106 && !blockDataManager.isLiquid(block)) {
                            return sy + 2;
                        }
                    }
                }
            }
        }
        // Fallback
        if (activeDimension == DimensionType.NETHER) return 32;
        return activeDimension.baseHeight + 3;
    }

    /**
     * Resolves the final spawn only after the immediate 3x3 spawn columns have
     * completed generation. Returns true when the player can begin simulation.
     */
    public boolean resolveSpawnAfterChunksGenerated() {
        if (!spawnLoading) return true;
        if (pendingSpawnX == Integer.MIN_VALUE || chunkManager == null) return false;

        int centerCx = Math.floorDiv(pendingSpawnX, 16);
        int centerCz = Math.floorDiv(pendingSpawnZ, 16);
        if (!chunkManager.areSpawnChunksGenerated(centerCx, centerCz)) return false;

        spawnLoadingMessage = "Detecting surface...";
        int surfaceY;
        if (activeDimension == DimensionType.AETHER || activeDimension == DimensionType.END) {
            surfaceY = findIslandSurface(pendingSpawnX, pendingSpawnZ);
        } else if (activeDimension == DimensionType.NETHER) {
            surfaceY = findNetherSpawn(pendingSpawnX, pendingSpawnZ);
        } else {
            surfaceY = findSurfaceNear(pendingSpawnX, pendingSpawnZ,
                Math.max(1, spawnLoadedMinY()), spawnLoadedMaxY(), 16);
        }

        player.setPosition(pendingSpawnX + 0.5, surfaceY, pendingSpawnZ + 0.5);
        player.resetVelocity();
        player.setSpawnPoint(new Vector3f(player.getPosition()));
        pendingSpawnX = Integer.MIN_VALUE;
        pendingSpawnZ = Integer.MIN_VALUE;
        spawnLoading = false;
        spawnLoadingMessage = "Spawn ready";
        // Let the chunk manager expand to the normal render-distance stream only
        // after the expensive Beta bootstrap has completed.
        chunkManager.finishSpawnBootstrap();
        // Generate the full biome noise map only after the first playable frame
        // is allowed through. The manager keeps the neutral fallback bound while
        // this runs on its single world-gen thread.
        chunkManager.queueBiomeMapGeneration();
        return true;
    }

    /** Compatibility entry point for older callers; readiness is checked internally. */
    public void adjustSpawnYAfterChunkLoad() {
        resolveSpawnAfterChunksGenerated();
    }

    /**
     * Island spawn finder: searches within a 6-chunk (96-block) radius for a solid terrain
     * block (full block) with air above. Used by Aether and End dimensions.
     * Excludes aerclouds, leaves, portals, and other non-full blocks.
     */
    private int findIslandSurface(int cx, int cz) {
        // The readiness check guarantees a 3x3 XZ area, so do not probe
        // beyond that generated area and accidentally treat unloaded columns
        // as terrain/air.
        int radius = 16;
        int scanMinY = Math.max(20, spawnLoadedMinY());
        // Leave two generated blocks above the candidate so the above/above2
        // air checks never read beyond the loaded spawn sections.
        int scanMaxY = spawnLoadedMaxY() - 2;
        for (int r = 0; r <= radius; r += 4) {
            for (int ox = -r; ox <= r; ox += 4) {
                for (int oz = -r; oz <= r; oz += 4) {
                    int sx = cx + ox, sz = cz + oz;
                    for (int sy = scanMaxY; sy >= scanMinY; sy--) {
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
        // Fallback: keep the coarser scan inside the same generated range;
        // never treat unloaded sections as empty terrain.
        return findSurfaceNear(cx, cz,
            Math.max(1, spawnLoadedMinY()), spawnLoadedMaxY(), 16);
    }

    /** Nether-specific spawn finder: scan downward from ceiling for a cave floor. */
    private int findNetherSpawn(int cx, int cz) {
        int scanMinY = Math.max(4, spawnLoadedMinY());
        int scanMaxY = Math.min(110, spawnLoadedMaxY() - 2);
        // Scan around for a cave floor: solid block with air above
        for (int r = 0; r <= 16; r += 4) {
            for (int ox = -r; ox <= r; ox += 4) {
                for (int oz = -r; oz <= r; oz += 4) {
                    int sx = cx + ox, sz = cz + oz;
                    for (int sy = scanMaxY; sy >= scanMinY; sy--) {
                        int below = world.getVoxel(sx, sy, sz);
                        int above = world.getVoxel(sx, sy + 1, sz);
                        int above2 = world.getVoxel(sx, sy + 2, sz);
                        if (below != 0 && !blockDataManager.isLiquid(below) && above == 0 && above2 == 0) {
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
