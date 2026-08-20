package com.voxel;

import com.voxel.camera.CameraController;
import com.voxel.crafting.CraftingManager;
import com.voxel.ui.UILayer;
import com.voxel.ui.UIManager;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.BlockDataManager.BlockData;
import com.voxel.utils.BlockDataManager.MaterialEffect;
import com.voxel.utils.BlockRegistry;
import com.voxel.utils.ShaderBlockRegistry;
import com.voxel.utils.ShaderUtil;
import com.voxel.utils.TextureManager;
import com.voxel.world.DimensionManager;
import com.voxel.world.DimensionType;
import com.voxel.entity.Entity;
import com.voxel.entity.MinecartEntity;
import com.voxel.entity.BlazeEntity;
import com.voxel.entity.ZombiePigmanEntity;
import com.voxel.entity.FireballEntity;
import com.voxel.entity.ModelPart;
import com.voxel.entity.VillagerEntity;
import com.voxel.game.VillagerTVSystem;
import com.voxel.game.VillagerVillageManager;
import com.voxel.game.AtmosphereRenderer;
import com.voxel.game.BlockInteraction;
import com.voxel.game.CommandProcessor;
import com.voxel.game.CraftingTableConstants;
import com.voxel.game.GameContext;
import com.voxel.game.ItemDefinitions;
import com.voxel.game.ItemDefinitions.ItemDefinition;
import com.voxel.game.ItemDefinitions.ItemStack;
import com.voxel.game.FurnaceManager;
import com.voxel.game.ChestManager;
import com.voxel.game.PlayerInventory;
import com.voxel.game.PortalSystem;
import com.voxel.world.RedstoneLogger;
import com.voxel.world.RedstoneManager;
import com.voxel.world.WorldGenerator;
import com.voxel.world.WorldGenLogger;
import com.voxel.GameLogger;
import com.voxel.utils.FixedPoint;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.voxel.game.GameContext.CameraMode;
import com.voxel.game.GameContext.GameMode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main Rendering Thread.
 * Strictly handles OpenGL and input polling.
 * Logic is offloaded to a background thread.
 */
public class Main {
    public static final int HOTBAR_SIZE = 5;
    public static final int INVENTORY_SIZE = 20;
    public static final int SLOT_W = 88;
    public static final int SLOT_H = 80;
    public static final int SLOT_TEX_W = 22;
    public static final int SLOT_TEX_H = 20;
    public static final int HOTBAR_X = 10;
    public static final int HOTBAR_Y = 100;
    // Inventory panel: covers 4 columns + crafting grid (2 grid cols + result slot)
    public static final int INVENTORY_PANEL_WIDTH = 460;
    public static final int INVENTORY_PANEL_HEIGHT = SLOT_H * HOTBAR_SIZE + 24;
    public static final float DAY_START_TIME = 720.0f;
    public static final float PLAYER_HALF_WIDTH = 0.3f;
    public static final float PLAYER_HEIGHT = 1.8f;
    public static final float PLAYER_EYE_HEIGHT = 1.6f;
    public static final float THIRD_PERSON_DISTANCE = 4.0f;
    public static final float THIRD_PERSON_TARGET_HEIGHT = 1.35f;
    public static final float CAMERA_COLLISION_STEP = 0.1f;

    public long window;
    public int quadProgram, computeProgram;
    public int quadVAO, quadVBO, renderTexture;
    public int indirectionSSBO, chunkPoolSSBO, bitmaskSSBO, occlusionSSBO, pointLightSSBO, lightSSBO;
    public int sdfSSBO;  // chunk-level SDF for sphere-trace acceleration (binding=10)

    // Cached compute shader uniform locations (avoid glGetUniformLocation per frame)
    public int locBlockTextures, locEntityTextures, locBlockData, locBlockAABBs, locBlockAABBInfo, locBlockAABBUVs;
    public volatile boolean needsWorldUpload = false;
    public volatile boolean needsCursorUpdate = false;
    public int locBiomeMap, locUITexture, locUISource;
    public int locDimensionId, locFogColor, locSkyTint;
    public int locHeartUVs;
    public int locCraftingItemCount;
    public int locDestroyStages; // cached u_DestroyStages (was a per-frame glGetUniformLocation)
    public int locMapMode, locMapPreview, locMapPreviewOrigin, locMapPreviewScale, locMapWorldOrigin, locMapBorder, locMapGroundY;
    public int craftingItemSSBO;
    public java.util.Iterator<Integer> dirtyUploadIterator;
    public static final int MAX_DIRTY_UPLOADS_PER_FRAME = 48;
    public int locQuadPass; // Cached quad shader inputTexture uniform
    public int locQuadFlipY; // Cached fullscreen texture orientation uniform

    // Reusable direct buffer for SDF SSBO sub-uploads (avoid per-frame alloc).
    private java.nio.ByteBuffer reusableSdfBuf;



    public FloatBuffer persistentPlBuf; // Persistent FloatBuffer for pointLightSSBO (no per-frame alloc)

    // 16 baked light pools (8 sun-trajectory + 8 moon-trajectory), regenerated ~20Hz.
    // No surface sun shadows — the main pass samples the ACTIVE sun/moon pools only
    // for volumetric god rays. Block light comes from the LightPool SSBO (unchanged).
    public int[] lightPoolTex;                       // [0..7] sun pools, [8..15] moon pools
    public volatile boolean lightPoolDirty = true;
    private final int shadowMapRes = 512;
    private final float shadowHalfExtent = 48.0f;
    private final float shadowDepth = 192.0f;
    private int shadowFrameCount = 0;
    private DimensionType cachedPoolDirsDim = null; // pool dirs are dimension-constant; recompute only on dimension switch
    private final float[][] poolDirs = new float[16][3]; // fixed light dir per pool
    private final float[] activeSunDir = new float[3];   // live sun dir for pool pick
    private final float[] activeMoonDir = new float[3];  // live moon dir for pool pick

    // ── 3D main-menu panorama ──
    // A hand-crafted voxel scene rendered by the raytracer behind the menu
    // (Minecraft-panorama style) while the real world is still uninitialized.
    public volatile boolean panoramaActive = false;
    private int currentTutorialZone = -1; // last showcase zone the popup announced
    private boolean tutorialMinecartsSpawned = false; // rideable coaster carts spawned yet
    private final java.util.Set<Integer> tutorialMobZonesSpawned = new java.util.HashSet<>(); // zones whose mobs are placed
    private int nextTutorialMobId = 65000; // unique id counter for tutorial-zone mobs
    private int nextSpawnCommandId = 75000; // unique id counter for /spawn-created mobs
    private com.voxel.World panoramaWorld;
    private int panoramaNextSlot = 0;
    private float panoramaAngle = 0f;    // orbit angle (radians)
    private float panoramaTime = 3600f;  // in-world time; drifts gently for sun motion
    private static final float PANORAMA_CX = 56f, PANORAMA_CY = 26f, PANORAMA_CZ = 56f;
    // Reusable zero point-light header (16 bytes) for the panorama pass — do NOT
    // use persistentPlBuf here: renderMenuPanorama also runs from
    // presentInitialLoadingFrame(), before persistentPlBuf is allocated.
    private final IntBuffer panoramaPlHeader = IntBuffer.allocate(4);
    private final float[] shadowCamPosPrev = new float[3];
    private int activeSunPool = 0, activeMoonPool = 8;
    private int prevActiveSunPool = -1, prevActiveMoonPool = -1;
    // Fixed uniform locations (declared in raytracer.comp)
    private static final int LOC_SHADOW_PASS = 22;
    private static final int LOC_SHADOW_ORIGIN = 23;
    private static final int LOC_SHADOW_RIGHT = 24;
    private static final int LOC_SHADOW_UP = 25;
    private static final int LOC_SHADOW_SUN_DIR = 26;
    private static final int LOC_SHADOW_EXTENT = 27;
    private static final int LOC_SHADOW_MAP_SIZE = 30;
    private static final int LOC_SUN_POOL = 31;
    private static final int LOC_MOON_POOL = 32;
    private static final int LOC_MOON_POOL_ORIGIN = 33;
    private static final int LOC_MOON_POOL_RIGHT = 34;
    private static final int LOC_MOON_POOL_UP = 35;
    private static final int LOC_MOON_POOL_DIR = 36;
    private static final int LOC_UNDER_WATER = 37; // 1 when the camera eye is inside a water block
    private static final int LOC_LARGE_COG = 38;   // 1 when any large cogwheel (295) is loaded

    /** Base spin rate (revolutions/second) for kinetic gears/shafts when powered. */
    private static final float KINETIC_RPS = 0.75f;

    /** True for any water block id (source 15 + flowing levels 150-164). */
    private static boolean isWaterId(int id) {
        return id == 15 || (id >= 150 && id <= 164);
    }
    public com.voxel.entity.EntityManager entityManager;
    public World world;
    public com.voxel.world.ChunkManager chunkManager;
    public TextureManager textureManager;
    public BlockDataManager blockDataManager;
    public BlockRegistry blockRegistry;
    public ShaderBlockRegistry shaderBlockRegistry;
    public com.voxel.utils.BiomeManager biomeManager;
    public Player player;
    public com.voxel.entity.PlayerEntity playerEntity;
    public CraftingManager craftingManager;
    public DimensionManager dimensionManager;
    public DimensionType activeDimension = DimensionType.OVERWORLD;
    public RedstoneManager redstoneManager;

    // --- Extracted subsystem references ---
    public GameContext ctx;
    public ItemDefinitions itemDefinitions;
    public PlayerInventory playerInventory;
    public BlockInteraction blockInteraction;
    public PortalSystem portalSystem;
    public CommandProcessor commandProcessor;
    public AtmosphereRenderer atmosphereRenderer;
    public com.voxel.audio.VillagerAudioManager villagerAudioManager;

    public com.voxel.camera.CameraController cameraController;
    public com.voxel.ui.HudUI hud;
    public com.voxel.game.WorldMapRenderer mapRenderer;

    public int width = 1280, height = 720;
    public final int CHUNK_SIZE = 16, REGION_SIZE = 128;

    public float lastMouseX = width / 2f, lastMouseY = height / 2f;
    public boolean firstMouse = true;
    public float scrollDelta = 0f; // accumulated scroll for map zoom
    public float yaw = -90, pitch = 0;
    public float playerYaw = -90;

    public GameMode gameMode = GameMode.SURVIVAL;
    public float worldTime = DAY_START_TIME;

    public boolean inventoryOpen = false;
    public boolean commandMode = false;
    public final StringBuilder commandBuffer = new StringBuilder();
    public String statusMessage = "";
    public double statusUntil = 0.0;
    public int statusLineOffset = 0;
    // Tutorial World zone title-card popup (driven by the logic thread, read by HudUI).
    public String tutorialPopupTitle = "";
    public String tutorialPopupSubtitle = "";
    public double tutorialPopupUntil = 0.0;
    public int lastMeasuredFps = 0;

    public boolean leftMouseHeld = false;
    public boolean leftMousePressedThisFrame = false;
    public int breakTargetX = Integer.MIN_VALUE;
    public int breakTargetY = Integer.MIN_VALUE;
    public int breakTargetZ = Integer.MIN_VALUE;
    public float breakProgress = 0.0f;
    public double lastPortalTeleportTime = 0;

    public static final int CRAFTING_SLOTS = 5;
    public static final int CRAFTING_RESULT_SLOT = 4;

    public Thread logicThread;
    public volatile boolean running = true;
    public CameraMode cameraMode = CameraMode.FIRST_PERSON;

    public volatile float craftingCameraYaw;    // Fixed yaw while using crafting table (volatile: read by GL thread)
    public volatile float craftingCameraPitch;   // Fixed pitch while using crafting table
    public boolean craftingCameraInited = false;

    public float cameraShake = 0.0f;
    public float hitStop = 0.0f;
    public float combatTime = 0.0f;
    public double lastAttackTime = 0;
    public double lastRollTime = 0;
    public boolean combatMode = false;

    // Sprint double-tap W detection
    private double lastWPressTime = 0;
    private boolean wWasPressed = false;

    /** Wall-clock nanos of last logic-tick completion (set by logic thread, read by render thread for interpolation). */
    public volatile long lastLogicTickNanos = System.nanoTime();

    public void run() {
        init();

        logicThread = new Thread(this::logicLoop, "LogicThread");
        logicThread.start();

        loop();

        running = false;
        try {
            logicThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Save data on shutdown (including the level.dat player state)
        if (ctx.worldSaveManager != null) {
            if (ctx.chunkManager != null) ctx.chunkManager.savePendingChanges();
            ctx.worldSaveManager.saveCraftingData(ctx.activeDimension, ctx.craftingTableManager);
            ctx.worldSaveManager.saveSurfaceCraftingData(ctx.activeDimension, ctx.surfaceCraftingManager);
            ctx.worldSaveManager.saveCommandBlockData(ctx.activeDimension, ctx.commandBlockManager);
            ctx.worldSaveManager.saveFurnaceData(ctx.activeDimension, ctx.furnaceManager);
            ctx.worldSaveManager.saveChestData(ctx.activeDimension, ctx.chestManager);
            if (ctx.machineManager != null) {
                ctx.worldSaveManager.saveMachineData(ctx.activeDimension, ctx.machineManager);
            }
            if (player != null && playerInventory != null && ctx.menuScreen == GameContext.MenuScreen.IN_GAME) {
                ctx.worldSaveManager.saveLevelData(ctx, player, playerInventory);
            }
        }

        glDeleteProgram(quadProgram);
        glDeleteProgram(computeProgram);
        glDeleteBuffers(quadVBO);
        glDeleteVertexArrays(quadVAO);
        glDeleteTextures(renderTexture);
        if (lightPoolTex != null) for (int t : lightPoolTex) glDeleteTextures(t);
        glDeleteBuffers(indirectionSSBO);
        glDeleteBuffers(chunkPoolSSBO);
        glDeleteBuffers(bitmaskSSBO);
        glDeleteBuffers(occlusionSSBO);
        glDeleteBuffers(pointLightSSBO);
        glDeleteBuffers(lightSSBO);
        glDeleteBuffers(craftingItemSSBO);
        glDeleteBuffers(sdfSSBO);
        if (persistentPlBuf != null) MemoryUtil.memFree(persistentPlBuf);
        if (villagerAudioManager != null) {
            villagerAudioManager.close();
        }
        chunkManager.shutdown();
        RedstoneLogger.shutdown();
        WorldGenLogger.shutdown();
        GameLogger.shutdown();

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    public void init() {
        final long initStartNanos = System.nanoTime();
        GLFWErrorCallback.createPrint(System.err).set();
        System.out.println("[BOOT] init start");
        final java.util.function.Consumer<String> bootMark = stage ->
            System.out.println("[BOOT] " + stage + " " + ((System.nanoTime() - initStartNanos) / 1_000_000L) + " ms");
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(width, height, "Voxel Engine", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetKeyCallback(window, this::handleKeyInput);
        glfwSetCharCallback(window, this::handleCharInput);
        glfwSetCursorPosCallback(window, this::handleCursorMoved);
        glfwSetMouseButtonCallback(window, this::handleMouseButton);
        glfwSetScrollCallback(window, this::handleScroll);

        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);
        GL.createCapabilities();

        // Build only the tiny fullscreen path needed for an immediate loading frame.
        // This runs before OpenAL, compute-shader setup, and asset registration, so
        // the first swap shows loading artwork instead of a native black window.
        quadProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/quad.vert", GL_VERTEX_SHADER),
            ShaderUtil.compileShader("src/main/resources/shaders/quad.frag", GL_FRAGMENT_SHADER)
        );
        locQuadPass = glGetUniformLocation(quadProgram, "inputTexture");
        locQuadFlipY = glGetUniformLocation(quadProgram, "u_FlipY");
        setupQuad();
        int earlyLoadingTexture = 0;
        File earlyLoadingFile = new File("src/main/resources/ui/loading.png");
        if (earlyLoadingFile.exists()) {
            try {
                earlyLoadingTexture = UIManager.loadTexture(earlyLoadingFile.getPath());
            } catch (RuntimeException e) {
                System.err.println("Note: early loading texture unavailable; using fallback color");
            }
        }
        presentEarlyLoadingFrame(earlyLoadingTexture);
        bootMark.accept("first loading frame presented");
        bootMark.accept("GL capabilities ready");

        // Initialize OpenAL on the render thread. Voice synthesis itself is queued
        // asynchronously and never runs inside the game loop.
        villagerAudioManager = new com.voxel.audio.VillagerAudioManager();
        villagerAudioManager.initialize();
        presentEarlyLoadingFrame(earlyLoadingTexture);
        bootMark.accept("OpenAL ready");

        computeProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/raytracer.comp", GL_COMPUTE_SHADER)
        );
        // quad.frag uses inputTexture; keep this cached so both the normal and
        // early loading passes explicitly bind the texture they draw.
        locQuadPass = glGetUniformLocation(quadProgram, "inputTexture"); // Cache to avoid per-frame lookup
        cacheUniformLocations();
        // Atmosphere uniforms handled by AtmosphereRenderer (no per-frame glGetUniformLocation)
        presentEarlyLoadingFrame(earlyLoadingTexture);
        bootMark.accept("shaders ready");



        entityManager = new com.voxel.entity.EntityManager();
        com.voxel.entity.EnemyEntity.setEntityManager(entityManager);
        com.voxel.entity.VillagerEntity.setEntityManager(entityManager);
        com.voxel.world.structure.MapGenVillage.setEntityManager(entityManager);
        com.voxel.world.structure.MapGenVillage.setTextureManager(textureManager);
        // Start near the origin; spawn resolution replaces the fallback Y with
        // the generated surface height before gameplay begins.
        player = new Player(0, 63, 0);

        setupTexture();
        // Generate procedural textures BEFORE loading so they're available in the texture array
        generateCapeTexture();
        setupResources();
        presentEarlyLoadingFrame(earlyLoadingTexture);
        bootMark.accept("resources ready");

        // Create shared game context (world/chunkManager/dimensionManager filled below after init)
        ctx = new GameContext();
        cameraController = new CameraController(ctx, this);
        ctx.activeDimension = activeDimension;
        ctx.commandBlockManager.beginDimension(activeDimension.id);
        ctx.entityManager = entityManager;
        ctx.blockDataManager = blockDataManager;
        ctx.blockRegistry = blockRegistry;
        ctx.shaderBlockRegistry = shaderBlockRegistry;
        ctx.biomeManager = biomeManager;
        ctx.textureManager = textureManager;
        ctx.player = player;
        ctx.gameMode = gameMode;
        ctx.cameraMode = cameraMode;
        ctx.width = width;
        ctx.height = height;
        ctx.lastMouseX = lastMouseX;
        ctx.lastMouseY = lastMouseY;
        // Defer world GPU upload to render thread (avoid GL calls from LogicThread)
        ctx.uploadWorldToGpu = () -> { needsWorldUpload = true; };
        ctx.updateCursorMode = this::updateCursorMode;
        ctx.dismountMinecart = () -> dismountMinecart(ctx.ridingMinecart);
        ctx.statusConsumer = this::setStatus;
        ctx.spawnMobCommand = this::spawnMobAtLook;
        ctx.uiDirtyMarker = () -> { hud.inventoryUiDirty = true; };
        ctx.villagerAudioManager = villagerAudioManager;

        // Create extracted subsystems
        itemDefinitions = new ItemDefinitions();
        itemDefinitions.setup(blockDataManager, textureManager);
        com.voxel.utils.MinecraftContentLoader.registerMissingItems(
                itemDefinitions, blockDataManager, textureManager,
                "src/main/resources/assets/minecraft/models/item");
        ctx.itemDefinitions = itemDefinitions;

        // Build canonical block/item registry (deduplicates direction/level/model variants)
        com.voxel.game.CanonicalRegistry canonicalRegistry = new com.voxel.game.CanonicalRegistry();
        canonicalRegistry.build(blockDataManager, itemDefinitions);
        ctx.canonicalRegistry = canonicalRegistry;

        playerInventory = new PlayerInventory(ctx);
        ctx.playerInventory = playerInventory;
        playerInventory.populateStarting();

        // Tracks items dropped in the world (hover + auto-pickup). Initialized after
        // playerInventory because pickup uses ctx.playerInventory.addItem().
        ctx.droppedItemManager = new com.voxel.game.DroppedItemManager(ctx);

        // Encased fans (Create-inspired): push dropped items when redstone-powered
        ctx.encasedFanSystem = new com.voxel.game.EncasedFanSystem(ctx);

        blockInteraction = new BlockInteraction(ctx);
        portalSystem = new PortalSystem(ctx, blockInteraction);
        commandProcessor = new CommandProcessor(ctx);
        ctx.commandProcessor = commandProcessor;
        atmosphereRenderer = new AtmosphereRenderer(computeProgram);

        // Initialize villager TV and village systems
        ctx.tvSystem = new VillagerTVSystem();
        ctx.tvSystem.setChannelChangeListener(channel -> {
            if (channel == VillagerTVSystem.CHANNEL_VNN && ctx.villagerAudioManager != null) {
                ctx.villagerAudioManager.requestNewsIntro();
            }
        });
        ctx.villageManager = new VillagerVillageManager();
        // Wire village manager into MapGenVillage (must happen after ctx.villageManager is set)
        com.voxel.world.structure.MapGenVillage.setVillageManager(ctx.villageManager);
        ctx.tvBlockX = 0; ctx.tvBlockY = 0; ctx.tvBlockZ = 0;

        // Initialize crafting system (MUST be before setupUi)
        craftingManager = new CraftingManager();
        int vanillaRecipeCount = com.voxel.crafting.VanillaRecipeLoader.load(
                craftingManager,
                "src/main/resources/assets/minecraft/recipes",
                new java.util.HashSet<>(itemDefinitions.getRegistry().keySet()));
        System.out.println("[MC content] Crafting registry ready with " + vanillaRecipeCount + " imported recipes");
        ctx.craftingManager = craftingManager;

        hud = new com.voxel.ui.HudUI(ctx, this, cameraController, playerInventory, textureManager, itemDefinitions, biomeManager);
        setupUi();
        // World map renderer (top-down view, M to toggle)
        mapRenderer = new com.voxel.game.WorldMapRenderer();
        int mapTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, mapTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, mapRenderer.getTexSize(), mapRenderer.getTexSize(), 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        mapRenderer.setTextureId(mapTex);
        ctx.mapTexId = mapTex;

        // Build the 3D panorama scene + SSBOs so the menu can render real voxel
        // terrain behind it (the raytracer otherwise has nothing to render until
        // the world is committed). Runs before the first presented loop frame.
        setupPanoramaWorld();

        ctx.initializing = true;
        ctx.spawnLoadingMessage = "Initializing world...";

        // The UI is now fully initialized, so present the actual loading overlay
        // before save/log setup and before the logic thread starts allocating the
        // world pools. This is the first frame users need to see.
        presentInitialLoadingFrame();
        if (earlyLoadingTexture != 0) glDeleteTextures(earlyLoadingTexture);
        bootMark.accept("UI loading frame presented");
        bootMark.accept("UI ready");

        // The save manager is created when the main menu commits a world
        // (new world or load save), so the chosen save slot is baked in before
        // dimension generation starts. A null save manager is safe everywhere
        // (all call sites null-check). Initialize it to the default slot so
        // nothing crashes if the world is created before menu confirm.
        ctx.worldSaveManager = com.voxel.world.WorldSaveManager.forSave("world");

        // Initialize world gen logging
        GameLogger.init();

        WorldGenLogger.init();

        // Persistent FloatBuffer for pointLightSSBO zeroing (cheap; do it here so
        // the SSBO clear in the render loop can run before the world exists).
        // Header (4 ints: count + padding) + MAX_POINT_LIGHTS lights * 8 floats.
        persistentPlBuf = MemoryUtil.memAllocFloat(4 + GameContext.MAX_POINT_LIGHTS * 8);
        persistentPlBuf.put(0, Float.intBitsToFloat(0));

        // Heavy world + initial-entity initialization is intentionally deferred to
        // the loading-screen phase (see GameContext.initializing). It runs on the
        // logic thread during the first tick() via Main.initializeWorldPhase() so
        // the spawn-loading overlay is visible immediately instead of after a long
        // blank window. Shader compile/link and GL setup stay here per the user
        // (negligible startup cost).
        // ctx.initializing and ctx.spawnLoadingMessage were set immediately after
        // setupUi(), before the loading frame was presented.

        updateCursorMode();
        setStatus("Mode: survival. Press E for inventory, / for commands. R to respawn.");
        bootMark.accept("init complete");
    }

    /** Draw the loading artwork directly, before the HUD and world exist. */
    private void presentEarlyLoadingFrame(int loadingTexture) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glClearColor(0.88f, 0.94f, 0.78f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        if (loadingTexture != 0) {
            glUseProgram(quadProgram);
            glBindTextureUnit(0, loadingTexture);
            if (locQuadPass >= 0) glUniform1i(locQuadPass, 0);
            if (locQuadFlipY >= 0) glUniform1i(locQuadFlipY, 1);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
        glfwSwapBuffers(window);
        // Redraw before the second swap; swapping alone exposes the untouched
        // back buffer, which is commonly still black.
        if (loadingTexture != 0) {
            glUseProgram(quadProgram);
            glBindTextureUnit(0, loadingTexture);
            if (locQuadPass >= 0) glUniform1i(locQuadPass, 0);
            if (locQuadFlipY >= 0) glUniform1i(locQuadFlipY, 1);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
        glfwSwapBuffers(window);
        glfwPollEvents();
        // Startup callbacks are explicitly null-safe; keep the native event pump
        // alive while synchronous initialization continues.
    }

    /**
     * Draw one UI-only loading frame before world initialization begins. The normal
     * render loop composites the UI through the ray tracer, but that path requires
     * world SSBOs. The UI FBO already contains an opaque loading background, so the
     * fullscreen quad can present it directly without touching world resources.
     */
    private void presentInitialLoadingFrame() {
        hud.updateSpawnLoadingOverlay(glfwGetTime());
        hud.uiManager.begin();
        for (UILayer layer : hud.uiLayers) layer.render(hud.uiManager);
        hud.uiManager.end();

        if (panoramaActive) {
            // Present the 3D panorama (with the menu UI composited by the
            // raytracer) as the very first visible frame.
            renderMenuPanorama(0f);
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, width, height);
            // Use the same bright fallback tone as the real loading background so
            // this first presented frame is visibly a loading frame, not a black flash.
            glClearColor(0.88f, 0.94f, 0.78f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(quadProgram);
            glBindTextureUnit(0, hud.uiManager.getUITexture());
            if (locQuadPass >= 0) glUniform1i(locQuadPass, 0);
            if (locQuadFlipY >= 0) glUniform1i(locQuadFlipY, 0);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
        glfwSwapBuffers(window);
        glfwPollEvents();
    }

    /**
     * Builds the hand-crafted 3D scene shown behind the main menu (rolling
     * hills, a lake, trees, a stone watchtower and floating islands) and
     * uploads it into the chunk SSBOs so the raytracer can render it while the
     * real world is still uninitialized. A slowly orbiting camera renders it
     * Minecraft-panorama style. When a world is committed, uploadWorldToGpu()
     * deletes these SSBOs and replaces them with the real world's data.
     * Runs once on the GL thread during init, before the render loop starts.
     */
    private void setupPanoramaWorld() {
        try {
            panoramaWorld = new com.voxel.World(256); // 8×3×8 = 192 slots (incl. +X/+Z light border) + headroom
            panoramaNextSlot = 0;

            int GRASS = blockRegistry.getId("grass_block");
            int DIRT  = blockRegistry.getId("dirt");
            int STONE = blockRegistry.getId("stone");
            int SAND  = blockRegistry.getId("sand");
            int WATER = blockRegistry.getId("water");
            int LOG   = blockRegistry.getId("oak_log");
            int LEAF  = blockRegistry.getId("oak_leaves");
            int COBBLE = blockRegistry.getId("cobblestone");
            if (GRASS <= 0) GRASS = 1;
            if (DIRT <= 0) DIRT = 13;
            if (STONE <= 0) STONE = 2;
            if (SAND <= 0) SAND = 14;
            if (WATER <= 0) WATER = 15;
            if (LOG <= 0) LOG = 5;
            if (LEAF <= 0) LEAF = 4;
            if (COBBLE <= 0) COBBLE = STONE;

            java.util.Random rnd = new java.util.Random(42);

            // Allocate an 8×3×8 chunk region: the 7×3×7 terrain (112×48×112 blocks)
            // plus a one-chunk air border on the +X/+Z sides. The border gives the
            // outer cliff faces a real (fully-lit) air chunk to sample light from
            // (the -X/-Z edges don't need one: the shader's DDA stops at buffer
            // coordinate 0 and the shading fallback samples the block's own light,
            // which is fully lit). Border chunks stay entirely air.
            for (int cx = 0; cx <= 7; cx++) {
                for (int cy = 0; cy <= 2; cy++) {
                    for (int cz = 0; cz <= 7; cz++) {
                        int slot = panoramaNextSlot++;
                        panoramaWorld.setChunkSlot(cx, cy, cz, slot);
                        panoramaWorld.clearChunkPoolSlot(slot);
                    }
                }
            }

            // Rolling-hills heightfield with a valley at the center for the lake.
            float[][] height = new float[112][112];
            for (int x = 0; x < 112; x++) {
                for (int z = 0; z < 112; z++) {
                    height[x][z] = 24f
                            + 5.5f * (float) Math.sin(x * 0.045)
                            + 4.0f * (float) Math.cos(z * 0.06)
                            + 2.5f * (float) Math.sin((x + z) * 0.028)
                            + 1.8f * (float) Math.sin(x * 0.11 + 1.7f) * (float) Math.cos(z * 0.13);
                }
            }
            // Carve the lake basin near the center.
            for (int x = 42; x <= 70; x++) {
                for (int z = 40; z <= 72; z++) {
                    float dx = (x - 56) / 16f, dz = (z - 56) / 15f;
                    float d = dx * dx + dz * dz;
                    if (d < 1.0f) {
                        height[x][z] = Math.min(height[x][z], 23f - (1f - d) * 2f);
                    }
                }
            }

            for (int x = 0; x < 112; x++) {
                for (int z = 0; z < 112; z++) {
                    int h = (int) Math.floor(height[x][z]);
                    boolean shore = h <= 25; // sandy shoreline around the lake
                    for (int y = 0; y <= h; y++) {
                        int type;
                        if (y == h) type = shore ? SAND : GRASS;
                        else if (y >= h - 3) type = DIRT;
                        else type = STONE;
                        placePanoramaVoxel(x, y, z, type);
                    }
                    // Lake water fills from the bed up to the waterline (y=26).
                    if (h < 26) {
                        for (int y = h + 1; y <= 26; y++) {
                            placePanoramaVoxel(x, y, z, WATER);
                        }
                    }
                }
            }

            // Scattered trees on dry, not-too-high ground.
            for (int i = 0; i < 16; i++) {
                int x = 8 + rnd.nextInt(96);
                int z = 8 + rnd.nextInt(96);
                int h = (int) Math.floor(height[x][z]);
                if (h < 25 || h > 33) continue;
                for (int y = h + 1; y <= h + 4; y++) placePanoramaVoxel(x, y, z, LOG);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        int rr = dx * dx + dz * dz;
                        if (rr > 4) continue;
                        placePanoramaVoxel(x + dx, h + 4, z + dz, LEAF);
                        if (rr <= 2) placePanoramaVoxel(x + dx, h + 5, z + dz, LEAF);
                    }
                }
                placePanoramaVoxel(x, h + 6, z, LEAF);
            }

            // A small cobblestone watchtower on the east shore of the lake.
            int tx = 74, tz = 40;
            int th = (int) Math.floor(height[tx][tz]);
            for (int y = th + 1; y <= th + 8; y++) {
                for (int dx = 0; dx <= 2; dx++) {
                    for (int dz = 0; dz <= 2; dz++) {
                        boolean rim = (dx == 0 || dx == 2) || (dz == 0 || dz == 2);
                        if (y <= th + 7) {
                            if (rim || y <= th + 2) placePanoramaVoxel(tx + dx, y, tz + dz, COBBLE);
                        } else if (rim) {
                            placePanoramaVoxel(tx + dx, y, tz + dz, COBBLE); // crenellation
                        }
                    }
                }
            }

            // Floating islands — the classic panorama look.
            buildPanoramaIsland(28, 41, 30, 6, GRASS, DIRT, STONE);
            buildPanoramaIsland(86, 42, 78, 5, GRASS, DIRT, STONE);

            // ── Create machines showcase (all spinning where kinetic) ──
            // A leveled plaza holding a windmill, cogwheel power line, millstone,
            // press, crushing wheel, drill, saw, belt feeding a vault, a hand
            // crank, encased fan, lit blaze burner + steam engine, copper tank
            // and brass casing pillars. Kinetic blocks carry FLAG_SPINNING (bit
            // 24) so the raytracer animates their texture strips; the windmill
            // sails use the 4-frame spin strip added to isKineticBlock.
            int SHAFT = 291, COG = 294, CRANK = 404, BEARING = 405, SAIL = 406,
                PRESS = 407, MILL = 408, CRUSHER = 409, DRILL = 410, SAW = 411,
                BELT = 413, VAULT = 414, CASING = 415, FAN = 263, BURNER = 395,
                ENGINE = 397, TANK = 398, TANK1 = 399;
            final int SPIN = 1; // KineticManager.FLAG_SPINNING
            // Level a flat plaza for the machines.
            for (int px = 62; px <= 84; px++) {
                for (int pz = 13; pz <= 30; pz++) {
                    for (int py = 27; py <= 44; py++) placePanoramaVoxel(px, py, pz, 0);
                    placePanoramaVoxel(px, 26, pz, GRASS);
                    placePanoramaVoxel(px, 25, pz, DIRT);
                    placePanoramaVoxel(px, 24, pz, DIRT);
                }
            }
            int mbx = 66, mbz = 24, mby = 26; // plaza origin + surface level
            // Windmill tower: bearing + 4 sails on a vertical shaft.
            placePanoramaVoxel(mbx + 7, mby + 1, mbz - 7, SHAFT, 0, SPIN);
            placePanoramaVoxel(mbx + 7, mby + 2, mbz - 7, SHAFT, 0, SPIN);
            placePanoramaVoxel(mbx + 7, mby + 3, mbz - 7, SHAFT, 0, SPIN);
            placePanoramaVoxel(mbx + 7, mby + 4, mbz - 7, SHAFT, 0, SPIN);
            placePanoramaVoxel(mbx + 7, mby + 5, mbz - 7, BEARING, 0, 0);
            placePanoramaVoxel(mbx + 6, mby + 5, mbz - 7, SAIL, 0, SPIN);
            placePanoramaVoxel(mbx + 8, mby + 5, mbz - 7, SAIL, 0, SPIN);
            placePanoramaVoxel(mbx + 7, mby + 5, mbz - 8, SAIL, 0, SPIN);
            placePanoramaVoxel(mbx + 7, mby + 5, mbz - 6, SAIL, 0, SPIN);
            placePanoramaVoxel(mbx + 8, mby + 1, mbz - 7, COG, 0, SPIN);
            // Cogwheel power line along +X.
            for (int n = 1; n <= 4; n++) placePanoramaVoxel(mbx + 8 + n, mby + 1, mbz - 7, COG, 0, SPIN);
            // Machine row (adjacent to the power line).
            placePanoramaVoxel(mbx + 8, mby + 1, mbz - 6, MILL, 0, SPIN);
            placePanoramaVoxel(mbx + 9, mby + 1, mbz - 6, PRESS, 0, SPIN);
            placePanoramaVoxel(mbx + 10, mby + 1, mbz - 6, CRUSHER, 0, SPIN);
            placePanoramaVoxel(mbx + 11, mby + 1, mbz - 6, DRILL, 0, SPIN); // facing down
            placePanoramaVoxel(mbx + 12, mby + 1, mbz - 6, SAW, 5, SPIN);    // facing +X (east)
            // Belt feeding the item vault (horizontal facing so it would run).
            for (int n = 0; n <= 3; n++) placePanoramaVoxel(mbx + 8 + n, mby + 1, mbz - 5, BELT, 5, SPIN);
            placePanoramaVoxel(mbx + 12, mby + 1, mbz - 5, VAULT, 0, 0);
            // Hand crank at the end of the line.
            placePanoramaVoxel(mbx + 13, mby + 1, mbz - 6, CRANK, 0, SPIN);
            // Encased fan + steam setup along the back row.
            placePanoramaVoxel(mbx + 9, mby + 1, mbz - 8, FAN, 5, 0);
            placePanoramaVoxel(mbx + 10, mby + 1, mbz - 8, BURNER, 0, 0);
            placePanoramaVoxel(mbx + 10, mby + 2, mbz - 8, ENGINE, 0, 0);
            placePanoramaVoxel(mbx + 11, mby + 1, mbz - 8, TANK, 0, 0);
            placePanoramaVoxel(mbx + 11, mby + 2, mbz - 8, TANK1, 0, 0);
            // Brass casing pillars for visual framing.
            for (int h = 1; h <= 3; h++) {
                placePanoramaVoxel(mbx + 6, mby + h, mbz - 3, CASING, 0, 0);
                placePanoramaVoxel(mbx + 6, mby + h, mbz - 4, CASING, 0, 0);
                placePanoramaVoxel(mbx + 13, mby + h, mbz - 3, CASING, 0, 0);
                placePanoramaVoxel(mbx + 13, mby + h, mbz - 4, CASING, 0, 0);
            }

            // Sky light: full brightness on every voxel. The shader samples the
            // light map at the hit face's neighbor (air or water for every visible
            // surface in this scene — no caves/overhangs), so surfaces are fully
            // lit and depth comes from AO + the sun term. The air border chunks
            // are already fully lit by this same loop.
            int[] chunkPoolArr = panoramaWorld.getChunkPool();
            int[] lightPoolArr = panoramaWorld.getLightPool();
            for (int slot = 0; slot < panoramaNextSlot; slot++) {
                int base = slot << 12;
                for (int i = 0; i < 4096; i++) {
                    lightPoolArr[base + i] = 0xFF;
                }
            }

            // ── Upload the scene to the chunk SSBOs (same layout as uploadWorldToGpu) ──
            int poolSize = panoramaWorld.getPoolSizeForAlloc();
            IntBuffer tableBuf = MemoryUtil.memAllocInt(panoramaWorld.getIndirectionTable().length);
            tableBuf.put(panoramaWorld.getIndirectionTable()).flip();
            indirectionSSBO = glCreateBuffers();
            glNamedBufferStorage(indirectionSSBO, tableBuf, GL_DYNAMIC_STORAGE_BIT);
            MemoryUtil.memFree(tableBuf);

            chunkPoolSSBO = glCreateBuffers();
            glNamedBufferStorage(chunkPoolSSBO, (long) poolSize * 4096 * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);
            IntBuffer poolBuf = MemoryUtil.memAllocInt(poolSize * 4096);
            poolBuf.put(chunkPoolArr).flip();
            glNamedBufferSubData(chunkPoolSSBO, 0, poolBuf);
            MemoryUtil.memFree(poolBuf);

            bitmaskSSBO = glCreateBuffers();
            glNamedBufferStorage(bitmaskSSBO, (long) poolSize * 128 * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);
            IntBuffer maskBuf = MemoryUtil.memAllocInt(poolSize * 128);
            maskBuf.put(panoramaWorld.getBitmaskPool()).flip();
            glNamedBufferSubData(bitmaskSSBO, 0, maskBuf);
            MemoryUtil.memFree(maskBuf);

            occlusionSSBO = glCreateBuffers();
            glNamedBufferStorage(occlusionSSBO, (long) poolSize * 4096 * Short.BYTES, GL_DYNAMIC_STORAGE_BIT);
            // Zero it (the shader's getVoxelOcclusion is currently uncalled, but
            // undefined storage is a foot-gun if it ever becomes live).
            java.nio.ByteBuffer zeroOcc = java.nio.ByteBuffer.allocate(poolSize * 4096 * 2);
            glNamedBufferSubData(occlusionSSBO, 0, zeroOcc);

            lightSSBO = glCreateBuffers();
            glNamedBufferStorage(lightSSBO, (long) poolSize * 4096 * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);
            IntBuffer lightBuf = MemoryUtil.memAllocInt(poolSize * 4096);
            lightBuf.put(lightPoolArr).flip();
            glNamedBufferSubData(lightSSBO, 0, lightBuf);
            MemoryUtil.memFree(lightBuf);

            pointLightSSBO = glCreateBuffers();
            glNamedBufferStorage(pointLightSSBO, 4096, GL_DYNAMIC_STORAGE_BIT);
            IntBuffer plHeader = MemoryUtil.memAllocInt(4);
            plHeader.put(0).put(0).put(0).put(0).flip();
            glNamedBufferSubData(pointLightSSBO, 0, plHeader);
            MemoryUtil.memFree(plHeader);

            craftingItemSSBO = glCreateBuffers();
            glNamedBufferStorage(craftingItemSSBO, (long) 80 * 32, GL_DYNAMIC_STORAGE_BIT);

            sdfSSBO = glCreateBuffers();
            glNamedBufferStorage(sdfSSBO, (long) poolSize * 8, GL_DYNAMIC_STORAGE_BIT);
            java.nio.ByteBuffer zeroSdf = java.nio.ByteBuffer.allocate(poolSize * 8);
            glNamedBufferSubData(sdfSSBO, 0, zeroSdf);

            // Zero the 16 light-pool textures so the panorama's god-ray sampling
            // sees clean zeros (no shafts) instead of undefined storage content.
            java.nio.ByteBuffer zeroPool = java.nio.ByteBuffer.allocate(shadowMapRes * shadowMapRes * 4);
            for (int i = 0; i < 16; i++) {
                glTextureSubImage2D(lightPoolTex[i], 0, 0, 0, shadowMapRes, shadowMapRes, GL_RED, GL_FLOAT, zeroPool);
            }

            // Give the menu terrain varied grass colors (the real biome map is
            // filled per-chunk once the actual world generates).
            biomeManager.createPanoramaBiomeData(2048);
            biomeManager.uploadBiomeMap();

            panoramaActive = true;
        } catch (RuntimeException e) {
            // Never let the menu break because of the panorama: fall back to the
            // 2D menu background image.
            System.err.println("Menu panorama setup failed, using 2D background:");
            e.printStackTrace();
            panoramaActive = false;
        }
    }

    private void placePanoramaVoxel(int x, int y, int z, int type) {
        placePanoramaVoxel(x, y, z, type, 0, 0);
    }

    private void placePanoramaVoxel(int x, int y, int z, int type, int extra, int flags) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        int slot = panoramaWorld.getIndirectionTable()[cx + cy * 128 + cz * 128 * 128];
        if (slot == com.voxel.World.EMPTY) return;
        panoramaWorld.setVoxelInPool(slot, x & 15, y & 15, z & 15, type, extra, flags);
    }

