package com.voxel.game;

import com.voxel.Player;
import com.voxel.World;
import com.voxel.crafting.CraftingManager;
import com.voxel.entity.Entity;
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
import com.voxel.world.WorldBorderManager;
import com.voxel.world.WorldSize;
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
    /** Create-style kinetic network (per-dimension, recreated on switch). */
    public com.voxel.world.KineticManager kineticManager;
    /** Create-style blaze burner / steam engine heat manager. */
    public com.voxel.world.BlazeBurnerManager blazeBurnerManager;
    /** Create-style copper tank fluid manager. */
    public com.voxel.world.CopperTankManager copperTankManager;

    // --- World size & border ---
    public WorldSize worldSize = WorldSize.MEDIUM;
    public WorldBorderManager borderManager = new WorldBorderManager(WorldSize.MEDIUM.intBits());

    // --- Seed ---
    /** World generation seed. 0 = default (classic layout). */
    public long worldSeed = 0L;

    // --- Save ---
    /** Name of the current save slot (folder under saves/). */
    public volatile String saveName = "world";

    // ── Main menu state machine ──
    public enum MenuScreen { MAIN, NEW_WORLD_NAME, NEW_WORLD_SEED, NEW_WORLD_SIZE, NEW_WORLD_MODE, LOAD_SAVE, OPTIONS, IN_GAME }
    /** Which menu screen is active (MAIN = title screen, IN_GAME = in world). */
    public volatile MenuScreen menuScreen = MenuScreen.MAIN;
    /** Currently highlighted menu option index. */
    public volatile int menuSelection = 0;
    /** Set by mouse-driven menu controls; consumed by the logic thread. */
    public volatile boolean menuConfirmRequested = false;
    public volatile boolean menuBackRequested = false;
    /** Cursor row for the load-save list. */
    public volatile int saveListSelection = 0;
    /** Saves found on disk (refreshed when the menu opens). */
    public volatile java.util.List<String> saveList = new java.util.ArrayList<>();
    /** Text being typed into the active menu field (world name / seed). */
    public volatile StringBuilder menuTextInput = new StringBuilder();
    /** True while the menu is accepting typed characters. */
    public volatile boolean menuTextActive = false;
    /** True when the Tutorial World (Create showcase) was chosen from the menu. */
    public volatile boolean tutorialWorld = false;
    /** True when the Point & Click demo world was chosen from the menu. */
    public volatile boolean pointClickWorld = false;
    /** Last seed parsed from the menu (0 = random). */
    public volatile long menuSeed = 0L;
    /** True when the menu was told to use a random seed. */
    public volatile boolean randomSeed = true;
    /** Pending player state for save-load (applied after spawn resolution). */
    public volatile boolean loadPending = false;
    public volatile double loadX, loadY, loadZ;
    public volatile float loadYaw, loadPitch, loadHealth;
    public volatile float loadWorldTime = 720f;
    public volatile com.voxel.world.DimensionType loadDimension = com.voxel.world.DimensionType.OVERWORLD;

    // ── End Portal ─────────────────────────────────────────────────────────
    /** Frames portal-teleport retries so a player doesn't bounce back and forth
     *  when both feet and waist voxels report an active end_portal block. */
    public volatile int endPortalCooldownTicks = 0;
    /** Once the Ender Dragon has spawned in the End, this stays true so a
     *  player teleporting back-and-forth doesn't re-summon the boss. */
    public volatile boolean enderDragonSpawned = false;
    /** Tracks the active Ender Dragon entity id, when one has been spawned,
     *  so subsequent ticks can poll for "is it dead" → drop egg. */
    public volatile int enderDragonEntityId = -1;
    /** Saved Overworld position the player came from; used as the return
     *  target when the player steps into the End gateway. */
    public volatile double endReturnX, endReturnZ, endReturnY;
    /** Restored inventory: slot -> item stack (may be null entries). */
    public volatile ItemDefinitions.ItemStack[] loadInventory;

    // ── UI theme ──
    public enum UiTheme { LIGHT, DARK }
    public volatile UiTheme uiTheme = UiTheme.DARK;

    // --- Startup menu (legacy world-size step, still used by NEW_WORLD_SIZE) ---
    /** When true, the render loop shows the world-size selection menu instead of gameplay. */
    public volatile boolean worldSizeMenu = false;
    /** Currently highlighted world-size index in the menu. */
    public volatile int worldSizeSelection = 2; // MEDIUM
    /** Set to true by the menu handler after ENTER is pressed to create the world. */
    public volatile boolean worldSizeConfirmed = false;

    // --- Entity ---
    public EntityManager entityManager;
    public PlayerEntity playerEntity;

    // --- Core managers ---
    public ItemDefinitions itemDefinitions;
    public CommandProcessor commandProcessor;
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
    // --- MCSM point-and-click: when non-null, world-space picking ray {ox,oy,oz,dx,dy,dz} under the free cursor. ---
    public volatile float[] cursorRayOverride;

    // --- Cinematic system (movie mode: scenes + letterbox/fade/title overlays) ---
    public com.voxel.cinematic.CinematicSystem cinematic;
    public float playerYaw = -90;
    public float lastMouseX = 640, lastMouseY = 360;
    public boolean firstMouse = true;
    public float cameraShake = 0.0f;
    public double lastAttackTime = 0;
    public double lastRollTime = 0;

    // --- Creative inventory ---
    /** True while the creative item picker (E in creative mode) is open. */
    public volatile boolean creativeMenuOpen = false;
    /** Create-style machine controller (crank/windmill/belt/press/millstone/...). */
    public volatile CreateMachineManager machineManager = null;
    /** Goggles overlay text describing the machine under the crosshair. */
    public volatile String machineLookInfo = "";
    /** Search filter for the creative item grid. */
    public volatile StringBuilder creativeSearch = new StringBuilder();
    /** Scroll offset (rows) for the creative grid. */
    public volatile int creativeScroll = 0;

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
    public SurfaceCraftingManager surfaceCraftingManager = new SurfaceCraftingManager();
    /** Persistent command-block programs for the active dimension. */
    public CommandBlockManager commandBlockManager = new CommandBlockManager();
    public boolean commandBlockEditorOpen = false;
    public int commandBlockEditorX, commandBlockEditorY, commandBlockEditorZ;
    public String commandBlockEditorCommand = "";
    public boolean craftingTableOpen = false;
    public int craftingTableBlockX, craftingTableBlockY, craftingTableBlockZ;
    /** Alt-right-click surface crafting target and interaction state. */
    public boolean surfaceCraftingOpen = false;
    public int surfaceCraftingBlockX, surfaceCraftingBlockY, surfaceCraftingBlockZ;
    public String[][] surfaceCraftingGrid = new String[2][2];

    // --- Furnace ---
    public FurnaceManager furnaceManager = new FurnaceManager();
    public boolean furnaceOpen = false;
    public int furnaceBlockX, furnaceBlockY, furnaceBlockZ;

    // --- Furnace walk-up cutscene (opens the furnace UI on completion) ---
    public boolean furnaceCutsceneActive = false;
    public float furnaceCutsceneTimer = 0.0f;
    public static final float FURNACE_CUTSCENE_DURATION = 0.9f;
    public Vector3f furnaceCutsceneStartPos = new Vector3f();
    public Vector3f furnaceCutsceneTargetPos = new Vector3f();
    public Vector3f furnaceCutsceneCameraStart = new Vector3f();
    public Vector3f furnaceCutsceneCameraTarget = new Vector3f();
    public float furnaceCutsceneStartYaw = -90, furnaceCutsceneStartPitch = 0;
    public float furnaceCutsceneTargetYaw = -90, furnaceCutsceneTargetPitch = -15;

    // --- Chest ---
    public ChestManager chestManager = new ChestManager();
    public boolean chestOpen = false;
    public int chestBlockX, chestBlockY, chestBlockZ;
    /** Smooth lid progress for the one currently selected chest (0 closed, 1 open). */
    public volatile float chestLidAngle = 0.0f;
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

    // --- Block placement preview (semi-transparent ghost) ---
    public int previewX = Integer.MIN_VALUE;   // placement cell (world coords)
    public int previewY = Integer.MIN_VALUE;
    public int previewZ = Integer.MIN_VALUE;
    public int previewBlock = -1;              // block id being previewed, -1 = none
    public int previewFacing = 0;              // facing/axle for directional blocks

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

    // --- Pause/menu state ---
    public volatile boolean pauseMenuOpen = false;
    public volatile int pauseSelection = 0;
    public volatile boolean pauseConfirmRequested = false;

    // --- Active UI state (which overlay is shown) ---
    public enum ActiveUI { NONE, INVENTORY, CHEST, FURNACE, CRAFTING_TABLE, SURFACE_CRAFTING, COMMAND_BLOCK, TV, MAP }
    public ActiveUI activeUI = ActiveUI.NONE;

    // --- World Map ---
    public boolean mapOpen = false;
    public float mapZoom = 1.0f;       // blocks-per-pixel scale (higher = zoomed out)
    public float mapPanX = 0f, mapPanY = 0f;  // world coords at map centre
    public int mapTexId = 0;            // OpenGL texture ID for the map image
    // Smooth zoom interpolation: target is what the controls change, display is
    // what the camera/UI read (lerped toward target each tick).
    public float mapTargetZoom = 1.0f;
    public float mapDisplayZoom = 1.0f;
    // Right-click drag state (map open only)
    public boolean mapDragging = false;
    public float mapDragStartX, mapDragStartY;
    public float mapDragPanStartX, mapDragPanStartY;
    // Map UI readouts
    public float mapCompassAngle = 0f;   // compass needle rotation (radians)
    public String mapCoordinateText = "";
    public float mapCoordinateUpdateTimer = 0f;

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

    // ── Minecarts ──
    /** The minecart the player is currently riding, or null. Written by the GL
     *  thread (interact) and read by the logic thread (tick) — volatile. */
    public volatile Entity ridingMinecart = null;
    /** Set by Main: dismounts the player from the current cart. */
    public Runnable dismountMinecart = null;
    /** Set by Main: spawns the named mob at the player's look target (/spawn <mob>). */
    public java.util.function.Consumer<String> spawnMobCommand = null;
    /** Cart spawns requested by block interaction (GL thread) for the logic
     *  thread to consume — avoids mutating the EntityManager off-thread.
     *  Thread-safe list: the GL thread adds, the logic thread drains. */
    public final java.util.List<Vector3f> minecartSpawnQueue =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    // --- Spawn resolution (deferred until spawn chunks are generated) ---
    public int pendingSpawnX = Integer.MIN_VALUE;
    /** Set when a dimension switch should build a landing island + return portal. */
    private boolean arrivalPortalPending = false;
    /** Dimension whose persisted drops should restore after spawn chunks load. */
    private DimensionType pendingDropRestoreDim = null;
    public int pendingSpawnZ = Integer.MIN_VALUE;
    private int pendingSpawnY = Integer.MIN_VALUE;
    public volatile boolean spawnLoading = true;
    public volatile String spawnLoadingMessage = "Generating spawn chunks...";

    // Same-dimension /tp gate: terrain streaming continues, but gameplay physics
    // stays paused until the destination section has finished generating.
    public volatile boolean teleportLoading = false;
    public volatile String teleportLoadingMessage = "Loading terrain...";

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
    /** Starts the short terrain-readiness gate used by same-dimension /tp. */
    public void beginTeleportTerrainWait() {
        teleportLoading = true;
        teleportLoadingMessage = "Loading terrain...";
    }

    public void finishTeleportTerrainWait() {
        teleportLoading = false;
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
            worldSaveManager.saveSurfaceCraftingData(previous, surfaceCraftingManager);
            worldSaveManager.saveCommandBlockData(previous, commandBlockManager);
            worldSaveManager.saveFurnaceData(previous, furnaceManager);
            worldSaveManager.saveChestData(previous, chestManager);
            // Persist the player's cross-dimension state too (position is updated
            // later in this method, so save before the teleport below).
            if (player != null && playerInventory != null) {
                worldSaveManager.saveLevelData(this, player, playerInventory);
            }
        }

        // Drops are per-dimension: persist the outgoing dimension's items, then
        // clear. The target dimension's drops restore once its chunks generate
        // (see resolveSpawnAfterChunksGenerated).
        if (previous != null && droppedItemManager != null) {
            if (worldSaveManager != null) {
                worldSaveManager.saveDroppedItems(previous, droppedItemManager.getSnapshot());
            }
            droppedItemManager.clearAll();
            pendingDropRestoreDim = target;
        }

        dimensionManager.ensureDimension(target, renderDistance);
        dimensionManager.switchTo(target);
        activeDimension = target;
        commandBlockManager.beginDimension(target.id);
        world = dimensionManager.getActiveWorld();
        chunkManager = dimensionManager.getActiveChunkManager();
        // The biome tint-map texture is shared across dimensions. Re-point the
        // provider to the newly active world's generator and repopulate the tiles
        // for its already-loaded columns (an existing dimension does not re-run
        // chunk generation, so without this its tint map would be stale).
        if (biomeManager != null) {
            com.voxel.biome.BiomeProvider targetProvider = dimensionManager.getActiveGenerator().getBiomeProvider();
            if (targetProvider != null) biomeManager.setBiomeProvider(targetProvider);
            chunkManager.refreshBiomeMap();
        }
        redstoneManager = new RedstoneManager(world, chunkManager);
        com.voxel.world.RedstoneLogger.log("DIMENSION SWITCH: created new RedstoneManager for " + target.name + " (was " + previous.name + ")");
        redstoneManager.setContainerManagers(chestManager, furnaceManager);
        kineticManager = new com.voxel.world.KineticManager(world, chunkManager, redstoneManager);
        com.voxel.world.RedstoneLogger.log("DIMENSION SWITCH: created new KineticManager for " + target.name);

        // Recreate fluid manager for the new dimension
        fluidManager = new com.voxel.world.FluidManager(world, chunkManager, blockDataManager, target == DimensionType.NETHER);
        chunkManager.setFluidManager(fluidManager);

        blazeBurnerManager = new com.voxel.world.BlazeBurnerManager(world, chunkManager);
        copperTankManager = new com.voxel.world.CopperTankManager(world, chunkManager);
        borderManager = new WorldBorderManager(worldSize.intBits());

        // Push the configured X/Z int bits into the Beta terrain precision tuning
        com.voxel.world.WorldGenerator gen = dimensionManager.getActiveGenerator();
        if (gen instanceof com.voxel.world.BetaWorldGenerator) {
            ((com.voxel.world.BetaWorldGenerator) gen).setWorldSize(worldSize);
        }

        if (previous != target) {
            dimensionManager.unloadDimension(previous);
            arrivalPortalPending = true;
        }

        // --- Determine spawn position with coordinate translation ---
        float tx = translateCoordinate(sourcePosition.x, previous, target);
        float tz = translateCoordinate(sourcePosition.z, previous, target);
        int spawnX = target == DimensionType.PORTAL_HALL ? 0 : Math.round(tx);
        int spawnZ = target == DimensionType.PORTAL_HALL ? 0 : Math.round(tz);
        // Do not scan terrain here: the target world's spawn chunks may not exist yet.
        // Use a harmless fallback while the loading overlay is shown, then resolve the
        // actual surface after ChunkManager confirms the spawn area is generated.
        int spawnY;
        if (target == DimensionType.PORTAL_HALL) {
            spawnY = 67;
        } else if (target == DimensionType.NETHER) {
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
        // Cinematic: one-time first-Nether reveal when entering the Nether.
        // The scene is queued onto the logic thread's cinematic tick, which
        // reads player position after the teleport has landed.
        if (cinematic != null && target == DimensionType.NETHER) {
            cinematic.playFirstNether();
        }
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
            worldSaveManager.loadSurfaceCraftingData(target, surfaceCraftingManager);
            worldSaveManager.loadCommandBlockData(target, commandBlockManager);
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
                        if (block != 0 && block != PORTAL_AETHER_BLOCK
                                && block != PORTAL_AETHER_EW_BLOCK
                                && !blockDataManager.isLiquid(block)) {
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
     * Builds a small landing island with a return portal at the dimension
     * arrival point. Materials follow the vanilla-Aether convention:
     * netherrack in the Nether, grass in the Overworld, holystone topped with
     * aether grass in the Aether. The portal is framed so stepping back in
     * returns to the dimension the player came from.
     */
    private void buildArrivalPlatformAndPortal(int x, int z, int surfaceY) {
        if (world == null || chunkManager == null) return;
        if (activeDimension == DimensionType.PORTAL_HALL) return;

        int fillId = -1, topId = -1;
        if (activeDimension == DimensionType.NETHER) {
            fillId = safeBlockId("netherrack");
        } else if (activeDimension == DimensionType.AETHER) {
            fillId = safeBlockId("holystone");
            topId = safeBlockId("aether_grass_block");
            if (topId <= 0) topId = fillId;
        } else if (activeDimension == DimensionType.END) {
            fillId = safeBlockId("end_stone");
        } else {
            fillId = safeBlockId("dirt");
            topId = safeBlockId("grass_block");
            if (topId <= 0) topId = fillId;
        }
        if (fillId <= 0) return;

        int groundY = surfaceY - 1;
        // 7x7 landing island (two layers deep so it floats safely in the Aether)
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                chunkManager.setVoxel(x + dx, groundY, z + dz,
                        topId > 0 ? topId : fillId);
                if (groundY - 1 >= 0) chunkManager.setVoxel(x + dx, groundY - 1, z + dz, fillId);
                // Clear an air pocket above so the portal and player fit
                for (int h = 0; h < 6; h++) {
                    int by = surfaceY + h;
                    if (world.getVoxel(x + dx, by, z + dz) != 0) {
                        chunkManager.setVoxel(x + dx, by, z + dz, 0);
                    }
                }
            }
        }

        // Return portal: nether-style portals link Nether<->Overworld,
        // aether-style portals link Aether<->Overworld.
        int frameId, portalId;
        if (activeDimension == DimensionType.AETHER) {
            frameId = 17; portalId = PORTAL_AETHER_BLOCK;
        } else {
            frameId = 16; portalId = PORTAL_NETHER_BLOCK;
        }

        int y0 = groundY; // frame bottom sits flush on the island surface
        for (int i = 0; i < 4; i++) {
            chunkManager.setVoxel(x + i, y0, z, frameId);
            chunkManager.setVoxel(x + i, y0 + 4, z, frameId);
        }
        for (int h = 1; h <= 3; h++) {
            chunkManager.setVoxel(x, y0 + h, z, frameId);
            chunkManager.setVoxel(x + 3, y0 + h, z, frameId);
        }
        for (int px = 1; px <= 2; px++) {
            for (int py = 1; py <= 3; py++) {
                chunkManager.setVoxel(x + px, y0 + py, z, portalId);
            }
        }
    }

    private static final int PORTAL_NETHER_BLOCK = 19;
    private static final int PORTAL_AETHER_BLOCK = 106;
    private static final int PORTAL_AETHER_EW_BLOCK = 127;

    /** Null-safe block-name lookup that never throws. */
    private int safeBlockId(String name) {
        Integer v = blockDataManager != null ? blockDataManager.findBlockId(name) : null;
        return v != null ? v : -1;
    }

    /**
     * Resolves the final spawn only after the immediate 3x3 spawn columns have
     * completed generation. Returns true when the player can begin simulation.
     */
    public boolean resolveSpawnAfterChunksGenerated() {
        if (!spawnLoading) return true;
        if (pendingSpawnX == Integer.MIN_VALUE || chunkManager == null) return false;

        int centerCx = Math.floorDiv(pendingSpawnX, 16);
        int centerCy = Math.floorDiv(pendingSpawnY, 16);
        int centerCz = Math.floorDiv(pendingSpawnZ, 16);
        if (!chunkManager.areSpawnChunksGenerated(centerCx, centerCy, centerCz)) return false;

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
        // Portal arrivals: build a small landing island with a return portal
        // once the destination chunks actually exist (writing earlier would be
        // lost when the chunks generate over them).
        if (arrivalPortalPending) {
            arrivalPortalPending = false;
            buildArrivalPlatformAndPortal(pendingSpawnX, pendingSpawnZ, surfaceY);
        }
        // Restore this dimension's persisted dropped items once terrain exists
        // (the ground search needs real voxels).
        if (pendingDropRestoreDim == activeDimension && worldSaveManager != null && droppedItemManager != null) {
            pendingDropRestoreDim = null;
            for (com.voxel.game.DroppedItemManager.DropSnapshot d : worldSaveManager.loadDroppedItems(activeDimension)) {
                droppedItemManager.restore(d.itemId, d.count, d.x, d.y, d.z);
            }
        }
        pendingSpawnX = Integer.MIN_VALUE;
        pendingSpawnZ = Integer.MIN_VALUE;
        spawnLoading = false;
        spawnLoadingMessage = "Spawn ready";
        // Let the chunk manager expand to the normal render-distance stream only
        // after the expensive Beta bootstrap has completed.
        chunkManager.finishSpawnBootstrap();
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