    /** Builds a grass-topped floating mound (classic panorama floating island). */
    private void buildPanoramaIsland(int cx, int baseY, int cz, int radius, int grass, int dirt, int stone) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                float d = (dx * dx + dz * dz) / (float) (radius * radius);
                if (d > 1.1f) continue;
                int thick = (int) ((1f - d) * (radius * 0.9f)) + 2;
                for (int t = 0; t < thick; t++) {
                    int y = baseY + t;
                    int type = (t == thick - 1) ? grass : (t >= thick - 3 ? dirt : stone);
                    placePanoramaVoxel(cx + dx, y, cz + dz, type);
                }
            }
        }
    }

    /**
     * Renders one frame of the 3D main-menu panorama: a full raytracer pass with
     * a slowly orbiting camera aimed at the hand-crafted scene, with the menu UI
     * composited on top (u_UITexture). Mirrors the world-render pass minus all
     * gameplay state (entities, point lights, break overlay, light-pool regen).
     */
    private void renderMenuPanorama(float dt) {
        // Slow orbit (~2.5 min/revolution) + gentle sun drift for a living sky.
        panoramaAngle += dt * 0.042f;
        panoramaTime += dt * 1.8f;
        if (panoramaTime > 24000f) panoramaTime -= 24000f;

        float a = panoramaAngle;
        float camX = PANORAMA_CX + (float) Math.cos(a) * 46f;
        float camZ = PANORAMA_CZ + (float) Math.sin(a) * 46f;
        float camY = PANORAMA_CY + 18f + (float) Math.sin(a * 0.7f) * 3.0f;
        float dx = PANORAMA_CX - camX, dy = PANORAMA_CY + 8f - camY, dz = PANORAMA_CZ - camZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ry = Math.atan2(dz, dx);
        double rp = Math.asin(Math.max(-1.0, Math.min(1.0, dy / dist)));
        float fx = (float) (Math.cos(ry) * Math.cos(rp)), fy = (float) Math.sin(rp), fz = (float) (Math.sin(ry) * Math.cos(rp));
        float rx = -fz, rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl > 0) { rx /= rl; rz /= rl; }
        float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;

        int cbx = (int) Math.floor(camX), cby = (int) Math.floor(camY), cbz = (int) Math.floor(camZ);
        float cfx = camX - cbx, cfy = camY - cby, cfz = camZ - cbz;

        glUseProgram(computeProgram);

        // No point lights (defensive re-zero of the count header every frame).
        panoramaPlHeader.rewind();
        glNamedBufferSubData(pointLightSSBO, 0, panoramaPlHeader);

        // Camera + world uniforms (buffer-relative; the panorama world offset is 0).
        glProgramUniform3f(computeProgram, 0, cfx, cfy, cfz);
        glProgramUniform3i(computeProgram, 29, cbx, cby, cbz);
        glProgramUniform3f(computeProgram, 1, fx, fy, fz);
        glProgramUniform3f(computeProgram, 2, rx, 0, rz);
        glProgramUniform3f(computeProgram, 3, ux, uy, uz);
        glProgramUniform1f(computeProgram, 4, panoramaTime);
        glProgramUniform1i(computeProgram, 5, 0); // no entities
        atmosphereRenderer.upload(panoramaTime, DimensionType.OVERWORLD);
        glProgramUniform1i(computeProgram, atmosphereRenderer.locDimensionID(), DimensionType.OVERWORLD.id);
        glProgramUniform3i(computeProgram, 6, 0, 0, 0); // world offset

        // No break overlay, no underwater camera.
        glProgramUniform1i(computeProgram, LOC_UNDER_WATER, 0);
        glProgramUniform3i(computeProgram, 19, 0, 0, 0);
        glProgramUniform1f(computeProgram, 20, 0.0f);
        int destroyBaseLayer = textureManager.getTextureIndex("destroy_stage_0");
        glProgramUniform1i(computeProgram, 21, destroyBaseLayer < 0 ? -1 : destroyBaseLayer);

        glUniform4f(locHeartUVs, hud.uvHeartFull.x, hud.uvHeartFull.y, hud.uvHeartFull.z, hud.uvHeartFull.w);
        glUniform4f(locHeartUVs + 1, hud.uvHeartHalf.x, hud.uvHeartHalf.y, hud.uvHeartHalf.z, hud.uvHeartHalf.w);
        glUniform4f(locHeartUVs + 2, hud.uvHeartEmpty.x, hud.uvHeartEmpty.y, hud.uvHeartEmpty.z, hud.uvHeartEmpty.w);

        bindTextures();
        glActiveTexture(GL_TEXTURE17);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getDestroyStageArrayId());
        if (locDestroyStages >= 0) glUniform1i(locDestroyStages, 17);
        glActiveTexture(GL_TEXTURE15);
        glBindTexture(GL_TEXTURE_2D, hud.uiTextureId);
        glUniform1i(locUISource, 15);

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, bitmaskSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, occlusionSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, pointLightSSBO);
        entityManager.bind(6, 7);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, craftingItemSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, lightSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 10, sdfSSBO);
        glProgramUniform1i(computeProgram, locCraftingItemCount, 0);

        // God-ray state: real uniforms, but the pools are zeroed so no shafts
        // appear — avoids any stale/undefined pool-texture sampling.
        glProgramUniform1i(computeProgram, LOC_SHADOW_PASS, 0);
        AtmosphereRenderer.computeSunDir(DimensionType.OVERWORLD, panoramaTime, activeSunDir);
        activeMoonDir[0] = -activeSunDir[0];
        activeMoonDir[1] = -activeSunDir[1];
        activeMoonDir[2] = -activeSunDir[2];
        uploadPoolBasis(LOC_SHADOW_ORIGIN, LOC_SHADOW_RIGHT, LOC_SHADOW_UP, LOC_SHADOW_SUN_DIR,
                activeSunDir, cbx, cby, cbz);
        uploadPoolBasis(LOC_MOON_POOL_ORIGIN, LOC_MOON_POOL_RIGHT, LOC_MOON_POOL_UP, LOC_MOON_POOL_DIR,
                activeMoonDir, cbx, cby, cbz);
        glProgramUniform2f(computeProgram, LOC_SHADOW_EXTENT, shadowHalfExtent, shadowDepth);
        glActiveTexture(GL_TEXTURE18);
        glBindTexture(GL_TEXTURE_2D, lightPoolTex[0]);
        glActiveTexture(GL_TEXTURE19);
        glBindTexture(GL_TEXTURE_2D, lightPoolTex[8]);
        glProgramUniform1i(computeProgram, LOC_SUN_POOL, 18);
        glProgramUniform1i(computeProgram, LOC_MOON_POOL, 19);
        glProgramUniform1f(computeProgram, LOC_SHADOW_MAP_SIZE, 1.0f / shadowMapRes);

        glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(quadProgram);
        glBindTextureUnit(0, renderTexture);
        glUniform1i(locQuadPass, 0);
        if (locQuadFlipY >= 0) glUniform1i(locQuadFlipY, 0);
        glBindVertexArray(quadVAO);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    public void cacheUniformLocations() {
        locBlockTextures = glGetUniformLocation(computeProgram, "u_BlockTextures");
        locEntityTextures = glGetUniformLocation(computeProgram, "u_EntityTextures");
        locBlockData = glGetUniformLocation(computeProgram, "u_BlockData");
        locBlockAABBs = glGetUniformLocation(computeProgram, "u_BlockAABBs");
        locBlockAABBInfo = glGetUniformLocation(computeProgram, "u_BlockAABBInfo");
        locBlockAABBUVs = glGetUniformLocation(computeProgram, "u_BlockAABBUVs");
        locBiomeMap = glGetUniformLocation(computeProgram, "u_BiomeMap");
        locUITexture = glGetUniformLocation(computeProgram, "u_UITexture");
        locUISource = glGetUniformLocation(computeProgram, "u_UISource");
        locDimensionId = glGetUniformLocation(computeProgram, "u_DimensionID");
        locFogColor = glGetUniformLocation(computeProgram, "u_FogColor");
        locSkyTint = glGetUniformLocation(computeProgram, "u_SkyTint");
        locHeartUVs = glGetUniformLocation(computeProgram, "u_HeartUVs");
        locCraftingItemCount = glGetUniformLocation(computeProgram, "u_CraftingItemCount");
        locDestroyStages = glGetUniformLocation(computeProgram, "u_DestroyStages");
        locMapMode = glGetUniformLocation(computeProgram, "u_MapMode");
        locMapPreview = glGetUniformLocation(computeProgram, "u_MapPreview");
        locMapPreviewOrigin = glGetUniformLocation(computeProgram, "u_MapPreviewOrigin");
        locMapPreviewScale = glGetUniformLocation(computeProgram, "u_MapPreviewScale");
        locMapGroundY = glGetUniformLocation(computeProgram, "u_MapGroundY");
        locMapWorldOrigin = glGetUniformLocation(computeProgram, "u_MapWorldOrigin");
        locMapBorder = glGetUniformLocation(computeProgram, "u_MapBorder");
    }

    /**
     * Spawns a mob of the given type where the player is looking (the empty
     * cell in front of the targeted block, dropped to the ground). This
     * replaces the old startup auto-spawn: mobs now appear only when the player
     * asks for them via {@code /spawn <mob>}.
     */
    private void spawnMobAtLook(String type) {
        Vector3f pos = spawnPosAtLook();

        com.voxel.entity.Entity mob;
        switch (type) {
            case "zombie":
                mob = new com.voxel.entity.ZombieEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "husk":
                mob = genericMob(pos, "husk.json");
                break;
            case "skeleton":
                mob = new com.voxel.entity.SkeletonEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "stray":
                mob = genericMob(pos, "stray.json");
                break;
            case "wither_skeleton":
            case "witherskeleton":
                mob = genericMob(pos, "wither_skeleton.json");
                break;
            case "spider":
                mob = new com.voxel.entity.SpiderEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "cave_spider":
            case "cavespider":
                mob = genericMob(pos, "cave_spider.json");
                break;
            case "enderman":
            case "enderman_entity":
                mob = new com.voxel.entity.EndermanEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "endermite":
                mob = new com.voxel.entity.EndermiteEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "silverfish":
                mob = new com.voxel.entity.SilverfishEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "bat":
                mob = genericMob(pos, "bat.json");
                break;
            case "dragon":
            case "ender_dragon":
                mob = genericMob(pos, "dragon.json");
                break;
            case "ghast":
                mob = genericMob(pos, "ghast.json");
                break;
            case "guardian":
            case "elder_guardian":
                mob = genericMob(pos, "guardian.json");
                break;
            case "horse":
            case "donkey":
            case "mule":
                mob = genericMob(pos, "horse.json");
                break;
            case "llama":
                mob = genericMob(pos, "llama.json");
                break;
            case "ocelot":
            case "cat":
                mob = genericMob(pos, "ocelot.json");
                break;
            case "parrot":
                mob = genericMob(pos, "parrot.json");
                break;
            case "polar_bear":
            case "polarbear":
                mob = genericMob(pos, "polar_bear.json");
                break;
            case "rabbit":
                mob = genericMob(pos, "rabbit.json");
                break;
            case "shulker":
                mob = genericMob(pos, "shulker.json");
                break;
            case "slime":
            case "small_slime":
                mob = genericMob(pos, "slime.json");
                break;
            case "magma_cube":
            case "magmacube":
                mob = new com.voxel.entity.MagmaCubeEntity(
                        nextSpawnCommandId++, pos, textureManager, player, 4);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "squid":
                mob = genericMob(pos, "squid.json");
                break;
            case "vex":
                mob = genericMob(pos, "vex.json");
                break;
            case "witch":
                mob = genericMob(pos, "witch.json");
                break;
            case "evoker":
            case "vindicator":
            case "illusioner":
            case "illager":
                mob = genericMob(pos, "illager.json");
                break;
            case "wither":
                mob = new com.voxel.entity.WitherEntity(
                        nextSpawnCommandId++, pos, textureManager, entityManager);
                ((com.voxel.entity.WitherEntity) mob).world = world;
                break;
            case "wolf":
                mob = genericMob(pos, "wolf.json");
                break;
            case "zombie_villager":
            case "zombievillager":
                mob = genericMob(pos, "zombie_villager.json");
                break;
            case "creeper": {
                com.voxel.entity.CreeperEntity creeper = new com.voxel.entity.CreeperEntity(
                    nextSpawnCommandId++, pos, textureManager, player);
                creeper.setWorld(world);
                com.voxel.entity.CreeperEntity.setChunkManager(chunkManager);
                mob = creeper;
                break;
            }
            case "blaze":
                mob = new BlazeEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "pigman":
            case "zombie_pigman":
            case "zombiepigman":
                mob = new ZombiePigmanEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.EnemyEntity) mob).setWorld(world);
                break;
            case "villager":
                mob = new VillagerEntity(nextSpawnCommandId++, pos, textureManager);
                ((VillagerEntity) mob).setWorld(world);
                break;
            case "iron_golem":
            case "irongolem":
            case "golem":
                mob = new com.voxel.entity.IronGolemEntity(nextSpawnCommandId++, pos, textureManager);
                ((com.voxel.entity.UtilityMobEntity) mob).setWorld(world);
                break;
            case "snow_golem":
            case "snowgolem":
            case "snowman":
            case "snow_golem_unsheared":
            case "snowman_unsheared":
                mob = new com.voxel.entity.SnowGolemEntity(nextSpawnCommandId++, pos, textureManager, true);
                ((com.voxel.entity.UtilityMobEntity) mob).setWorld(world);
                break;
            case "snow_golem_sheared":
            case "snowman_sheared":
            case "snow_golem_no_pumpkin":
            case "snowman_no_pumpkin":
                mob = new com.voxel.entity.SnowGolemEntity(nextSpawnCommandId++, pos, textureManager, false);
                ((com.voxel.entity.UtilityMobEntity) mob).setWorld(world);
                break;
            case "pig":
                mob = new com.voxel.entity.PigEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.FarmAnimalEntity) mob).setWorld(world);
                break;
            case "cow":
                mob = new com.voxel.entity.CowEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.FarmAnimalEntity) mob).setWorld(world);
                break;
            case "mooshroom":
                mob = genericMob(pos, "mooshroom.json");
                break;
            case "chicken":
                mob = new com.voxel.entity.ChickenEntity(nextSpawnCommandId++, pos, textureManager, player);
                ((com.voxel.entity.FarmAnimalEntity) mob).setWorld(world);
                break;
            case "sheep":
            case "sheep_unsheared":
            case "unsheared_sheep":
                mob = new com.voxel.entity.SheepEntity(nextSpawnCommandId++, pos, textureManager, player, false);
                ((com.voxel.entity.FarmAnimalEntity) mob).setWorld(world);
                break;
            case "sheep_sheared":
            case "sheared_sheep":
                mob = new com.voxel.entity.SheepEntity(nextSpawnCommandId++, pos, textureManager, player, true);
                ((com.voxel.entity.FarmAnimalEntity) mob).setWorld(world);
                break;
            default:
                setStatus("Unknown mob: " + type + ". Try: zombie, husk, skeleton, stray, wither_skeleton, spider, cave_spider, enderman, endermite, silverfish, bat, dragon, ghast, guardian, horse, llama, cat, parrot, polar_bear, rabbit, shulker, slime, magma_cube, squid, vex, witch, evoker, vindicator, wither, wolf, creeper, villager, iron_golem, snow_golem, snow_golem_sheared, blaze, pigman, pig, cow, mooshroom, chicken, sheep, sheep_sheared.");
                return;
        }

        mob.dimension = activeDimension;
        entityManager.addEntity(mob);
        setStatus("Spawned " + type);
    }

    /** Creates one of the source-model-only mobs with the shared hostile behavior. */
    private com.voxel.entity.Entity genericMob(Vector3f pos, String modelFile) {
        com.voxel.entity.GenericMobEntity mob = new com.voxel.entity.GenericMobEntity(
                nextSpawnCommandId++, pos, textureManager, player,
                "src/main/resources/assets/minecraft/models/entity/" + modelFile);
        mob.setWorld(world);
        return mob;
    }

    /** Raycasts from the player's view and returns a grounded spawn position. */
    private Vector3f spawnPosAtLook() {
        Vector3f spawnPos;
        int[] hit = raycastBlock(64.0f);
        if (hit != null) {
            // hit[3..5] is the empty cell the ray crossed just before the block.
            spawnPos = new Vector3f(hit[3] + 0.5f, hit[4], hit[5] + 0.5f);
        } else {
            // No block in view: spawn 10 blocks along the look ray.
            Vector3f dir = getLookDirection();
            Vector3f eye = getActiveCameraPosition();
            spawnPos = new Vector3f(eye.x + dir.x * 10.0f, eye.y + dir.y * 10.0f, eye.z + dir.z * 10.0f);
        }

        // Drop straight down to stand on the first solid block below.
        int sx = (int) Math.floor(spawnPos.x);
        int sz = (int) Math.floor(spawnPos.z);
        int sy = (int) Math.floor(spawnPos.y);
        for (int y = Math.min(sy, 255); y >= 0; y--) {
            if (world.getVoxel(sx, y, sz) != 0) { sy = y + 1; break; }
        }
        return new Vector3f(sx + 0.5f, sy, sz + 0.5f);
    }

    /** Debug helper (C key): spawns a creeper at the player's look target. */
    private void spawnCreeperAtLook() {
        spawnMobAtLook("creeper");
    }

    public void spawnNetherMobs(Player p) {
        Vector3f pos = p.getPosition();
        // Pigmen: neutral horde, scattered near the portal
        for (int i = 0; i < 5; i++) {
            ZombiePigmanEntity pigman = new ZombiePigmanEntity(70000 + i,
                new Vector3f(pos.x + (i - 2f) * 5f, pos.y, pos.z + (i % 3 - 1f) * 6f),
                textureManager, p);
            pigman.dimension = activeDimension;
            pigman.setWorld(world);
            entityManager.addEntity(pigman);
        }
        // Blazes: floating shooters, elevated above the portal area
        for (int i = 0; i < 2; i++) {
            BlazeEntity blaze = new BlazeEntity(70100 + i,
                new Vector3f(pos.x + (i * 8f - 4f), pos.y + 3f + i * 2f, pos.z + (i * 6f - 3f)),
                textureManager, p);
            blaze.dimension = activeDimension;
            blaze.setWorld(world);
            entityManager.addEntity(blaze);
        }
        setStatus("Entered the Nether... hostile mobs near!");
    }

    /**
     * Places a themed mob roster in a showcase zone the first time the player
     * enters it. The tutorial world is handcrafted and block-explosions would
     * wreck it, so the roster uses peaceful farm animals plus zombies,
     * skeletons and spiders — no creepers.
     */
    private void spawnTutorialZoneMobs(int zoneIdx) {
        if (zoneIdx < 0 || !tutorialMobZonesSpawned.add(zoneIdx)) return;
        int cx = com.voxel.world.TutorialWorldAuthor.zones()[zoneIdx].cx;
        int cz = com.voxel.world.TutorialWorldAuthor.zones()[zoneIdx].cz;
        switch (zoneIdx) {
            case 4: // Biome Garden — farm animals graze while spiders prowl the treeline
                spawnTutorialPig(cx - 8, cz + 2);
                spawnTutorialCow(cx + 8, cz - 2);
                spawnTutorialChicken(cx - 2, cz + 8);
                spawnTutorialSheep(cx + 4, cz + 6);
                spawnTutorialSpider(cx - 12, cz + 4);
                spawnTutorialSpider(cx + 10, cz - 3);
                spawnTutorialSpider(cx - 4, cz + 6);
                spawnTutorialSpider(cx + 6, cz + 3);
                break;
            case 6: // Quarry Mine — skeletons guard the dig rim
                spawnTutorialSkeleton(cx - 12, cz);
                spawnTutorialSkeleton(cx + 12, cz + 2);
                spawnTutorialSkeleton(cx, cz - 12);
                spawnTutorialSkeleton(cx + 8, cz + 12);
                spawnTutorialSpider(cx - 10, cz + 10);
                spawnTutorialSpider(cx + 10, cz - 10);
                break;
            case 9: // Combat Arena — a mixed gladiator roster
                spawnTutorialSkeleton(cx - 6, cz);
                spawnTutorialSkeleton(cx, cz - 6);
                spawnTutorialSkeleton(cx + 6, cz);
                spawnTutorialSkeleton(cx, cz + 6);
                spawnTutorialZombie(cx - 4, cz - 4);
                spawnTutorialZombie(cx + 4, cz - 4);
                spawnTutorialZombie(cx - 4, cz + 4);
                spawnTutorialZombie(cx + 4, cz + 4);
                spawnTutorialSpider(cx, cz - 8);
                spawnTutorialSpider(cx, cz + 8);
                break;
            default:
                break;
        }
    }

    /** Surface Y (one above the topmost solid block) at a column, for standing mobs. */
    private int surfaceYAt(float x, float z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        for (int y = 127; y >= 0; y--) {
            if (world.getVoxel(ix, y, iz) > 0) return y + 1;
        }
        return 64;
    }

    private void spawnTutorialSkeleton(float x, float z) {
        com.voxel.entity.SkeletonEntity skeleton = new com.voxel.entity.SkeletonEntity(
            nextTutorialMobId++, new Vector3f(x + 0.5f, surfaceYAt(x, z), z + 0.5f), textureManager, player);
        skeleton.dimension = activeDimension;
        skeleton.setWorld(world);
        entityManager.addEntity(skeleton);
    }

    private void spawnTutorialSpider(float x, float z) {
        com.voxel.entity.SpiderEntity spider = new com.voxel.entity.SpiderEntity(
            nextTutorialMobId++, new Vector3f(x + 0.5f, surfaceYAt(x, z), z + 0.5f), textureManager, player);
        spider.dimension = activeDimension;
        spider.setWorld(world);
        entityManager.addEntity(spider);
    }

    private void spawnTutorialZombie(float x, float z) {
        com.voxel.entity.ZombieEntity zombie = new com.voxel.entity.ZombieEntity(
            nextTutorialMobId++, new Vector3f(x + 0.5f, surfaceYAt(x, z), z + 0.5f), textureManager, player);
        zombie.dimension = activeDimension;
        zombie.setWorld(world);
        entityManager.addEntity(zombie);
    }

    private void spawnTutorialPig(float x, float z) {
        com.voxel.entity.PigEntity pig = new com.voxel.entity.PigEntity(
            nextTutorialMobId++, new Vector3f(x + 0.5f, surfaceYAt(x, z), z + 0.5f), textureManager, player);
        pig.dimension = activeDimension;
        pig.setWorld(world);
        entityManager.addEntity(pig);
    }

    private void spawnTutorialCow(float x, float z) {
        com.voxel.entity.CowEntity cow = new com.voxel.entity.CowEntity(
            nextTutorialMobId++, new Vector3f(x + 0.5f, surfaceYAt(x, z), z + 0.5f), textureManager, player);
        cow.dimension = activeDimension;
        cow.setWorld(world);
        entityManager.addEntity(cow);
    }

    private void spawnTutorialChicken(float x, float z) {
        com.voxel.entity.ChickenEntity chicken = new com.voxel.entity.ChickenEntity(
            nextTutorialMobId++, new Vector3f(x + 0.5f, surfaceYAt(x, z), z + 0.5f), textureManager, player);
        chicken.dimension = activeDimension;
        chicken.setWorld(world);
        entityManager.addEntity(chicken);
    }

    private void spawnTutorialSheep(float x, float z) {
        com.voxel.entity.SheepEntity sheep = new com.voxel.entity.SheepEntity(
            nextTutorialMobId++, new Vector3f(x + 0.5f, surfaceYAt(x, z), z + 0.5f), textureManager, player);
        sheep.dimension = activeDimension;
        sheep.setWorld(world);
        entityManager.addEntity(sheep);
    }

    /**
     * One-shot deferred init that creates the Overworld dimension + chunk and
     * redstone / fluid managers + the player entity + initial enemy roster, then
     * kicks off the per-dimension spawn resolution. Runs on the LOGIC thread
     * from tick() while ctx.initializing is true, while the spawn-loading
     * overlay is already visible on the GL thread. Shader compile/link and GL
     * resource creation stay in init() — they're not moved.
     */
    private void initializeWorldPhase() {
        // A fresh world instance: reset the zone popup so a new or reloaded
        // tutorial world re-fires title cards.
        currentTutorialZone = -1;
        tutorialMinecartsSpawned = false;
        // Create only the saved dimension at startup; other dimensions lazy-load.
        // A save may have been last used outside the Overworld, and restoring its
        // coordinates into an Overworld manager makes the player appear stuck in
        // an unloaded/empty region.
        DimensionType initialDimension = ctx.loadPending ? ctx.loadDimension : DimensionType.OVERWORLD;
        int initialRenderDistance = initialDimension == DimensionType.OVERWORLD ? 8 : 6;
        dimensionManager = new DimensionManager(blockDataManager, ctx.worldSaveManager, biomeManager);
        dimensionManager.setWorldSeed(ctx.worldSeed);
        dimensionManager.setTutorialWorld(ctx.tutorialWorld);
        dimensionManager.createDimension(initialDimension, initialRenderDistance);
        // createDimension populates the dimension map but leaves the active
        // dimension pointer alone; make sure it targets the selected dimension
        // so the getters below can never resolve to null.
        dimensionManager.switchTo(initialDimension);
        activeDimension = initialDimension;
        ctx.activeDimension = initialDimension;
        // ── End Portal wiring: procedural placement of the single Stronghold on
        //    the Mojang-style ring around origin. The same world seed always
        //    yields the same stronghold chunk so save loads remain
        //    deterministic. The fallback path uses the loaded X/Z so save
        //    restores still drop the player near a portal they remember.
        int strongholdBaseY = 32;
        int strongholdCX = 0;
        int strongholdCZ = 0;
        com.voxel.world.StrongholdPlacement.Resolution resolved =
                com.voxel.world.StrongholdPlacement.resolve(ctx.worldSeed);
        strongholdCX = resolved.chunkX;
        strongholdCZ = resolved.chunkZ;
        strongholdBaseY = resolved.baseY;
        if (ctx.loadPending) {
            // Save-loaded worlds: clamp the procedural position so the
            // stronghold isn't 1500 chunks away from the player's saved
            // X/Z — that would make the portal effectively unreachable for
            // a returning player. Use the saved chunk column instead.
            int savedCX = (int) Math.floor(ctx.loadX / 16.0);
            int savedCZ = (int) Math.floor(ctx.loadZ / 16.0);
            // Average the two: keeps seed determinism but brings the
            // stronghold within walking distance.
            strongholdCX = (strongholdCX + savedCX + 4) / 2;
            strongholdCZ = (strongholdCZ + savedCZ + 4) / 2;
            strongholdBaseY = Math.max(8, (int) Math.floor(ctx.loadY) - 4);
        }
        com.voxel.world.StrongholdLocator.reset();
        com.voxel.world.StrongholdLocator.setStrongholdChunk(strongholdCX, strongholdCZ, strongholdBaseY);
        com.voxel.world.StrongholdLocator.seedFallback(
                strongholdCX * 16 + 16,
                strongholdCZ * 16 + 16);

        // Push the configured X/Z int bits into the Beta terrain precision tuning
        com.voxel.world.WorldGenerator activeGen = dimensionManager.getActiveGenerator();
        if (activeGen instanceof com.voxel.world.BetaWorldGenerator) {
            ((com.voxel.world.BetaWorldGenerator) activeGen).setWorldSize(ctx.worldSize);
        }

        world = dimensionManager.getWorld(initialDimension, initialRenderDistance);
        chunkManager = dimensionManager.getChunkManager(initialDimension, initialRenderDistance);
        if (world == null || chunkManager == null) {
            throw new IllegalStateException(
                initialDimension.name + " dimension failed to initialize (world=" + world
                + ", chunkManager=" + chunkManager
                + ", active=" + dimensionManager.getActiveDimension() + ")");
        }
        ctx.world = world;
        ctx.chunkManager = chunkManager;
        ctx.dimensionManager = dimensionManager;

        // The Tutorial World is a bundled handcrafted map that was copied into
        // the save dir and is streamed in from disk by ChunkManager — no runtime
        // builder is needed. Chunks outside the template fall back to the flat
        // TutorialWorldGenerator selected above via setTutorialWorld().

        redstoneManager = new RedstoneManager(world, chunkManager);
        ctx.redstoneManager = redstoneManager;
        redstoneManager.setContainerManagers(ctx.chestManager, ctx.furnaceManager);

        // Kinetic network (Create-style shafts/cogs/clutch/water wheel)
        com.voxel.world.KineticManager kineticManager = new com.voxel.world.KineticManager(world, chunkManager, redstoneManager);
        ctx.kineticManager = kineticManager;

        // Create-style machines (crank, windmill, belt, press, millstone, crusher,
        // drill, saw, deployer). Recreated per dimension like the other managers.
        ctx.machineManager = new com.voxel.game.CreateMachineManager(ctx, world, chunkManager, ctx.droppedItemManager);
        kineticManager.setMachineManager(ctx.machineManager);
        ctx.worldSaveManager.loadMachineData(activeDimension, ctx.machineManager);

        ctx.fluidManager = new com.voxel.world.FluidManager(world, chunkManager, blockDataManager, false);
        chunkManager.setFluidManager(ctx.fluidManager);

        player.setDimension(activeDimension);
        playerEntity = new com.voxel.entity.PlayerEntity(10_000, new Vector3f(player.getPosition()), textureManager);
        ctx.worldSaveManager.loadCommandBlockData(activeDimension, ctx.commandBlockManager);
        ctx.worldSaveManager.loadSurfaceCraftingData(activeDimension, ctx.surfaceCraftingManager);
        ctx.worldSaveManager.loadCraftingData(activeDimension, ctx.craftingTableManager);
        ctx.worldSaveManager.loadFurnaceData(activeDimension, ctx.furnaceManager);
        ctx.worldSaveManager.loadChestData(activeDimension, ctx.chestManager);
        playerEntity.dimension = activeDimension;
        entityManager.addEntity(playerEntity);

        // Hand the GPU world-upload to the render thread (uploadWorldToGpu() is
        // a GL-only call; ctx.uploadWorldToGpu is already wired to
        // (() -> { needsWorldUpload = true; }) in init()).
        needsWorldUpload = true;

        // Begin the existing per-dimension spawn resolution flow. Once the spawn
        // chunks finish generating + the surface is detected, the loading overlay
        // hides.
        ctx.spawnLoadingMessage = ctx.loadPending ? "Loading world..." : "Generating spawn chunks...";
        // A save load must move the bootstrap player to the saved column before
        // asking ChunkManager to stream terrain. Previously spawn resolution used
        // the saved X/Z while the manager still queued the default (0, 0) column,
        // so areSpawnChunksGenerated() waited forever for chunks that were never
        // requested. The exact saved state is applied again after the readiness
        // probe, but the bootstrap position must already be in the saved section.
        if (ctx.loadPending) {
            player.setPosition(ctx.loadX, ctx.loadY, ctx.loadZ);
            player.resetVelocity();
            ctx.beginSpawnResolution((int) Math.floor(ctx.loadX), (int) Math.floor(ctx.loadZ));
        } else {
            ctx.beginSpawnResolution(0, 0);
        }
        chunkManager.updateFixedPosition(player.getFixedX(), player.getFixedY(), player.getFixedZ(), yaw);

        ctx.initializing = false;
    }

    /**
     * Applies the saved player state (position, health, yaw/pitch, inventory,
     * world time) once spawn resolution has placed the player on real terrain.
     * Called from tick() when ctx.loadPending && !ctx.spawnLoading.
     */
    private void applyLoadedPlayerState() {
        if (!ctx.loadPending) return;
        ctx.loadPending = false;
        // Spawn resolution already snapped X/Z near the saved column; keep the
        // exact saved Y (which may differ from the surface probe).
        player.setPosition(ctx.loadX, ctx.loadY, ctx.loadZ);
        player.resetVelocity();
        yaw = ctx.loadYaw;
        pitch = ctx.loadPitch;
        playerYaw = yaw;
        ctx.yaw = yaw;
        ctx.pitch = pitch;
        ctx.worldTime = ctx.loadWorldTime;
        // Restore inventory (non-null entries only).
        if (ctx.loadInventory != null) {
            for (int i = 0; i < Math.min(ctx.loadInventory.length, PlayerInventory.INVENTORY_SIZE); i++) {
                com.voxel.game.ItemDefinitions.ItemStack stack = ctx.loadInventory[i];
                if (stack != null) {
                    playerInventory.setSlot(i, stack);
                } else if (i < PlayerInventory.INVENTORY_SIZE) {
                    playerInventory.clearSlot(i);
                }
            }
        }
        // Restore health (clamped to max).
        float maxHp = player.getMaxHealth();
        player.setHealth(Math.max(1, Math.min(maxHp, ctx.loadHealth)));
        // Restore spawn point so death respawns in the saved world.
        player.setSpawnPoint(new org.joml.Vector3f(player.getPosition()));
        hud.inventoryUiDirty = true;
        setStatus("Welcome back to \"" + ctx.saveName + "\"!");
    }

    public void setupUi() { hud.setupUi(); }

    public void tryLoadUiTexture() { hud.tryLoadUiTexture(); }

    public void tryLoadFontTexture() { hud.tryLoadFontTexture(); }

    public void buildInventoryUi(UILayer layer) {         hud.buildInventoryUi(layer); }

    public void logicLoop() {
        long lastTime = System.nanoTime();
        final long targetNanos = 16_666_666L;
        while (running) {
            long now = System.nanoTime();
            long elapsed = now - lastTime;

            if (elapsed >= targetNanos) {
                float dt = elapsed / 1_000_000_000f;
                lastTime = now;
                tick(dt);

                long workTime = System.nanoTime() - now;
                long sleepTime = (targetNanos - workTime) / 1_000_000L;
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException ignored) {
                    }
                }
            } else {
                Thread.yield();
            }
        }
    }

    /** Sync game state from GameContext (may have changed via commands/portals) */
    public void syncGameState() {
        if (gameMode != ctx.gameMode) { gameMode = ctx.gameMode; }
        if (combatMode != ctx.combatMode) { combatMode = ctx.combatMode; }
        if (cameraMode != ctx.cameraMode) { cameraMode = ctx.cameraMode; }
        if (commandMode != ctx.commandMode) { commandMode = ctx.commandMode; }
        if (inventoryOpen != ctx.inventoryOpen) {
            inventoryOpen = ctx.inventoryOpen;
        }
    }

    /**
     * Logic-thread minecart driver: consumes cart-spawn requests from the GL
     * thread, steps every cart (with the riding player's W/S as control), syncs
     * a riding player's position onto the cart, and auto-dismounts on death or
     * dimension change.
     */
    private void updateMinecarts(float dt) {
        if (ctx == null || entityManager == null || world == null) return;

        // Spawn carts requested by block interaction (GL thread → logic thread).
        // The queue is a synchronized list: hold its lock while draining.
        if (!ctx.minecartSpawnQueue.isEmpty()) {
            java.util.List<org.joml.Vector3f> queue = ctx.minecartSpawnQueue;
            int baseId = 70000 + entityManager.getEntityCount();
            synchronized (queue) {
                for (org.joml.Vector3f p : queue) {
                    MinecartEntity cart = new MinecartEntity(baseId++, p, ctx.textureManager);
                    cart.dimension = activeDimension;
                    entityManager.addEntity(cart);
                }
                queue.clear();
            }
        }

        Entity riding = ctx.ridingMinecart;
        if (riding != null && (!(riding instanceof MinecartEntity)
                || riding.dimension != activeDimension || player.isDead())) {
            dismountMinecart(riding);
            riding = null;
        }

        // Riding input: W accelerates forward along the rail, S reverses.
        float control = 0;
        if (riding instanceof MinecartEntity) {
            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) control += 1.0f;
            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) control -= 1.0f;
        }

        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            Entity e = entityManager.getEntity(i);
            if (e instanceof MinecartEntity && e.dimension == activeDimension) {
                ((MinecartEntity) e).updateCart(world, dt, control);
            }
        }

        // Re-read: the GL thread may have dismounted mid-tick (E key / right
        // click), so don't snap the player to a cart they just left.
        riding = ctx.ridingMinecart;
        if (riding instanceof MinecartEntity) {
            MinecartEntity cart = (MinecartEntity) riding;
            player.ridingCart = true;
            player.cartX = cart.getPosX();
            player.cartY = cart.getPosY() + 0.55f;
            player.cartZ = cart.getPosZ();
        } else {
            player.ridingCart = false;
        }
    }

    /** Places the player on the ground beside the given cart and stops riding. */
    private void dismountMinecart(Entity cart) {
        ctx.ridingMinecart = null;
        player.ridingCart = false;
        float cx = cart != null ? cart.getPosX() : player.getPosition().x;
        float cy = cart != null ? cart.getPosY() : player.getPosition().y;
        float cz = cart != null ? cart.getPosZ() : player.getPosition().z;
        int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] off : offs) {
            int bx = (int) Math.floor(cx) + off[0];
            int bz = (int) Math.floor(cz) + off[1];
            int gy = (int) Math.floor(cy);
            while (gy > 0 && world.getVoxel(bx, gy - 1, bz) == 0) gy--;
            if (gy <= 0) continue;
            if (world.getVoxel(bx, gy, bz) == 0 && world.getVoxel(bx, gy + 1, bz) == 0) {
                player.teleport(bx + 0.5, gy, bz + 0.5);
                return;
            }
        }
        player.teleport(cx + 0.5, cy + 1.0, cz + 0.5);
    }

    /**
     * Handles UP/DOWN/ENTER/BACKSPACE for the full main-menu state machine
     * (title screen, new-world wizard, save selection). Called from tick()
     * while ctx.menuScreen != IN_GAME and the world has not been created yet.
     */
    /** Requests a menu row activation from the render-thread mouse callback. */
    public void requestMenuSelection(int index) {
        if (ctx == null || ctx.menuScreen == GameContext.MenuScreen.IN_GAME) return;
        ctx.menuSelection = Math.max(0, index);
        switch (ctx.menuScreen) {
            case LOAD_SAVE:
                // Save rows select; the explicit LOAD button commits.
                if (index < Math.min(ctx.saveList.size(), 5)) {
                    ctx.saveListSelection = index;
                    return;
                }
                if (index == Math.min(ctx.saveList.size(), 5)) {
                    ctx.menuConfirmRequested = true;
                    return;
                }
                if (index == Math.min(ctx.saveList.size(), 5) + 1) {
                    requestDeleteSelectedSave();
                    return;
                }
                requestMenuBack();
                return;
            case NEW_WORLD_SIZE:
                if (index < com.voxel.world.WorldSize.values().length) {
                    ctx.worldSizeSelection = index;
                    ctx.menuConfirmRequested = true;
                } else requestMenuBack();
                return;
            case NEW_WORLD_MODE:
                if (index == 0 || index == 1) {
                    ctx.gameMode = index == 1 ? GameContext.GameMode.CREATIVE : GameContext.GameMode.SURVIVAL;
                } else if (index == 2) ctx.menuConfirmRequested = true;
                else requestMenuBack();
                return;
            case MAIN:
                ctx.menuConfirmRequested = true;
                return;
            case OPTIONS:
                if (index == 0) {
                    ctx.uiTheme = ctx.uiTheme == GameContext.UiTheme.DARK
                        ? GameContext.UiTheme.LIGHT : GameContext.UiTheme.DARK;
                } else requestMenuBack();
                return;
            case NEW_WORLD_NAME:
            case NEW_WORLD_SEED:
                if (index == 0) ctx.menuConfirmRequested = true;
                else requestMenuBack();
                return;
            default: return;
        }
    }

    public void requestMenuBack() {
        if (ctx != null) ctx.menuBackRequested = true;
    }

    public void requestDeleteSelectedSave() {
        if (ctx == null || ctx.saveList.isEmpty()) return;
        String doomed = ctx.saveList.get(Math.max(0, Math.min(ctx.saveListSelection, ctx.saveList.size() - 1)));
        com.voxel.world.WorldSaveManager.deleteSave(doomed);
        ctx.saveList = com.voxel.world.WorldSaveManager.listSaves();
        ctx.saveListSelection = Math.min(ctx.saveListSelection, Math.max(0, ctx.saveList.size() - 1));
        setStatus("Deleted save \"" + doomed + "\"");
    }

    public void requestPauseSelection(int index) {
        if (ctx == null || !ctx.pauseMenuOpen) return;
        ctx.pauseSelection = Math.max(0, Math.min(2, index));
        ctx.pauseConfirmRequested = true;
    }

    private void handlePauseMenuInput() {
        boolean confirm = ctx.pauseConfirmRequested;
        ctx.pauseConfirmRequested = false;
        if (menuKeyPressed(GLFW_KEY_UP)) ctx.pauseSelection = (ctx.pauseSelection + 2) % 3;
        if (menuKeyPressed(GLFW_KEY_DOWN)) ctx.pauseSelection = (ctx.pauseSelection + 1) % 3;
        if (menuKeyPressed(GLFW_KEY_ESCAPE)) {
            ctx.pauseMenuOpen = false;
            updateCursorMode();
            return;
        }
        if (!confirm && !menuKeyPressed(GLFW_KEY_ENTER)) return;
        switch (ctx.pauseSelection) {
            case 0:
                ctx.pauseMenuOpen = false;
                updateCursorMode();
                break;
            case 1:
                ctx.uiTheme = ctx.uiTheme == GameContext.UiTheme.DARK
                    ? GameContext.UiTheme.LIGHT : GameContext.UiTheme.DARK;
                ctx.pauseConfirmRequested = false;
                break;
            case 2:
                // The normal shutdown path persists level.dat and pending chunks.
                ctx.pauseMenuOpen = false;
                glfwSetWindowShouldClose(window, true);
                break;
            default: break;
        }
    }

    private void handleMainMenuInput() {
        com.voxel.world.WorldSize[] sizes = com.voxel.world.WorldSize.values();
        GameContext.MenuScreen screen = ctx.menuScreen;
        boolean confirmRequested = ctx.menuConfirmRequested;
        ctx.menuConfirmRequested = false;
        if (ctx.menuBackRequested) {
            ctx.menuBackRequested = false;
            switch (screen) {
                case MAIN: break;
                case NEW_WORLD_NAME:
                case NEW_WORLD_SEED:
                case LOAD_SAVE: ctx.menuScreen = GameContext.MenuScreen.MAIN; ctx.menuSelection = 0; break;
                case NEW_WORLD_SIZE: ctx.menuScreen = GameContext.MenuScreen.NEW_WORLD_SEED; ctx.menuTextActive = true; break;
                case NEW_WORLD_MODE: ctx.menuScreen = GameContext.MenuScreen.NEW_WORLD_SIZE; break;
                case OPTIONS: ctx.menuScreen = GameContext.MenuScreen.MAIN; ctx.menuSelection = 3; break;
                default: break;
            }
            return;
        }
        if (screen != GameContext.MenuScreen.MAIN && menuKeyPressed(GLFW_KEY_ESCAPE)) {
            requestMenuBack();
            return;
        }

        switch (screen) {
            case MAIN: {
                // Title screen: world creation, tutorial, saves, and options.
                int optionCount = 4;
                if (menuKeyPressed(GLFW_KEY_UP)) {
                    ctx.menuSelection--;
                    if (ctx.menuSelection < 0) ctx.menuSelection = optionCount - 1;
                }
                if (menuKeyPressed(GLFW_KEY_DOWN)) {
                    ctx.menuSelection++;
                    if (ctx.menuSelection >= optionCount) ctx.menuSelection = 0;
                }
                if (menuKeyPressed(GLFW_KEY_ENTER) || confirmRequested) {
                    switch (ctx.menuSelection) {
                        case 0: // New World
                            ctx.menuScreen = GameContext.MenuScreen.NEW_WORLD_NAME;
                            ctx.menuTextActive = true;
                            ctx.menuTextInput.setLength(0);
                            ctx.menuTextInput.append("New World");
                            break;
                        case 1: // Tutorial World (load the bundled handcrafted world)
                            ctx.tutorialWorld = true;
                            ctx.saveName = "tutorial";
                            // The handcrafted world ships in git-tracked resources
                            // (not the git-ignored saves/ dir). Copy it into the
                            // save folder, then load it from disk like any save.
                            copyTutorialTemplate();
                            loadSaveIntoContext("tutorial");
                            ctx.borderManager.setBorderFromBits(ctx.worldSize.intBits());
                            setStatus("Entering Tutorial World — a hand-built Create showcase!");
                            break;
                        case 2: // Load Save
                            ctx.saveList = com.voxel.world.WorldSaveManager.listSaves();
                            ctx.saveListSelection = 0;
                            ctx.menuScreen = GameContext.MenuScreen.LOAD_SAVE;
                            break;
                        case 3: // Options
                            ctx.menuScreen = GameContext.MenuScreen.OPTIONS;
                            ctx.menuSelection = 0;
                            break;
                    }
                }
                break;
            }

            case NEW_WORLD_NAME: {
                if (menuKeyPressed(GLFW_KEY_ENTER) || confirmRequested) {
                    String name = ctx.menuTextInput.toString().trim();
                    if (name.isEmpty()) name = "New World";
                    // Sanitize: keep folder-safe characters only
                    name = name.replaceAll("[^a-zA-Z0-9 _-]", "").trim();
                    if (name.isEmpty()) name = "New World";
                    ctx.saveName = name;
                    ctx.menuScreen = GameContext.MenuScreen.NEW_WORLD_SEED;
                    ctx.menuTextActive = true;
                    ctx.menuTextInput.setLength(0);
                }
                break;
            }

            case NEW_WORLD_SEED: {
                if (menuKeyPressed(GLFW_KEY_ENTER) || confirmRequested) {
                    String seedText = ctx.menuTextInput.toString().trim();
                    if (seedText.isEmpty()) {
                        ctx.randomSeed = true;
                        ctx.menuSeed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
                    } else {
                        ctx.randomSeed = false;
                        try {
                            ctx.menuSeed = Long.parseLong(seedText);
                        } catch (NumberFormatException e) {
                            // Support string seeds via hashCode (Minecraft-style)
                            ctx.menuSeed = seedText.hashCode();
                        }
                    }
                    ctx.worldSizeSelection = 2; // MEDIUM default
                    ctx.menuScreen = GameContext.MenuScreen.NEW_WORLD_SIZE;
                    ctx.menuTextActive = false;
                }
                break;
            }

            case NEW_WORLD_SIZE: {
                if (menuKeyPressed(GLFW_KEY_UP)) {
                    ctx.worldSizeSelection--;
                    if (ctx.worldSizeSelection < 0) ctx.worldSizeSelection = sizes.length - 1;
                }
                if (menuKeyPressed(GLFW_KEY_DOWN)) {
                    ctx.worldSizeSelection++;
                    if (ctx.worldSizeSelection >= sizes.length) ctx.worldSizeSelection = 0;
                }
                if (menuKeyPressed(GLFW_KEY_ENTER) || confirmRequested) {
                    ctx.worldSize = sizes[ctx.worldSizeSelection];
                    ctx.borderManager.setBorderFromBits(ctx.worldSize.intBits());
                    ctx.gameMode = GameContext.GameMode.SURVIVAL;
                    ctx.menuScreen = GameContext.MenuScreen.NEW_WORLD_MODE;
                }
                break;
            }

            case NEW_WORLD_MODE: {
                if (menuKeyPressed(GLFW_KEY_UP) || menuKeyPressed(GLFW_KEY_DOWN)) {
                    ctx.gameMode = ctx.gameMode == GameContext.GameMode.SURVIVAL
                        ? GameContext.GameMode.CREATIVE : GameContext.GameMode.SURVIVAL;
                }
                if (menuKeyPressed(GLFW_KEY_ENTER) || confirmRequested) {
                    // Commit the new world: create save manager + seed, then let
                    // initializeWorldPhase() run on the next tick.
                    ctx.worldSeed = ctx.randomSeed ? ctx.menuSeed : ctx.menuSeed;
                    ctx.worldSaveManager = com.voxel.world.WorldSaveManager.forSave(ctx.saveName);
                    ctx.loadPending = false;
                    ctx.worldSizeConfirmed = true;
                    ctx.worldSizeMenu = false;
                    ctx.menuScreen = GameContext.MenuScreen.IN_GAME;
                    ctx.menuTextActive = false;
                    ctx.spawnLoadingMessage = "Generating spawn chunks...";
                    setStatus("Created world \"" + ctx.saveName + "\" (seed " + ctx.worldSeed + ")");
                }
                break;
            }

            case OPTIONS: {
                if (menuKeyPressed(GLFW_KEY_UP) || menuKeyPressed(GLFW_KEY_DOWN)) {
                    ctx.menuSelection = (ctx.menuSelection + 1) % 2;
                }
                if (menuKeyPressed(GLFW_KEY_ENTER) || confirmRequested) {
                    if (ctx.menuSelection == 0) {
                        ctx.uiTheme = ctx.uiTheme == GameContext.UiTheme.DARK
                            ? GameContext.UiTheme.LIGHT : GameContext.UiTheme.DARK;
                    } else {
                        requestMenuBack();
                    }
                }
                break;
            }

            case LOAD_SAVE: {
                int n = ctx.saveList.size();
                if (menuKeyPressed(GLFW_KEY_UP) && n > 0) {
                    ctx.saveListSelection--;
                    if (ctx.saveListSelection < 0) ctx.saveListSelection = n - 1;
                }
                if (menuKeyPressed(GLFW_KEY_DOWN) && n > 0) {
                    ctx.saveListSelection++;
                    if (ctx.saveListSelection >= n) ctx.saveListSelection = 0;
                }
                if (menuKeyPressed(GLFW_KEY_BACKSPACE) && n > 0) {
                    String doomed = ctx.saveList.get(ctx.saveListSelection);
                    com.voxel.world.WorldSaveManager.deleteSave(doomed);
                    ctx.saveList = com.voxel.world.WorldSaveManager.listSaves();
                    if (ctx.saveListSelection >= ctx.saveList.size()) {
                        ctx.saveListSelection = Math.max(0, ctx.saveList.size() - 1);
                    }
                    setStatus("Deleted save \"" + doomed + "\"");
                }
                if (menuKeyPressed(GLFW_KEY_ENTER) && n > 0) {
                    String name = ctx.saveList.get(ctx.saveListSelection);
                    loadSaveIntoContext(name);
                }
                if (menuKeyPressed(GLFW_KEY_ESCAPE)) {
                    ctx.menuScreen = GameContext.MenuScreen.MAIN;
                    ctx.menuSelection = 2;
                }
                break;
            }

            default:
                break;
        }
    }

    /** Copies the bundled handcrafted Tutorial World template into the save dir. */
    private void copyTutorialTemplate() {
        java.io.File src = new java.io.File("src/main/resources/tutorial_world");
        java.io.File dst = new java.io.File(com.voxel.world.WorldSaveManager.SAVES_DIR, "tutorial");
        com.voxel.world.WorldSaveManager.deleteSave("tutorial");
        if (!src.isDirectory()) {
            System.err.println("Tutorial template missing: " + src.getAbsolutePath());
            return;
        }
        copyDir(src, dst);
    }

    private static void copyDir(java.io.File src, java.io.File dst) {
        if (src.isDirectory()) {
            dst.mkdirs();
            java.io.File[] children = src.listFiles();
            if (children == null) return;
            for (java.io.File c : children) copyDir(c, new java.io.File(dst, c.getName()));
        } else {
            try {
                java.nio.file.Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                System.err.println("Failed to copy " + src + ": " + e.getMessage());
            }
        }
    }

    /** Loads level.dat metadata + player state from a save into ctx and commits. */
    private void loadSaveIntoContext(String name) {
        ctx.saveName = name;
        ctx.worldSaveManager = com.voxel.world.WorldSaveManager.forSave(name);
        com.voxel.world.WorldSaveManager.PlayerState ps = ctx.worldSaveManager.loadLevelData(ctx);
        if (ps != null) {
            ctx.loadPending = true;
            ctx.loadX = ps.x;
            ctx.loadY = ps.y;
            ctx.loadZ = ps.z;
            ctx.loadYaw = ps.yaw;
            ctx.loadPitch = ps.pitch;
            ctx.loadHealth = ps.health;
            ctx.loadWorldTime = ctx.worldTime;
            ctx.loadInventory = ps.inventory;
        }
        ctx.worldSizeConfirmed = true;
        ctx.worldSizeMenu = false;
        ctx.menuScreen = GameContext.MenuScreen.IN_GAME;
        ctx.menuTextActive = false;
        ctx.spawnLoadingMessage = "Loading world...";
        setStatus("Loading save \"" + name + "\"");
    }

    /** Builds the menu text for the loading-screen overlay. */
    private String buildMenuMessage() {
        GameContext.MenuScreen screen = ctx.menuScreen;
        if (screen == GameContext.MenuScreen.IN_GAME) {
            return ctx.spawnLoadingMessage;
        }
        StringBuilder sb = new StringBuilder();
        if (screen == GameContext.MenuScreen.MAIN) {
            sb.append("VOXEL ENGINE\n\n");
            String[] options = { "New World", "Tutorial World", "Load Save", "Theme: " +
                (ctx.uiTheme == GameContext.UiTheme.DARK ? "Dark" : "Light") };
            for (int i = 0; i < options.length; i++) {
                sb.append(ctx.menuSelection == i ? "> " : "  ").append(options[i]).append("\n");
            }
            sb.append("\nUP/DOWN select, ENTER confirm, M mouse");
        } else if (screen == GameContext.MenuScreen.NEW_WORLD_NAME) {
            sb.append("NEW WORLD — NAME\n\n").append(ctx.menuTextInput).append("_\n\nENTER to continue");
        } else if (screen == GameContext.MenuScreen.NEW_WORLD_SEED) {
            sb.append("NEW WORLD — SEED (blank = random)\n\n").append(ctx.menuTextInput).append("_\n\nENTER to continue");
        } else if (screen == GameContext.MenuScreen.NEW_WORLD_SIZE) {
            com.voxel.world.WorldSize[] sizes = com.voxel.world.WorldSize.values();
            sb.append("NEW WORLD — SIZE\n\n");
            for (int i = 0; i < sizes.length; i++) {
                String marker = (i == ctx.worldSizeSelection) ? "> " : "  ";
                sb.append(marker).append(sizes[i].displayName())
                  .append(" (").append(sizes[i].intBits()).append("-bit, border ")
                  .append(sizes[i].borderRadius() / 1000).append("K)\n");
            }
            sb.append("\nUP/DOWN to change, ENTER to confirm");
        } else if (screen == GameContext.MenuScreen.NEW_WORLD_MODE) {
            sb.append("NEW WORLD — MODE\n\n");
            sb.append(ctx.gameMode == GameContext.GameMode.SURVIVAL ? "> " : "  ").append("Survival\n");
            sb.append(ctx.gameMode == GameContext.GameMode.CREATIVE ? "> " : "  ").append("Creative\n");
            sb.append("\nUP/DOWN to change, ENTER to create");
        } else if (screen == GameContext.MenuScreen.LOAD_SAVE) {
            sb.append("LOAD SAVE\n\n");
            if (ctx.saveList.isEmpty()) {
                sb.append("  (no saves found)\n\nESC to go back");
            } else {
                for (int i = 0; i < ctx.saveList.size(); i++) {
                    sb.append(ctx.saveListSelection == i ? "> " : "  ").append(ctx.saveList.get(i)).append("\n");
                }
                sb.append("\nENTER to load, BACKSPACE to delete, ESC back");
            }
        }
        return sb.toString();
    }

    // Simple key-press detector for menus (detects edge: was not pressed last frame, is now)
    private final java.util.BitSet menuKeysDown = new java.util.BitSet(512);
    private boolean menuKeyPressed(int key) {
        boolean now = glfwGetKey(window, key) == GLFW_PRESS;
        boolean wasDown = menuKeysDown.get(key);
        menuKeysDown.set(key, now);
        return now && !wasDown;
    }

    /** Edge-detecting key press for map controls (separate from menu). */
    private final java.util.BitSet mapKeysDown = new java.util.BitSet(512);
    private boolean keyJustPressed(int key) {
        boolean now = glfwGetKey(window, key) == GLFW_PRESS;
        boolean wasDown = mapKeysDown.get(key);
        mapKeysDown.set(key, now);
        return now && !wasDown;
    }

    /** Map zoom bounds: min 0.25x (closest) / max 4x (widest view).
     *  Capped so the visible area stays inside the chunk-stream radius
     *  (render distance ~8 chunks), avoiding a sea of unloaded void. */
    private static float clampMapZoom(float z) {
        return Math.max(0.25f, Math.min(4.0f, z));
    }

    /** Camera height for the top-down map view at a given zoom level. */
    private static float mapCameraHeight(float zoom) {
        // zoom 0.25 → ~50 blocks up (close terrain), zoom 1 → ~140 (roughly
        // matches the ~128-block chunk stream radius), zoom 4 → ~500 (wide
        // overview that still keeps most of the screen on loaded terrain).
        return 120f * zoom + 20f;
    }

    // ── Per-tick budget: limits heavy ops to keep 20 TPS consistent ──
    private static final int MAX_ENTITY_AI_PER_TICK = 48;
    private static final int STALE_CLEANUP_TICK_INTERVAL = 20; // ~1s at 20 TPS
    private int aiUpdateOffset = 0;
    private int staleCleanupCounter = 0;
    private int autosaveCounter = 0;

    public void tick(float dt) {
        if (!running) return;

        // Deferred world + initial-entity init runs once on the logic thread while
        // Main menu: runs BEFORE the world is created, so the chosen seed / save
        // slot can be baked into dimension generation. While any menu screen is
        // active the world does not exist yet — only menu input + rendering run.
        if (ctx.menuScreen != GameContext.MenuScreen.IN_GAME) {
            handleMainMenuInput();
            ctx.spawnLoadingMessage = buildMenuMessage();
            return;
        }

        // Pause owns the logic thread after the world is ready: rendering and UI
        // continue, but movement, AI, fluids, redstone, and chunk streaming stop.
        if (ctx.pauseMenuOpen) {
            handlePauseMenuInput();
            lastLogicTickNanos = System.nanoTime();
            return;
        }

        // Deferred world + initial-entity init runs once on the logic thread while
        // the spawn-loading overlay is already visible on the GL thread. By the
        // time this returns, world / chunkManager / redstoneManager /
        // fluidManager / playerEntity are wired up and beginSpawnResolution() has
        // been invoked, so the rest of tick() can run normally.
        if (ctx.initializing) {
            initializeWorldPhase();
        }

        syncGameState();

        // Dimension switches replace the active world/chunk manager on the logic thread.
        // Pick up that reference before checking spawn-generation readiness.
        if (chunkManager != ctx.chunkManager) {
            chunkManager = ctx.chunkManager;
            com.voxel.entity.CreeperEntity.setChunkManager(chunkManager);
            world = ctx.world;
            activeDimension = ctx.activeDimension;
            redstoneManager = ctx.redstoneManager;
            player.setDimension(activeDimension);
        }

        if (cameraMode == CameraMode.FIRST_PERSON) {
            playerYaw = yaw;
        }

        int pcx = FixedPoint.blockX(player.getFixedX()) >> 4;
        int pcy = FixedPoint.blockX(player.getFixedY()) >> 4;
        int pcz = FixedPoint.blockX(player.getFixedZ()) >> 4;
        boolean chunksReady = chunkManager.isChunkLoaded(pcx, pcz);
        boolean teleportReady = !ctx.teleportLoading
                || chunkManager.isPlayerSectionGenerated(pcx, pcy, pcz);
        if (ctx.teleportLoading && teleportReady) {
            ctx.finishTeleportTerrainWait();
        }

        // The player is NEVER frozen waiting for terrain: the single section
        // they are standing in may still be mid-generation, so neighboring
        // chunks stream in around them without freezing gameplay. (Fall-through
        // is prevented by fast generation + the sync 3×3×3 load in
        // ensure3x3x3Loaded.)

        // Resolve the final surface only after ChunkManager reports that all immediate
        // spawn columns have finished generation/loading.
        if (ctx.spawnLoading) {
            ctx.resolveSpawnAfterChunksGenerated();
        }

        // A loaded save: once spawn resolution places the player on terrain,
        // restore the exact saved position / health / inventory / time.
        if (ctx.loadPending && !ctx.spawnLoading) {
            applyLoadedPlayerState();
        }

        if (chunksReady && !ctx.spawnLoading && !ctx.teleportLoading) {
            // Compatibility call; resolution is already complete by this point.
            ctx.adjustSpawnYAfterChunkLoad();

            // Tutorial World: show a title card when the player enters a new
            // showcase zone. The world itself is the bundled handcrafted map
            // loaded from disk; nothing is built at runtime here.
            if (ctx.tutorialWorld) {
                int zoneIdx = com.voxel.world.TutorialWorldAuthor.zoneAt(player.getPosition().x, player.getPosition().z);
                if (zoneIdx != currentTutorialZone) {
                    currentTutorialZone = zoneIdx;
                    if (zoneIdx >= 0) {
                        com.voxel.world.TutorialWorldAuthor.Zone zone = com.voxel.world.TutorialWorldAuthor.zones()[zoneIdx];
                        showTutorialPopup(zone.name, zone.subtitle);
                    }
                }
                // Rideable minecarts: spawn live entities (not static blocks) once
                // the player reaches the coaster, when its chunks are loaded so
                // each cart snaps straight onto its rail.
                if (!tutorialMinecartsSpawned && currentTutorialZone == com.voxel.world.TutorialWorldAuthor.MINECART_ZONE) {
                    for (float[] s : com.voxel.world.TutorialWorldAuthor.minecartSpawns()) {
                        ctx.minecartSpawnQueue.add(new Vector3f(s[0], s[1], s[2]));
                    }
                    tutorialMinecartsSpawned = true;
                }
                // Populate showcase zones with themed mobs the first time the
                // player enters them (the combat arena and quarry should not sit
                // empty — they exist to be fought in).
                spawnTutorialZoneMobs(currentTutorialZone);
            }

            handleInput(dt);

            // Spawn initial nether mobs on first nether entry
            if (activeDimension == DimensionType.NETHER && entityManager.getEntityCount(DimensionType.NETHER) < 2) {
                spawnNetherMobs(player);
            }

            // Parachute deploy: auto-activate when falling fast in the Aether
            if (activeDimension == DimensionType.AETHER && !player.isOnGround() && !player.isParachuteDeployed()
                    && player.getVelocity().y < -8.0f && player.getPosition().y > 0) {
                // Find a parachute in the player's inventory
                for (int i = 0; i < playerInventory.getInventorySize(); i++) {
                    ItemStack stack = playerInventory.getSlot(i);
                    if (stack != null && (stack.itemId.equals("cold_parachute") || stack.itemId.equals("golden_parachute"))) {
                        // Initialize durability on first deploy (golden = 20 uses)
                        if (stack.durability == 0 && stack.itemId.equals("golden_parachute")) {
                            stack.durability = 20;
                        }
                        player.deployParachute(stack.itemId, i);
                        setStatus("Parachute deployed!");
                        break;
                    }
                }
            }

            updateMinecarts(dt);

            player.update(dt, world, blockDataManager);
            // Hard world border clamp (keeps player within Far Lands boundary)
            ctx.borderManager.clamp(player);

            // ── End Portal & End Gateway teleport detection ──
            // Player must walk into a real end_portal block (Overworld) or an
            // end_gateway block (End) to trigger the dimension switch.
            // Gate the check behind non-spawn/non-teleport loading so the
            // load-on-entry path doesn't fire a phantom transition.
            if (ctx.dimensionManager != null && !ctx.spawnLoading && !ctx.teleportLoading) {
                if (ctx.endPortalCooldownTicks > 0) ctx.endPortalCooldownTicks--;
                int feetY = com.voxel.utils.FixedPoint.blockX(player.getFixedY());
                int feetX = com.voxel.utils.FixedPoint.blockX(player.getFixedX());
                int feetZ = com.voxel.utils.FixedPoint.blockX(player.getFixedZ());
                int feetBlock = world.getVoxel(feetX, feetY, feetZ);
                if (feetBlock != 0) {
                    String feetName = blockDataManager.getName(feetBlock);
                    if (feetName != null) {
                        if (feetName.contains("end_gateway")
                                && ctx.activeDimension == com.voxel.world.DimensionType.END) {
                            com.voxel.world.EndPortalLogic.teleportBackToOverworld(
                                    player, ctx, ctx.dimensionManager);
                            setStatus("Returned to the Overworld");
                            needsWorldUpload = true;
                        } else if (feetName.contains("end_portal")
                                && !feetName.contains("frame")
                                && ctx.activeDimension != com.voxel.world.DimensionType.END) {
                            boolean teleported =
                                com.voxel.world.EndPortalLogic.tickPortalEntryCheck(
                                    player, world, blockDataManager, ctx, ctx.dimensionManager);
                            if (teleported) {
                                // Refresh world + chunkManager refs after the
                                // dimension switch so render/UI see the End.
                                activeDimension = ctx.activeDimension;
                                world = dimensionManager.getActiveWorld();
                                chunkManager = dimensionManager.getActiveChunkManager();
                                ctx.world = world;
                                ctx.chunkManager = chunkManager;
                                player.setDimension(activeDimension);
                                if (playerEntity != null) playerEntity.dimension = activeDimension;
                                setStatus("Welcome to The End");
                                needsWorldUpload = true;
                            }
                        }
                    }
                }
            }

            // ── Ender Dragon spawn-on-End-enter ──
            // The first time the player lands in the End, raise the boss above
            // the obsidian pillar. After that, poll the entity each tick to
            // drop the dragon_egg when it expires.
            if (activeDimension == com.voxel.world.DimensionType.END
                    && !ctx.enderDragonSpawned
                    && entityManager != null && textureManager != null) {
                ctx.enderDragonSpawned = true;
                com.voxel.entity.EnderDragonEntity dragon =
                        new com.voxel.entity.EnderDragonEntity(
                                80_000 + entityManager.getEntityCount(),
                                new Vector3f(100.0f, 120.0f, 0.0f),
                                textureManager,
                                entityManager);
                dragon.setWorld(world);
                entityManager.addEntity(dragon);
                ctx.enderDragonEntityId = dragon.id;
                setStatus("The Ender Dragon rises...");

                // ── End crystals: spawn one above each end_crystal_base ──
                // Without these the dragon has no regen pool, which makes the
                // fight trivially short and removes the Mojang-flavoured
                // "destroy crystals, then fight" loop.
                float[][] crystalOffsets = {
                        {-4.0f, 0.0f}, {4.0f, 0.0f}, {0.0f, 4.0f}};
                for (float[] off : crystalOffsets) {
                    com.voxel.entity.EndCrystalEntity crystal =
                            new com.voxel.entity.EndCrystalEntity(
                                    81_000 + entityManager.getEntityCount(),
                                    new Vector3f(102.5f, 49.0f, 2.5f + off[1]),
                                    textureManager);
                    crystal.setDragon(dragon);
                    crystal.world = world;
                    entityManager.addEntity(crystal);
                }
                dragon.dimension = com.voxel.world.DimensionType.END;
                entityManager.addEntity(dragon);
                ctx.enderDragonEntityId = dragon.id;
                setStatus("The Ender Dragon rises...");
            }
            if (ctx.enderDragonEntityId >= 0 && entityManager != null) {
                com.voxel.entity.Entity dragonEnt =
                        entityManager.getEntity(ctx.enderDragonEntityId);
                if (dragonEnt instanceof com.voxel.entity.EnderDragonEntity) {
                    com.voxel.entity.EnderDragonEntity dragon =
                            (com.voxel.entity.EnderDragonEntity) dragonEnt;
                    if (dragon.isDead() && !dragon.markedDropped()) {
                        dragon.dropLoot(world, blockDataManager);
                        dragon.markDropped();
                        setStatus("The Ender Dragon has been slain");
                        needsWorldUpload = true;
                    }
                }
            }

            // ── Mob spawner: emit blazes from any active spawner. ──
            com.voxel.world.MobSpawnerLogic.tick(world, blockDataManager,
                    entityManager, player);

            // ── Beacon buffs: scan all known beacons, find the best one near the
            // player, and apply its tier buffs (jump, speed, regen at tier 4).
            // Each tick we recompute so removing a pyramid block immediately
            // drops the buff.
            {
                com.voxel.world.BeaconLogic.scan(world, blockDataManager);
                float playerX = com.voxel.utils.FixedPoint.toFloat(player.getFixedX());
                float playerY = com.voxel.utils.FixedPoint.toFloat(player.getFixedY());
                float playerZ = com.voxel.utils.FixedPoint.toFloat(player.getFixedZ());
                int bestLevel = 0;
                for (Long key : com.voxel.world.BeaconLogic.getActiveBeacons()) {
                    int[] xyz = com.voxel.world.BeaconLogic.decodeKey(key);
                    float dx = playerX - xyz[0];
                    float dy = playerY - xyz[1];
                    float dz = playerZ - xyz[2];
                    float distSq = dx * dx + dy * dy + dz * dz;
                    float radius = com.voxel.world.BeaconLogic.buffRadius(
                            com.voxel.world.BeaconLogic.pyramidLevel(
                                    world, blockDataManager, xyz[0], xyz[1], xyz[2]));
                    if (distSq <= radius * radius) {
                        int level = com.voxel.world.BeaconLogic.pyramidLevel(
                                world, blockDataManager, xyz[0], xyz[1], xyz[2]);
                        if (level > bestLevel) bestLevel = level;
                    }
                }
                // Apply the buff fields. Mojang-style curves: tier 1 → +30% speed,
                // tier 4 → +90% speed + jump + regen. We approximate with a flat
                // 0.3 * level multiplier.
                float jumpBuff = bestLevel >= 1 ? 0.2f * bestLevel : 0.0f;
                float speedBuff = bestLevel >= 1 ? 0.3f * bestLevel : 0.0f;
                boolean regenBuff = bestLevel >= 4;
                player.setBeaconBuffs(jumpBuff, speedBuff, regenBuff);
            }

            // ── Wither death → drop nether star ──
            // Wither entities are tracked by type: scan the entity list each
            // tick for a dead Wither, drop a Nether Star, and forget about it.
            if (entityManager != null) {
                java.util.List<com.voxel.entity.Entity> liveEntities = entityManager.getEntitiesSnapshot();
                for (com.voxel.entity.Entity e : liveEntities) {
                    if (e instanceof com.voxel.entity.WitherEntity) {
                        com.voxel.entity.WitherEntity w = (com.voxel.entity.WitherEntity) e;
                        if (w.isDead() && !w.markedDropped()) {
                            w.dropLoot(ctx.droppedItemManager);
                            w.markDropped();
                            setStatus("The Wither has been slain");
                            needsWorldUpload = true;
                        }
                    }
                }
            }

            // Parachute landing: consume durability when player touches ground
            if (player.isOnGround() && player.getParachuteItemId() != null) {
                String itemId = player.getParachuteItemId();
                int slotIdx = player.getParachuteSlotIndex();
                player.resetParachute();
                // Target the exact slot that was deployed
                if (slotIdx >= 0 && slotIdx < playerInventory.getInventorySize()) {
                    ItemStack stack = playerInventory.getSlot(slotIdx);
                    if (stack != null && stack.itemId.equals(itemId)) {
                        if (itemId.equals("golden_parachute")) {
                            stack.durability--;
                            if (stack.durability <= 0) {
                                stack.count--;
                                if (stack.count <= 0) playerInventory.clearSlot(slotIdx);
                                setStatus("Golden parachute worn out!");
                            } else {
                                setStatus("Parachute landed (" + stack.durability + " uses left)");
                            }
                        } else {
                            // Cold parachute: single use
                            stack.count--;
                            if (stack.count <= 0) playerInventory.clearSlot(slotIdx);
                            setStatus("Parachute used up!");
                        }
                    }
                }
            }
        }

        player.setYaw(playerYaw);
        player.setPitch(pitch);

        if (playerEntity != null) {
            playerEntity.syncFromPlayer(player, playerYaw, pitch, cameraMode != CameraMode.FIRST_PERSON, dt);
        }

        // ── Startup menu screens refresh the loading-screen message ──
        if (ctx.menuScreen != GameContext.MenuScreen.IN_GAME) {
            ctx.spawnLoadingMessage = buildMenuMessage();
        }

        // Keep chunk streaming alive while spawn loading or a same-dimension
        // teleport is waiting for its destination section. Do not run movement,
        // combat, AI, fluids, or other gameplay simulation during either gate.
        if (ctx.spawnLoading || ctx.teleportLoading) {
            if (ctx.spawnLoading) {
                // Keep retrying the immediate spawn area if the first pass could
                // not allocate every section.
                chunkManager.retrySpawnGeneration(player.getPosition(), yaw);
            } else {
                // A same-dimension /tp may stay in the current XZ column while
                // changing Y sections, so retry from exact fixed-point position.
                chunkManager.retryTeleportGeneration(
                    player.getFixedX(), player.getFixedY(), player.getFixedZ(), yaw);
            }
            chunkManager.updateFixedPosition(player.getFixedX(), player.getFixedY(), player.getFixedZ(), yaw);
            lastLogicTickNanos = System.nanoTime();
            return;
        }

        // --- Crafting cutscene: walk player towards the table ---
        if (ctx.craftingCutsceneActive) {
            ctx.craftingCutsceneTimer += dt;
            float t = Math.min(1.0f, ctx.craftingCutsceneTimer / GameContext.CRAFTING_CUTSCENE_DURATION);
            // Use smoothstep for ease-in-out
            float smoothT = t * t * (3.0f - 2.0f * t);

            // Lerp player position
            Vector3f pos = player.getPosition();
            pos.set(
                ctx.cutsceneStartPos.x + (ctx.cutsceneTargetPos.x - ctx.cutsceneStartPos.x) * smoothT,
                ctx.cutsceneStartPos.y + (ctx.cutsceneTargetPos.y - ctx.cutsceneStartPos.y) * smoothT,
                ctx.cutsceneStartPos.z + (ctx.cutsceneTargetPos.z - ctx.cutsceneStartPos.z) * smoothT
            );

            // Lerp camera yaw/pitch
            yaw = ctx.cutsceneStartYaw + (ctx.cutsceneTargetYaw - ctx.cutsceneStartYaw) * smoothT;
            pitch = ctx.cutsceneStartPitch + (ctx.cutsceneTargetPitch - ctx.cutsceneStartPitch) * smoothT;
            ctx.yaw = yaw;
            ctx.pitch = pitch;
            playerYaw = yaw;

            // When cutscene completes: open the crafting UI
            if (t >= 1.0f) {
                ctx.craftingCutsceneActive = false;
                ctx.craftingTableOpen = true;
                ctx.inventoryOpen = true;
                inventoryOpen = true;
                craftingCameraInited = true;
                craftingCameraYaw = yaw;
                craftingCameraPitch = CraftingTableConstants.CRAFTING_TABLE_PITCH;
                needsCursorUpdate = true; // Signal render loop to release cursor on GL thread
                ctx.activeUI = GameContext.ActiveUI.CRAFTING_TABLE;
                // Load existing items from CraftingTableManager into the player's grid
                playerInventory.loadFromCraftingTable(ctx.craftingTableBlockX, ctx.craftingTableBlockY, ctx.craftingTableBlockZ);
                ctx.setStatus("Crafting Table — 3x3 grid");
            }
        }

        // --- Furnace cutscene: walk player towards the furnace, then open its UI ---
        if (ctx.furnaceCutsceneActive) {
            ctx.furnaceCutsceneTimer += dt;
            float ft = Math.min(1.0f, ctx.furnaceCutsceneTimer / GameContext.FURNACE_CUTSCENE_DURATION);
            float smoothT = ft * ft * (3.0f - 2.0f * ft);

            // Lerp player position to the walk target
            Vector3f fpos = player.getPosition();
            fpos.set(
                ctx.furnaceCutsceneStartPos.x + (ctx.furnaceCutsceneTargetPos.x - ctx.furnaceCutsceneStartPos.x) * smoothT,
                ctx.furnaceCutsceneStartPos.y + (ctx.furnaceCutsceneTargetPos.y - ctx.furnaceCutsceneStartPos.y) * smoothT,
                ctx.furnaceCutsceneStartPos.z + (ctx.furnaceCutsceneTargetPos.z - ctx.furnaceCutsceneStartPos.z) * smoothT
            );

            // Lerp camera yaw/pitch toward the furnace
            yaw = ctx.furnaceCutsceneStartYaw + (ctx.furnaceCutsceneTargetYaw - ctx.furnaceCutsceneStartYaw) * smoothT;
            pitch = ctx.furnaceCutsceneStartPitch + (ctx.furnaceCutsceneTargetPitch - ctx.furnaceCutsceneStartPitch) * smoothT;
            ctx.yaw = yaw;
            ctx.pitch = pitch;
            playerYaw = yaw;

            // When cutscene completes: open the furnace UI
            if (ft >= 1.0f) {
                ctx.furnaceCutsceneActive = false;
                blockInteraction.openFurnace(ctx.furnaceBlockX, ctx.furnaceBlockY, ctx.furnaceBlockZ);
                inventoryOpen = true;
                needsCursorUpdate = true; // Signal render loop to release cursor on GL thread
                ctx.setStatus("Furnace — smelt ore, burn fuel");
            }
        }

        worldTime += dt;
        ctx.worldTime = worldTime;
        VillagerEntity.setGlobalWorldTime(worldTime);
        blockInteraction.updateMining(dt);
        blockInteraction.updatePlacementPreview();

        if (cameraShake > 0) cameraShake -= dt * 5.0f;
        if (ctx.cameraShake > 0) ctx.cameraShake -= dt * 5.0f;
        
        // Combat timers
        if (ctx.comboTimer > 0) {
            ctx.comboTimer -= dt;
            if (ctx.comboTimer <= 0) ctx.comboCount = 0; // Combo expired
        }
        if (ctx.isCharging) ctx.chargeTime += dt;
        
        // I-frame timer
        if (ctx.invincible) {
            ctx.iFrameTimer -= dt;
            if (ctx.iFrameTimer <= 0) {
                ctx.invincible = false;
            }
        }

        // Update damage numbers
        for (int i = ctx.damageNumbers.size() - 1; i >= 0; i--) {
            ctx.damageNumbers.get(i).update(dt);
            if (ctx.damageNumbers.get(i).isExpired()) {
                ctx.damageNumbers.remove(i);
            }
        }

        // Lock-on: auto-face the locked enemy
        if (combatMode && ctx.lockedEntityIndex >= 0) {
            com.voxel.entity.Entity locked = entityManager.getEntity(ctx.lockedEntityIndex);
            if (locked != null && locked instanceof com.voxel.entity.EnemyEntity) {
                com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) locked;
                if (!enemy.isDead()) {
                    // Auto-face the locked enemy
                    Vector3f toTarget = new Vector3f(enemy.getPosition()).sub(player.getPosition());
                    float targetYaw = (float) Math.toDegrees(Math.atan2(toTarget.x, toTarget.z));
                    // Smoothly rotate player toward target
                    float diff = ((targetYaw - playerYaw) + 180) % 360 - 180;
                    playerYaw += diff * Math.min(1.0f, dt * 8.0f);
                    // Camera yaw follows player yaw (over-the-shoulder)
                    yaw = playerYaw;
                } else {
                    ctx.lockedEntityIndex = -1; // Enemy died, unlock
                }
            } else {
                ctx.lockedEntityIndex = -1;
            }
        }

        // --- Enemy AI (rate-limited: near entities first, then round-robin, capped per tick) ---
        Vector3f pPos = player.getPosition();
        int processed = 0;
        int totalEntities = entityManager.getEntityCount();
        // Every ~1s, reset the round-robin offset so no entity starves
        staleCleanupCounter++;
        if (staleCleanupCounter >= STALE_CLEANUP_TICK_INTERVAL) {
            staleCleanupCounter = 0;
            aiUpdateOffset = 0;
        }
        // Pass 0: entities within 32 blocks (always get updated)
        for (int i = 0; i < totalEntities && processed < MAX_ENTITY_AI_PER_TICK; i++) {
            com.voxel.entity.Entity e = entityManager.getEntity(i);
            if (e.dimension != activeDimension) continue;
            if (e instanceof com.voxel.entity.EnemyEntity) {
                com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) e;
                if (!enemy.isDead() && enemy.getPosition().distanceSquared(pPos) < 1024f) {
                    enemy.updateAI(pPos, dt);
                    processed++;
                }
            }
        }
        // Pass 1: round-robin through remaining entities (capped by budget)
        int startIdx = (aiUpdateOffset % Math.max(1, totalEntities));
        for (int i = 0; i < totalEntities && processed < MAX_ENTITY_AI_PER_TICK; i++) {
            int idx = (startIdx + i) % totalEntities;
            com.voxel.entity.Entity e = entityManager.getEntity(idx);
            if (e.dimension != activeDimension) continue;
            if (e instanceof com.voxel.entity.EnemyEntity) {
                com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) e;
                if (!enemy.isDead() && enemy.getPosition().distanceSquared(pPos) >= 1024f) {
                    enemy.updateAI(pPos, dt);
                    processed++;
                }
            }
        }
        aiUpdateOffset = (aiUpdateOffset + processed) % Math.max(1, totalEntities);

        // Projectile cleanup + player damage (cheap — always full-scan)
        for (int i = entityManager.getEntityCount() - 1; i >= 0; i--) {
            com.voxel.entity.Entity e = entityManager.getEntity(i);
            if (e instanceof FireballEntity) {
                FireballEntity fb = (FireballEntity) e;
                if (fb.isExpired()) {
                    // Can't remove from list without an API; mark expired = skip
                    continue;
                }
                Vector3f pPos2 = player.getPosition();
                if (fb.getPosition().distanceSquared(pPos2) < 2.5f) {
                    player.takeDamage(4.0f);
                    fb.expire();
                    ctx.setStatus("Hit by a blaze fireball!");
                }
            } else if (e instanceof com.voxel.entity.ArrowEntity) {
                com.voxel.entity.ArrowEntity arrow = (com.voxel.entity.ArrowEntity) e;
                if (arrow.isExpired()) {
                    continue;
                }
                Vector3f pPos2 = player.getPosition();
                if (arrow.getPosition().distanceSquared(pPos2) < 1.6f) {
                    player.takeDamage(com.voxel.entity.ArrowEntity.DAMAGE);
                    arrow.expire();
                    ctx.setStatus("Hit by a skeleton's arrow!");
                }
            }
        }
        // Remove dead enemies and expired fireballs from the list
        entityManager.pruneExpired();

        // Tick furnaces (smelting logic)
        if (ctx.furnaceManager != null && ctx.chunkManager != null) {
            ctx.furnaceManager.tickAll(ctx.chunkManager, dt);
        }

        // Tick blaze burners and steam engines
        if (ctx.blazeBurnerManager != null) {
            ctx.blazeBurnerManager.tick(dt);
        }

        // Tick encased fans (push dropped items along the fan's facing when powered)
        if (ctx.encasedFanSystem != null) {
            ctx.encasedFanSystem.tick(dt);
        }

        // Tick dropped items (bob animation + auto-pickup when player walks over)
        if (ctx.droppedItemManager != null) {
            ctx.droppedItemManager.update(dt, player.getPosition());
        }

        entityManager.update(dt);
        portalSystem.checkTeleport();

        // Tick villager TV system
        if (ctx.tvSystem != null) {
            ctx.tvSystem.tick(dt);
        }

        // Fall from Aether: if player drops below y=0, fall back to Overworld
        if (activeDimension == DimensionType.AETHER && player.getPosition().y < 0) {
            ctx.setStatus("Fell out of the Aether!");
            ctx.switchToDimension(DimensionType.OVERWORLD);
        }

        // ---- Crafting table camera (fixed position above table) ----
        if (ctx.craftingTableOpen) {
            if (!craftingCameraInited) {
                craftingCameraYaw = Math.round(yaw / 90.0f) * 90.0f;
                craftingCameraPitch = CraftingTableConstants.CRAFTING_TABLE_PITCH;
                craftingCameraInited = true;
            }
            yaw = craftingCameraYaw;
            pitch = craftingCameraPitch;
        } else {
            if (craftingCameraInited) {
                craftingCameraInited = false;
            }
        }

        // ---- Villager TV cutscene: zoom camera toward TV ----
        if (ctx.tvCutsceneActive) {
            ctx.tvCutsceneTimer += dt;
            float t = Math.min(1.0f, ctx.tvCutsceneTimer / GameContext.TV_CUTSCENE_DURATION);
            float smoothT = t * t * (3.0f - 2.0f * t);

            // Lerp camera yaw/pitch to looking at TV
            yaw = ctx.tvCutsceneStartYaw + (ctx.tvCutsceneTargetYaw - ctx.tvCutsceneStartYaw) * smoothT;
            pitch = ctx.tvCutsceneStartPitch + (ctx.tvCutsceneTargetPitch - ctx.tvCutsceneStartPitch) * smoothT;
            ctx.yaw = yaw;
            ctx.pitch = pitch;
            playerYaw = yaw;

            // When cutscene completes, activate TV watching mode
            if (t >= 1.0f && !ctx.tvWatching) {
                ctx.tvWatching = true;
                ctx.activeUI = GameContext.ActiveUI.TV;
                needsCursorUpdate = true;
            }
        }            // Sync fields after potential dimension switch from PortalSystem/CommandProcessor
        if (chunkManager != ctx.chunkManager) {
            chunkManager = ctx.chunkManager;
            com.voxel.entity.CreeperEntity.setChunkManager(chunkManager);
            world = ctx.world;
            activeDimension = ctx.activeDimension;
            redstoneManager = ctx.redstoneManager;
            // Fluid manager is recreated on dimension switch by GameContext.switchToDimension
        }

        Vector3f pPosForRS = player.getPosition();
        if (redstoneManager != null) {
            redstoneManager.setPlayerPosition(pPosForRS.x, pPosForRS.y, pPosForRS.z);
            redstoneManager.tickLamps();
            // Kinetic network evaluates after the redstone pass so clutch/gearshift
            // power states reflect the latest network.
            if (ctx.machineManager != null) {
                ctx.machineManager.tick(dt);
            }
            if (ctx.kineticManager != null) {
                ctx.kineticManager.tick();
            }
            updateMachineLookInfo();
            ctx.commandBlockManager.tick(ctx, dt);
        }

        // Tick fluid flow (process up to 64 pending fluid blocks per tick)
        if (ctx.fluidManager != null) {
            // Fluids: rate-limited with proximity prioritisation (closest first)
            Vector3f fluidPlr = player.getPosition();
            ctx.fluidManager.tick(32, fluidPlr.x, fluidPlr.y, fluidPlr.z);
        }

        // /light point lights are DYNAMIC: they follow the player. The command stores
        // radius/RGB/intensity once, but the position is re-snapped to the player's
        // current location every tick so the light stays glued to them (the render
        // thread still converts to buffer-relative coords at upload time).
        // +1.0 lift: keeps the light in an air cell at block boundaries / slopes so
        // fastShadow never reports it as self-occluded (light would blink out).
        int npl = ctx.numPointLights;
        if (npl > 0) {
            Vector3f lp = player.getPosition();
            float[] ld = ctx.pointLightData;
            for (int i = 0; i < npl; i++) {
                int b = i * 8;
                ld[b] = lp.x;
                ld[b + 1] = lp.y + 1.0f;
                ld[b + 2] = lp.z;
            }
        }

        chunkManager.updateFixedPosition(player.getFixedX(), player.getFixedY(), player.getFixedZ(), yaw);

        // Center-ray look-ahead: preload the first unloaded chunk the camera is
        // looking at (second priority behind the player's own chunk).
        chunkManager.requestLookAheadFixed(
            player.getFixedX(), player.getFixedY(), player.getFixedZ(), yaw, pitch);

        // ── Map: also load chunks around the map's top-down camera ──
        if (ctx.mapOpen) {
            // Mirror the render-loop camera height so chunks stream in around
            // whatever zoom level the player is viewing.
            float mapCamY = mapCameraHeight(ctx.mapDisplayZoom);
            chunkManager.updateMapFixedPosition(
                FixedPoint.fromFloat(ctx.mapPanX),
                FixedPoint.fromFloat(mapCamY),
                FixedPoint.fromFloat(ctx.mapPanY), 0f);

            // Simplified biome view for unloaded chunks (sampled by the shader
            // where rays fall through EMPTY columns). Void past the world border
            // so the map stops exactly at the world size.
            WorldGenerator mapGen = chunkManager.getGenerator();
            mapRenderer.updatePreview(mapGen == null ? null : mapGen.getMapBiomeProvider(),
                    ctx.mapPanX, ctx.mapPanY, ctx.mapZoom,
                    (float) ctx.borderManager.getBorderRadius());
        }

        // Periodic auto-save: write level.dat (player state + metadata) every
        // 10 seconds so crashes lose at most a few moments of progress.
        if (++autosaveCounter >= 200) {
            autosaveCounter = 0;
            if (ctx.worldSaveManager != null && player != null && playerInventory != null) {
                if (ctx.chunkManager != null) ctx.chunkManager.savePendingChanges();
                ctx.worldSaveManager.saveLevelData(ctx, player, playerInventory);
                if (ctx.machineManager != null) {
                    ctx.worldSaveManager.saveMachineData(ctx.activeDimension, ctx.machineManager);
                }
            }
        }

        // Record wall-clock time for render-thread interpolation
        lastLogicTickNanos = System.nanoTime();
    }

    public void handleInput(float dt) {
        // ── Map controls: WASD/arrows pan, scroll zoom, right-drag pan ──
        if (ctx.mapOpen) {
            // Keyboard pan: WASD or arrow keys (dt-scaled for consistent speed).
            // W/S drive mapPanX and A/D drive mapPanY — the top-down camera
            // presents the world with axes swapped on screen, so the keys are
            // remapped to match what the player sees. Up/down are swapped to
            // match the camera's screen orientation.
            float panSpeed = ctx.mapZoom * 40f;
            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS)
                ctx.mapPanX += panSpeed * dt;
            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS)
                ctx.mapPanX -= panSpeed * dt;
            if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS)
                ctx.mapPanY -= panSpeed * dt;
            if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS)
                ctx.mapPanY += panSpeed * dt;
            // Keyboard zoom: multiplicative steps (consistent feel with scroll)
            if (keyJustPressed(GLFW_KEY_EQUAL) || keyJustPressed(GLFW_KEY_KP_ADD))
                ctx.mapTargetZoom = clampMapZoom(ctx.mapTargetZoom / 1.5f);
            if (keyJustPressed(GLFW_KEY_MINUS) || keyJustPressed(GLFW_KEY_KP_SUBTRACT))
                ctx.mapTargetZoom = clampMapZoom(ctx.mapTargetZoom * 1.5f);
            // Scroll zoom: smooth multiplier toward the target zoom
            if (scrollDelta != 0f) {
                float zoomFactor = (float) Math.pow(1.15, scrollDelta);
                ctx.mapTargetZoom = clampMapZoom(ctx.mapTargetZoom / zoomFactor);
                scrollDelta = 0f;
            }
            // Smooth zoom interpolation toward the target
            float zoomDiff = ctx.mapTargetZoom - ctx.mapDisplayZoom;
            if (Math.abs(zoomDiff) > 0.001f) {
                ctx.mapDisplayZoom += zoomDiff * Math.min(1.0f, dt * 6.0f);
                ctx.mapZoom = ctx.mapDisplayZoom;
            }
            // Drag panning is handled in handleCursorMoved (render thread) so
            // it always uses the freshest cursor position.
            // Center on player (C or Home key)
            if (keyJustPressed(GLFW_KEY_HOME) || keyJustPressed(GLFW_KEY_C)) {
                ctx.mapPanX = player.getPosition().x;
                ctx.mapPanY = player.getPosition().z;
            }
            // Reset zoom (0 key)
            if (keyJustPressed(GLFW_KEY_0)) {
                ctx.mapTargetZoom = 1.0f;
            }
            // Update coordinate + compass readouts (throttled)
            ctx.mapCoordinateUpdateTimer += dt;
            if (ctx.mapCoordinateUpdateTimer > 0.1f) {
                ctx.mapCoordinateUpdateTimer = 0f;
                ctx.mapCoordinateText = String.format(Locale.ROOT, "X: %.0f   Z: %.0f   Zoom: %.1fx",
                    ctx.mapPanX, ctx.mapPanY, ctx.mapDisplayZoom);
            }
            ctx.mapCompassAngle = (float) Math.toRadians(yaw + 90);
            return;
        }
        
        if (inventoryOpen || commandMode || player.isDead() || ctx.craftingCutsceneActive || ctx.tvCutsceneActive || ctx.furnaceCutsceneActive) return;

        // Compute forward/right vectors early (needed for dodge roll and movement)
        double ry = Math.toRadians(yaw);
        float fx = (float) Math.cos(ry), fz = (float) Math.sin(ry);
        float rx = -fz, rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl > 0) { rx /= rl; rz /= rl; }

        // Direction-aware dodge roll with i-frames (Left Alt in combat mode)
        if (glfwGetKey(window, GLFW_KEY_LEFT_ALT) == GLFW_PRESS && combatMode) {
            double now = glfwGetTime();
            if (now - lastRollTime > 1.0) {
                playerEntity.startRoll();
                // Determine roll direction from WASD input (raw strafe/forward)
                float rollStrafe = 0, rollForward = 0;
                if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) rollForward += 1.0f;
                if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) rollForward -= 1.0f;
                if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) rollStrafe += 1.0f;
                if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) rollStrafe -= 1.0f;
                float rollLen = (float) Math.sqrt(rollStrafe * rollStrafe + rollForward * rollForward);
                if (rollLen > 0.01f) {
                    rollStrafe /= rollLen;
                    rollForward /= rollLen;
                } else {
                    rollForward = 1.0f; // Default forward
                }
                // move(dx=strafing, dy, dz=forward, speed) — tick() handles yaw rotation
                player.move(rollStrafe * 20, 0.5f, rollForward * 20, 10.0f);
                lastRollTime = now;
                ctx.invincible = true;
                ctx.iFrameTimer = 0.5f; // 0.5s of invincibility
                setStatus("Dodge!");
            }
        }

        // Combat mode: charge attack on hold, fire on release
        if (combatMode && !inventoryOpen) {
            if (leftMousePressedThisFrame) {
                ctx.isCharging = true;
                ctx.chargeTime = 0.0f;
                ctx.comboTimer = 0.0f; // Reset combo on new charge start
            }
            // If charging and mouse released OR hit max charge time
            if (ctx.isCharging) {
                if (!leftMouseHeld || ctx.chargeTime >= 1.5f) {
                    ctx.isCharging = false;
                    float chargePercent = Math.min(1.0f, ctx.chargeTime / 1.2f);
                    // Combo multiplier: 1st hit = 1.0x, 2nd = 1.5x, 3rd = 2.5x
                    float comboMult;
                    switch (ctx.comboCount) {
                        case 1: comboMult = 1.5f; break;
                        case 2: comboMult = 2.5f; break;
                        default: comboMult = 1.0f;
                    }
                    float damage = (4.0f + chargePercent * 8.0f) * comboMult;
                    ctx.lastAttackDamage = damage;
                    playerEntity.startAttack();
                    performCombatAttack(damage);
                    ctx.comboCount = (ctx.comboCount + 1) % 3;
                    ctx.comboTimer = 0.8f; // Reset combo window
                    lastAttackTime = glfwGetTime();
                    ctx.cameraShake = 0.8f + chargePercent * 1.2f;
                }
            }
        } else if (!combatMode && !inventoryOpen) {
            // Normal mode: instant attack on click
            if (leftMousePressedThisFrame) {
                double now = glfwGetTime();
                if (now - lastAttackTime > 0.25f) {
                    playerEntity.startAttack();
                    performCombatAttack(4.0f);
                    lastAttackTime = now;
                }
            }
        }

        // Standard WASD: W=forward, A=left, S=backward, D=right
        float strafe = 0, forward = 0;
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) forward += 1.0f;
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) forward -= 1.0f;
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) strafe += 1.0f;
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) strafe -= 1.0f;

        float mvLen = (float) Math.sqrt(strafe * strafe + forward * forward);
        if (mvLen > 0) {
            strafe /= mvLen;
            forward /= mvLen;

            if (combatMode) {
                // Restrict to 1 line of movement (axis-aligned)
                if (Math.abs(strafe) > Math.abs(forward)) {
                    forward = 0;
                } else {
                    strafe = 0;
                }
            }

            if (cameraMode == CameraMode.THIRD_PERSON_FOLLOW) {
                // Compute world-space direction matching tick() rotation convention
                // forward → (cos, sin), left strafe → (-sin, cos)
                float wx = forward * fx + strafe * fz;
                float wz = forward * fz - strafe * fx;
                playerYaw = (float) Math.toDegrees(Math.atan2(wz, wx));
            }
        }

        // Sprint: Left Control key OR double-tap W while on ground and moving forward
        boolean ctrlDown = glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS;
        boolean wDown = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        boolean sprintByCtrl = ctrlDown && !player.isFlying() && player.isOnGround();

        // Double-tap W: measure gap between release and re-press (<300ms)
        boolean sprintByDoubleTap = false;
        if (!wDown && wWasPressed) {
            lastWPressTime = glfwGetTime();  // record release time
        }
        if (wDown && !wWasPressed && forward > 0.1f && !player.isFlying() && player.isOnGround()) {
            if (glfwGetTime() - lastWPressTime < 0.3) {
                sprintByDoubleTap = true;
            }
        }
        wWasPressed = wDown;

        player.setSprinting(sprintByCtrl || sprintByDoubleTap);
        // Sneak: Left Shift while on ground
        if (!player.isFlying()) {
            boolean shiftDown = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
            player.setSneaking(shiftDown);
        }

        // Pass raw strafe/forward — tick() handles yaw rotation + acceleration internally
        player.move(strafe, 0, forward, 0);

        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            if (gameMode == GameMode.CREATIVE) {
                // Creative: auto-fly, no isFlying gate needed
                player.setFlying(true);
                player.move(0, 0.05f, 0, 0);
            } else if (player.isFlying()) {
                player.move(0, 0.05f, 0, 0);
            } else {
                player.jump(world, blockDataManager);
            }
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            if (gameMode == GameMode.CREATIVE) {
                // Creative: shift = fly down, not sneak
                player.setFlying(true);
                player.setSneaking(false);
                player.move(0, -0.05f, 0, 0);
            } else if (player.isFlying()) {
                player.move(0, -0.05f, 0, 0);
            }
            // Survival: shift is handled by setSneaking() above
        }

        if (gameMode == GameMode.CREATIVE) {
            if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) player.setFlying(true);
            if (glfwGetKey(window, GLFW_KEY_G) == GLFW_PRESS) player.setFlying(false);
        }
    }

    public void performCombatAttack(float damage) {
        Vector3f pPos = player.getPosition();
        Vector3f pDir = getLookDirection();

        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            com.voxel.entity.Entity e = entityManager.getEntity(i);
            if (e.dimension != activeDimension) continue;
            if (e instanceof com.voxel.entity.EnemyEntity) {
                com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) e;
                if (enemy.isDead()) continue;

                Vector3f toEnemy = new Vector3f(enemy.getPosition()).sub(pPos);
                float dist = toEnemy.length();

                // Wider hit cone in combat mode (0.35 vs 0.45) for better feel
                float minDot = combatMode ? 0.35f : 0.45f;
                float maxDist = combatMode ? 5.0f : 4.5f;

                if (dist < maxDist) {
                    toEnemy.normalize();
                    float dot = toEnemy.dot(pDir);
                    if (dot > minDot) {
                        Vector3f knockback = new Vector3f(toEnemy).mul(0.8f + damage * 0.05f);
                        enemy.takeDamage(damage, knockback);
                        cameraShake = 0.8f + damage * 0.08f;
                        // Spawn damage number at enemy position
                        ctx.damageNumbers.add(new GameContext.DamageNumber(
                            enemy.getPosX(), enemy.getPosY() + 2.0f, enemy.getPosZ(),
                            damage
                        ));
                        // Enemy telegraph: flash on hit
                        enemy.hitFlashTime = 0.3f;
                        
                        
                        // Combo hit text
                        String[] comboText = {"Hit!", "Double!", "TRIPLE!"};
                        int comboIdx = Math.max(0, Math.min(ctx.comboCount, 2));
                        ctx.setStatus(comboText[comboIdx] + " (" + String.format("%.0f", damage) + " dmg)");
                    }
                }
            }
        }
    }

    public void loop() {
        float lastTime = (float) glfwGetTime();
        int frames = 0;
        float fpsTime = 0;
        boolean wasUiCovered = false; // tracks fullscreen-UI state to refresh light pools on close

        while (!glfwWindowShouldClose(window)) {
            float currentTime = (float) glfwGetTime();
            float dt = currentTime - lastTime;
            lastTime = currentTime;
            fpsTime += dt;
            frames++;
            if (fpsTime >= 1.0f) {
                // Bug fix: previously wrote to Main.lastMeasuredFps, but HUD reads
                // ctx.lastMeasuredFps — so the window title always showed 0.
                ctx.lastMeasuredFps = frames;
                lastMeasuredFps = frames;
                frames = 0;
                fpsTime = 0;
            }

            if (player.isDead()) {
                statusMessage = "YOU DIED! Press R to respawn.";
                statusUntil = glfwGetTime() + 1.0;
            }

            // Sync dimension changes from GameContext (render loop needs current world)
            if (chunkManager != ctx.chunkManager) {
                chunkManager = ctx.chunkManager;
                com.voxel.entity.CreeperEntity.setChunkManager(chunkManager);
                world = ctx.world;
                activeDimension = ctx.activeDimension;
                redstoneManager = ctx.redstoneManager;
                player.setDimension(activeDimension);
            }
            boolean prevInventoryOpen = inventoryOpen;
            syncGameState();
            // Update cursor mode when inventoryOpen changes (e.g. from crafting table right-click)
            if (inventoryOpen != prevInventoryOpen) {
                updateCursorMode();
            }
            // Cursor update requested by logic thread (e.g. crafting cutscene completion)
            if (needsCursorUpdate) {
                updateCursorMode();
                needsCursorUpdate = false;
            }

            // World-side GPU bookkeeping only runs once the LOGIC thread has
            // fully completed Main.initializeWorldPhase() — i.e. once
            // GameContext.initializing (volatile) flips false. That single
            // volatile write is the happens-before barrier for everything
            // initializeWorldPhase() did, including all entityManager.addEntity
            // calls; checking it AFTER the world/chunkManager null guards closes
            // the race window where the GL thread would otherwise start calling
            // entityManager.uploadToGPU() concurrently with the LOGIC thread
            // still adding the initial enemies.
            if (!ctx.initializing) {
                redstoneManager.applyLampChanges();
                if (ctx.kineticManager != null) {
                    ctx.kineticManager.applySwaps();
                }
                if (ctx.blazeBurnerManager != null) {
                    ctx.blazeBurnerManager.drainSwaps();
                }
                if (ctx.copperTankManager != null) {
                    ctx.copperTankManager.drainSwaps();
                }
                // Deferred world GPU upload (must happen on GL thread)
                if (needsWorldUpload) {
                    uploadWorldToGpu();
                    needsWorldUpload = false;
                }

                uploadDirtyChunks();

                // Upload biome map to GPU when the gen thread has slid it
                if (chunkManager.isBiomeMapDirty()) {
                    biomeManager.uploadBiomeMap();
                    chunkManager.clearBiomeMapDirty();
                }
            }

            updateInventoryUi();
            hud.updateTVOverlay(glfwGetTime(), worldTime);
            hud.updateSpawnLoadingOverlay(glfwGetTime());
            hud.updateTutorialPopup(glfwGetTime());
            hud.updateWindowTitle();

            hud.uiManager.begin();
            for (UILayer layer : hud.uiLayers) layer.render(hud.uiManager);
            hud.uiManager.end();

            // Release the menu panorama's CPU scene once a world is committed
            // (its SSBOs were already replaced by the real world's upload).
            if (panoramaWorld != null && ctx.menuScreen == GameContext.MenuScreen.IN_GAME) {
                panoramaWorld = null;
            }

            // --- Compute partial ticks for smooth interpolation between logic ticks ---
            long nowNanos = System.nanoTime();
            float elapsedSinceLogic = (nowNanos - lastLogicTickNanos) / 1e9f;
            float logicPartialTicks = Math.min(1.0f, elapsedSinceLogic / 0.0167f);

            // Player uses its own 20Hz wall-clock time for interpolation
            float elapsedSincePlayerTick = (nowNanos - player.getLastTickWallNanos()) / 1e9f;
            float playerPartialTicks = Math.min(1.0f, elapsedSincePlayerTick / Player.TICK_RATE_SECONDS);

            // Camera uses interpolated player position
            Vector3f cameraPos = cameraController.getActiveCameraPosition(playerPartialTicks);

            // ── Map: top-down camera, height derived from zoom level ──
            // Zooming in lowers the camera for closer terrain; zooming out
            // raises it for a wider overview (capped by mapCameraHeight).
            if (ctx.mapOpen) {
                float mapCamY = mapCameraHeight(ctx.mapDisplayZoom);
                cameraPos = new Vector3f(ctx.mapPanX, mapCamY, ctx.mapPanY);
                yaw = 0f; pitch = -90f;
            }

            // Same gate as block A above: world / chunkManager / entityManager
            // are only safe to read AFTER initializeWorldPhase() finishes (see
            // the volatile ctx.initializing flag).
            if (ctx.menuScreen != GameContext.MenuScreen.IN_GAME && panoramaActive) {
                // 3D main-menu panorama: the raytracer renders the hand-crafted
                // scene with a slowly orbiting camera and composites the menu UI
                // (u_UITexture) on top — the same path used in-world.
                renderMenuPanorama(Math.min(dt, 0.1f));
            } else if (!ctx.initializing) {
            // World buffer origin — used to make all shader positions buffer-relative
            int wox = world.getOffsetX(), woy = world.getOffsetY(), woz = world.getOffsetZ();

            // Upload entities with exact fixed-point worldOffset (bypass float precision loss)
            entityManager.uploadToGPU(activeDimension, cameraPos, logicPartialTicks, player,
                (long)wox * FixedPoint.SCALE,
                (long)woy * FixedPoint.SCALE,
                (long)woz * FixedPoint.SCALE);

            // Upload dynamic point lights (converted from absolute world coords to
            // buffer-relative space so the shader compares them against voxel space).
            // Clamp at the source too (belt-and-suspenders with the shader's min()):
            // a corrupt count must never overrun the 132-float buffer or reach the GPU.
            int npl = Math.min(ctx.numPointLights, GameContext.MAX_POINT_LIGHTS);
            persistentPlBuf.clear();
            // Header count MUST be written as raw int bits (Float.intBitsToFloat),
            // not as (float)npl: the shader reads this back as `int numPointLights`.
            // Writing 1.0f here stores bits 0x3F800000 = 1,065,353,216 as an int,
            // driving the point-light loop to a billion iterations -> GPU hang /
            // frozen screen at uncapped FPS. (npl == 0 masked this bug: 0.0f has
            // all-zero bits.)
            persistentPlBuf.put(Float.intBitsToFloat(npl)).put(0).put(0).put(0);
            float[] pld = ctx.pointLightData;
            for (int i = 0; i < npl; i++) {
                int b = i * 8;
                persistentPlBuf.put(pld[b] - wox).put(pld[b + 1] - woy).put(pld[b + 2] - woz).put(pld[b + 3]);
                persistentPlBuf.put(pld[b + 4]).put(pld[b + 5]).put(pld[b + 6]).put(pld[b + 7]);
            }
            persistentPlBuf.flip();
            glNamedBufferSubData(pointLightSSBO, 0, persistentPlBuf);

            // Compute camera vectors early (used by prepass and compute dispatch)
            double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
            float fx = (float) (Math.cos(ry) * Math.cos(rp)), fy = (float) Math.sin(rp), fz = (float) (Math.sin(ry) * Math.cos(rp));
            float rx = -fz, rz = fx;
            float rl = (float) Math.sqrt(rx * rx + rz * rz);
            if (rl > 0) { rx /= rl; rz /= rl; }
            float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
            
            glUseProgram(computeProgram);
            // Camera in buffer-relative space: decompose into camBlock (relative to u_WorldOffset,
            // always in 0-2048 range → full float32 sub-block precision) + camFrac (0-1).
            // First-person mode uses player's 64-bit fixed-point longs (1/256 resolution).
            // Detached cameras (crafting table view + all cutscenes) must use the
            // cutscene/camera position from getActiveCameraPosition() instead of the
            // player eye, otherwise the rendered view never matches the raycast used
            // for crafting-cell clicks.
            int cbx, cby, cbz;
            float cfx, cfy, cfz;
            boolean detachedCamera = ctx.craftingCutsceneActive || ctx.craftingTableOpen
                || ctx.furnaceCutsceneActive || ctx.mapOpen;
            if (cameraMode == CameraMode.FIRST_PERSON && !detachedCamera) {
                // Interpolate in pure fixed-point (no float→long precision loss)
                long px = FixedPoint.lerp(player.getFixedPrevX(), player.getFixedX(), playerPartialTicks);
                long py = FixedPoint.lerp(player.getFixedPrevY(), player.getFixedY(), playerPartialTicks)
                    + FixedPoint.fromFloat(CameraController.PLAYER_EYE_HEIGHT);
                long pz = FixedPoint.lerp(player.getFixedPrevZ(), player.getFixedZ(), playerPartialTicks);
                cbx = FixedPoint.camBlock(px) - wox;
                cby = FixedPoint.camBlock(py) - woy;
                cbz = FixedPoint.camBlock(pz) - woz;
                cfx = FixedPoint.camFrac(px);
                cfy = FixedPoint.camFrac(py);
                cfz = FixedPoint.camFrac(pz);
            } else {
                // Third-person/orbit/fixed: compute camera offset from player in float
                // (delta is small, within float precision), then apply to player's
                // fixed-point position to get exact camera position at any world coord.
                Vector3f playerPosF = player.getInterpolatedPosition(playerPartialTicks);
                float camDeltaX = cameraPos.x - playerPosF.x;
                float camDeltaY = cameraPos.y - playerPosF.y;
                float camDeltaZ = cameraPos.z - playerPosF.z;
                long pxTP = FixedPoint.lerp(player.getFixedPrevX(), player.getFixedX(), playerPartialTicks);
                long pyTP = FixedPoint.lerp(player.getFixedPrevY(), player.getFixedY(), playerPartialTicks);
                long pzTP = FixedPoint.lerp(player.getFixedPrevZ(), player.getFixedZ(), playerPartialTicks);
                long camX_fp = pxTP + FixedPoint.fromFloat(camDeltaX);
                long camY_fp = pyTP + FixedPoint.fromFloat(camDeltaY);
                long camZ_fp = pzTP + FixedPoint.fromFloat(camDeltaZ);
                cbx = FixedPoint.camBlock(camX_fp) - wox;
                cby = FixedPoint.camBlock(camY_fp) - woy;
                cbz = FixedPoint.camBlock(camZ_fp) - woz;
                cfx = FixedPoint.camFrac(camX_fp);
                cfy = FixedPoint.camFrac(camY_fp);
                cfz = FixedPoint.camFrac(camZ_fp);
            }
            if (cameraShake > 0.01f) {
                cfx += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
                cfy += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
                cfz += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
            }
            // Underwater flag: when the camera eye is clipped inside a water block,
            // the shader switches to the underwater pass (fog, tint, caustics, no
            // stretched near-surface texture). Cheap voxel check, uploaded once.
            int uwx = cbx + wox, uwy = cby + woy, uwz = cbz + woz;
            int eyeVoxel = world.getVoxel(uwx, uwy, uwz);
            boolean underWater = isWaterId(eyeVoxel);
            if (!underWater && cfy < 0.6f) {
                underWater = isWaterId(world.getVoxel(uwx, uwy - 1, uwz));
            }
            glProgramUniform1i(computeProgram, LOC_UNDER_WATER, underWater ? 1 : 0);
            glProgramUniform1i(computeProgram, LOC_LARGE_COG, (ctx.kineticManager != null && ctx.kineticManager.hasLargeCog()) ? 1 : 0);
            // No camera-clipped uniform: the raytracer now detects the eye voxel
            // itself (reads _camBlock's solidity straight from the GPU pools) and
            // treats it as air on the first bounce — no CPU→GPU handoff to go stale.
            glProgramUniform3f(computeProgram, 0, cfx, cfy, cfz);
            glProgramUniform3i(computeProgram, 29, cbx, cby, cbz);

            glProgramUniform3f(computeProgram, 1, fx, fy, fz);
            glProgramUniform3f(computeProgram, 2, rx, 0, rz);
            glProgramUniform3f(computeProgram, 3, ux, uy, uz);
            glProgramUniform1f(computeProgram, 4, worldTime);
            glProgramUniform1i(computeProgram, 5, entityManager.getUploadedEntityCount());
            atmosphereRenderer.upload(worldTime, activeDimension);
            glProgramUniform1i(computeProgram, atmosphereRenderer.locDimensionID(), activeDimension.id);
            // Upload world sliding window offset
            glProgramUniform3i(computeProgram, 6, world.getOffsetX(), world.getOffsetY(), world.getOffsetZ());

            // Upload block break overlay uniforms.
            // breakTarget* is in ABSOLUTE world coords (from raycastBlock -> world.getVoxel),
            // but the shader compares against buffer-relative blockCoord (derived from
            // _camBlock which is already offset-subtracted). Convert here so the overlay
            // matches after the sliding window recenters (offset != 0).
            if (ctx.breakTargetX != Integer.MIN_VALUE) {
                glProgramUniform3i(computeProgram, 19, ctx.breakTargetX - wox, ctx.breakTargetY - woy, ctx.breakTargetZ - woz);
                glProgramUniform1f(computeProgram, 20, ctx.breakProgress / Math.max(1.0f, ctx.blockDataManager.getHardness(ctx.world.getVoxel(ctx.breakTargetX, ctx.breakTargetY, ctx.breakTargetZ))));
            } else {
                glProgramUniform3i(computeProgram, 19, 0, 0, 0);
                glProgramUniform1f(computeProgram, 20, 0.0f);
            }
            // Upload destroy stage base layer index (computed from Minecraft destroy_stage_0 texture)
            int destroyBaseLayer = textureManager.getTextureIndex("destroy_stage_0");
            glProgramUniform1i(computeProgram, 21, destroyBaseLayer < 0 ? -1 : destroyBaseLayer);

            // Semi-transparent placement preview (ghost block). previewX/Y/Z are in
            // ABSOLUTE world coords like breakTarget*, so subtract the sliding-window
            // offset so the shader's buffer-relative DDA matches it.
            if (ctx.previewBlock >= 0) {
                glProgramUniform3i(computeProgram, 39, ctx.previewX - wox, ctx.previewY - woy, ctx.previewZ - woz);
                glProgramUniform1i(computeProgram, 40, ctx.previewBlock);
                glProgramUniform1i(computeProgram, 41, ctx.previewFacing);
            } else {
                glProgramUniform3i(computeProgram, 39, 0, 0, 0);
                glProgramUniform1i(computeProgram, 40, -1);
                glProgramUniform1i(computeProgram, 41, 0);
            }

            // Upload UI UVs
            glUniform4f(locHeartUVs, hud.uvHeartFull.x, hud.uvHeartFull.y, hud.uvHeartFull.z, hud.uvHeartFull.w);
            glUniform4f(locHeartUVs + 1, hud.uvHeartHalf.x, hud.uvHeartHalf.y, hud.uvHeartHalf.z, hud.uvHeartHalf.w);
            glUniform4f(locHeartUVs + 2, hud.uvHeartEmpty.x, hud.uvHeartEmpty.y, hud.uvHeartEmpty.z, hud.uvHeartEmpty.w);

            bindTextures();

            // Bind destroy_stage texture array at texture unit 17
            glActiveTexture(GL_TEXTURE17);
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getDestroyStageArrayId());
            if (locDestroyStages >= 0) glUniform1i(locDestroyStages, 17);

            glActiveTexture(GL_TEXTURE15);
            glBindTexture(GL_TEXTURE_2D, hud.uiTextureId);
            glUniform1i(locUISource, 15);

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, bitmaskSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, occlusionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, pointLightSSBO);
            entityManager.bind(6, 7);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, craftingItemSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, lightSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 10, sdfSSBO);


            uploadCraftingItems();

            // ── 16 baked light pools (8 sun-trajectory + 8 moon-trajectory, ~20Hz) ──
            // No surface sun shadows: the main pass samples the ACTIVE sun/moon pools
            // for god rays only. Regen active pools when terrain/camera moved or the
            // active pool index changed; round-robin 2 pools/tick keeps all fresh.
            shadowFrameCount++;
            float camBX = cbx + cfx, camBY = cby + cfy, camBZ = cbz + cfz;
            // Fixed pool directions are constant per dimension — derive them once
            // per dimension switch instead of re-running 16 sin/cos pairs every frame.
            if (cachedPoolDirsDim != activeDimension) {
                cachedPoolDirsDim = activeDimension;
                for (int i = 0; i < 8; i++) {
                    float sampleT = (i + 0.5f) / 8.0f * 1440.0f;
                    AtmosphereRenderer.computeSunDir(activeDimension, sampleT, poolDirs[i]);
                    if (activeDimension == DimensionType.NETHER) { poolDirs[8+i][0]=0f; poolDirs[8+i][1]=0.5f; poolDirs[8+i][2]=0f; }
                    else if (activeDimension == DimensionType.END) { poolDirs[8+i][0]=0f; poolDirs[8+i][1]=-1f; poolDirs[8+i][2]=0f; }
                    else if (activeDimension == DimensionType.AETHER) { poolDirs[8+i][0]=0f; poolDirs[8+i][1]=-1f; poolDirs[8+i][2]=-0.3f; }
                    else { poolDirs[8+i][0] = -poolDirs[i][0]; poolDirs[8+i][1] = -poolDirs[i][1]; poolDirs[8+i][2] = -poolDirs[i][2]; }
                }
            }
            AtmosphereRenderer.computeSunDir(activeDimension, worldTime, activeSunDir);
            if (activeDimension == DimensionType.NETHER) { activeMoonDir[0]=0f; activeMoonDir[1]=0.5f; activeMoonDir[2]=0f; }
            else if (activeDimension == DimensionType.END) { activeMoonDir[0]=0f; activeMoonDir[1]=-1f; activeMoonDir[2]=0f; }
            else if (activeDimension == DimensionType.AETHER) { activeMoonDir[0]=0f; activeMoonDir[1]=-1f; activeMoonDir[2]=-0.3f; }
            else { activeMoonDir[0] = -activeSunDir[0]; activeMoonDir[1] = -activeSunDir[1]; activeMoonDir[2] = -activeSunDir[2]; }
            activeSunPool = nearestPool(activeSunDir, 0);
            activeMoonPool = nearestPool(activeMoonDir, 8); // nearestPool already returns the absolute index (8..15)
            float camDX = camBX - shadowCamPosPrev[0], camDY = camBY - shadowCamPosPrev[1], camDZ = camBZ - shadowCamPosPrev[2];
            // Fullscreen UI (inventory/crafting/command/TV) hides the god-ray shafts,
            // so skip the pool-regeneration dispatches entirely until it closes. The
            // flags stay dirty and re-run on the first visible frame.
            boolean uiCovered = inventoryOpen || commandMode || ctx.tvWatching;
            // Refresh the shafts immediately when the fullscreen UI closes (pools
            // were deliberately skipped while covered).
            if (wasUiCovered && !uiCovered) lightPoolDirty = true;
            wasUiCovered = uiCovered;
            boolean poolTick = !uiCovered && (shadowFrameCount % 5 == 0); // ~12Hz background refresh (was 20Hz)
            boolean activeChanged = (activeSunPool != prevActiveSunPool) || (activeMoonPool != prevActiveMoonPool);
            boolean activeDirty = !uiCovered && (lightPoolDirty || (camDX * camDX + camDY * camDY + camDZ * camDZ) > 4.0f || activeChanged);
            int r32f = org.lwjgl.opengl.GL30.GL_R32F;
            if (activeDirty) {
                regenLightPool(activeSunPool, camBX, camBY, camBZ, r32f);
                regenLightPool(activeMoonPool, camBX, camBY, camBZ, r32f);
                lightPoolDirty = false;
                shadowCamPosPrev[0] = camBX; shadowCamPosPrev[1] = camBY; shadowCamPosPrev[2] = camBZ;
                prevActiveSunPool = activeSunPool; prevActiveMoonPool = activeMoonPool;
            }
            if (poolTick) {
                int rr = (shadowFrameCount / 5) % 16; // round-robin 1 pool per tick (all fresh ~1.3s)
                regenLightPool(rr, camBX, camBY, camBZ, r32f);
            }
            glProgramUniform1i(computeProgram, LOC_SHADOW_PASS, 0);
            // Bind ACTIVE pools + upload their ortho bases for the god-ray march.
            uploadPoolBasis(LOC_SHADOW_ORIGIN, LOC_SHADOW_RIGHT, LOC_SHADOW_UP, LOC_SHADOW_SUN_DIR,
                            poolDirs[activeSunPool], camBX, camBY, camBZ);
            uploadPoolBasis(LOC_MOON_POOL_ORIGIN, LOC_MOON_POOL_RIGHT, LOC_MOON_POOL_UP, LOC_MOON_POOL_DIR,
                            poolDirs[activeMoonPool], camBX, camBY, camBZ);
            glProgramUniform2f(computeProgram, LOC_SHADOW_EXTENT, shadowHalfExtent, shadowDepth);
            glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE18);
            glBindTexture(GL_TEXTURE_2D, lightPoolTex[activeSunPool]);
            glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE19);
            glBindTexture(GL_TEXTURE_2D, lightPoolTex[activeMoonPool]);
            glProgramUniform1i(computeProgram, LOC_SUN_POOL, 18);
            glProgramUniform1i(computeProgram, LOC_MOON_POOL, 19);
            glProgramUniform1f(computeProgram, LOC_SHADOW_MAP_SIZE, 1.0f / shadowMapRes);

            glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
            glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            if (ctx.screenshotRequested) {
                takeScreenshot();
                ctx.screenshotRequested = false;
            }

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(quadProgram);
            glBindTextureUnit(0, renderTexture);
            glUniform1i(locQuadPass, 0);
            if (locQuadFlipY >= 0) glUniform1i(locQuadFlipY, 0);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            } else {
                // Loading-screen-only frame: the UI texture already holds the
                // current full-screen overlay (main-menu panorama + title, or the
                // spawn-loading artwork during world creation), so present it
                // directly through the quad. The raytracer that normally
                // composites the UI cannot run while the world is still
                // initializing — without this the menu phase would be black.
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, width, height);
                glClear(GL_COLOR_BUFFER_BIT);
                glUseProgram(quadProgram);
                glBindTextureUnit(0, hud.uiManager.getUITexture());
                glUniform1i(locQuadPass, 0);
                if (locQuadFlipY >= 0) glUniform1i(locQuadFlipY, 0);
                glBindVertexArray(quadVAO);
                glDrawArrays(GL_TRIANGLES, 0, 6);
            }

            glfwSwapBuffers(window);
            glfwPollEvents();
            if (villagerAudioManager != null) {
                villagerAudioManager.update();
            }
            leftMousePressedThisFrame = false;
            // ctx.leftMousePressedThisFrame is consumed/reset by the logic thread in tick()
        }
    }

    public void handleKeyInput(long win, int key, int scancode, int action, int mods) {
        // Ignore input callbacks while the early loading frame is being presented.
        // The normal loop will begin polling once all dependent state exists.
        if (ctx == null || player == null) return;
        // Menu screens own the keyboard (world does not exist yet). The menu
        // keys are polled by the logic thread, but block gameplay hotkeys here.
        if (ctx.menuScreen != GameContext.MenuScreen.IN_GAME) return;
        if (action == GLFW_PRESS && key == GLFW_KEY_ESCAPE) {
            if (ctx.pauseMenuOpen) {
                ctx.pauseMenuOpen = false;
                updateCursorMode();
                return;
            }
            if (ctx.mapOpen) {
                ctx.mapOpen = false;
                ctx.activeUI = GameContext.ActiveUI.NONE;
                needsCursorUpdate = true;
                return;
            }
            if (!inventoryOpen && !commandMode && ctx.activeUI == GameContext.ActiveUI.NONE) {
                ctx.pauseMenuOpen = true;
                ctx.pauseSelection = 0;
                updateCursorMode();
                return;
            }
        }
        if (action == GLFW_PRESS) {
            // Creative mode: DELETE destroys the held stack (or the selected hotbar
            // item when not holding anything). Non-printable, so it never collides
            // with the creative search box's character capture.
            if (key == GLFW_KEY_DELETE && gameMode == GameMode.CREATIVE) {
                playerInventory.deleteCreativeItem();
                hud.inventoryUiDirty = true;
                showSelectedItemName();
                setStatus("Deleted item");
                return;
            }
            // Creative picker search: BACKSPACE edits the filter.
            if (ctx.creativeMenuOpen && inventoryOpen) {
                if (key == GLFW_KEY_BACKSPACE && ctx.creativeSearch.length() > 0) {
                    ctx.creativeSearch.deleteCharAt(ctx.creativeSearch.length() - 1);
                    hud.inventoryUiDirty = true;
                }
                return;
            }
            // Menu text fields: BACKSPACE edits the active buffer.
            if (ctx.menuTextActive && ctx.menuScreen != GameContext.MenuScreen.IN_GAME) {
                if (key == GLFW_KEY_BACKSPACE && ctx.menuTextInput.length() > 0) {
                    ctx.menuTextInput.deleteCharAt(ctx.menuTextInput.length() - 1);
                }
                return;
            }
            if (commandMode) {
                handleCommandModeKey(key);
                return;
            }

            if (key == GLFW_KEY_R && player.isDead()) {
                player.respawn();
                setStatus("Respawned.");
                return;
            }

            if (key == GLFW_KEY_V) {
                // Spawn a test villager at the player's position
                com.voxel.entity.VillagerEntity v = new com.voxel.entity.VillagerEntity(
                    70000 + (int)(Math.random() * 1000),
                    new Vector3f(player.getPosition()),
                    textureManager
                );
                v.dimension = activeDimension;
                v.setWorld(world);
                entityManager.addEntity(v);
                System.out.println("Spawned villager at " + player.getPosition());
                return;
            }

            if (key == GLFW_KEY_F5) {
                toggleCameraMode();
                return;
            }

            if (key == GLFW_KEY_SLASH) {
                openCommandMode();
                return;
            }

            if (key == GLFW_KEY_E) {
                if (ctx.ridingMinecart != null) {
                    dismountMinecart(ctx.ridingMinecart);
                    setStatus("Dismounted minecart");
                    return;
                }
                // Creative mode opens the item picker instead of the survival bag.
                if (gameMode == GameMode.CREATIVE) {
                    toggleCreativeMenu();
                } else {
                    toggleInventory();
                    showSelectedItemName();
                }
                return;
            }

            if (key == GLFW_KEY_M) {
                if (ctx.mapOpen) {
                    ctx.mapOpen = false; ctx.activeUI = GameContext.ActiveUI.NONE;
                    needsCursorUpdate = true; setStatus("");
                } else {
                    ctx.mapOpen = true; ctx.activeUI = GameContext.ActiveUI.MAP;
                    // Reset pan to player position on open
                    ctx.mapPanX = player.getPosition().x;
                    ctx.mapPanY = player.getPosition().z;
                    ctx.mapTargetZoom = ctx.mapZoom;
                    ctx.mapDisplayZoom = ctx.mapZoom;
                    ctx.mapDragging = false;
                    needsCursorUpdate = true;
                    setStatus("Map — scroll/+/ - to zoom, drag or WASD to pan, C to center, M to close");
                }
                return;
            }

            if (key == GLFW_KEY_C) {
                spawnCreeperAtLook();
                return;
            }

            if (key == GLFW_KEY_X) {
                combatMode = !combatMode;
                ctx.combatMode = combatMode;
                if (combatMode) {
                    cameraMode = CameraMode.THIRD_PERSON_FOLLOW;
                    ctx.cameraMode = cameraMode;
                    ctx.lockedEntityIndex = -1; // Reset lock when toggling
                }
                setStatus("Combat Mode: " + (combatMode ? "ON (Story Mode)" : "OFF"));
                return;
            }

            // Lock-on targeting (Tab key in combat mode)
            if (key == GLFW_KEY_TAB && combatMode) {
                if (ctx.lockedEntityIndex >= 0) {
                    ctx.lockedEntityIndex = -1; // Unlock
                    setStatus("Lock-off");
                } else {
                    // Find nearest enemy within 25 blocks
                    Vector3f pPos = player.getPosition();
                    float nearestDist = 25.0f;
                    int nearestIdx = -1;
                    for (int i = 0; i < entityManager.getEntityCount(); i++) {
                        com.voxel.entity.Entity e = entityManager.getEntity(i);
                        if (e.dimension != activeDimension) continue;
                        if (e instanceof com.voxel.entity.EnemyEntity) {
                            com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) e;
                            if (enemy.isDead()) continue;
                            float dist = pPos.distance(enemy.getPosition());
                            if (dist < nearestDist) {
                                nearestDist = dist;
                                nearestIdx = i;
                            }
                        }
                    }
                    ctx.lockedEntityIndex = nearestIdx;
                    if (nearestIdx >= 0) {
                        setStatus("Locked on!");
                    } else {
                        setStatus("No enemies to lock");
                    }
                }
                return;
            }

            // Scroll status text with +/- (multi-line help, lists, etc.)
            if ((key == GLFW_KEY_MINUS || key == GLFW_KEY_KP_SUBTRACT) && !statusMessage.isEmpty()) {
                statusLineOffset = Math.max(0, statusLineOffset - 1);
                return;
            }
            if ((key == GLFW_KEY_EQUAL || key == GLFW_KEY_KP_ADD) && !statusMessage.isEmpty()) {
                statusLineOffset++;
                return;
            }

            if (key == GLFW_KEY_ESCAPE) {
                if (ctx.tvWatching) {
                    // Exit TV watching mode
                    blockInteraction.stopWatchingTV();
                    updateCursorMode();
                    return;
                }
                if (ctx.craftingCutsceneActive) {
                    // Abort crafting cutscene
                    ctx.craftingCutsceneActive = false;
                    ctx.setStatus("Cancelled");
                    return;
                }
                if (ctx.furnaceCutsceneActive) {
                    // Abort furnace cutscene
                    ctx.furnaceCutsceneActive = false;
                    ctx.setStatus("Cancelled");
                    return;
                }
                if (inventoryOpen) {
                    setInventoryOpen(false);
                    showSelectedItemName();
                    return;
                }
                glfwSetWindowShouldClose(win, true);
                return;
            }

            if (key >= GLFW_KEY_1 && key < GLFW_KEY_1 + HOTBAR_SIZE) {
                playerInventory.setSelectedSlot(key - GLFW_KEY_1);
                showSelectedItemName();
                return;
            }

            // TV channel cycling (LEFT/RIGHT arrows while watching TV)
            if (ctx.tvWatching && (key == GLFW_KEY_LEFT || key == GLFW_KEY_RIGHT)) {
                blockInteraction.cycleTVChannel();
                return;
            }
        }

        if (action == GLFW_RELEASE && key == GLFW_KEY_ESCAPE && commandMode) {
            cancelCommandMode();
        }
    }

    public void handleCommandModeKey(int key) {
        if (ctx != null && ctx.commandBlockEditorOpen) {
            if (key == GLFW_KEY_ESCAPE) {
                ctx.commandBlockEditorOpen = false;
                ctx.commandMode = false;
                commandMode = false;
                setInventoryOpen(false);
                return;
            }
            if (key == GLFW_KEY_BACKSPACE && ctx.commandBlockEditorCommand.length() > 0) {
                ctx.commandBlockEditorCommand = ctx.commandBlockEditorCommand.substring(0, ctx.commandBlockEditorCommand.length() - 1);
                hud.inventoryUiDirty = true;
                return;
            }
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                blockInteraction.saveCommandBlockEditor();
                ctx.commandBlockEditorOpen = false;
                ctx.commandMode = false;
                commandMode = false;
                setInventoryOpen(false);
                return;
            }
            return;
        }
        if (key == GLFW_KEY_ESCAPE) {
            cancelCommandMode();
            return;
        }
        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
            String text = commandBuffer.toString().trim();
            commandMode = false;
            ctx.commandMode = false;
            updateCursorMode();
            commandProcessor.execute(text);
            commandBuffer.setLength(0);
            return;
        }
        if (key == GLFW_KEY_BACKSPACE && commandBuffer.length() > 0) {
            commandBuffer.deleteCharAt(commandBuffer.length() - 1);
        }
    }

    public void handleCharInput(long win, int codepoint) {
        // Creative picker search box accepts typed characters.
        if (ctx != null && ctx.creativeMenuOpen && inventoryOpen) {
            if (codepoint >= 32 && codepoint <= 126 && ctx.creativeSearch.length() < 32) {
                ctx.creativeSearch.append((char) codepoint);
                hud.inventoryUiDirty = true;
            }
            return;
        }
        // Main-menu text fields (world name / seed) accept typed characters.
        if (ctx != null && ctx.menuTextActive && ctx.menuScreen != GameContext.MenuScreen.IN_GAME) {
            if (codepoint >= 32 && codepoint <= 126 && ctx.menuTextInput.length() < 64) {
                ctx.menuTextInput.append((char) codepoint);
            }
            return;
        }
        if (ctx != null && ctx.commandBlockEditorOpen) {
            if (codepoint >= 32 && codepoint <= 126 && ctx.commandBlockEditorCommand.length() < 512) {
                ctx.commandBlockEditorCommand += (char) codepoint;
                hud.inventoryUiDirty = true;
            }
            return;
        }
        if (!commandMode) return;
        if (codepoint < 32 || codepoint > 126) return;
        commandBuffer.append((char) codepoint);
    }

    public void handleCursorMoved(long win, double xpos, double ypos) {
        // Menu screens have no camera to turn; just track the cursor position.
        if (ctx != null && ctx.menuScreen != GameContext.MenuScreen.IN_GAME) {
            lastMouseX = (float) xpos;
            lastMouseY = (float) ypos;
            ctx.lastMouseX = lastMouseX;
            ctx.lastMouseY = lastMouseY;
            return;
        }
        if (firstMouse) {
            lastMouseX = (float) xpos;
            lastMouseY = (float) ypos;
            firstMouse = false;
        }

        // GLFW can deliver an initial cursor event during the early loading swap,
        // before GameContext exists. Keep the raw position, but defer camera sync
        // until normal gameplay initialization has completed.
        if (ctx == null) return;

        // Map drag-pan is computed HERE (render thread) so it always uses the
        // freshest cursor position — reading lastMouseX/Y from the logic thread
        // in handleInput would see stale values and the drag would appear dead.
        if (ctx.mapOpen && ctx.mapDragging) {
            float dx = (float) xpos - ctx.mapDragStartX;
            float dy = (float) ypos - ctx.mapDragStartY;
            // 1:1 grab: FOV is 90°, so the vertical world span at the map camera
            // is 2×height → world-units-per-pixel = 2h / viewportHeight. This makes
            // the world point under the cursor stay under the cursor while dragging.
            // Axes swapped (dx drives panY, dy drives panX); Y inverted again → +dy.
            float unitsPerPixel = 2f * mapCameraHeight(ctx.mapZoom) / (float) height;
            ctx.mapPanX = ctx.mapDragPanStartX + dy * unitsPerPixel;
            ctx.mapPanY = ctx.mapDragPanStartY - dx * unitsPerPixel;
            lastMouseX = (float) xpos;
            lastMouseY = (float) ypos;
            return;
        }

        if (inventoryOpen || commandMode) {
            // Track mouse position for inventory UI interactions (slot clicks, item drag)
            lastMouseX = (float) xpos;
            lastMouseY = (float) ypos;
            ctx.lastMouseX = lastMouseX;
            ctx.lastMouseY = lastMouseY;
            return;
        }

        float xoffset = (float) xpos - lastMouseX;
        float yoffset = lastMouseY - (float) ypos;
        ctx.lastMouseX = (float) xpos;
        ctx.lastMouseY = (float) ypos;
        lastMouseX = (float) xpos;
        lastMouseY = (float) ypos;

        float sensitivity = 0.1f;
        yaw += xoffset * sensitivity;
        pitch += yoffset * sensitivity;
        if (pitch > 89.0f) pitch = 89.0f;
        if (pitch < -89.0f) pitch = -89.0f;

        // Sync with GameContext so BlockInteraction uses the same camera angles
        ctx.yaw = yaw;
        ctx.pitch = pitch;
    }

    public void handleScroll(long win, double xoffset, double yoffset) {
        scrollDelta += (float) yoffset;
        // Creative picker grid scrolls with the wheel.
        if (ctx != null && ctx.creativeMenuOpen && inventoryOpen && yoffset != 0) {
            ctx.creativeScroll = Math.max(0, ctx.creativeScroll + (int) Math.signum(-yoffset));
            hud.inventoryUiDirty = true;
        }
    }

    public void handleMouseButton(long win, int button, int action, int mods) {
        // Mouse callbacks may arrive before the game context/UI are ready.
        if (ctx == null || player == null || blockInteraction == null) return;        // During startup menus the world does not exist; route clicks only to
        // menu controls and never into gameplay raycasts.
        if (ctx.menuScreen != GameContext.MenuScreen.IN_GAME) {
            if (action == GLFW_PRESS && button == GLFW_MOUSE_BUTTON_LEFT) {
                hud.handleMouseClick(lastMouseX, lastMouseY);
            }
            return;
        }

        if (ctx.pauseMenuOpen) {

            if (action == GLFW_PRESS && button == GLFW_MOUSE_BUTTON_LEFT) {
                hud.handleMouseClick(lastMouseX, lastMouseY);
            }
            return;
        }

        // Map drag pan: LEFT or RIGHT mouse both pan (like Google Maps).
        // left press first tries the map overlay buttons (zoom/reset/center);
        // if none is hit, the press starts a drag. Everything is consumed so
        // world actions (break/place) never fire underneath the map UI.
        if (ctx.mapOpen) {
            boolean isLeft = button == GLFW_MOUSE_BUTTON_LEFT;
            boolean isRight = button == GLFW_MOUSE_BUTTON_RIGHT;
            if (action == GLFW_PRESS && (isLeft || isRight)) {
                // Left press on an overlay button: fire it, do NOT start a drag.
                boolean hitButton = isLeft && hud.handleMouseClick((float) lastMouseX, (float) lastMouseY);
                if (!hitButton) {
                    ctx.mapDragging = true;
                    ctx.mapDragStartX = (float) lastMouseX;
                    ctx.mapDragStartY = (float) lastMouseY;
                    ctx.mapDragPanStartX = ctx.mapPanX;
                    ctx.mapDragPanStartY = ctx.mapPanY;
                }
            } else if (action == GLFW_RELEASE && (isLeft || isRight)) {
                ctx.mapDragging = false;
            }
            // While the map is open, never fall through to world interaction.
            return;
        }

        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW_PRESS) {
                leftMouseHeld = true;
                ctx.leftMouseHeld = true;
                leftMousePressedThisFrame = true;
                ctx.leftMousePressedThisFrame = true;
            } else if (action == GLFW_RELEASE) {
                leftMouseHeld = false;
                ctx.leftMouseHeld = false;
                blockInteraction.resetMining();
            }
        }

        if (action != GLFW_PRESS) return;

        // Explicit HUD controls take priority over the world/table raycast. This
        // lets the MCSM Craft button consume the click instead of treating it as
        // a miss and continuing into inventory/world input.
        if (ctx.craftingTableOpen && inventoryOpen && hud.handleMouseClick(lastMouseX, lastMouseY)) {
            return;
        }

        // Surface crafting uses the four quadrants of the targeted block's top face.
        if (ctx.surfaceCraftingOpen && inventoryOpen) {
            int cell = blockInteraction.raycastSurfaceCraftingCell();
            if (cell >= 0) {
                playerInventory.handleCraftingSlotClick(cell);
                hud.inventoryUiDirty = true;
                return;
            }
        }

        // Crafting table drag-and-drop via 3D raycast
        if (ctx.craftingTableOpen && inventoryOpen) {
            System.out.println("Crafting: mouse click at screen (" + lastMouseX + "," + lastMouseY + ")");
            int cell = raycastCraftingCell();
            if (cell >= 0) {
                System.out.println("Crafting: slot click " + cell);
                playerInventory.handleCrafting3x3SlotClick(cell);
                hud.inventoryUiDirty = true;
                return;
            }
            System.out.println("Crafting: cell miss, falling through to UI");
            // Fall through to UI click handling for inventory slots
        }

        if (inventoryOpen) {
            for (int i = hud.uiLayers.size() - 1; i >= 0; i--) {
                if (hud.uiLayers.get(i).handleMouseClick(lastMouseX, lastMouseY)) return;
            }
            return;
        }

        if (commandMode) return;

        // Cutscenes take over input entirely: never let a stray click place or
        // break blocks, or re-trigger the cutscene mid-animation.
        if (ctx.craftingCutsceneActive || ctx.furnaceCutsceneActive || ctx.tvCutsceneActive) return;

        if (button == GLFW_MOUSE_BUTTON_RIGHT) {
            if ((mods & GLFW_MOD_ALT) != 0) {
                blockInteraction.attemptSurfaceCrafting();
            } else if ((mods & GLFW_MOD_SHIFT) != 0) {
                portalSystem.attemptActivate();
            } else {
                blockInteraction.attemptPlaceBlock();
                // A command-block editor is opened by BlockInteraction, but the
                // keyboard callback is owned by Main. Mirror the modal state
                // immediately so Enter/Escape/Backspace are captured this frame.
                if (ctx.commandBlockEditorOpen) {
                    commandMode = true;
                    updateCursorMode();
                }
            }
        }
    }

    public void openCommandMode() {
        if (inventoryOpen) setInventoryOpen(false);
        hud.inventoryUiDirty = true;
        commandMode = true;
        ctx.commandMode = true;
        ctx.leftMousePressedThisFrame = false; // prevent stale press from inventory
        commandBuffer.setLength(0);
        updateCursorMode();
    }

    public void cancelCommandMode() {
        hud.inventoryUiDirty = true;
        commandMode = false;
        ctx.commandMode = false;
        commandBuffer.setLength(0);
        updateCursorMode();
    }

    public void toggleInventory() {
        hud.inventoryUiDirty = true;
        setInventoryOpen(!inventoryOpen);
    }

    /** Opens/closes the creative item picker (E in creative mode). */
    public void toggleCreativeMenu() {
        hud.inventoryUiDirty = true;
        boolean open = !ctx.creativeMenuOpen;
        ctx.creativeMenuOpen = open;
        ctx.activeUI = open ? GameContext.ActiveUI.INVENTORY : GameContext.ActiveUI.NONE;
        setInventoryOpen(open);
        ctx.creativeSearch.setLength(0);
        ctx.creativeScroll = 0;
        updateCursorMode();
    }

    /** Adds a stack of the given item via the creative picker. */
    public void creativeGiveItem(String itemId) {
        if (itemId == null) return;
        ItemDefinitions.ItemDefinition def = itemDefinitions.getDefinition(itemId);
        if (def == null) return;
        int amount = def.maxStack;
        boolean added = playerInventory.addItem(itemId, amount);
        if (!added && def.kind == ItemDefinitions.ItemKind.BLOCK) {
            // Try at least one when full
            playerInventory.addItem(itemId, 1);
        }
        hud.inventoryUiDirty = true;
        setStatus("Given " + def.displayName + " x" + amount);
    }

    public void toggleCameraMode() {
        CameraMode[] modes = CameraMode.values();
        int currentOrdinal = cameraMode.ordinal();
        int nextOrdinal = (currentOrdinal + 1) % modes.length;
        CameraMode newMode = modes[nextOrdinal];

        cameraMode = newMode;
        ctx.cameraMode = cameraMode;

        String modeName = cameraMode.name().toLowerCase().replace('_', ' ');
        setStatus("Camera: " + modeName);
    }

    /**
     * Goggles overlay: when the selected item is the goggles, describe the
     * machine under the crosshair (name + power state). Runs on the logic
     * thread; HudUI renders {@code ctx.machineLookInfo}.
     */
    private void updateMachineLookInfo() {
        ctx.machineLookInfo = "";
        if (ctx.machineManager == null || ctx.world == null || ctx.playerInventory == null) return;
        if (ctx.inventoryOpen || ctx.commandMode) return;
        com.voxel.game.ItemDefinitions.ItemStack sel = ctx.playerInventory.getSelected();
        if (sel == null || !"goggles".equals(sel.itemId)) return;
        int[] hit = blockInteraction.raycastBlock(6.0f);
        if (hit == null) return;
        int b = ctx.world.getVoxel(hit[0], hit[1], hit[2]);
        if (b <= 0) return;
        String name = machineDisplayName(b);
        if (name == null) return;
        String state = ctx.machineManager.isMachinePowered(hit[0], hit[1], hit[2]) ? "Powered" : "Idle";
        ctx.machineLookInfo = name + " — " + state;
    }

    /** Display name for Create machine blocks, or null for non-machines. */
    private static String machineDisplayName(int block) {
        switch (block) {
            case 404: return "Hand Crank";
            case 405: return "Windmill Bearing";
            case 406: return "Windmill Sail";
            case 407: return "Mechanical Press";
            case 408: return "Millstone";
            case 409: return "Crushing Wheel";
            case 410: return "Mechanical Drill";
            case 411: return "Mechanical Saw";
            case 412: return "Deployer";
            case 413: return "Mechanical Belt";
            case 414: return "Item Vault";
            case 415: return "Brass Casing";
            default: return null;
        }
    }

    public void setInventoryOpen(boolean open) {
        hud.inventoryUiDirty = true;
        inventoryOpen = open;
        ctx.inventoryOpen = open;
        if (open) {
            ctx.leftMousePressedThisFrame = false; // prevent stale press from world
        }
        if (!open) {
            ctx.creativeMenuOpen = false; // closing inventory always closes the picker
        }
        if (!open) {
            // Save surface-crafting grid before closing. Its ingredients remain
            // stored on the targeted block and can be reopened later.
            if (ctx.surfaceCraftingOpen) {
                playerInventory.returnSurfaceCraftingItems();
                if (ctx.worldSaveManager != null) {
                    ctx.worldSaveManager.saveSurfaceCraftingData(ctx.activeDimension, ctx.surfaceCraftingManager);
                }
            }
            // Save crafting grid back to CraftingTableManager before closing
            if (ctx.craftingTableOpen) {
                playerInventory.saveToCraftingTable(ctx.craftingTableBlockX, ctx.craftingTableBlockY, ctx.craftingTableBlockZ);
                if (ctx.worldSaveManager != null) {
                    ctx.worldSaveManager.saveCraftingData(ctx.activeDimension, ctx.craftingTableManager);
                }
            }
            // Save furnace data on close
            if (ctx.furnaceOpen && ctx.worldSaveManager != null) {
                ctx.worldSaveManager.saveFurnaceData(ctx.activeDimension, ctx.furnaceManager);
            }
            // Save chest data on close
            if (ctx.chestOpen && ctx.worldSaveManager != null) {
                ctx.worldSaveManager.saveChestData(ctx.activeDimension, ctx.chestManager);
            }
            playerInventory.setCarriedStack(null);
            ctx.craftingTableOpen = false;
            ctx.surfaceCraftingOpen = false;
            ctx.commandBlockEditorOpen = false;
            ctx.commandMode = false;
            ctx.furnaceOpen = false;
            ctx.chestOpen = false;
            ctx.activeUI = GameContext.ActiveUI.NONE;
            ctx.leftMousePressedThisFrame = false; // prevent stale press from inventory
        }
        updateCursorMode();
    }

    public void updateCursorMode() {
        boolean inMenu = ctx != null && ctx.menuScreen != GameContext.MenuScreen.IN_GAME;
        boolean freeCursor = inMenu || inventoryOpen || commandMode || ctx.mapOpen || (ctx != null && ctx.pauseMenuOpen);
        glfwSetInputMode(window, GLFW_CURSOR, freeCursor ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
        if (freeCursor) {
            // Resync the tracked cursor position with the OS position. After
            // GLFW_CURSOR_DISABLED the virtual cursor drifts away from the real
            // one, so without this the first click after a cutscene would register
            // at a stale screen coordinate and miss the button the user is aiming at.
            double[] mx = new double[1], my = new double[1];
            glfwGetCursorPos(window, mx, my);
            lastMouseX = (float) mx[0];
            lastMouseY = (float) my[0];
            if (ctx != null) {
                ctx.lastMouseX = lastMouseX;
                ctx.lastMouseY = lastMouseY;
            }
        } else {
            firstMouse = true;
        }
    }

    public void setStatus(String message) {
        statusMessage = message;
        statusUntil = glfwGetTime() + 3.0;
        statusLineOffset = 0;
        System.out.println(message);
    }

    /** Shows the Tutorial World zone title-card popup for a few seconds. */
    public void showTutorialPopup(String title, String subtitle) {
        tutorialPopupTitle = title;
        tutorialPopupSubtitle = subtitle;
        tutorialPopupUntil = glfwGetTime() + 6.0;
    }

    // (buildVisibleChunkList removed — depth prepass is gone.)

    public void updateInventoryUi() { hud.updateInventoryUi(); }

    // --- Furnace slot click handler ---
    public void handleFurnaceSlotClick(int slot) { hud.handleFurnaceSlotClick(slot); }

    // --- Chest slot click handler ---
    public void handleChestSlotClick(int slot) { hud.handleChestSlotClick(slot); }

    public void showSelectedItemName() { hud.showSelectedItemName(); }

    // Crafting table texture layout (16x16): 2x2 pixel cells, 1px borders, 4px margins
    public static final float CT_MARGIN = 4.0f / 16.0f;     // 0.25
    public static final float CT_CELL = 2.0f / 16.0f;       // 0.125
    public static final float CT_GAP = 1.0f / 16.0f;        // 0.0625
    public static final float CT_STEP = CT_CELL + CT_GAP;   // 0.1875
    public static final float CT_HALF_CELL = CT_CELL / 2.0f; // 0.0625
    public static final float CRAFTING_ITEM_SCALE = 0.125f; // 1/8 scale — fills one 2x2 pixel cell

    /** Reusable hosting for the uploadRenderItems FloatBuffer (avoids per-frame alloc). */
    private float[] reusableItemDataBuf;
    private java.nio.FloatBuffer reusableItemNioBuf;

    /**
     * Pack all "3D miniature blocks" rendered in the world into the craftingItemSSBO
     * (which now also hosts dropped-item entries — see {@link com.voxel.game.DroppedItemManager}).
     *
     * Layout per CraftingItem (8 floats):
     *   [0..2] = position.xyz, [3] = blockId (as int bits), [4] = scale, [5..7] = padding
     * Order: crafting-grid items first (up to 9), then dropped items (up to MAX_DROPPED_ITEMS).
     * Total u_CraftingItemCount uniform = craftCount + dropCount.
     */
    public void uploadCraftingItems() {
        int maxCraftItems = 9; // 3x3 grid (existing behaviour)
        int maxItems = maxCraftItems + com.voxel.game.DroppedItemManager.MAX_ITEMS;

        if (reusableItemDataBuf == null || reusableItemDataBuf.length < maxItems * 8) {
            if (reusableItemNioBuf != null) MemoryUtil.memFree(reusableItemNioBuf);
            reusableItemDataBuf = new float[maxItems * 8];
            reusableItemNioBuf = MemoryUtil.memAllocFloat(maxItems * 8);
        }

        int count = 0;
        int craftCount = 0;

        // ---- Crafting-grid items (3x3 table or 2x2 exposed surface) ----
        String[][] grid = null;
        boolean surfaceGrid = ctx.surfaceCraftingOpen && ctx.activeUI == GameContext.ActiveUI.SURFACE_CRAFTING;
        if (ctx.craftingTableOpen) {
            grid = playerInventory.getCraftingGrid3x3();
        } else if (surfaceGrid) {
            grid = playerInventory.getCraftingGrid();
        } else if (ctx.craftingTableManager.hasGrid(ctx.craftingTableBlockX, ctx.craftingTableBlockY, ctx.craftingTableBlockZ)) {
            grid = ctx.craftingTableManager.getGrid(ctx.craftingTableBlockX, ctx.craftingTableBlockY, ctx.craftingTableBlockZ);
        }

        if (grid != null) {
            float woxf = (float)world.getOffsetX(), woyf = (float)world.getOffsetY(), wozf = (float)world.getOffsetZ();
            int gridSize = surfaceGrid ? 2 : 3;
            int blockX = surfaceGrid ? ctx.surfaceCraftingBlockX : ctx.craftingTableBlockX;
            int blockY = surfaceGrid ? ctx.surfaceCraftingBlockY : ctx.craftingTableBlockY;
            int blockZ = surfaceGrid ? ctx.surfaceCraftingBlockZ : ctx.craftingTableBlockZ;
            float bx = blockX - woxf;
            float bz = blockZ - wozf;
            float by = blockY - woyf + 1.0f + CRAFTING_ITEM_SCALE * 0.5f;

            for (int r = 0; r < gridSize; r++) {
                for (int c = 0; c < gridSize; c++) {
                    String itemId = grid[r][c];
                    if (itemId == null) continue;
                    ItemDefinitions.ItemDefinition def = itemDefinitions.getDefinition(itemId);
                    if (def == null || def.blockId <= 0) continue;
                    float px;
                    float pz;
                    if (surfaceGrid) {
                        px = bx + (c + 0.5f) / 2.0f;
                        pz = bz + (r + 0.5f) / 2.0f;
                    } else {
                        pz = bz + CT_MARGIN + c * CT_STEP + CT_HALF_CELL;
                        px = bx + (1.0f - CT_MARGIN) - r * CT_STEP - CT_HALF_CELL;
                    }
                    int idx = count * 8;
                    reusableItemDataBuf[idx] = px;
                    reusableItemDataBuf[idx + 1] = by;
                    reusableItemDataBuf[idx + 2] = pz;
                    reusableItemDataBuf[idx + 3] = Float.intBitsToFloat(def.blockId);
                    reusableItemDataBuf[idx + 4] = CRAFTING_ITEM_SCALE;
                    reusableItemDataBuf[idx + 5] = 0f;
                    reusableItemDataBuf[idx + 6] = 0f;
                    reusableItemDataBuf[idx + 7] = 0f;
                    count++;
                }
            }
            craftCount = count;
        }

        // ---- Dropped items (slice immediately after the crafting-grid entries) ----
        // Dropped items share the same shader path as crafting items: each entry encodes
        // (position.xyz, blockId-as-bits, scale). The shader renders whatever entries have
        // blockId > 0, scaling them independently per entry. We use DROPPED_ITEM_SCALE
        // (0.25) for dropped items vs CRAFTING_ITEM_SCALE (0.125) for crafting-grid items,
        // and the per-entry scale field in itemData[idx + 4] picks the right one.
        int dropCount = 0;
        if (ctx.droppedItemManager != null) {
            dropCount = ctx.droppedItemManager.buildUpload(reusableItemDataBuf, craftCount, world.getOffsetX(), world.getOffsetY(), world.getOffsetZ());
        }
        int totalCount = craftCount + dropCount;

        if (totalCount > 0) {
            reusableItemNioBuf.clear();
            reusableItemNioBuf.put(reusableItemDataBuf, 0, totalCount * 8);
            reusableItemNioBuf.flip();
            glNamedBufferSubData(craftingItemSSBO, 0, reusableItemNioBuf);
        }

        glProgramUniform1i(computeProgram, locCraftingItemCount, totalCount);
    }

    public void bindTextures() {
        glActiveTexture(GL_TEXTURE6);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getTextureArrayId());
        glUniform1i(locBlockTextures, 6);

        glActiveTexture(GL_TEXTURE16);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getEntityTextureArrayId());
        glUniform1i(locEntityTextures, 16);

        glActiveTexture(GL_TEXTURE7);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getTextureId());
        glUniform1i(locBlockData, 7);
        glActiveTexture(GL_TEXTURE12);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBTextureId());
        glUniform1i(locBlockAABBs, 12);
        glActiveTexture(GL_TEXTURE11);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getInfoTextureId());
        glUniform1i(locBlockAABBInfo, 11);
        glActiveTexture(GL_TEXTURE13);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBUVTextureId());
        glUniform1i(locBlockAABBUVs, 13);
        glActiveTexture(GL_TEXTURE8);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getBiomeMapId());
        glUniform1i(locBiomeMap, 8);
        glActiveTexture(GL_TEXTURE10);
        glBindTexture(GL_TEXTURE_2D, hud.uiManager.getUITexture());
        glUniform1i(locUITexture, 10);

        // ── Per-dimension atmosphere ──
        // Overworld keeps the bright daylight palette; Nether leans deep
        // red; End is intentionally dark with a magenta horizon.
        glUniform1i(locDimensionId, activeDimension == null ? 0 : activeDimension.id);
        float fogR, fogG, fogB, skyR, skyG, skyB;
        if (activeDimension == com.voxel.world.DimensionType.NETHER) {
            fogR = 0.42f; fogG = 0.05f; fogB = 0.05f;
            skyR = 0.30f; skyG = 0.06f; skyB = 0.06f;
        } else if (activeDimension == com.voxel.world.DimensionType.END) {
            fogR = 0.05f; fogG = 0.02f; fogB = 0.07f;
            skyR = 0.10f; skyG = 0.04f; skyB = 0.18f;
        } else {
            // Overworld + Aether + Portal Hall: a neutral blue-grey that
            // matches the daytime atmosphere the rest of the lighting pass
            // assumes.
            fogR = 0.62f; fogG = 0.70f; fogB = 0.85f;
            skyR = 1.00f; skyG = 1.00f; skyB = 1.00f;
        }
        glUniform3f(locFogColor, fogR, fogG, fogB);
        glUniform3f(locSkyTint, skyR, skyG, skyB);

        // ── Map preview: simplified biome view for unloaded chunks ──
        // Upload any freshly-baked region, then bind + drive the uniforms. The
        // origin/scale are in buffer-relative space (the shader's DDA voxel
        // space), so they shift with the buffer origin like the camera does.
        // Unit 20 (NOT 15): the render loop rebinds unit 15 to the raw ui.png
        // atlas (u_UISource) after this call, which would make the preview
        // sample ui.png instead of biome colors.
        mapRenderer.uploadIfDirty();
        glActiveTexture(GL_TEXTURE20);
        glBindTexture(GL_TEXTURE_2D, mapRenderer.getTextureId());
        glUniform1i(locMapPreview, 20);
        glUniform1i(locMapMode, ctx.mapOpen ? 1 : 0);
        int mwox = world == null ? 0 : world.getOffsetX();
        int mwoz = world == null ? 0 : world.getOffsetZ();
        glUniform2f(locMapPreviewOrigin, mapRenderer.getOriginX() - mwox, mapRenderer.getOriginZ() - mwoz);
        glUniform1f(locMapPreviewScale, mapRenderer.getBlocksPerTexel());
        glUniform2f(locMapWorldOrigin, mwox, mwoz);
        glUniform1f(locMapBorder, (float) ctx.borderManager.getBorderRadius());
        int mwoy = world == null ? 0 : world.getOffsetY();
        // The map preview projects onto the ground plane at world sea level (63),
        // so off-center perspective rays sample the correct landing XZ.
        glUniform1f(locMapGroundY, 63f - mwoy);
    }

    public void setupResources() {
        textureManager = new TextureManager();
        textureManager.loadTextures(
            "src/main/resources/assets/minecraft/textures/blocks",
            "src/main/resources/assets/minecraft/textures/items",
            "src/main/resources/assets/aether/textures/block/natural",
            "src/main/resources/assets/aether/textures/block/construction",
            "src/main/resources/assets/aether/textures/block/dungeon",
            "src/main/resources/assets/aether/textures/block/utility",
            "src/main/resources/assets/aether/textures/block/miscellaneous"
        );
        textureManager.loadEntityTextures("src/main/resources/assets/minecraft/textures/entity");
        // Snow Golem pumpkin layer uses the existing block textures on the entity array.
        textureManager.loadItemAsEntityTexture(
            "src/main/resources/assets/minecraft/textures/blocks/pumpkin_face_off.png",
            "pumpkin_face_off_entity");
        textureManager.loadItemAsEntityTexture(
            "src/main/resources/assets/minecraft/textures/blocks/pumpkin_side.png",
            "pumpkin_side_entity");
        textureManager.loadItemAsEntityTexture(
            "src/main/resources/assets/minecraft/textures/blocks/pumpkin_top.png",
            "pumpkin_top_entity");
        textureManager.loadDestroyStages("src/main/resources/assets/minecraft/textures/blocks");
        
        biomeManager = new com.voxel.utils.BiomeManager();
        
        blockDataManager = new BlockDataManager();
        blockRegistry = new BlockRegistry();
        shaderBlockRegistry = new ShaderBlockRegistry();
        blockRegistry.register("grass_block", 1);
        shaderBlockRegistry.register(1, 1);
        blockDataManager.registerBlock(1, "grass_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        // Per-biome-category grass variants: same model as grass_block, but each
        // carries a fixed category tint color instead of a location-based sample.
        blockRegistry.register("taiga_grass", 86);
        shaderBlockRegistry.register(86, 86);
        blockDataManager.registerBlock(86, "taiga_grass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("jungle_grass", 87);
        shaderBlockRegistry.register(87, 87);
        blockDataManager.registerBlock(87, "jungle_grass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("swamp_grass", 88);
        shaderBlockRegistry.register(88, 88);
        blockDataManager.registerBlock(88, "swamp_grass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("savanna_grass", 89);
        shaderBlockRegistry.register(89, 89);
        blockDataManager.registerBlock(89, "savanna_grass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("tundra_grass", 90);
        shaderBlockRegistry.register(90, 90);
        blockDataManager.registerBlock(90, "tundra_grass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("stone", 2);
        shaderBlockRegistry.register(2, 2);
        blockDataManager.registerBlock(2, "stone", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("glass", 3);
        shaderBlockRegistry.register(3, 3);
        blockDataManager.registerBlock(3, "glass", textureManager, "src/main/resources/assets/minecraft/models/block", 150, 50, 255);
        blockRegistry.register("oak_leaves", 4);
        shaderBlockRegistry.register(4, 4);
        blockDataManager.registerBlock(4, "oak_leaves", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("oak_log", 5);
        shaderBlockRegistry.register(5, 5);
        blockDataManager.registerBlock(5, "oak_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("dirt", 13);
        shaderBlockRegistry.register(13, 13);
        blockDataManager.registerBlock(13, "dirt", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("sand", 14);
        shaderBlockRegistry.register(14, 14);
        blockDataManager.registerBlock(14, "sand", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("water", 15);
        shaderBlockRegistry.register(15, 15);
        blockDataManager.registerBlock(15, "water", textureManager, "src/main/resources/assets/minecraft/models/block", 150, 100, 255);
        // ── 15 flowing water levels (150-164), each with its own height model ──
        // water_0 = source (ID 15, full block), water_1..water_15 = flowing (IDs 150-164)
        for (int level = 1; level <= 15; level++) {
            int id = 149 + level; // 150..164
            String name = "water_" + level;
            blockRegistry.register(name, id);
            shaderBlockRegistry.register(id, id);
            blockDataManager.registerBlock(id, name, textureManager, "src/main/resources/assets/minecraft/models/block", 150, 100, 255);
        }
        blockRegistry.register("obsidian", 16);
        shaderBlockRegistry.register(16, 16);
        blockDataManager.registerBlock(16, "obsidian", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("glowstone", 17);
        shaderBlockRegistry.register(17, 17);
        blockDataManager.registerBlock(17, "glowstone", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 0, 255, 255);
        blockDataManager.setLightColor(17, 255, 220, 150);
        blockRegistry.register("end_stone", 18);
        shaderBlockRegistry.register(18, 18);
        blockDataManager.registerBlock(18, "end_stone", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("nether_portal", 19);
        shaderBlockRegistry.register(19, 19);
        blockDataManager.registerBlock(19, "nether_portal", textureManager, "src/main/resources/assets/minecraft/models/block", 60, 0, 255, 180);
        blockDataManager.setLightColor(19, 150, 50, 220);
        // --- Nether Dimension Blocks ---
        blockRegistry.register("netherrack", 20);
        shaderBlockRegistry.register(20, 20);
        blockDataManager.registerBlock(20, "netherrack", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("lava", 21);
        shaderBlockRegistry.register(21, 21);
        blockDataManager.registerBlock(21, "lava", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 50, 255, 200);
        blockDataManager.setLightColor(21, 255, 150, 50);
        blockRegistry.register("soul_sand", 22);
        shaderBlockRegistry.register(22, 22);
        blockDataManager.registerBlock(22, "soul_sand", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("quartz_ore", 23);
        shaderBlockRegistry.register(23, 23);
        blockDataManager.registerBlock(23, "quartz_ore", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("nether_bricks", 24);
        shaderBlockRegistry.register(24, 24);
        blockDataManager.registerBlock(24, "nether_bricks", textureManager, "src/main/resources/assets/minecraft/models/block");
        // --- Redstone Blocks ---
        blockRegistry.register("redstone_block", 25);
        shaderBlockRegistry.register(25, 25);
        blockDataManager.registerBlock(25, "redstone_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("redstone_ore", 26);
        shaderBlockRegistry.register(26, 26);
        blockDataManager.registerBlock(26, "redstone_ore", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 0, 255, 40);
        blockDataManager.setLightColor(26, 255, 30, 30);
        blockRegistry.register("redstone_torch", 27);
        shaderBlockRegistry.register(27, 27);
        blockDataManager.registerBlock(27, "redstone_torch", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 0, 255, 200);
        blockDataManager.setLightColor(27, 255, 50, 50);
        blockRegistry.register("redstone_lamp", 28);
        shaderBlockRegistry.register(28, 28);
        blockDataManager.registerBlock(28, "redstone_lamp", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("redstone_wire", 29);
        shaderBlockRegistry.register(29, 29);
        blockDataManager.registerBlock(29, "redstone_wire", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("redstone_lamp_on", 30);
        shaderBlockRegistry.register(30, 30);
        blockDataManager.registerBlock(30, "redstone_lamp_on", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 0, 255, 255);
        blockDataManager.setLightColor(30, 255, 220, 150);
        // --- Piston Blocks ---
        // Directional piston blocks: each direction has its own block ID and model.
        // The model JSON assigns the piston-face texture to the correct face.
        // Direction mapping: 0=down, 1=up, 2=north, 3=south, 4=west, 5=east.
        // Normal piston (IDs 31, 264-268)
        blockRegistry.register("piston_normal", 31);              // facing up
        shaderBlockRegistry.register(31, 31);
        blockDataManager.registerBlock(31, "piston_normal", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(31).facingDirection = 0; // down
        blockRegistry.register("piston_normal_down", 264);
        shaderBlockRegistry.register(264, 264);
        blockDataManager.registerBlock(264, "piston_normal_down", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(264).facingDirection = 1; // up
        blockRegistry.register("piston_normal_north", 265);
        shaderBlockRegistry.register(265, 265);
        blockDataManager.registerBlock(265, "piston_normal_north", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(265).facingDirection = 2; // north
        blockRegistry.register("piston_normal_south", 266);
        shaderBlockRegistry.register(266, 266);
        blockDataManager.registerBlock(266, "piston_normal_south", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(266).facingDirection = 3; // south
        blockRegistry.register("piston_normal_west", 267);
        shaderBlockRegistry.register(267, 267);
        blockDataManager.registerBlock(267, "piston_normal_west", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(267).facingDirection = 4; // west
        blockRegistry.register("piston_normal_east", 268);
        shaderBlockRegistry.register(268, 268);
        blockDataManager.registerBlock(268, "piston_normal_east", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(268).facingDirection = 5; // east
        // Sticky piston (IDs 32, 269-273)
        blockRegistry.register("sticky_piston", 32);              // facing up
        shaderBlockRegistry.register(32, 32);
        blockDataManager.registerBlock(32, "sticky_piston", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(32).facingDirection = 0; // down
        blockRegistry.register("sticky_piston_down", 269);
        shaderBlockRegistry.register(269, 269);
        blockDataManager.registerBlock(269, "sticky_piston_down", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(269).facingDirection = 1; // up
        blockRegistry.register("sticky_piston_north", 270);
        shaderBlockRegistry.register(270, 270);
        blockDataManager.registerBlock(270, "sticky_piston_north", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(270).facingDirection = 2; // north
        blockRegistry.register("sticky_piston_south", 271);
        shaderBlockRegistry.register(271, 271);
        blockDataManager.registerBlock(271, "sticky_piston_south", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(271).facingDirection = 3; // south
        blockRegistry.register("sticky_piston_west", 272);
        shaderBlockRegistry.register(272, 272);
        blockDataManager.registerBlock(272, "sticky_piston_west", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(272).facingDirection = 4; // west
        blockRegistry.register("sticky_piston_east", 273);
        shaderBlockRegistry.register(273, 273);
        blockDataManager.registerBlock(273, "sticky_piston_east", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.blockRegistry.get(273).facingDirection = 5; // east
        blockRegistry.register("piston_head_normal", 33);
        shaderBlockRegistry.register(33, 33);
        blockDataManager.registerBlock(33, "piston_head_normal", textureManager, "src/main/resources/assets/minecraft/models/block");
        // Sticky piston head. RedstoneManager previously pointed at ID 34, which is
        // registered to poppy — extended sticky pistons placed a flower as their head.
        blockRegistry.register("piston_head_sticky", 259);
        shaderBlockRegistry.register(259, 259);
        blockDataManager.registerBlock(259, "piston_head_sticky", textureManager, "src/main/resources/assets/minecraft/models/block");
        // --- Aether Dimension Blocks ---
        String aetherModels = "src/main/resources/assets/aether/models/block";
        blockRegistry.register("aether_grass_block", 100);
        shaderBlockRegistry.register(100, 100);
        blockDataManager.registerBlock(100, "aether_grass_block", textureManager, aetherModels);
        blockRegistry.register("holystone", 101);
        shaderBlockRegistry.register(101, 101);
        blockDataManager.registerBlock(101, "holystone", textureManager, aetherModels);
        blockRegistry.register("aether_dirt", 102);
        shaderBlockRegistry.register(102, 102);
        blockDataManager.registerBlock(102, "aether_dirt", textureManager, aetherModels);
        blockRegistry.register("skyroot_log", 103);
        shaderBlockRegistry.register(103, 103);
        blockDataManager.registerBlock(103, "skyroot_log", textureManager, aetherModels);
        blockRegistry.register("skyroot_leaves", 104);
        shaderBlockRegistry.register(104, 104);
        blockDataManager.registerBlock(104, "skyroot_leaves", textureManager, aetherModels);
        blockRegistry.register("aerogel", 105);
        shaderBlockRegistry.register(105, 105);
        blockDataManager.registerBlock(105, "aerogel", textureManager, aetherModels, 120, 30, 255);
        blockRegistry.register("aether_portal_ns", 106);
        shaderBlockRegistry.register(106, 106);
        blockDataManager.registerBlock(106, "aether_portal_ns", textureManager, aetherModels, 0, 0, 255, 0);
        blockRegistry.register("aether_portal_ew", 127);
        shaderBlockRegistry.register(127, 127);
        blockDataManager.registerBlock(127, "aether_portal_ew", textureManager, aetherModels, 0, 0, 255, 0);
        
        blockRegistry.register("ambrosium_ore", 107);
        shaderBlockRegistry.register(107, 107);
        blockDataManager.registerBlock(107, "ambrosium_ore", textureManager, aetherModels);
        blockRegistry.register("gravitite_ore", 108);
        shaderBlockRegistry.register(108, 108);
        blockDataManager.registerBlock(108, "gravitite_ore", textureManager, aetherModels);
        blockRegistry.register("quicksoil", 109);
        shaderBlockRegistry.register(109, 109);
        blockDataManager.registerBlock(109, "quicksoil", textureManager, aetherModels);
        blockRegistry.register("icestone", 110);
        shaderBlockRegistry.register(110, 110);
        blockDataManager.registerBlock(110, "icestone", textureManager, aetherModels);
        blockRegistry.register("zanite_ore", 111);
        shaderBlockRegistry.register(111, 111);
        blockDataManager.registerBlock(111, "zanite_ore", textureManager, aetherModels);
        blockRegistry.register("skyroot_planks", 112);
        shaderBlockRegistry.register(112, 112);
        blockDataManager.registerBlock(112, "skyroot_planks", textureManager, aetherModels);
        blockRegistry.register("mossy_holystone", 113);
        shaderBlockRegistry.register(113, 113);
        blockDataManager.registerBlock(113, "mossy_holystone", textureManager, aetherModels);
        blockRegistry.register("holystone_bricks", 114);
        shaderBlockRegistry.register(114, 114);
        blockDataManager.registerBlock(114, "holystone_bricks", textureManager, aetherModels);
        // --- Functional Blocks (crafting table, furnace, chest) ---
        blockRegistry.register("crafting_table", 115);
        shaderBlockRegistry.register(115, 115);
        blockDataManager.registerBlock(115, "crafting_table", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("furnace_off", 116);
        shaderBlockRegistry.register(116, 116);
        blockDataManager.registerBlock(116, "furnace_off", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("furnace_on", 117);
        shaderBlockRegistry.register(117, 117);
        blockDataManager.registerBlock(117, "furnace_on", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 0, 255, 220);
        blockDataManager.setLightColor(117, 255, 150, 60);
        blockRegistry.register("chest", 118);
        shaderBlockRegistry.register(118, 118);
        blockDataManager.registerBlock(118, "chest", textureManager, "src/main/resources/assets/minecraft/models/block");
        // --- Vegetation & Decorative Blocks ---
        blockRegistry.register("birch_log", 119);
        shaderBlockRegistry.register(119, 119);
        blockDataManager.registerBlock(119, "birch_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("spruce_log", 120);
        shaderBlockRegistry.register(120, 120);
        blockDataManager.registerBlock(120, "spruce_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("dandelion", 121);
        shaderBlockRegistry.register(121, 121);
        blockDataManager.registerBlock(121, "dandelion", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("rose", 122);
        shaderBlockRegistry.register(122, 122);
        blockDataManager.registerBlock(122, "rose", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("tallgrass", 123);
        shaderBlockRegistry.register(123, 123);
        blockDataManager.registerBlock(123, "tallgrass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockRegistry.register("blue_aercloud", 124);
        shaderBlockRegistry.register(124, 124);
        blockDataManager.registerBlock(124, "blue_aercloud", textureManager, aetherModels, 100, 0, 255);
        blockRegistry.register("cold_aercloud", 125);
        shaderBlockRegistry.register(125, 125);
        blockDataManager.registerBlock(125, "cold_aercloud", textureManager, aetherModels, 100, 0, 255);
        blockRegistry.register("golden_aercloud", 126);
        shaderBlockRegistry.register(126, 126);
        blockDataManager.registerBlock(126, "golden_aercloud", textureManager, aetherModels, 100, 0, 255);
        // --- Biome decoration blocks (IDs 34-91 inclusive) ---
        String mcModels = "src/main/resources/assets/minecraft/models/block";
        blockRegistry.register("poppy", 34);
        shaderBlockRegistry.register(34, 34);
        blockDataManager.registerBlock(34, "poppy", textureManager, mcModels);
        blockRegistry.register("tallgrass", 35);
        shaderBlockRegistry.register(35, 35);
        blockDataManager.registerBlock(35, "tallgrass", textureManager, mcModels);
        blockRegistry.register("dead_bush", 36);
        shaderBlockRegistry.register(36, 36);
        blockDataManager.registerBlock(36, "dead_bush", textureManager, mcModels);
        blockRegistry.register("brown_mushroom", 37);
        shaderBlockRegistry.register(37, 37);
        blockDataManager.registerBlock(37, "brown_mushroom", textureManager, mcModels);
        blockRegistry.register("red_mushroom", 38);
        shaderBlockRegistry.register(38, 38);
        blockDataManager.registerBlock(38, "red_mushroom", textureManager, mcModels);
        blockRegistry.register("cactus", 39);
        shaderBlockRegistry.register(39, 39);
        blockDataManager.registerBlock(39, "cactus", textureManager, mcModels);
        blockRegistry.register("reeds", 40);
        shaderBlockRegistry.register(40, 40);
        blockDataManager.registerBlock(40, "reeds", textureManager, mcModels);
        blockRegistry.register("waterlily", 41);
        shaderBlockRegistry.register(41, 41);
        blockDataManager.registerBlock(41, "waterlily", textureManager, mcModels);
        // "waterlily" trips the name-based liquid heuristic ("water" substring) — reset it
        // to an opaque matte block so the fluid sim and raytracer treat lily pads as inert
        // foliage, not a second liquid that overwrites lake water (same fix as water_wheel).
        blockDataManager.setEffect(41, BlockDataManager.MaterialEffect.NONE);
        blockDataManager.setTransparency(41, 0);
        blockDataManager.setReflectivity(41, 0);
        blockRegistry.register("pumpkin", 42);
        shaderBlockRegistry.register(42, 42);
        blockDataManager.registerBlock(42, "pumpkin", textureManager, mcModels);
        blockRegistry.register("melon", 43);
        shaderBlockRegistry.register(43, 43);
        blockDataManager.registerBlock(43, "melon", textureManager, mcModels);
        blockRegistry.register("vine", 44);
        shaderBlockRegistry.register(44, 44);
        blockDataManager.registerBlock(44, "vine", textureManager, mcModels);
        blockRegistry.register("oak_sapling", 45);
        shaderBlockRegistry.register(45, 45);
        blockDataManager.registerBlock(45, "oak_sapling", textureManager, mcModels);
        blockRegistry.register("birch_log", 46);
        shaderBlockRegistry.register(46, 46);
        blockDataManager.registerBlock(46, "birch_log", textureManager, mcModels);
        blockRegistry.register("spruce_log", 47);
        shaderBlockRegistry.register(47, 47);
        blockDataManager.registerBlock(47, "spruce_log", textureManager, mcModels);
        blockRegistry.register("spruce_leaves", 48);
        shaderBlockRegistry.register(48, 48);
        blockDataManager.registerBlock(48, "spruce_leaves", textureManager, mcModels);
        blockRegistry.register("jungle_log", 49);
        shaderBlockRegistry.register(49, 49);
        blockDataManager.registerBlock(49, "jungle_log", textureManager, mcModels);
        blockRegistry.register("jungle_leaves", 50);
        shaderBlockRegistry.register(50, 50);
        blockDataManager.registerBlock(50, "jungle_leaves", textureManager, mcModels);
        blockRegistry.register("acacia_log", 51);
        shaderBlockRegistry.register(51, 51);
        blockDataManager.registerBlock(51, "acacia_log", textureManager, mcModels);
        blockRegistry.register("dark_oak_log", 52);
        shaderBlockRegistry.register(52, 52);
        blockDataManager.registerBlock(52, "dark_oak_log", textureManager, mcModels);
        blockRegistry.register("dark_oak_leaves", 53);
        shaderBlockRegistry.register(53, 53);
        blockDataManager.registerBlock(53, "dark_oak_leaves", textureManager, mcModels);
        blockRegistry.register("gravel", 54);
        shaderBlockRegistry.register(54, 54);
        blockDataManager.registerBlock(54, "gravel", textureManager, mcModels);
        blockRegistry.register("clay", 55);
        shaderBlockRegistry.register(55, 55);
        blockDataManager.registerBlock(55, "clay", textureManager, mcModels);
        blockRegistry.register("brown_mushroom_block", 56);
        shaderBlockRegistry.register(56, 56);
        blockDataManager.registerBlock(56, "brown_mushroom_block", textureManager, mcModels);
        blockRegistry.register("red_mushroom_block", 57);
        shaderBlockRegistry.register(57, 57);
        blockDataManager.registerBlock(57, "red_mushroom_block", textureManager, mcModels);
        blockRegistry.register("mushroom_stem", 58);
        shaderBlockRegistry.register(58, 58);
        blockDataManager.registerBlock(58, "mushroom_stem", textureManager, mcModels);
        blockRegistry.register("sandstone", 59);
        shaderBlockRegistry.register(59, 59);
        blockDataManager.registerBlock(59, "sandstone", textureManager, mcModels);
        blockRegistry.register("bone_block", 60);
        shaderBlockRegistry.register(60, 60);
        blockDataManager.registerBlock(60, "bone_block", textureManager, mcModels);
        blockRegistry.register("coal_ore", 61);
        shaderBlockRegistry.register(61, 61);
        blockDataManager.registerBlock(61, "coal_ore", textureManager, mcModels, 0, 0, 255, 25);
        blockDataManager.setLightColor(61, 255, 140, 50);
        blockRegistry.register("tulip", 62);
        shaderBlockRegistry.register(62, 62);
        blockDataManager.registerBlock(62, "tulip", textureManager, mcModels);
        blockRegistry.register("azure_bluet", 63);
        shaderBlockRegistry.register(63, 63);
        blockDataManager.registerBlock(63, "azure_bluet", textureManager, mcModels);
        blockRegistry.register("fern", 64);
        shaderBlockRegistry.register(64, 64);
        blockDataManager.registerBlock(64, "fern", textureManager, mcModels);
        blockRegistry.register("hardened_clay", 65);
        shaderBlockRegistry.register(65, 65);
        blockDataManager.registerBlock(65, "hardened_clay", textureManager, mcModels);
        blockRegistry.register("mycelium", 66);
        shaderBlockRegistry.register(66, 66);
        blockDataManager.registerBlock(66, "mycelium", textureManager, mcModels);
        blockRegistry.register("snow_layer", 67);
        shaderBlockRegistry.register(67, 67);
        blockDataManager.registerBlock(67, "snow_layer", textureManager, mcModels);
        // ── Snow layer levels (like water level models, height 1/8 to 8/8 of block) ──
        for (int level = 1; level <= 8; level++) {
            int id = 249 + level; // 250..257
            String name = "snow_" + level;
            String modelName = "snow_" + level; // uses snow_1..snow_8.json models
            blockRegistry.register(name, id);
            shaderBlockRegistry.register(id, id);
            blockDataManager.registerBlock(id, modelName, textureManager, mcModels);
        }
        blockRegistry.register("ice", 68);
        shaderBlockRegistry.register(68, 68);
        blockDataManager.registerBlock(68, "ice", textureManager, mcModels);
        blockRegistry.register("packed_ice", 69);
        shaderBlockRegistry.register(69, 69);
        blockDataManager.registerBlock(69, "packed_ice", textureManager, mcModels);
        blockRegistry.register("birch_sapling", 70);
        shaderBlockRegistry.register(70, 70);
        blockDataManager.registerBlock(70, "birch_sapling", textureManager, mcModels);
        blockRegistry.register("cobblestone", 71);
        shaderBlockRegistry.register(71, 71);
        blockDataManager.registerBlock(71, "cobblestone", textureManager, mcModels);
        blockRegistry.register("oak_planks", 72);
        shaderBlockRegistry.register(72, 72);
        blockDataManager.registerBlock(72, "oak_planks", textureManager, mcModels);
        blockRegistry.register("spruce_planks", 73);
        shaderBlockRegistry.register(73, 73);
        blockDataManager.registerBlock(73, "spruce_planks", textureManager, mcModels);
        blockRegistry.register("birch_planks", 74);
        shaderBlockRegistry.register(74, 74);
        blockDataManager.registerBlock(74, "birch_planks", textureManager, mcModels);
        blockRegistry.register("jungle_planks", 75);
        shaderBlockRegistry.register(75, 75);
        blockDataManager.registerBlock(75, "jungle_planks", textureManager, mcModels);
        blockRegistry.register("acacia_planks", 76);
        shaderBlockRegistry.register(76, 76);
        blockDataManager.registerBlock(76, "acacia_planks", textureManager, mcModels);
        blockRegistry.register("dark_oak_planks", 77);
        shaderBlockRegistry.register(77, 77);
        blockDataManager.registerBlock(77, "dark_oak_planks", textureManager, mcModels);
        blockRegistry.register("red_sand", 78);
        shaderBlockRegistry.register(78, 78);
        blockDataManager.registerBlock(78, "red_sand", textureManager, mcModels);
        blockRegistry.register("smooth_sandstone", 79);
        shaderBlockRegistry.register(79, 79);
        blockDataManager.registerBlock(79, "smooth_sandstone", textureManager, mcModels);
        blockRegistry.register("acacia_sapling", 80);
        shaderBlockRegistry.register(80, 80);
        blockDataManager.registerBlock(80, "acacia_sapling", textureManager, mcModels);
        blockRegistry.register("iron_ore", 81);
        shaderBlockRegistry.register(81, 81);
        blockDataManager.registerBlock(81, "iron_ore", textureManager, mcModels, 0, 0, 255, 20);
        blockDataManager.setLightColor(81, 220, 200, 180);
        blockRegistry.register("gold_ore", 82);
        shaderBlockRegistry.register(82, 82);
        blockDataManager.registerBlock(82, "gold_ore", textureManager, mcModels, 0, 0, 255, 35);
        blockDataManager.setLightColor(82, 255, 220, 80);
        blockRegistry.register("diamond_ore", 83);
        shaderBlockRegistry.register(83, 83);
        blockDataManager.registerBlock(83, "diamond_ore", textureManager, mcModels, 0, 0, 255, 40);
        blockDataManager.setLightColor(83, 100, 240, 255);
        blockRegistry.register("emerald_ore", 84);
        shaderBlockRegistry.register(84, 84);
        blockDataManager.registerBlock(84, "emerald_ore", textureManager, mcModels, 0, 0, 255, 30);
        blockDataManager.setLightColor(84, 80, 255, 80);
        blockRegistry.register("lapis_ore", 85);
        shaderBlockRegistry.register(85, 85);
        blockDataManager.registerBlock(85, "lapis_ore", textureManager, mcModels, 0, 0, 255, 25);
        blockDataManager.setLightColor(85, 40, 80, 255);
        blockRegistry.register("wool", 91);
        shaderBlockRegistry.register(91, 91);
        blockDataManager.registerBlock(91, "wool", textureManager, mcModels);

        // --- Staple Minecraft Blocks ---
        blockRegistry.register("brick", 130);
        shaderBlockRegistry.register(130, 130);
        blockDataManager.registerBlock(130, "brick", textureManager, mcModels);
        blockRegistry.register("stone_brick", 131);
        shaderBlockRegistry.register(131, 131);
        blockDataManager.registerBlock(131, "stonebrick_normal", textureManager, mcModels);
        blockRegistry.register("mossy_cobblestone", 132);
        shaderBlockRegistry.register(132, 132);
        blockDataManager.registerBlock(132, "mossy_cobblestone", textureManager, mcModels);
        blockRegistry.register("andesite", 133);
        shaderBlockRegistry.register(133, 133);
        blockDataManager.registerBlock(133, "andesite", textureManager, mcModels);
        blockRegistry.register("diorite", 134);
        shaderBlockRegistry.register(134, 134);
        blockDataManager.registerBlock(134, "diorite", textureManager, mcModels);
        blockRegistry.register("granite", 135);
        shaderBlockRegistry.register(135, 135);
        blockDataManager.registerBlock(135, "granite", textureManager, mcModels);
        blockRegistry.register("bookshelf", 136);
        shaderBlockRegistry.register(136, 136);
        blockDataManager.registerBlock(136, "bookshelf", textureManager, mcModels);
        blockRegistry.register("iron_block", 137);
        shaderBlockRegistry.register(137, 137);
        blockDataManager.registerBlock(137, "iron_block", textureManager, mcModels);
        blockRegistry.register("gold_block", 138);
        shaderBlockRegistry.register(138, 138);
        blockDataManager.registerBlock(138, "gold_block", textureManager, mcModels);
        blockRegistry.register("diamond_block", 139);
        shaderBlockRegistry.register(139, 139);
        blockDataManager.registerBlock(139, "diamond_block", textureManager, mcModels);
        blockRegistry.register("emerald_block", 140);
        shaderBlockRegistry.register(140, 140);
        blockDataManager.registerBlock(140, "emerald_block", textureManager, mcModels);
        blockRegistry.register("lapis_block", 141);
        shaderBlockRegistry.register(141, 141);
        blockDataManager.registerBlock(141, "lapis_block", textureManager, mcModels);

        // --- Create-inspired ores and metal blocks (copper + zinc) ---
        blockRegistry.register("copper_ore", 142);
        shaderBlockRegistry.register(142, 142);
        blockDataManager.registerBlock(142, "copper_ore", textureManager, mcModels, 0, 0, 255, 15);
        blockDataManager.setLightColor(142, 230, 140, 60);
        blockRegistry.register("copper_block", 143);
        shaderBlockRegistry.register(143, 143);
        blockDataManager.registerBlock(143, "copper_block", textureManager, mcModels);
        blockRegistry.register("zinc_ore", 144);
        shaderBlockRegistry.register(144, 144);
        blockDataManager.registerBlock(144, "zinc_ore", textureManager, mcModels, 0, 0, 255, 12);
        blockDataManager.setLightColor(144, 150, 220, 150);
        blockRegistry.register("zinc_block", 145);
        shaderBlockRegistry.register(145, 145);
        blockDataManager.registerBlock(145, "zinc_block", textureManager, mcModels);

        // --- Orientable log variants (axis chosen from clicked face at placement) ---
        // Every log type gets an X-axis and a Z-axis variant (same piston-style
        // per-face-texture approach as oak_log_x/z); placement picks the variant
        // from the clicked face normal, breaking always drops the base item.
        blockRegistry.register("oak_log_x", 260);
        shaderBlockRegistry.register(260, 260);
        blockDataManager.registerBlock(260, "oak_log_x", textureManager, mcModels);
        blockRegistry.register("oak_log_z", 261);
        shaderBlockRegistry.register(261, 261);
        blockDataManager.registerBlock(261, "oak_log_z", textureManager, mcModels);
        blockRegistry.register("birch_log_x", 438);
        shaderBlockRegistry.register(438, 438);
        blockDataManager.registerBlock(438, "birch_log_x", textureManager, mcModels);
        blockRegistry.register("birch_log_z", 439);
        shaderBlockRegistry.register(439, 439);
        blockDataManager.registerBlock(439, "birch_log_z", textureManager, mcModels);
        blockRegistry.register("spruce_log_x", 440);
        shaderBlockRegistry.register(440, 440);
        blockDataManager.registerBlock(440, "spruce_log_x", textureManager, mcModels);
        blockRegistry.register("spruce_log_z", 441);
        shaderBlockRegistry.register(441, 441);
        blockDataManager.registerBlock(441, "spruce_log_z", textureManager, mcModels);
        blockRegistry.register("jungle_log_x", 442);
        shaderBlockRegistry.register(442, 442);
        blockDataManager.registerBlock(442, "jungle_log_x", textureManager, mcModels);
        blockRegistry.register("jungle_log_z", 443);
        shaderBlockRegistry.register(443, 443);
        blockDataManager.registerBlock(443, "jungle_log_z", textureManager, mcModels);
        blockRegistry.register("acacia_log_x", 444);
        shaderBlockRegistry.register(444, 444);
        blockDataManager.registerBlock(444, "acacia_log_x", textureManager, mcModels);
        blockRegistry.register("acacia_log_z", 445);
        shaderBlockRegistry.register(445, 445);
        blockDataManager.registerBlock(445, "acacia_log_z", textureManager, mcModels);
        blockRegistry.register("dark_oak_log_x", 446);
        shaderBlockRegistry.register(446, 446);
        blockDataManager.registerBlock(446, "dark_oak_log_x", textureManager, mcModels);
        blockRegistry.register("dark_oak_log_z", 447);
        shaderBlockRegistry.register(447, 447);
        blockDataManager.registerBlock(447, "dark_oak_log_z", textureManager, mcModels);
        blockRegistry.register("skyroot_log_x", 448);
        shaderBlockRegistry.register(448, 448);
        blockDataManager.registerBlock(448, "skyroot_log_x", textureManager, aetherModels);
        blockRegistry.register("skyroot_log_z", 449);
        shaderBlockRegistry.register(449, 449);
        blockDataManager.registerBlock(449, "skyroot_log_z", textureManager, aetherModels);

        // --- Create-inspired blocks ---
        blockRegistry.register("andesite_casing", 262);
        shaderBlockRegistry.register(262, 262);
        blockDataManager.registerBlock(262, "andesite_casing", textureManager, mcModels);
        blockRegistry.register("encased_fan", 263);
        shaderBlockRegistry.register(263, 263);
        blockDataManager.registerBlock(263, "encased_fan", textureManager, mcModels);

        // Villager TV block (ID 274)
        blockRegistry.register("villager_tv", 274);
        shaderBlockRegistry.register(274, 274);
        blockDataManager.registerBlock(274, "villager_tv", textureManager, mcModels);

        // --- Ancient-builder command blocks and power-fragment block ---
        blockRegistry.register("command_block", 275);
        shaderBlockRegistry.register(275, 275);
        blockDataManager.registerBlock(275, "command_block", textureManager, mcModels);
        blockRegistry.register("chain_command_block", 276);
        shaderBlockRegistry.register(276, 276);
        blockDataManager.registerBlock(276, "chain_command_block", textureManager, mcModels);
        blockRegistry.register("repeating_command_block", 277);
        shaderBlockRegistry.register(277, 277);
        blockDataManager.registerBlock(277, "repeating_command_block", textureManager, mcModels);
        blockRegistry.register("power_fragment_block", 278);
        shaderBlockRegistry.register(278, 278);
        blockDataManager.registerBlock(278, "power_fragment_block", textureManager, mcModels);

        shaderBlockRegistry.registerDirectional(263, com.voxel.utils.Direction.DOWN, 263, 0);
        shaderBlockRegistry.registerDirectional(263, com.voxel.utils.Direction.UP, 263, 1);
        shaderBlockRegistry.registerDirectional(263, com.voxel.utils.Direction.NORTH, 263, 2);
        shaderBlockRegistry.registerDirectional(263, com.voxel.utils.Direction.SOUTH, 263, 3);
        shaderBlockRegistry.registerDirectional(263, com.voxel.utils.Direction.WEST, 263, 4);
        shaderBlockRegistry.registerDirectional(263, com.voxel.utils.Direction.EAST, 263, 5);

        // --- Stair Blocks ---
        blockRegistry.register("oak_stairs", 200);
        shaderBlockRegistry.register(200, 200);
        blockDataManager.registerBlock(200, "oak_stairs", textureManager, mcModels);
        blockRegistry.register("cobblestone_stairs", 201);
        shaderBlockRegistry.register(201, 201);
        blockDataManager.registerBlock(201, "stone_stairs", textureManager, mcModels);
        blockRegistry.register("stone_brick_stairs", 202);
        shaderBlockRegistry.register(202, 202);
        blockDataManager.registerBlock(202, "stone_brick_stairs", textureManager, mcModels);
        blockRegistry.register("brick_stairs", 203);
        shaderBlockRegistry.register(203, 203);
        blockDataManager.registerBlock(203, "brick_stairs", textureManager, mcModels);
        blockRegistry.register("sandstone_stairs", 204);
        shaderBlockRegistry.register(204, 204);
        blockDataManager.registerBlock(204, "sandstone_stairs", textureManager, mcModels);
        blockRegistry.register("nether_brick_stairs", 205);
        shaderBlockRegistry.register(205, 205);
        blockDataManager.registerBlock(205, "nether_brick_stairs", textureManager, mcModels);

        // --- Slab Blocks ---
        blockRegistry.register("oak_slab", 206);
        shaderBlockRegistry.register(206, 206);
        blockDataManager.registerBlock(206, "half_slab_oak", textureManager, mcModels);
        blockRegistry.register("cobblestone_slab", 207);
        shaderBlockRegistry.register(207, 207);
        blockDataManager.registerBlock(207, "half_slab_cobblestone", textureManager, mcModels);
        blockRegistry.register("stone_brick_slab", 208);
        shaderBlockRegistry.register(208, 208);
        blockDataManager.registerBlock(208, "half_slab_stone_brick", textureManager, mcModels);
        blockRegistry.register("brick_slab", 209);
        shaderBlockRegistry.register(209, 209);
        blockDataManager.registerBlock(209, "half_slab_brick", textureManager, mcModels);
        blockRegistry.register("sandstone_slab", 210);
        shaderBlockRegistry.register(210, 210);
        blockDataManager.registerBlock(210, "half_slab_sandstone", textureManager, mcModels);

        // --- Spawner (mob spawner cage) ---
        blockRegistry.register("spawner", 258);
        shaderBlockRegistry.register(258, 258);
        blockDataManager.registerBlock(258, "spawner", textureManager, mcModels);

        // --- Torch ---
        blockRegistry.register("torch", 211);
        shaderBlockRegistry.register(211, 211);
        blockDataManager.registerBlock(211, "normal_torch", textureManager, mcModels, 0, 0, 255, 12);
        blockDataManager.setLightColor(211, 255, 220, 140);

        // --- Flat item models (for crafting-table item rendering) ---
        blockRegistry.register("item_flint", 212);
        shaderBlockRegistry.register(212, 212);
        blockDataManager.registerBlock(212, "item_flint", textureManager, mcModels);
        blockRegistry.register("item_iron_ingot", 213);
        shaderBlockRegistry.register(213, 213);
        blockDataManager.registerBlock(213, "item_iron_ingot", textureManager, mcModels);
        blockRegistry.register("item_stick", 214);
        shaderBlockRegistry.register(214, 214);
        blockDataManager.registerBlock(214, "item_stick", textureManager, mcModels);
        blockRegistry.register("item_flint_and_steel", 215);
        shaderBlockRegistry.register(215, 215);
        blockDataManager.registerBlock(215, "item_flint_and_steel", textureManager, mcModels);
        blockRegistry.register("item_bucket", 216);
        shaderBlockRegistry.register(216, 216);
        blockDataManager.registerBlock(216, "item_bucket", textureManager, mcModels);
        blockRegistry.register("item_water_bucket", 217);
        shaderBlockRegistry.register(217, 217);
        blockDataManager.registerBlock(217, "item_water_bucket", textureManager, mcModels);
        blockRegistry.register("item_lava_bucket", 218);
        shaderBlockRegistry.register(218, 218);
        blockDataManager.registerBlock(218, "item_lava_bucket", textureManager, mcModels);
        // --- Tool item models (pickaxes, shovels, axes) ---
        blockRegistry.register("item_wood_pickaxe", 219);
        shaderBlockRegistry.register(219, 219);
        blockDataManager.registerBlock(219, "item_wood_pickaxe", textureManager, mcModels);
        blockRegistry.register("item_wood_shovel", 220);
        shaderBlockRegistry.register(220, 220);
        blockDataManager.registerBlock(220, "item_wood_shovel", textureManager, mcModels);
        blockRegistry.register("item_wood_axe", 221);
        shaderBlockRegistry.register(221, 221);
        blockDataManager.registerBlock(221, "item_wood_axe", textureManager, mcModels);
        blockRegistry.register("item_stone_pickaxe", 222);
        shaderBlockRegistry.register(222, 222);
        blockDataManager.registerBlock(222, "item_stone_pickaxe", textureManager, mcModels);
        blockRegistry.register("item_stone_shovel", 223);
        shaderBlockRegistry.register(223, 223);
        blockDataManager.registerBlock(223, "item_stone_shovel", textureManager, mcModels);
        blockRegistry.register("item_stone_axe", 224);
        shaderBlockRegistry.register(224, 224);
        blockDataManager.registerBlock(224, "item_stone_axe", textureManager, mcModels);
        blockRegistry.register("item_iron_pickaxe", 225);
        shaderBlockRegistry.register(225, 225);
        blockDataManager.registerBlock(225, "item_iron_pickaxe", textureManager, mcModels);
        blockRegistry.register("item_iron_shovel", 226);
        shaderBlockRegistry.register(226, 226);
        blockDataManager.registerBlock(226, "item_iron_shovel", textureManager, mcModels);
        blockRegistry.register("item_iron_axe", 227);
        shaderBlockRegistry.register(227, 227);
        blockDataManager.registerBlock(227, "item_iron_axe", textureManager, mcModels);
        blockRegistry.register("item_diamond_pickaxe", 228);
        shaderBlockRegistry.register(228, 228);
        blockDataManager.registerBlock(228, "item_diamond_pickaxe", textureManager, mcModels);
        blockRegistry.register("item_diamond_shovel", 229);
        shaderBlockRegistry.register(229, 229);
        blockDataManager.registerBlock(229, "item_diamond_shovel", textureManager, mcModels);
        blockRegistry.register("item_diamond_axe", 230);
        shaderBlockRegistry.register(230, 230);
        blockDataManager.registerBlock(230, "item_diamond_axe", textureManager, mcModels);
        // --- Drop item models (vertical planes for dropped items in the world) ---
        blockRegistry.register("item_drop_flint", 231);
        shaderBlockRegistry.register(231, 231);
        blockDataManager.registerBlock(231, "item_drop_flint", textureManager, mcModels);
        blockRegistry.register("item_drop_iron_ingot", 232);
        shaderBlockRegistry.register(232, 232);
        blockDataManager.registerBlock(232, "item_drop_iron_ingot", textureManager, mcModels);
        blockRegistry.register("item_drop_stick", 233);
        shaderBlockRegistry.register(233, 233);
        blockDataManager.registerBlock(233, "item_drop_stick", textureManager, mcModels);
        blockRegistry.register("item_drop_flint_and_steel", 234);
        shaderBlockRegistry.register(234, 234);
        blockDataManager.registerBlock(234, "item_drop_flint_and_steel", textureManager, mcModels);
        blockRegistry.register("item_drop_bucket", 235);
        shaderBlockRegistry.register(235, 235);
        blockDataManager.registerBlock(235, "item_drop_bucket", textureManager, mcModels);
        blockRegistry.register("item_drop_water_bucket", 236);
        shaderBlockRegistry.register(236, 236);
        blockDataManager.registerBlock(236, "item_drop_water_bucket", textureManager, mcModels);
        blockRegistry.register("item_drop_lava_bucket", 237);
        shaderBlockRegistry.register(237, 237);
        blockDataManager.registerBlock(237, "item_drop_lava_bucket", textureManager, mcModels);
        blockRegistry.register("item_drop_wood_pickaxe", 238);
        shaderBlockRegistry.register(238, 238);
        blockDataManager.registerBlock(238, "item_drop_wood_pickaxe", textureManager, mcModels);
        blockRegistry.register("item_drop_wood_shovel", 239);
        shaderBlockRegistry.register(239, 239);
        blockDataManager.registerBlock(239, "item_drop_wood_shovel", textureManager, mcModels);
        blockRegistry.register("item_drop_wood_axe", 240);
        shaderBlockRegistry.register(240, 240);
        blockDataManager.registerBlock(240, "item_drop_wood_axe", textureManager, mcModels);
        blockRegistry.register("item_drop_stone_pickaxe", 241);
        shaderBlockRegistry.register(241, 241);
        blockDataManager.registerBlock(241, "item_drop_stone_pickaxe", textureManager, mcModels);
        blockRegistry.register("item_drop_stone_shovel", 242);
        shaderBlockRegistry.register(242, 242);
        blockDataManager.registerBlock(242, "item_drop_stone_shovel", textureManager, mcModels);
        blockRegistry.register("item_drop_stone_axe", 243);
        shaderBlockRegistry.register(243, 243);
        blockDataManager.registerBlock(243, "item_drop_stone_axe", textureManager, mcModels);
        blockRegistry.register("item_drop_iron_pickaxe", 244);
        shaderBlockRegistry.register(244, 244);
        blockDataManager.registerBlock(244, "item_drop_iron_pickaxe", textureManager, mcModels);
        blockRegistry.register("item_drop_iron_shovel", 245);
        shaderBlockRegistry.register(245, 245);
        blockDataManager.registerBlock(245, "item_drop_iron_shovel", textureManager, mcModels);
        blockRegistry.register("item_drop_iron_axe", 246);
        shaderBlockRegistry.register(246, 246);
        blockDataManager.registerBlock(246, "item_drop_iron_axe", textureManager, mcModels);
        blockRegistry.register("item_drop_diamond_pickaxe", 247);
        shaderBlockRegistry.register(247, 247);
        blockDataManager.registerBlock(247, "item_drop_diamond_pickaxe", textureManager, mcModels);
        blockRegistry.register("item_drop_diamond_shovel", 248);
        shaderBlockRegistry.register(248, 248);
        blockDataManager.registerBlock(248, "item_drop_diamond_shovel", textureManager, mcModels);
        blockRegistry.register("item_drop_diamond_axe", 249);
        shaderBlockRegistry.register(249, 249);
        blockDataManager.registerBlock(249, "item_drop_diamond_axe", textureManager, mcModels);

        // --- New material items (coal, diamond, gold/copper/zinc ingots, charcoal) ---
        blockRegistry.register("item_coal", 279);
        shaderBlockRegistry.register(279, 279);
        blockDataManager.registerBlock(279, "item_coal", textureManager, mcModels);
        blockRegistry.register("item_diamond", 280);
        shaderBlockRegistry.register(280, 280);
        blockDataManager.registerBlock(280, "item_diamond", textureManager, mcModels);
        blockRegistry.register("item_gold_ingot", 281);
        shaderBlockRegistry.register(281, 281);
        blockDataManager.registerBlock(281, "item_gold_ingot", textureManager, mcModels);
        blockRegistry.register("item_copper_ingot", 282);
        shaderBlockRegistry.register(282, 282);
        blockDataManager.registerBlock(282, "item_copper_ingot", textureManager, mcModels);
        blockRegistry.register("item_zinc_ingot", 283);
        shaderBlockRegistry.register(283, 283);
        blockDataManager.registerBlock(283, "item_zinc_ingot", textureManager, mcModels);
        blockRegistry.register("item_charcoal", 284);
        shaderBlockRegistry.register(284, 284);
        blockDataManager.registerBlock(284, "item_charcoal", textureManager, mcModels);
        blockRegistry.register("item_drop_coal", 285);
        shaderBlockRegistry.register(285, 285);
        blockDataManager.registerBlock(285, "item_drop_coal", textureManager, mcModels);
        blockRegistry.register("item_drop_diamond", 286);
        shaderBlockRegistry.register(286, 286);
        blockDataManager.registerBlock(286, "item_drop_diamond", textureManager, mcModels);
        blockRegistry.register("item_drop_gold_ingot", 287);
        shaderBlockRegistry.register(287, 287);
        blockDataManager.registerBlock(287, "item_drop_gold_ingot", textureManager, mcModels);
        blockRegistry.register("item_drop_copper_ingot", 288);
        shaderBlockRegistry.register(288, 288);
        blockDataManager.registerBlock(288, "item_drop_copper_ingot", textureManager, mcModels);
        blockRegistry.register("item_drop_zinc_ingot", 289);
        shaderBlockRegistry.register(289, 289);
        blockDataManager.registerBlock(289, "item_drop_zinc_ingot", textureManager, mcModels);
        blockRegistry.register("item_drop_charcoal", 290);
        shaderBlockRegistry.register(290, 290);
        blockDataManager.registerBlock(290, "item_drop_charcoal", textureManager, mcModels);

        // --- Create-inspired kinetic blocks (shafts, cogs, water wheel) ---
        // Orientable shafts: 291 = vertical (Y), 292 = X axis, 293 = Z axis.
        blockRegistry.register("shaft", 291);
        shaderBlockRegistry.register(291, 291);
        blockDataManager.registerBlock(291, "shaft", textureManager, mcModels);
        // Full block for collision/placement: the visual is a thin rod, but the
        // cell must be solid so blocks can be placed against it and the player
        // can't stand inside it (which previously broke placement entirely).
        blockDataManager.setFullBlock(291, true);
        blockDataManager.setRotating(291, KINETIC_RPS);
        blockRegistry.register("shaft_x", 292);
        shaderBlockRegistry.register(292, 292);
        blockDataManager.registerBlock(292, "shaft_x", textureManager, mcModels);
        blockDataManager.setFullBlock(292, true);
        blockDataManager.setRotating(292, KINETIC_RPS);
        blockRegistry.register("shaft_z", 293);
        shaderBlockRegistry.register(293, 293);
        blockDataManager.registerBlock(293, "shaft_z", textureManager, mcModels);
        blockDataManager.setFullBlock(293, true);
        blockDataManager.setRotating(293, KINETIC_RPS);
        blockRegistry.register("cogwheel", 294);
        shaderBlockRegistry.register(294, 294);
        blockDataManager.registerBlock(294, "cogwheel", textureManager, mcModels);
        blockDataManager.setFullBlock(294, true);
        blockDataManager.setRotating(294, KINETIC_RPS);
        blockRegistry.register("large_cogwheel", 295);
        shaderBlockRegistry.register(295, 295);
        blockDataManager.registerBlock(295, "large_cogwheel", textureManager, mcModels);
        blockDataManager.setFullBlock(295, true);
        blockDataManager.setRotating(295, KINETIC_RPS);
        // Large cogwheel multiblock parts (422-429): one per cell of the 3x1x3
        // footprint around the center (295). Each renders its own slice of the
        // 3-block gear via the raytracer's getGear() center-offset + per-cell clip.
        String[] largeCogParts = {
            "large_cogwheel_n", "large_cogwheel_s", "large_cogwheel_w", "large_cogwheel_e",
            "large_cogwheel_nw", "large_cogwheel_ne", "large_cogwheel_sw", "large_cogwheel_se"
        };
        for (int i = 0; i < largeCogParts.length; i++) {
            int partId = 422 + i;
            blockRegistry.register(largeCogParts[i], partId);
            shaderBlockRegistry.register(partId, partId);
            blockDataManager.registerBlock(partId, "large_cogwheel_part", textureManager, mcModels);
            blockDataManager.setFullBlock(partId, true);
            blockDataManager.setRotating(partId, KINETIC_RPS);
        }
        blockRegistry.register("water_wheel", 296);
        shaderBlockRegistry.register(296, 296);
        blockDataManager.registerBlock(296, "water_wheel", textureManager, mcModels);
        blockDataManager.setFullBlock(296, true);
        // "water_wheel" trips the name-based liquid heuristic — reset it to an
        // opaque, matte block so the shader draws it as a wooden wheel, not water.
        blockDataManager.setEffect(296, BlockDataManager.MaterialEffect.NONE);
        blockDataManager.setTransparency(296, 0);
        blockDataManager.setReflectivity(296, 0);
        blockDataManager.setRotating(296, KINETIC_RPS);
        // Water wheel multiblock parts (430-437): one per cell of the 3x3x1
        // footprint around the center (296), rendered the same way as the large
        // cogwheel parts (getGear center-offset + per-cell clip).
        String[] waterWheelParts = {
            "water_wheel_up", "water_wheel_down", "water_wheel_left", "water_wheel_right",
            "water_wheel_upleft", "water_wheel_upright", "water_wheel_downleft", "water_wheel_downright"
        };
        for (int i = 0; i < waterWheelParts.length; i++) {
            int partId = 430 + i;
            blockRegistry.register(waterWheelParts[i], partId);
            shaderBlockRegistry.register(partId, partId);
            blockDataManager.registerBlock(partId, "water_wheel_part", textureManager, mcModels);
            blockDataManager.setFullBlock(partId, true);
            blockDataManager.setEffect(partId, BlockDataManager.MaterialEffect.NONE);
            blockDataManager.setTransparency(partId, 0);
            blockDataManager.setReflectivity(partId, 0);
            blockDataManager.setRotating(partId, KINETIC_RPS);
        }

        // --- Colored redstone lamps (297-328): off = 297+2c, on = 297+2c+1 ---
        String[] lampColors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                               "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        for (int c = 0; c < lampColors.length; c++) {
            String col = lampColors[c];
            int offId = 297 + c * 2;
            int onId = offId + 1;
            blockRegistry.register("lamp_" + col, offId);
            shaderBlockRegistry.register(offId, offId);
            blockDataManager.registerBlock(offId, "lamp_" + col + "_off", textureManager, mcModels);
            shaderBlockRegistry.registerOnOff(offId, false, offId);
            shaderBlockRegistry.registerOnOff(offId, true, onId);
            blockRegistry.register("lamp_" + col + "_on", onId);
            shaderBlockRegistry.register(onId, onId);
            blockDataManager.registerBlock(onId, "lamp_" + col + "_on", textureManager, mcModels, 0, 0, 255, 255);
        }

        // --- Redstone repeaters (329-336): off = 329-332 (n/s/w/e), on = 333-336 ---
        String[] hDirs = {"north", "south", "west", "east"};
        for (int d = 0; d < 4; d++) {
            String dir = hDirs[d];
            int offId = 329 + d;
            int onId = 333 + d;
            blockRegistry.register("repeater_" + dir, offId);
            shaderBlockRegistry.register(offId, offId);
            blockDataManager.registerBlock(offId, "repeater_" + dir, textureManager, mcModels);
            blockDataManager.setFullBlock(offId, false);
            blockRegistry.register("repeater_" + dir + "_on", onId);
            shaderBlockRegistry.register(onId, onId);
            blockDataManager.registerBlock(onId, "repeater_" + dir + "_on", textureManager, mcModels, 0, 0, 255, 100);
            blockDataManager.setFullBlock(onId, false);
        }

        // --- Redstone comparators (337-352): off/on/off-sub/on-sub per direction ---
        String[] compStates = {"", "_on", "_sub", "_sub_on"};
        for (int d = 0; d < 4; d++) {
            String dir = hDirs[d];
            for (int s = 0; s < 4; s++) {
                int id = 337 + d + s * 4;
                String name = "comparator_" + dir + compStates[s];
                blockRegistry.register(name, id);
                shaderBlockRegistry.register(id, id);
                blockDataManager.registerBlock(id, name, textureManager, mcModels);
                blockDataManager.setFullBlock(id, false);
            }
        }

        // --- Create clutches + gearshifts (353-356) ---
        blockRegistry.register("clutch", 353);
        shaderBlockRegistry.register(353, 353);
        blockDataManager.registerBlock(353, "clutch", textureManager, mcModels);
        blockRegistry.register("clutch_on", 354);
        shaderBlockRegistry.register(354, 354);
        blockDataManager.registerBlock(354, "clutch_on", textureManager, mcModels);
        blockRegistry.register("gearshift", 355);
        shaderBlockRegistry.register(355, 355);
        blockDataManager.registerBlock(355, "gearshift", textureManager, mcModels);
        blockRegistry.register("gearshift_on", 356);
        shaderBlockRegistry.register(356, 356);
        blockDataManager.registerBlock(356, "gearshift_on", textureManager, mcModels);

        // --- Dye items (357-388) + nether quartz (389-390) ---
        String[] dyeTints = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                             "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        for (int c = 0; c < dyeTints.length; c++) {
            String col = dyeTints[c];
            String tex = col.equals("light_gray") ? "dye_powder_silver" : "dye_powder_" + col;
            int itemId = 357 + c;
            int dropId = 373 + c;
            blockRegistry.register("item_" + col + "_dye", itemId);
            shaderBlockRegistry.register(itemId, itemId);
            blockDataManager.registerBlock(itemId, "item_" + col + "_dye", textureManager, mcModels);
            blockRegistry.register("item_drop_" + col + "_dye", dropId);
            shaderBlockRegistry.register(dropId, dropId);
            blockDataManager.registerBlock(dropId, "item_drop_" + col + "_dye", textureManager, mcModels);
        }
        blockRegistry.register("item_quartz", 389);
        shaderBlockRegistry.register(389, 389);
        blockDataManager.registerBlock(389, "item_quartz", textureManager, mcModels);
        blockRegistry.register("item_drop_quartz", 390);
        shaderBlockRegistry.register(390, 390);
        blockDataManager.registerBlock(390, "item_drop_quartz", textureManager, mcModels);

        // --- Rails and minecarts ---
        blockRegistry.register("rail_ns", 391);
        shaderBlockRegistry.register(391, 391);
        blockDataManager.registerBlock(391, "rail_ns", textureManager, mcModels);
        blockRegistry.register("rail_ew", 392);
        shaderBlockRegistry.register(392, 392);
        blockDataManager.registerBlock(392, "rail_ew", textureManager, mcModels);
        // Curved corner rails (Beta 1.7.3 metadata 6-9): each connects exactly one
        // N-S and one E-W neighbour. Placement auto-creates them at corners and
        // converts neighbours (see BlockInteraction.refreshRailShapes).
        // IDs 450-453 (393-397 are taken by item_minecart/blaze burners/steam engine).
        blockRegistry.register("rail_curve_se", 450);
        shaderBlockRegistry.register(450, 450);
        blockDataManager.registerBlock(450, "rail_curve_se", textureManager, mcModels);
        blockRegistry.register("rail_curve_sw", 451);
        shaderBlockRegistry.register(451, 451);
        blockDataManager.registerBlock(451, "rail_curve_sw", textureManager, mcModels);
        blockRegistry.register("rail_curve_nw", 452);
        shaderBlockRegistry.register(452, 452);
        blockDataManager.registerBlock(452, "rail_curve_nw", textureManager, mcModels);
        blockRegistry.register("rail_curve_ne", 453);
        shaderBlockRegistry.register(453, 453);
        blockDataManager.registerBlock(453, "rail_curve_ne", textureManager, mcModels);
        blockRegistry.register("item_minecart", 393);
        shaderBlockRegistry.register(393, 393);
        blockDataManager.registerBlock(393, "item_minecart", textureManager, mcModels);

        // --- Blaze burner / steam engine / copper tank ---
        blockRegistry.register("blaze_burner", 394);
        shaderBlockRegistry.registerOnOff(394, true, 395);
        blockDataManager.registerBlock(394, "blaze_burner", textureManager, mcModels);
        blockRegistry.register("blaze_burner_lit", 395);
        shaderBlockRegistry.register(395, 395);
        blockDataManager.registerBlock(395, "blaze_burner_lit", textureManager, mcModels);
        blockRegistry.register("steam_engine", 396);
        shaderBlockRegistry.registerOnOff(396, true, 397);
        blockDataManager.registerBlock(396, "steam_engine", textureManager, mcModels);
        blockRegistry.register("steam_engine_active", 397);
        shaderBlockRegistry.register(397, 397);
        blockDataManager.registerBlock(397, "steam_engine_active", textureManager, mcModels);
        for (int lev = 0; lev <= 5; lev++) {
            int id = 398 + lev;
            String modelFile = lev == 0 ? "copper_tank" : "copper_tank_" + lev;
            blockRegistry.register(modelFile, id);
            shaderBlockRegistry.register(id, id);
            blockDataManager.registerBlock(id, modelFile, textureManager, mcModels);
        }

        // --- Create machines (404-415) ---
        blockRegistry.register("hand_crank", 404);
        shaderBlockRegistry.register(404, 404);
        blockDataManager.registerBlock(404, "hand_crank", textureManager, mcModels);
        blockDataManager.setFullBlock(404, false);
        blockRegistry.register("windmill_bearing", 405);
        shaderBlockRegistry.register(405, 405);
        blockDataManager.registerBlock(405, "windmill_bearing", textureManager, mcModels);
        blockRegistry.register("windmill_sail", 406);
        shaderBlockRegistry.register(406, 406);
        blockDataManager.registerBlock(406, "windmill_sail", textureManager, mcModels);
        blockDataManager.setFullBlock(406, false); // thin canvas panel (AABB model)
        blockRegistry.register("mechanical_press", 407);
        shaderBlockRegistry.register(407, 407);
        blockDataManager.registerBlock(407, "mechanical_press", textureManager, mcModels);
        blockRegistry.register("millstone", 408);
        shaderBlockRegistry.register(408, 408);
        blockDataManager.registerBlock(408, "millstone", textureManager, mcModels);
        blockRegistry.register("crushing_wheel", 409);
        shaderBlockRegistry.register(409, 409);
        blockDataManager.registerBlock(409, "crushing_wheel", textureManager, mcModels);
        blockRegistry.register("mechanical_drill", 410);
        shaderBlockRegistry.register(410, 410);
        blockDataManager.registerBlock(410, "mechanical_drill", textureManager, mcModels);
        blockRegistry.register("mechanical_saw", 411);
        shaderBlockRegistry.register(411, 411);
        blockDataManager.registerBlock(411, "mechanical_saw", textureManager, mcModels);
        blockRegistry.register("deployer", 412);
        shaderBlockRegistry.register(412, 412);
        blockDataManager.registerBlock(412, "deployer", textureManager, mcModels);
        blockRegistry.register("belt_conveyor", 413);
        shaderBlockRegistry.register(413, 413);
        blockDataManager.registerBlock(413, "belt_conveyor", textureManager, mcModels);
        blockDataManager.setFullBlock(413, false);
        blockRegistry.register("item_vault", 414);
        shaderBlockRegistry.register(414, 414);
        blockDataManager.registerBlock(414, "item_vault", textureManager, mcModels);
        blockRegistry.register("brass_casing", 415);
        shaderBlockRegistry.register(415, 415);
        blockDataManager.registerBlock(415, "brass_casing", textureManager, mcModels);

        // --- Create tools & materials (416-421) ---
        blockRegistry.register("item_wrench", 416);
        shaderBlockRegistry.register(416, 416);
        blockDataManager.registerBlock(416, "item_wrench", textureManager, mcModels);
        blockRegistry.register("item_drop_wrench", 417);
        shaderBlockRegistry.register(417, 417);
        blockDataManager.registerBlock(417, "item_drop_wrench", textureManager, mcModels);
        blockRegistry.register("item_goggles", 418);
        shaderBlockRegistry.register(418, 418);
        blockDataManager.registerBlock(418, "item_goggles", textureManager, mcModels);
        blockRegistry.register("item_drop_goggles", 419);
        shaderBlockRegistry.register(419, 419);
        blockDataManager.registerBlock(419, "item_drop_goggles", textureManager, mcModels);
        blockRegistry.register("item_brass_ingot", 420);
        shaderBlockRegistry.register(420, 420);
        blockDataManager.registerBlock(420, "item_brass_ingot", textureManager, mcModels);
        blockRegistry.register("item_drop_brass_ingot", 421);
        shaderBlockRegistry.register(421, 421);
        blockDataManager.registerBlock(421, "item_drop_brass_ingot", textureManager, mcModels);

        // --- Additional Minecraft utility and End blocks (454-471) ---
        // These use the native Minecraft block models already present in the resource pack.
        blockRegistry.register("bedrock", 454);
        shaderBlockRegistry.register(454, 454);
        blockDataManager.registerBlock(454, "bedrock", textureManager, mcModels);

        blockRegistry.register("coal_block", 455);
        shaderBlockRegistry.register(455, 455);
        blockDataManager.registerBlock(455, "coal_block", textureManager, mcModels);

        blockRegistry.register("anvil", 456);
        shaderBlockRegistry.register(456, 456);
        blockDataManager.registerBlock(456, "anvil_undamaged", textureManager, mcModels);
        blockDataManager.setFullBlock(456, false);

        blockRegistry.register("beacon", 457);
        shaderBlockRegistry.register(457, 457);
        blockDataManager.registerBlock(457, "beacon", textureManager, mcModels, 80, 20, 255, 80);
        blockDataManager.setLightColor(457, 120, 220, 255);

        blockRegistry.register("enchanting_table", 458);
        shaderBlockRegistry.register(458, 458);
        blockDataManager.registerBlock(458, "enchanting_table_base", textureManager, mcModels);

        blockRegistry.register("dispenser", 459);
        shaderBlockRegistry.register(459, 459);
        blockDataManager.registerBlock(459, "dispenser", textureManager, mcModels);

        blockRegistry.register("dropper", 460);
        shaderBlockRegistry.register(460, 460);
        blockDataManager.registerBlock(460, "dropper", textureManager, mcModels);

        blockRegistry.register("hopper", 461);
        shaderBlockRegistry.register(461, 461);
        blockDataManager.registerBlock(461, "hopper_down", textureManager, mcModels);
        blockDataManager.setFullBlock(461, false);

        blockRegistry.register("cauldron", 462);
        shaderBlockRegistry.register(462, 462);
        blockDataManager.registerBlock(462, "cauldron_empty", textureManager, mcModels);
        blockDataManager.setFullBlock(462, false);

        blockRegistry.register("brewing_stand", 463);
        shaderBlockRegistry.register(463, 463);
        blockDataManager.registerBlock(463, "brewing_stand", textureManager, mcModels);
        blockDataManager.setFullBlock(463, false);

        blockRegistry.register("end_bricks", 464);
        shaderBlockRegistry.register(464, 464);
        blockDataManager.registerBlock(464, "end_bricks", textureManager, mcModels);

        blockRegistry.register("end_rod", 465);
        shaderBlockRegistry.register(465, 465);
        blockDataManager.registerBlock(465, "end_rod", textureManager, mcModels, 0, 0, 255, 180);
        blockDataManager.setLightColor(465, 220, 180, 255);
        blockDataManager.setFullBlock(465, false);

        blockRegistry.register("dragon_egg", 466);
        shaderBlockRegistry.register(466, 466);
        blockDataManager.registerBlock(466, "dragon_egg", textureManager, mcModels);
        blockDataManager.setFullBlock(466, false);

        blockRegistry.register("prismarine", 467);
        shaderBlockRegistry.register(467, 467);
        blockDataManager.registerBlock(467, "prismarine_rough", textureManager, mcModels);

        blockRegistry.register("prismarine_bricks", 468);
        shaderBlockRegistry.register(468, 468);
        blockDataManager.registerBlock(468, "prismarine_bricks", textureManager, mcModels);

        blockRegistry.register("dark_prismarine", 469);
        shaderBlockRegistry.register(469, 469);
        blockDataManager.registerBlock(469, "prismarine_dark", textureManager, mcModels);

        blockRegistry.register("sea_lantern", 470);
        shaderBlockRegistry.register(470, 470);
        blockDataManager.registerBlock(470, "sea_lantern", textureManager, mcModels, 0, 0, 255, 220);
        blockDataManager.setLightColor(470, 150, 240, 255);

        blockRegistry.register("iron_bars", 471);
        shaderBlockRegistry.register(471, 471);
        blockDataManager.registerBlock(471, "iron_bars_post", textureManager, mcModels, 120, 0, 255);
        blockDataManager.setFullBlock(471, false);

        // Auto-register every remaining vanilla 1.12.2 blockstate from the resource pack.
        // Hand-authored IDs above remain authoritative; new content gets stable IDs
        // after the existing registry and reuses the native Minecraft models/textures.
        com.voxel.utils.MinecraftContentLoader.registerMissingBlocks(
                blockDataManager, blockRegistry, shaderBlockRegistry, textureManager,
                "src/main/resources/assets/minecraft/blockstates",
                "src/main/resources/assets/minecraft/models/block", 472);

        // Register shader state variants for directional and on/off blocks
        shaderBlockRegistry.registerOnOff(28, true, 30);
        shaderBlockRegistry.registerOnOff(116, true, 117);
        shaderBlockRegistry.register(29, 29);

        // Seed the BeaconLogic with the IDs of the four pyramid metals.
        // Each entry is the BLOCK id, not the item id, because the pyramid
        // scan reads from the world voxel pool. Netherite would qualify too
        // but isn't bundled with this resource pack.
        com.voxel.world.BeaconLogic.VALID_PYRAMID_BLOCKS.add(blockDataManager.findBlockId("iron_block"));
        com.voxel.world.BeaconLogic.VALID_PYRAMID_BLOCKS.add(blockDataManager.findBlockId("gold_block"));
        com.voxel.world.BeaconLogic.VALID_PYRAMID_BLOCKS.add(blockDataManager.findBlockId("diamond_block"));
        com.voxel.world.BeaconLogic.VALID_PYRAMID_BLOCKS.add(blockDataManager.findBlockId("emerald_block"));

        blockDataManager.uploadToGPU();
    }

    public void generateCapeTexture() {
        try {
            java.io.File capeFile = new java.io.File("src/main/resources/assets/minecraft/textures/items/cape.png");
            if (!capeFile.exists()) {
                // Generate a simple cape texture (64x64)
                BufferedImage capeImg = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = capeImg.createGraphics();
                // Red cape with golden trim
                g.setColor(new Color(180, 30, 30));
                g.fillRect(0, 0, 64, 64);
                // Gold trim at top
                g.setColor(new Color(255, 200, 50));
                g.fillRect(0, 0, 64, 4);
                // Darker shading on edges
                g.setColor(new Color(120, 20, 20, 100));
                g.fillRect(0, 0, 4, 64);
                g.fillRect(60, 0, 4, 64);
                g.dispose();
                ImageIO.write(capeImg, "PNG", capeFile);
                System.out.println("Generated cape texture.");
            }
        } catch (IOException e) {
            System.err.println("Failed to generate cape texture.");
        }
    }

    public void setupQuad() {
        float[] vertices = {-1, -1, 1, -1, -1, 1, 1, -1, 1, 1, -1, 1};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(vertices.length).put(vertices);
            buffer.flip();
            quadVBO = glCreateBuffers();
            glNamedBufferStorage(quadVBO, buffer, 0);
            quadVAO = glCreateVertexArrays();
            glEnableVertexArrayAttrib(quadVAO, 0);
            glVertexArrayAttribFormat(quadVAO, 0, 2, GL_FLOAT, false, 0);
            glVertexArrayAttribBinding(quadVAO, 0, 0);
            glVertexArrayVertexBuffer(quadVAO, 0, quadVBO, 0, 2 * Float.BYTES);
        }
    }

    // ── Light-pool helpers ──

    /** Nearest of the 8 fixed pool directions to the live direction. */
    private int nearestPool(float[] dir, int base) {
        int best = base; float bestDot = -2f;
        for (int i = 0; i < 8; i++) {
            float[] d = poolDirs[base + i];
            float dot = dir[0]*d[0] + dir[1]*d[1] + dir[2]*d[2];
            if (dot > bestDot) { bestDot = dot; best = base + i; }
        }
        return best;
    }

    /** Compute the ortho basis (right/up/near-plane origin) for a pool direction and upload. */
    private void uploadPoolBasis(int locOrigin, int locRight, int locUp, int locDir,
                                 float[] dir, float camBX, float camBY, float camBZ) {
        float sx = dir[0], sy = dir[1], sz = dir[2];
        float svx = -sx, svy = -sy, svz = -sz; // view forward = -dir (from light into scene)
        float srx = svz, srz = -svx;           // right = cross(worldUp, v) = (vz, 0, -vx)
        float srl = (float) Math.sqrt(srx * srx + srz * srz);
        if (srl < 1e-4f) { srx = 1.0f; srz = 0.0f; srl = 1.0f; } // dir parallel to worldUp
        srx /= srl; srz /= srl;
        float sux = svy * srz, suy = svz * srx - svx * srz, suz = -svy * srx; // up = cross(v, right)
        float eyeX = camBX + sx * (shadowDepth * 0.5f);
        float eyeY = camBY + sy * (shadowDepth * 0.5f);
        float eyeZ = camBZ + sz * (shadowDepth * 0.5f);
        glProgramUniform3f(computeProgram, locOrigin, eyeX, eyeY, eyeZ);
        glProgramUniform3f(computeProgram, locRight, srx, 0.0f, srz);
        glProgramUniform3f(computeProgram, locUp, sux, suy, suz);
        glProgramUniform3f(computeProgram, locDir, sx, sy, sz);
    }

    /** Regenerate one light pool: bind it as the write image, dispatch the gen pass. */
    private void regenLightPool(int idx, float camBX, float camBY, float camBZ, int r32f) {
        uploadPoolBasis(LOC_SHADOW_ORIGIN, LOC_SHADOW_RIGHT, LOC_SHADOW_UP, LOC_SHADOW_SUN_DIR,
                        poolDirs[idx], camBX, camBY, camBZ);
        glProgramUniform2f(computeProgram, LOC_SHADOW_EXTENT, shadowHalfExtent, shadowDepth);
        glBindImageTexture(1, lightPoolTex[idx], 0, false, 0, GL_WRITE_ONLY, r32f);
        glProgramUniform1i(computeProgram, LOC_SHADOW_PASS, 1);
        int gx = (shadowMapRes + 15) / 16;
        glDispatchCompute(gx, gx, 1);
        glMemoryBarrier(org.lwjgl.opengl.GL45.GL_TEXTURE_FETCH_BARRIER_BIT | org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    }

    public void setupTexture() {
        renderTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(renderTexture, 1, GL_RGBA8, width, height);
        glTextureParameteri(renderTexture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(renderTexture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        // 16 baked light pools (8 sun-trajectory + 8 moon-trajectory). Written by
        // pool-gen dispatches via image unit 1; ACTIVE pools sampled on units 18/19.
        int r32f = org.lwjgl.opengl.GL30.GL_R32F;
        int clampE = org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
        lightPoolTex = new int[16];
        for (int i = 0; i < 16; i++) {
            lightPoolTex[i] = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(lightPoolTex[i], 1, r32f, shadowMapRes, shadowMapRes);
            glTextureParameteri(lightPoolTex[i], GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameteri(lightPoolTex[i], GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameteri(lightPoolTex[i], GL_TEXTURE_WRAP_S, clampE);
            glTextureParameteri(lightPoolTex[i], GL_TEXTURE_WRAP_T, clampE);
        }
    }

    /**
     * Reads the current compute shader output (renderTexture) back from the GPU
     * and saves it as a timestamped PNG in the screenshots/ folder.
     */
    public void takeScreenshot() {
        int numPixels = width * height;
        java.nio.ByteBuffer pixels = MemoryUtil.memAlloc(numPixels * 4);
        try {
            glGetTextureImage(renderTexture, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = (y * width + x) * 4;
                    int r = pixels.get(idx) & 0xFF;
                    int g = pixels.get(idx + 1) & 0xFF;
                    int b = pixels.get(idx + 2) & 0xFF;
                    int a = pixels.get(idx + 3) & 0xFF;
                    // OpenGL origin is bottom-left; flip to top-left for image
                    image.setRGB(x, height - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            File dir = new File("screenshots");
            if (!dir.exists()) dir.mkdirs();

            File out = new File(dir, "screenshot_" + System.currentTimeMillis() + ".png");
            ImageIO.write(image, "PNG", out);
            System.out.println("Screenshot saved: " + out.getPath());
        } catch (IOException e) {
            System.err.println("Failed to save screenshot: " + e.getMessage());
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    public void uploadWorldToGpu() {
        int poolSize = world.getPoolSizeForAlloc();
        int[] table = world.getIndirectionTable();
        IntBuffer buf = MemoryUtil.memAllocInt(table.length);
        buf.put(table).flip();
        // Delete old SSBOs before creating new ones (prevents GPU memory leak on dimension switch)
        if (indirectionSSBO != 0) glDeleteBuffers(indirectionSSBO);
        if (chunkPoolSSBO != 0) glDeleteBuffers(chunkPoolSSBO);
        if (bitmaskSSBO != 0) glDeleteBuffers(bitmaskSSBO);
        if (occlusionSSBO != 0) glDeleteBuffers(occlusionSSBO);
        if (pointLightSSBO != 0) glDeleteBuffers(pointLightSSBO);
        if (lightSSBO != 0) glDeleteBuffers(lightSSBO);

        indirectionSSBO = glCreateBuffers();
        glNamedBufferStorage(indirectionSSBO, buf, GL_DYNAMIC_STORAGE_BIT);
        MemoryUtil.memFree(buf);

        chunkPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(chunkPoolSSBO, (long) poolSize * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);

        int[] bitmaskPool = world.getBitmaskPool();
        IntBuffer bbuf = MemoryUtil.memAllocInt(bitmaskPool.length);
        bbuf.put(bitmaskPool).flip();
        bitmaskSSBO = glCreateBuffers();
        glNamedBufferStorage(bitmaskSSBO, bbuf, GL_DYNAMIC_STORAGE_BIT);
        MemoryUtil.memFree(bbuf);

        short[] occlusionPool = world.getOcclusionPool();
        java.nio.ShortBuffer obuf = MemoryUtil.memAllocShort(occlusionPool.length);
        obuf.put(occlusionPool).flip();
        occlusionSSBO = glCreateBuffers();
        glNamedBufferStorage(occlusionSSBO, obuf, GL_DYNAMIC_STORAGE_BIT);
        MemoryUtil.memFree(obuf);

        pointLightSSBO = glCreateBuffers();
        glNamedBufferStorage(pointLightSSBO, 4096, GL_DYNAMIC_STORAGE_BIT);

        // Crafting/dropped-item SSBO: 9 crafting-grid entries + 64 dropped items max
        // (= 73 entries × 32 bytes = 2336 bytes). Rounded up to 2560 bytes (80 entries)
        // for clean alignment and a small expansion reserve for future item-rendering uses.
        craftingItemSSBO = glCreateBuffers();
        glNamedBufferStorage(craftingItemSSBO, (long) 80 * 32, GL_DYNAMIC_STORAGE_BIT);
        // Light pool SSBO (same size as chunk pool: poolSize * 16³ ints)
        lightSSBO = glCreateBuffers();
        glNamedBufferStorage(lightSSBO, (long) poolSize * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);

        // SDF pool SSBO: chunk-level directional SDF, 8 bytes per chunk section
        // (6 directional distance bytes + 2 padding bytes). Packed as 2 uints/slot.
        sdfSSBO = glCreateBuffers();
        glNamedBufferStorage(sdfSSBO, (long) poolSize * 8, GL_DYNAMIC_STORAGE_BIT);

        uploadDirtyChunks();
    }

    // Reusable direct buffers for uploadDirtyChunks (allocated once, reused per frame)
    public java.nio.IntBuffer reusableVoxelBuf;
    public java.nio.IntBuffer reusableMaskBuf;
    public java.nio.ShortBuffer reusableOccBuf;
    public java.nio.IntBuffer reusableTableBuf;
    public java.nio.IntBuffer reusableLightBuf;

    public void uploadDirtyChunks() {
        java.util.Set<Integer> dirty = chunkManager.getDirtySlots();

        // Capped upload: persist an iterator across frames to avoid per-frame spikes
        if (dirtyUploadIterator == null) {
            dirtyUploadIterator = dirty.iterator();
        }

        boolean tableDirty = chunkManager.isTableDirty();
        if (!dirtyUploadIterator.hasNext() && !tableDirty) {
            dirtyUploadIterator = null;
            // A pending light upload is complete once the dirty set drains empty:
            // every producer pairs lightsNeedUpload=true with dirtySlots.add(...), so
            // no slots left to drain means every changed slot's light reached the GPU.
            if (chunkManager.needsLightUpload() && dirty.isEmpty()) {
                chunkManager.clearLightUpload();
            }
            return;
        }

        int vpc = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        int poolSize = world.getPoolSizeForAlloc();
        // Chunks changed this frame -> the light pools must refresh
        lightPoolDirty = true;

        // Lazy-init reusable buffers sized to current world
        int sdfBytesPerChunk = 8; // 6 directional SDF bytes + 2 padding per chunk section
        if (reusableVoxelBuf == null || reusableVoxelBuf.capacity() < vpc) {
            if (reusableVoxelBuf != null) MemoryUtil.memFree(reusableVoxelBuf);
            if (reusableMaskBuf != null) MemoryUtil.memFree(reusableMaskBuf);
            if (reusableOccBuf != null) MemoryUtil.memFree(reusableOccBuf);
            if (reusableTableBuf != null) MemoryUtil.memFree(reusableTableBuf);
            if (reusableLightBuf != null) MemoryUtil.memFree(reusableLightBuf);
            if (reusableSdfBuf != null) MemoryUtil.memFree(reusableSdfBuf);
            reusableVoxelBuf = MemoryUtil.memAllocInt(vpc);
            reusableMaskBuf = MemoryUtil.memAllocInt(128);
            reusableOccBuf = MemoryUtil.memAllocShort(vpc);
            reusableTableBuf = MemoryUtil.memAllocInt(REGION_SIZE * REGION_SIZE * REGION_SIZE);
            reusableLightBuf = MemoryUtil.memAllocInt(vpc);
            reusableSdfBuf = MemoryUtil.memAlloc(sdfBytesPerChunk);
        }

        if (tableDirty) {
            int[] table = world.getIndirectionTable();
            reusableTableBuf.clear();
            reusableTableBuf.put(table).flip();
            glNamedBufferSubData(indirectionSSBO, 0, reusableTableBuf);
            chunkManager.clearTableDirtyOnly();
        }

        int[] pool = world.getChunkPool();
        int[] masks = world.getBitmaskPool();
        short[] occs = world.getOcclusionPool();
        // Light pool slice per slot — uploaded with each dirty slot below (replaces the
        // old one-shot full-pool upload, which copied+uploaded the ENTIRE pool — at
        // poolSize>=2048 that is >=32MB, and with the default render distance it is
        // 140-300MB — in a single frame on EVERY lighting pass).
        int[] lightPool = world.getLightPool();

        int uploaded = 0;
        while (dirtyUploadIterator.hasNext() && uploaded < MAX_DIRTY_UPLOADS_PER_FRAME) {
            int s = dirtyUploadIterator.next();
            // Black-frame guard: while the light thread is rebuilding this slot's light
            // (CPU pool cleared to zeros), hold the light-slice upload. Geometry still
            // uploads immediately, but the slot stays dirty so the FINAL converged light
            // is pushed once the rebuild task completes.
            boolean lightPending = chunkManager.isLightRebuildPending(s);
            if (!lightPending) {
                dirtyUploadIterator.remove();
            }

            reusableVoxelBuf.clear();
            reusableVoxelBuf.put(pool, s * vpc, vpc).flip();
            glNamedBufferSubData(chunkPoolSSBO, (long) s * vpc * Integer.BYTES, reusableVoxelBuf);

            reusableMaskBuf.clear();
            reusableMaskBuf.put(masks, s * 128, 128).flip();
            glNamedBufferSubData(bitmaskSSBO, (long) s * 128 * Integer.BYTES, reusableMaskBuf);

            reusableOccBuf.clear();
            reusableOccBuf.put(occs, s * vpc, vpc).flip();
            glNamedBufferSubData(occlusionSSBO, (long) s * vpc * Short.BYTES, reusableOccBuf);

            // Per-slot light pool slice (16KB each). The old code uploaded the ENTIRE
            // pool in one frame on every lighting pass (a 140-300MB copy + upload with
            // the default pool sizes) — the worst recurring frame spike. Only the dirty
            // slot's slice is uploaded here, capped at 48/frame; BFS completions
            // re-mark affected slots so GPU light always converges with the pool.
            if (!lightPending) {
                reusableLightBuf.clear();
                reusableLightBuf.put(lightPool, s * vpc, vpc).flip();
                glNamedBufferSubData(lightSSBO, (long) s * vpc * Integer.BYTES, reusableLightBuf);
            }

            // Pack directional SDF (8 bytes per slot) into 2 uints and upload.
            byte[] sdfs = world.getDirSdfPool();
            int[] tmp = new int[2];
            int base = s * 8;
            tmp[0] = ((sdfs[base]     & 0xFF))
                   | ((sdfs[base + 1] & 0xFF) << 8)
                   | ((sdfs[base + 2] & 0xFF) << 16)
                   | ((sdfs[base + 3] & 0xFF) << 24);
            tmp[1] = ((sdfs[base + 4] & 0xFF))
                   | ((sdfs[base + 5] & 0xFF) << 8);
            reusableSdfBuf.clear();
            java.nio.IntBuffer sdfIntView = reusableSdfBuf.asIntBuffer();
            sdfIntView.put(tmp);
            sdfIntView.flip();
            glNamedBufferSubData(sdfSSBO, (long) s * sdfBytesPerChunk, reusableSdfBuf);

            uploaded++;
        }

        // Iterator exhausted: reset for next cycle. If the dirty set drained to empty,
        // any pending light upload is complete too (see the early return above).
        if (!dirtyUploadIterator.hasNext()) {
            dirtyUploadIterator = null;
            if (chunkManager.needsLightUpload() && dirty.isEmpty()) {
                chunkManager.clearLightUpload();
            }
        }

    }

    public static void main(String[] args) {
        new Main().run();
    }

    public Vector3f getLookDirection() {
        return new Vector3f(
            (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))),
            (float) Math.sin(Math.toRadians(pitch)),
            (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)))
        ).normalize();
    }

    public Vector3f getPlayerEyePosition() {
        return new Vector3f(player.getPosition()).add(0, PLAYER_EYE_HEIGHT, 0);
    }

    public Vector3f getActiveCameraPosition() {
        Vector3f eye = getPlayerEyePosition();

        // Cutscene: smoothly lerp camera from start pos to crafting camera target pos
        if (ctx.craftingCutsceneActive) {
            float t = Math.min(1.0f, ctx.craftingCutsceneTimer / GameContext.CRAFTING_CUTSCENE_DURATION);
            float smoothT = t * t * (3.0f - 2.0f * t);
            return new Vector3f(
                ctx.cutsceneCameraStartPos.x + (ctx.cutsceneCameraTargetPos.x - ctx.cutsceneCameraStartPos.x) * smoothT,
                ctx.cutsceneCameraStartPos.y + (ctx.cutsceneCameraTargetPos.y - ctx.cutsceneCameraStartPos.y) * smoothT,
                ctx.cutsceneCameraStartPos.z + (ctx.cutsceneCameraTargetPos.z - ctx.cutsceneCameraStartPos.z) * smoothT
            );
        }

        // Furnace cutscene: lerp the camera toward the furnace front
        if (ctx.furnaceCutsceneActive) {
            float ft = Math.min(1.0f, ctx.furnaceCutsceneTimer / GameContext.FURNACE_CUTSCENE_DURATION);
            float smoothT = ft * ft * (3.0f - 2.0f * ft);
            return new Vector3f(
                ctx.furnaceCutsceneCameraStart.x + (ctx.furnaceCutsceneCameraTarget.x - ctx.furnaceCutsceneCameraStart.x) * smoothT,
                ctx.furnaceCutsceneCameraStart.y + (ctx.furnaceCutsceneCameraTarget.y - ctx.furnaceCutsceneCameraStart.y) * smoothT,
                ctx.furnaceCutsceneCameraStart.z + (ctx.furnaceCutsceneCameraTarget.z - ctx.furnaceCutsceneCameraStart.z) * smoothT
            );
        }

        // Crafting camera: 45° isometric-like angle, position computed during cutscene setup
        if (ctx.craftingTableOpen) {
            return new Vector3f(ctx.cutsceneCameraTargetPos);
        }

        // Cutscene manager active:

        if (cameraMode == CameraMode.FIRST_PERSON) {
            return eye;
        }

        // Story Mode Style: Over-the-shoulder with slight offset
        Vector3f look = getLookDirection();
        Vector3f right = new Vector3f(look).cross(new Vector3f(0, 1, 0)).normalize();
        
        Vector3f target = new Vector3f(player.getPosition()).add(0, THIRD_PERSON_TARGET_HEIGHT, 0);
        // Offset camera slightly to the right of the player
        target.add(right.mul(0.6f)); 

        Vector3f desired = new Vector3f(target).sub(new Vector3f(look).mul(THIRD_PERSON_DISTANCE));
        return resolveCameraCollision(target, desired);
    }

    public Vector3f resolveCameraCollision(Vector3f origin, Vector3f desired) {
        Vector3f delta = new Vector3f(desired).sub(origin);
        float length = delta.length();
        if (length <= 0.0001f) {
            return new Vector3f(origin);
        }

        Vector3f direction = delta.div(length);
        Vector3f lastFree = new Vector3f(origin);
        for (float traveled = CAMERA_COLLISION_STEP; traveled <= length; traveled += CAMERA_COLLISION_STEP) {
            Vector3f sample = new Vector3f(origin).fma(traveled, direction);
            if (isSolidCameraSample(sample)) {
                return lastFree;
            }
            lastFree.set(sample);
        }
        return desired;
    }

    public boolean isSolidCameraSample(Vector3f sample) {
        int voxel = world.getVoxel(
            (int) Math.floor(sample.x),
            (int) Math.floor(sample.y),
            (int) Math.floor(sample.z)
        );
        return voxel > 0 && blockDataManager.isFullBlock(voxel);
    }

    /**
     * Raycasts through the mouse cursor to determine which 3x3 crafting cell the cursor is pointing at.
     * Uses the KNOWN crafting camera orientation (volatile fields) instead of yaw/pitch
     * which may be stale on the GL thread.
     * Computes a perspective ray through the mouse cursor so edge cells can be clicked.
     * Cell detection matches the texture layout: 2x2 pixel cells, 1px borders, 4px margins.
     * @return slot index (0-8), or -1 if no cell hit
     */
    public int raycastCraftingCell() {
        if (!ctx.craftingTableOpen) return -1;

        Vector3f pos = getActiveCameraPosition();
        float topY = ctx.craftingTableBlockY + 1.0f;
        float bx = ctx.craftingTableBlockX;
        float bz = ctx.craftingTableBlockZ;

        // Use the known crafting camera orientation (thread-safe volatile fields)
        double ry = Math.toRadians(craftingCameraYaw);
        double rp = Math.toRadians(craftingCameraPitch); // -45° (looking downward at 45° angle)
        float fx = (float) (Math.cos(ry) * Math.cos(rp));
        float fy = (float) Math.sin(rp);
        float fz = (float) (Math.sin(ry) * Math.cos(rp));

        // Compute right and up camera basis vectors (same as loop())
        float rx = -fz, rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl > 0) { rx /= rl; rz /= rl; }
        float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;

        // Mouse cursor NDC (-1..1)
        float ndcX = 2.0f * lastMouseX / width - 1.0f;
        float ndcY = 1.0f - 2.0f * lastMouseY / height;

        // Perspective projection (same FOV as the compute shader)
        float tanHalfFov = (float) Math.tan(Math.toRadians(45.0)); // FOV 90°
        float aspect = (float) width / height;

        // Ray direction through the mouse cursor
        float dx = fx + (ndcX * tanHalfFov * aspect * rx) + (ndcY * tanHalfFov * ux);
        float dy = fy + (ndcY * tanHalfFov * uy);
        float dz = fz + (ndcX * tanHalfFov * aspect * rz) + (ndcY * tanHalfFov * uz);

        // Normalize
        float dLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dLen < 0.0001f) return -1;
        dx /= dLen; dy /= dLen; dz /= dLen;

        // Ray-plane intersection with the table top face
        if (dy >= 0) return -1;
        float t = (topY - pos.y) / dy;
        if (t <= 0) return -1;

        float hitX = pos.x + dx * t;
        float hitZ = pos.z + dz * t;

        // Local UV on the top face (0..1)
        float u = hitX - bx;
        float v = hitZ - bz;

        // Outside table face
        if (u < 0 || u > 1.0f || v < 0 || v > 1.0f) return -1;

        // Check against texture layout: 4px margins, 2px cells, 1px borders
        // From UV (0..1): margin = 4/16, cell start for cell i = margin + i * (cell + gap), cell width = 2/16
        // hitX (→) maps to row (top=high X, bottom=low X), hitZ (→) maps to column (left=low Z, right=high Z)
        float ru = u - CT_MARGIN;
        float rv = v - CT_MARGIN;
        if (ru < 0 || rv < 0) return -1; // In left/top margin
        // X → row (reversed: high X = row 0 = top)
        int col = (int)(rv / CT_STEP);
        int row = 2 - (int)(ru / CT_STEP);
        if (col > 2 || row < 0 || row > 2) return -1; // Past last cell (in right/bottom margin)

        // Check if within the cell area or in the 1px gap
        float withinU = ru - (2 - row) * CT_STEP;
        float withinV = rv - col * CT_STEP;
        if (withinU >= CT_CELL || withinV >= CT_CELL) return -1; // In border/gap

        return row * 3 + col;
    }

    public int[] raycastBlock(float maxDist) {
        Vector3f dir = getLookDirection();
        Vector3f pos = getActiveCameraPosition();
        float step = 0.05f;
        int lastX = (int) Math.floor(pos.x);
        int lastY = (int) Math.floor(pos.y);
        int lastZ = (int) Math.floor(pos.z);
        for (float t = 0; t < maxDist; t += step) {
            int x = (int) Math.floor(pos.x);
            int y = (int) Math.floor(pos.y);
            int z = (int) Math.floor(pos.z);
            if (world.getVoxel(x, y, z) != 0) {
                return new int[]{x, y, z, lastX, lastY, lastZ};
            }
            lastX = x;
            lastY = y;
            lastZ = z;
            pos.add(dir.x * step, dir.y * step, dir.z * step);
        }
        return null;
    }
}
