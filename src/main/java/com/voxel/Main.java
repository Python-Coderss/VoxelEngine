package com.voxel;

import com.voxel.crafting.CraftingManager;
import com.voxel.lighting.LightPropagationEngine;
import com.voxel.ui.UILayer;
import com.voxel.ui.UIManager;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.BlockDataManager.BlockData;
import com.voxel.utils.BlockDataManager.MaterialEffect;
import com.voxel.utils.ShaderUtil;
import com.voxel.utils.TextureManager;
import com.voxel.world.DimensionManager;
import com.voxel.world.DimensionType;
import com.voxel.entity.Entity;
import com.voxel.entity.ModelPart;
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
import com.voxel.world.WorldGenLogger;
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
    private static final int HOTBAR_SIZE = 5;
    private static final int INVENTORY_SIZE = 20;
    private static final int SLOT_W = 88;
    private static final int SLOT_H = 80;
    private static final int SLOT_TEX_W = 22;
    private static final int SLOT_TEX_H = 20;
    private static final int HOTBAR_X = 10;
    private static final int HOTBAR_Y = 100;
    // Inventory panel: covers 4 columns + crafting grid (2 grid cols + result slot)
    private static final int INVENTORY_PANEL_WIDTH = 460;
    private static final int INVENTORY_PANEL_HEIGHT = SLOT_H * HOTBAR_SIZE + 24;
    private static final float DAY_START_TIME = 720.0f;
    private static final float PLAYER_HALF_WIDTH = 0.3f;
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_EYE_HEIGHT = 1.6f;
    private static final float THIRD_PERSON_DISTANCE = 4.0f;
    private static final float THIRD_PERSON_TARGET_HEIGHT = 1.35f;
    private static final float CAMERA_COLLISION_STEP = 0.1f;

    private long window;
    private int quadProgram, computeProgram;
    private int quadVAO, quadVBO, renderTexture;
    private int indirectionSSBO, chunkPoolSSBO, bitmaskSSBO, occlusionSSBO, pointLightSSBO;

    // Cached compute shader uniform locations (avoid glGetUniformLocation per frame)
    private int locBlockTextures, locEntityTextures, locBlockData, locBlockAABBs, locBlockAABBInfo, locBlockAABBUVs;
    private volatile boolean needsWorldUpload = false;
    private volatile boolean needsCursorUpdate = false;
    private int locBiomeMap, locGrassColormap, locUITexture, locFoliageColormap, locUISource;
    private int locHeartUVs;
    private int locCraftingItemCount;
    private int craftingItemSSBO;
    private int locSunDir, locSunColor, locMoonDir, locMoonColor, locSkyZenith, locSkyHorizon, locAmbient;
    private int locDimensionID;

    private com.voxel.entity.EntityManager entityManager;
    private World world;
    private com.voxel.world.ChunkManager chunkManager;
    private TextureManager textureManager;
    private BlockDataManager blockDataManager;
    private com.voxel.utils.BiomeManager biomeManager;
    private UIManager uiManager;
    private final List<UILayer> uiLayers = new ArrayList<>();
    private Player player;
    private com.voxel.entity.PlayerEntity playerEntity;
    private CraftingManager craftingManager;
    private DimensionManager dimensionManager;
    private DimensionType activeDimension = DimensionType.OVERWORLD;
    private RedstoneManager redstoneManager;

    // --- Extracted subsystem references ---
    private GameContext ctx;
    private ItemDefinitions itemDefinitions;
    private PlayerInventory playerInventory;
    private BlockInteraction blockInteraction;
    private PortalSystem portalSystem;
    private CommandProcessor commandProcessor;
    private AtmosphereRenderer atmosphereRenderer;

    private int width = 1280, height = 720;
    private final int CHUNK_SIZE = 16, REGION_SIZE = 128;

    private float lastMouseX = width / 2f, lastMouseY = height / 2f;
    private boolean firstMouse = true;
    private float yaw = -90, pitch = 0;
    private float playerYaw = -90;

    private GameMode gameMode = GameMode.SURVIVAL;
    private float worldTime = DAY_START_TIME;

    private boolean inventoryOpen = false;
    private boolean commandMode = false;
    private final StringBuilder commandBuffer = new StringBuilder();
    private String statusMessage = "";
    private double statusUntil = 0.0;
    private int statusLineOffset = 0;
    private int lastMeasuredFps = 0;

    private boolean leftMouseHeld = false;
    private boolean leftMousePressedThisFrame = false;
    private int breakTargetX = Integer.MIN_VALUE;
    private int breakTargetY = Integer.MIN_VALUE;
    private int breakTargetZ = Integer.MIN_VALUE;
    private float breakProgress = 0.0f;
    private double lastPortalTeleportTime = 0;

    private int uiTextureId = 0;
    private Vector2i uiTextureSize = new Vector2i(1, 1);
    private int fontTextureId = 0;
    private Vector2i fontTextureSize = new Vector2i(1, 1);
    private UILayer.UIElement crosshairElement;
    private UILayer.UIElement hotbarActiveElement;
    private UILayer.UIElement inventoryPanelElement;
    private UILayer.UIElement carriedItemElement;
    private final UILayer.UIElement[] slotBackgrounds = new UILayer.UIElement[INVENTORY_SIZE];
    private final UILayer.UIElement[] slotItemElements = new UILayer.UIElement[INVENTORY_SIZE];
    private final UILayer.UIElement[] slotCountBars = new UILayer.UIElement[INVENTORY_SIZE];
    private final UILayer.UIElement[] slotCountDigit1 = new UILayer.UIElement[INVENTORY_SIZE];
    private final UILayer.UIElement[] slotCountDigit2 = new UILayer.UIElement[INVENTORY_SIZE];
    private UILayer.UITextElement itemNameElement;

    // Crafting grid (4 input slots in 2x2 + 1 result slot)
    private static final int CRAFTING_SLOTS = 5;
    private static final int CRAFTING_RESULT_SLOT = 4;
    private final UILayer.UIElement[] craftingSlotBackgrounds = new UILayer.UIElement[CRAFTING_SLOTS];
    private final UILayer.UIElement[] craftingSlotItems = new UILayer.UIElement[CRAFTING_SLOTS];

    // MCSM-style 3x3 crafting table grid (10 slots: 9 input + 1 result)
    private final UILayer.UIElement[] crafting3x3SlotBackgrounds = new UILayer.UIElement[9];
    private final UILayer.UIElement[] crafting3x3SlotItems = new UILayer.UIElement[9];
    private UILayer.UIElement craftingTableBg;

    // --- Furnace UI elements ---
    private UILayer.UIElement furnacePanelBg;
    private UILayer.UIElement furnaceInputBg, furnaceFuelBg, furnaceOutputBg;
    private UILayer.UIElement furnaceInputItem, furnaceFuelItem, furnaceOutputItem;
    private UILayer.UIElement furnaceProgressBar, furnaceFuelBar;
    private UILayer.UITextElement furnaceFuelText;

    // --- Chest UI elements ---
    private UILayer.UIElement chestPanelBg;
    private final UILayer.UIElement[] chestSlotBackgrounds = new UILayer.UIElement[20];
    private final UILayer.UIElement[] chestSlotItems = new UILayer.UIElement[20];
    private final UILayer.UIElement[] chestCountBars = new UILayer.UIElement[20];
    private final UILayer.UIElement[] chestCountDigit1 = new UILayer.UIElement[20];
    private final UILayer.UIElement[] chestCountDigit2 = new UILayer.UIElement[20];

    private double itemNameDisplayUntil = 0.0;
    private final UILayer.UIElement[] playerHearts = new UILayer.UIElement[10];
    private final UILayer.UIElement[] heartBases = new UILayer.UIElement[10];
    private UILayer.UITextElement commandTextElement;
    private UILayer.UITextElement statusTextElement;

    private Thread logicThread;
    private volatile boolean running = true;
    private CameraMode cameraMode = CameraMode.FIRST_PERSON;

    private int lastBiomeOffsetX = 0;
    private int lastBiomeOffsetZ = 0;

    private volatile float craftingCameraYaw;    // Fixed yaw while using crafting table (volatile: read by GL thread)
    private volatile float craftingCameraPitch;   // Fixed pitch while using crafting table
    private boolean craftingCameraInited = false;

    private float cameraShake = 0.0f;
    private float hitStop = 0.0f;
    private float combatTime = 0.0f;
    private double lastAttackTime = 0;
    private double lastRollTime = 0;
    private boolean combatMode = false;

    private Vector4f uvHeartFull = new Vector4f(99, 2, 7, 7);
    private Vector4f uvHeartHalf = new Vector4f(108, 2, 7, 7);
    private Vector4f uvHeartEmpty = new Vector4f(90, 2, 7, 7);
    private Vector4f uvHeartBase = new Vector4f(62, 1, 9, 9);

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

        // Save data on shutdown
        if (ctx.worldSaveManager != null) {
            ctx.worldSaveManager.saveCraftingData(ctx.activeDimension, ctx.craftingTableManager);
            ctx.worldSaveManager.saveFurnaceData(ctx.activeDimension, ctx.furnaceManager);
            ctx.worldSaveManager.saveChestData(ctx.activeDimension, ctx.chestManager);
        }

        glDeleteProgram(quadProgram);
        glDeleteProgram(computeProgram);
        glDeleteBuffers(quadVBO);
        glDeleteVertexArrays(quadVAO);
        glDeleteTextures(renderTexture);
        glDeleteBuffers(indirectionSSBO);
        glDeleteBuffers(chunkPoolSSBO);
        glDeleteBuffers(bitmaskSSBO);
        glDeleteBuffers(occlusionSSBO);
        glDeleteBuffers(pointLightSSBO);
        glDeleteBuffers(craftingItemSSBO);
        chunkManager.shutdown();
        RedstoneLogger.shutdown();
        WorldGenLogger.shutdown();

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
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

        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);
        GL.createCapabilities();

        quadProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/quad.vert", GL_VERTEX_SHADER),
            ShaderUtil.compileShader("src/main/resources/shaders/quad.frag", GL_FRAGMENT_SHADER)
        );
        computeProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/raytracer.comp", GL_COMPUTE_SHADER)
        );
        cacheUniformLocations();
        cacheAtmosphereUniforms();

        entityManager = new com.voxel.entity.EntityManager();
        com.voxel.entity.EnemyEntity.setEntityManager(entityManager);
        player = new Player(1024, 63, 1024); // Spawn above the water pool at y=62

        setupQuad();
        setupTexture();
        // Generate procedural textures BEFORE loading so they're available in the texture array
        generateCapeTexture();
        setupResources();

        // Create shared game context (world/chunkManager/dimensionManager filled below after init)
        ctx = new GameContext();
        ctx.activeDimension = activeDimension;
        ctx.entityManager = entityManager;
        ctx.blockDataManager = blockDataManager;
        ctx.biomeManager = biomeManager;
        ctx.textureManager = textureManager;
        ctx.player = player;
        ctx.gameMode = gameMode;
        ctx.cameraMode = cameraMode;
        ctx.width = width;
        ctx.height = height;
        // Defer world GPU upload to render thread (avoid GL calls from LogicThread)
        ctx.uploadWorldToGpu = () -> { needsWorldUpload = true; };
        ctx.updateCursorMode = this::updateCursorMode;
        ctx.statusConsumer = this::setStatus;

        // Create extracted subsystems
        itemDefinitions = new ItemDefinitions();
        itemDefinitions.setup(blockDataManager, textureManager);
        ctx.itemDefinitions = itemDefinitions;

        playerInventory = new PlayerInventory(ctx);
        ctx.playerInventory = playerInventory;
        playerInventory.populateStarting();

        blockInteraction = new BlockInteraction(ctx);
        portalSystem = new PortalSystem(ctx, blockInteraction);
        commandProcessor = new CommandProcessor(ctx);
        atmosphereRenderer = new AtmosphereRenderer(computeProgram);

        // Initialize crafting system (MUST be before setupUi)
        craftingManager = new CraftingManager();
        ctx.craftingManager = craftingManager;

        uiManager = new UIManager(width, height);
        setupUi();

        // Initialize world save manager (dev/world folder)
        ctx.worldSaveManager = new com.voxel.world.WorldSaveManager("dev/world");

        // Initialize world gen logging
        WorldGenLogger.init();

        // Initialize dimension system (only create Overworld at startup to save memory)
        dimensionManager = new DimensionManager(blockDataManager, ctx.worldSaveManager);
        dimensionManager.createDimension(DimensionType.OVERWORLD, 8);
        // Other dimensions (Nether, End, Aether) are created lazily when first visited

        // Use Overworld as default
        world = dimensionManager.getActiveWorld();
        chunkManager = dimensionManager.getActiveChunkManager();
        ctx.world = world;
        ctx.chunkManager = chunkManager;
        ctx.dimensionManager = dimensionManager;

        redstoneManager = new RedstoneManager(world, chunkManager);
        ctx.redstoneManager = redstoneManager;

        playerEntity = new com.voxel.entity.PlayerEntity(10_000, new Vector3f(player.getPosition()), textureManager);
        playerEntity.dimension = activeDimension;
        entityManager.addEntity(playerEntity);


        // Spawn initial enemies
        spawnInitialEnemies(player);

        chunkManager.update(player.getPosition(), yaw);
        uploadWorldToGpu();
        updateCursorMode();
        setStatus("Mode: survival. Press E for inventory, / for commands. R to respawn.");
    }

    private void cacheUniformLocations() {
        locBlockTextures = glGetUniformLocation(computeProgram, "u_BlockTextures");
        locEntityTextures = glGetUniformLocation(computeProgram, "u_EntityTextures");
        locBlockData = glGetUniformLocation(computeProgram, "u_BlockData");
        locBlockAABBs = glGetUniformLocation(computeProgram, "u_BlockAABBs");
        locBlockAABBInfo = glGetUniformLocation(computeProgram, "u_BlockAABBInfo");
        locBlockAABBUVs = glGetUniformLocation(computeProgram, "u_BlockAABBUVs");
        locBiomeMap = glGetUniformLocation(computeProgram, "u_BiomeMap");
        locGrassColormap = glGetUniformLocation(computeProgram, "u_GrassColormap");
        locUITexture = glGetUniformLocation(computeProgram, "u_UITexture");
        locFoliageColormap = glGetUniformLocation(computeProgram, "u_FoliageColormap");
        locUISource = glGetUniformLocation(computeProgram, "u_UISource");
        locHeartUVs = glGetUniformLocation(computeProgram, "u_HeartUVs");
        locCraftingItemCount = glGetUniformLocation(computeProgram, "u_CraftingItemCount");
    }

    private void cacheAtmosphereUniforms() {
        locSunDir = glGetUniformLocation(computeProgram, "u_SunDir");
        locSunColor = glGetUniformLocation(computeProgram, "u_SunColor");
        locMoonDir = glGetUniformLocation(computeProgram, "u_MoonDir");
        locMoonColor = glGetUniformLocation(computeProgram, "u_MoonColor");
        locSkyZenith = glGetUniformLocation(computeProgram, "u_SkyZenith");
        locSkyHorizon = glGetUniformLocation(computeProgram, "u_SkyHorizon");
        locAmbient = glGetUniformLocation(computeProgram, "u_Ambient");
        locDimensionID = glGetUniformLocation(computeProgram, "u_DimensionID");
    }

    private void spawnInitialEnemies(Player p) {
        for (int i = 0; i < 3; i++) {
            com.voxel.entity.ZombieEntity zombie = new com.voxel.entity.ZombieEntity(100 + i, new Vector3f(1030 + i * 12, 64, 1030), textureManager, p);
            zombie.dimension = activeDimension;
            zombie.setWorld(world);
            entityManager.addEntity(zombie);
        }
    }

    private void setupUi() {
        UILayer hudLayer = new UILayer();

        crosshairElement = new UILayer.UIElement(
            new Vector2f(width / 2f - 2, height / 2f - 2),
            new Vector2f(4, 4),
            new Vector4f(1, 1, 1, 1)
        );
        hudLayer.addElement(crosshairElement);

        inventoryPanelElement = new UILayer.UIElement(
            new Vector2f(HOTBAR_X - 8, HOTBAR_Y - 12),
            new Vector2f(INVENTORY_PANEL_WIDTH, INVENTORY_PANEL_HEIGHT),
            new Vector4f(0, 0, 0, 0.45f)
        );
        inventoryPanelElement.visible = false;
        hudLayer.addElement(inventoryPanelElement);

        tryLoadUiTexture();
        tryLoadFontTexture();
        buildInventoryUi(hudLayer);

        uiLayers.add(hudLayer);
        updateInventoryUi();
    }

    private void tryLoadUiTexture() {
        try {
            java.io.File uiFile = new java.io.File("src/main/resources/ui/ui.png");
            if (uiFile.exists()) {
                uiTextureId = UIManager.loadTexture(uiFile.getPath());
                uiTextureSize = UIManager.getTextureSize(uiTextureId);
            }
        } catch (Exception e) {
            System.err.println("Note: ui.png not found at src/main/resources/ui/");
        }
    }

    private void tryLoadFontTexture() {
        try {
            java.io.File fontFile = new java.io.File("src/main/resources/assets/minecraft/textures/font/ascii.png");
            if (fontFile.exists()) {
                fontTextureId = UIManager.loadTexture(fontFile.getPath());
                fontTextureSize = UIManager.getTextureSize(fontTextureId);
            }
        } catch (Exception e) {
            System.err.println("Note: ascii.png not found");
        }
    }

    private void buildInventoryUi(UILayer layer) {
        // Half-pixel UV inset to prevent texture atlas bleeding
        float halfU = 0.5f / uiTextureSize.x;
        float halfV = 0.5f / uiTextureSize.y;
        float uScaleInset = (float) (SLOT_TEX_W - 1) / uiTextureSize.x;
        float vScaleInset = (float) (SLOT_TEX_H - 1) / uiTextureSize.y;

        // Build crafting grid slots (placed to the right of the main inventory)
        int craftingGridX = HOTBAR_X + 440;
        int craftingGridY = HOTBAR_Y + 20;
        for (int index = 0; index < CRAFTING_SLOTS; index++) {
            boolean isResult = index == CRAFTING_RESULT_SLOT;
            int row = index / 2;
            int col = index % 2;
            float cx = craftingGridX + col * (SLOT_W + 8);
            float cy = craftingGridY + row * SLOT_H;

            // Result slot is a bit to the right, centered vertically
            if (isResult) {
                cx = craftingGridX + 2 * (SLOT_W + 8) + 16;
                cy = craftingGridY + SLOT_H / 2;
            }

            UILayer.UIElement background = new UILayer.UIElement(
                new Vector2f(cx, cy),
                new Vector2f(SLOT_W, SLOT_H),
                new Vector4f(isResult ? 1.0f : 0.9f, isResult ? 0.9f : 0.9f, isResult ? 0.8f : 0.9f, 1)
            );
            if (uiTextureId != 0) {
                background.textureId = uiTextureId;
                background.uvOffset = new Vector2f(halfU, halfV);
                background.uvScale = new Vector2f(uScaleInset, vScaleInset);
            }
            final int slotIndex = index;
            background.onClick = () -> playerInventory.handleCraftingSlotClick(slotIndex);
            background.visible = false; // Hidden initially, shown with inventory
            craftingSlotBackgrounds[index] = background;
            layer.addElement(background);

            UILayer.UIElement itemElement = new UILayer.UIElement(
                new Vector2f(cx + 24, cy + 16),
                new Vector2f(40, 40),
                new Vector4f(0, 0, 0, 0)
            );
            itemElement.visible = false;
            craftingSlotItems[index] = itemElement;
            layer.addElement(itemElement);
        }

        // 3x3 Crafting table grid: centered on screen, no slot textures
        float ctGridW = 3 * (SLOT_W + 8) - 8;
        float ctGridH = 3 * SLOT_H;
        int ctX = (int)((width - ctGridW) / 2);
        int ctY = (int)((height - ctGridH) / 2);

        // Background panel behind the 3x3 grid
        craftingTableBg = new UILayer.UIElement(
            new Vector2f(ctX - 10, ctY - 10),
            new Vector2f(ctGridW + 20, ctGridH + 20),
            new Vector4f(0.65f, 0.5f, 0.35f, 0.4f)
        );
        craftingTableBg.visible = false;
        layer.addElement(craftingTableBg);

        for (int i = 0; i < 9; i++) {
            int r = i / 3;
            int c = i % 3;
            float cx = ctX + c * (SLOT_W + 8);
            float cy = ctY + r * SLOT_H;

            UILayer.UIElement bg = new UILayer.UIElement(
                new Vector2f(cx, cy),
                new Vector2f(SLOT_W, SLOT_H),
                new Vector4f(0.9f, 0.9f, 0.9f, 1)
            );
            final int slotIndex = i;
            bg.onClick = () -> playerInventory.handleCrafting3x3SlotClick(slotIndex);
            bg.visible = false;
            crafting3x3SlotBackgrounds[i] = bg;
            layer.addElement(bg);

            UILayer.UIElement itemEl = new UILayer.UIElement(
                new Vector2f(cx + 24, cy + 16),
                new Vector2f(40, 40),
                new Vector4f(0, 0, 0, 0)
            );
            itemEl.visible = false;
            crafting3x3SlotItems[i] = itemEl;
            layer.addElement(itemEl);
        }

        for (int index = 0; index < INVENTORY_SIZE; index++) {
            int row = index % HOTBAR_SIZE;
            int column = index / HOTBAR_SIZE;
            float x = HOTBAR_X + column * (SLOT_W + 12);
            float y = HOTBAR_Y + row * SLOT_H;

            UILayer.UIElement background = new UILayer.UIElement(
                new Vector2f(x, y),
                new Vector2f(SLOT_W, SLOT_H),
                new Vector4f(column == 0 ? 1 : 0.95f, column == 0 ? 1 : 0.95f, column == 0 ? 1 : 0.95f, 1)
            );
            if (uiTextureId != 0) {
                background.textureId = uiTextureId;
                background.uvOffset = new Vector2f(halfU, column == 0 ? row * (float) SLOT_TEX_H / uiTextureSize.y + halfV : halfV);
                background.uvScale = new Vector2f(uScaleInset, vScaleInset);
            }
            final int slotIndex = index;
            background.onClick = () -> playerInventory.handleInventorySlotClick(slotIndex);
            slotBackgrounds[index] = background;
            layer.addElement(background);

            UILayer.UIElement itemElement = new UILayer.UIElement(
                new Vector2f(x + 24, y + 16),
                new Vector2f(40, 40),
                new Vector4f(0, 0, 0, 0)
            );
            itemElement.visible = false;
            slotItemElements[index] = itemElement;
            layer.addElement(itemElement);

            UILayer.UIElement countBar = new UILayer.UIElement(
                new Vector2f(x + 12, y + SLOT_H - 12),
                new Vector2f(0, 6),
                new Vector4f(1, 1, 1, 0.9f)
            );
            countBar.visible = false;
            slotCountBars[index] = countBar;
            layer.addElement(countBar);

            UILayer.UIElement digit1 = new UILayer.UIElement(
                new Vector2f(x + SLOT_W - 32, y + SLOT_H - 24),
                new Vector2f(16, 16),
                new Vector4f(1, 1, 1, 1)
            );
            digit1.visible = false;
            digit1.textureId = fontTextureId;
            slotCountDigit1[index] = digit1;
            layer.addElement(digit1);

            UILayer.UIElement digit2 = new UILayer.UIElement(
                new Vector2f(x + SLOT_W - 18, y + SLOT_H - 24),
                new Vector2f(16, 16),
                new Vector4f(1, 1, 1, 1)
            );
            digit2.visible = false;
            digit2.textureId = fontTextureId;
            slotCountDigit2[index] = digit2;
            layer.addElement(digit2);
        }

        hotbarActiveElement = new UILayer.UIElement(
            new Vector2f(HOTBAR_X, HOTBAR_Y + playerInventory.getSelectedSlot() * SLOT_H),
            new Vector2f(SLOT_W, SLOT_H),
            new Vector4f(1, 1, 1, 1)
        );
        if (uiTextureId != 0) {
            hotbarActiveElement.textureId = uiTextureId;
            hotbarActiveElement.uvOffset = new Vector2f((22.0f + 0.5f) / uiTextureSize.x, halfV);
            hotbarActiveElement.uvScale = new Vector2f(uScaleInset, vScaleInset);
        }
        layer.addElement(hotbarActiveElement);

        carriedItemElement = new UILayer.UIElement(
            new Vector2f(0, 0),
            new Vector2f(28, 28),
            new Vector4f(0, 0, 0, 0)
        );
        carriedItemElement.visible = false;
        layer.addElement(carriedItemElement);

        itemNameElement = new UILayer.UITextElement(
            new Vector2f(HOTBAR_X + 100, HOTBAR_Y - 40),
            "",
            2.5f,
            new Vector4f(1, 1, 1, 1),
            fontTextureId
        );
        itemNameElement.visible = false;
        layer.addElement(itemNameElement);

        for (int i = 0; i < 10; i++) {
            // Heart base/container rendered behind each heart (add first = render behind)
            heartBases[i] = new UILayer.UIElement(
                new Vector2f(HOTBAR_X + i * 30 - 3, HOTBAR_Y - 30 - 3),
                new Vector2f(27, 27),
                new Vector4f(1, 1, 1, 1)
            );
            heartBases[i].textureId = uiTextureId;
            heartBases[i].visible = true;
            layer.addElement(heartBases[i]);
            
            // Heart icon on top (centered within the 27x27 base)
            playerHearts[i] = new UILayer.UIElement(
                new Vector2f(HOTBAR_X + i * 30, HOTBAR_Y - 30),
                new Vector2f(21, 21),
                new Vector4f(1, 1, 1, 1)
            );
            playerHearts[i].textureId = uiTextureId;
            playerHearts[i].visible = true;
            layer.addElement(playerHearts[i]);
        }

        // --- Furnace UI: 3 slots (input, fuel, output) + progress bar ---
        int furnaceX = HOTBAR_X + 20;
        int furnaceY = HOTBAR_Y - 160;
        int furnaceSlotW = SLOT_W;
        int furnaceSlotH = SLOT_H;
        int furnacePanelW = 4 * furnaceSlotW + 80;
        int furnacePanelH = 2 * furnaceSlotH + 60;

        furnacePanelBg = new UILayer.UIElement(
            new Vector2f(furnaceX - 10, furnaceY - 10),
            new Vector2f(furnacePanelW, furnacePanelH),
            new Vector4f(0.3f, 0.3f, 0.3f, 0.6f)
        );
        furnacePanelBg.visible = false;
        layer.addElement(furnacePanelBg);

        // Input slot (left)
        furnaceInputBg = new UILayer.UIElement(
            new Vector2f(furnaceX, furnaceY + furnaceSlotH / 2),
            new Vector2f(furnaceSlotW, furnaceSlotH),
            new Vector4f(0.9f, 0.9f, 0.9f, 1)
        );
        furnaceInputBg.visible = false;
        furnaceInputBg.onClick = () -> handleFurnaceSlotClick(0);
        layer.addElement(furnaceInputBg);

        furnaceInputItem = new UILayer.UIElement(
            new Vector2f(furnaceX + 24, furnaceY + furnaceSlotH / 2 + 16),
            new Vector2f(40, 40),
            new Vector4f(0, 0, 0, 0)
        );
        furnaceInputItem.visible = false;
        layer.addElement(furnaceInputItem);

        // Fuel slot (below input)
        furnaceFuelBg = new UILayer.UIElement(
            new Vector2f(furnaceX, furnaceY + furnaceSlotH + furnaceSlotH / 2 + 12),
            new Vector2f(furnaceSlotW, furnaceSlotH),
            new Vector4f(0.85f, 0.85f, 0.7f, 1)
        );
        furnaceFuelBg.visible = false;
        furnaceFuelBg.onClick = () -> handleFurnaceSlotClick(1);
        layer.addElement(furnaceFuelBg);

        furnaceFuelItem = new UILayer.UIElement(
            new Vector2f(furnaceX + 24, furnaceY + furnaceSlotH + furnaceSlotH / 2 + 28),
            new Vector2f(40, 40),
            new Vector4f(0, 0, 0, 0)
        );
        furnaceFuelItem.visible = false;
        layer.addElement(furnaceFuelItem);

        // Fuel bar (to the right of fuel slot)
        furnaceFuelBar = new UILayer.UIElement(
            new Vector2f(furnaceX + furnaceSlotW + 12, furnaceY + furnaceSlotH + furnaceSlotH / 2 + 24),
            new Vector2f(0, 20),
            new Vector4f(1.0f, 0.5f, 0.0f, 0.8f)
        );
        furnaceFuelBar.visible = false;
        layer.addElement(furnaceFuelBar);

        furnaceFuelText = new UILayer.UITextElement(
            new Vector2f(furnaceX + furnaceSlotW + 12, furnaceY + furnaceSlotH + furnaceSlotH / 2 + 26),
            "", 1.5f, new Vector4f(1, 1, 1, 1), fontTextureId
        );
        furnaceFuelText.visible = false;
        layer.addElement(furnaceFuelText);

        // Output slot (right of input)
        int outputX = furnaceX + 2 * furnaceSlotW + 30;
        furnaceOutputBg = new UILayer.UIElement(
            new Vector2f(outputX, furnaceY + furnaceSlotH / 2),
            new Vector2f(furnaceSlotW, furnaceSlotH),
            new Vector4f(0.9f, 0.8f, 0.7f, 1)
        );
        furnaceOutputBg.visible = false;
        furnaceOutputBg.onClick = () -> handleFurnaceSlotClick(2);
        layer.addElement(furnaceOutputBg);

        furnaceOutputItem = new UILayer.UIElement(
            new Vector2f(outputX + 24, furnaceY + furnaceSlotH / 2 + 16),
            new Vector2f(40, 40),
            new Vector4f(0, 0, 0, 0)
        );
        furnaceOutputItem.visible = false;
        layer.addElement(furnaceOutputItem);

        // Progress arrow between input and output
        furnaceProgressBar = new UILayer.UIElement(
            new Vector2f(furnaceX + furnaceSlotW + 12, furnaceY + furnaceSlotH / 2 + furnaceSlotH / 2 - 8),
            new Vector2f(0, 16),
            new Vector4f(0.8f, 0.8f, 0.2f, 0.9f)
        );
        furnaceProgressBar.visible = false;
        layer.addElement(furnaceProgressBar);

        // --- Chest UI: 2 rows of 10 slots, placed above the inventory panel ---
        float chestGridW = 10 * (SLOT_W + 8) - 8;
        float chestGridH = 2 * SLOT_H;
        int chestX = HOTBAR_X;
        int chestY = HOTBAR_Y - (int)chestGridH - 20;

        chestPanelBg = new UILayer.UIElement(
            new Vector2f(chestX - 8, chestY - 12),
            new Vector2f(chestGridW + 16, chestGridH + 24),
            new Vector4f(0.2f, 0.15f, 0.1f, 0.6f)
        );
        chestPanelBg.visible = false;
        layer.addElement(chestPanelBg);

        for (int i = 0; i < 20; i++) {
            int row = i / 10;
            int col = i % 10;
            float cx = chestX + col * (SLOT_W + 8);
            float cy = chestY + row * SLOT_H;

            UILayer.UIElement bg = new UILayer.UIElement(
                new Vector2f(cx, cy),
                new Vector2f(SLOT_W, SLOT_H),
                new Vector4f(0.85f, 0.7f, 0.55f, 1)
            );
            final int slotIdx = i;
            bg.onClick = () -> handleChestSlotClick(slotIdx);
            bg.visible = false;
            chestSlotBackgrounds[i] = bg;
            layer.addElement(bg);

            UILayer.UIElement itemEl = new UILayer.UIElement(
                new Vector2f(cx + 24, cy + 16),
                new Vector2f(40, 40),
                new Vector4f(0, 0, 0, 0)
            );
            itemEl.visible = false;
            chestSlotItems[i] = itemEl;
            layer.addElement(itemEl);

            UILayer.UIElement countBar = new UILayer.UIElement(
                new Vector2f(cx + 12, cy + SLOT_H - 12),
                new Vector2f(0, 6),
                new Vector4f(1, 1, 1, 0.9f)
            );
            countBar.visible = false;
            chestCountBars[i] = countBar;
            layer.addElement(countBar);

            UILayer.UIElement digit1 = new UILayer.UIElement(
                new Vector2f(cx + SLOT_W - 32, cy + SLOT_H - 24),
                new Vector2f(16, 16),
                new Vector4f(1, 1, 1, 1)
            );
            digit1.visible = false;
            digit1.textureId = fontTextureId;
            chestCountDigit1[i] = digit1;
            layer.addElement(digit1);

            UILayer.UIElement digit2 = new UILayer.UIElement(
                new Vector2f(cx + SLOT_W - 18, cy + SLOT_H - 24),
                new Vector2f(16, 16),
                new Vector4f(1, 1, 1, 1)
            );
            digit2.visible = false;
            digit2.textureId = fontTextureId;
            chestCountDigit2[i] = digit2;
            layer.addElement(digit2);
        }

        commandTextElement = new UILayer.UITextElement(
            new Vector2f(20, height - 40),
            "",
            2.0f,
            new Vector4f(1, 1, 1, 1),
            fontTextureId
        );
        commandTextElement.visible = false;
        layer.addElement(commandTextElement);

        statusTextElement = new UILayer.UITextElement(
            new Vector2f(20, height - 70),
            "",
            2.0f,
            new Vector4f(1, 1, 0.5f, 1),
            fontTextureId
        );
        statusTextElement.charLineLimit = 20;
        statusTextElement.visible = false;
        layer.addElement(statusTextElement);
    }

    private void logicLoop() {
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
    private void syncGameState() {
        if (gameMode != ctx.gameMode) { gameMode = ctx.gameMode; }
        if (combatMode != ctx.combatMode) { combatMode = ctx.combatMode; }
        if (cameraMode != ctx.cameraMode) { cameraMode = ctx.cameraMode; }
        if (commandMode != ctx.commandMode) { commandMode = ctx.commandMode; }
        if (inventoryOpen != ctx.inventoryOpen) {
            inventoryOpen = ctx.inventoryOpen;
        }
    }

    private void tick(float dt) {
        if (!running) return;
        syncGameState();

        if (cameraMode == CameraMode.FIRST_PERSON) {
            playerYaw = yaw;
        }

        int pcx = (int) Math.floor(player.getPosition().x) >> 4;
        int pcz = (int) Math.floor(player.getPosition().z) >> 4;
        boolean chunksReady = chunkManager.isChunkLoaded(pcx, pcz);

        if (chunksReady) {
            // After dimension switch: adjust player y to 1 block above the actual surface
            // once spawn chunks are loaded (surface scan fails earlier since chunks aren't ready)
            ctx.adjustSpawnYAfterChunkLoad();

            handleInput(dt);

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

            player.update(dt, world, blockDataManager);

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
            playerEntity.syncFromPlayer(player, playerYaw, pitch, cameraMode == CameraMode.THIRD_PERSON, dt);
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

        worldTime += dt;
        blockInteraction.updateMining(dt);

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
                    Vector3f toTarget = new Vector3f(enemy.position).sub(player.getPosition());
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

        // --- Enemy AI (now handled inside EnemyEntity) ---
        Vector3f pPos = player.getPosition();
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            com.voxel.entity.Entity e = entityManager.getEntity(i);
            if (e.dimension != activeDimension) continue;
            if (e instanceof com.voxel.entity.EnemyEntity) {
                com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) e;
                if (!enemy.isDead()) {
                    enemy.updateAI(pPos, dt);
                }
            }
        }

        // Tick furnaces (smelting logic)
        if (ctx.furnaceManager != null && ctx.chunkManager != null) {
            ctx.furnaceManager.tickAll(ctx.chunkManager, dt);
        }

        entityManager.update(dt);
        portalSystem.checkTeleport();

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

        // Sync fields after potential dimension switch from PortalSystem/CommandProcessor
        if (chunkManager != ctx.chunkManager) {
            chunkManager = ctx.chunkManager;
            world = ctx.world;
            activeDimension = ctx.activeDimension;
            redstoneManager = ctx.redstoneManager;
        }

        Vector3f pPosForRS = player.getPosition();
        if (redstoneManager != null) {
            redstoneManager.setPlayerPosition(pPosForRS.x, pPosForRS.y, pPosForRS.z);
            redstoneManager.tickLamps();
        }

        chunkManager.update(player.getPosition(), yaw);
    }

    private void handleInput(float dt) {
        if (inventoryOpen || commandMode || player.isDead() || ctx.craftingCutsceneActive) return;

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
                // Determine roll direction from WASD input
                float rollDx = 0, rollDz = 0;
                if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) { rollDx += fx; rollDz += fz; }
                if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) { rollDx -= fx; rollDz -= fz; }
                if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { rollDx -= rx; rollDz -= rz; }
                if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { rollDx += rx; rollDz += rz; }
                float rollLen = (float) Math.sqrt(rollDx * rollDx + rollDz * rollDz);
                if (rollLen > 0.01f) {
                    rollDx /= rollLen;
                    rollDz /= rollLen;
                } else {
                    rollDx = fx; rollDz = fz; // Default forward
                }
                player.move(rollDx * 20, 0.5f, rollDz * 20, 10.0f);
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

        float speed = player.isFlying() ? 1.5f : 0.4f;

        float dx = 0, dz = 0;
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            dx += fx;
            dz += fz;
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            dx -= fx;
            dz -= fz;
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            dx -= rx;
            dz -= rz;
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            dx += rx;
            dz += rz;
        }

        float mvLen = (float) Math.sqrt(dx * dx + dz * dz);
        if (mvLen > 0) {
            dx /= mvLen;
            dz /= mvLen;

            if (combatMode) {
                // Restrict to 1 line of movement (axis-aligned)
                if (Math.abs(dx) > Math.abs(dz)) {
                    dz = 0;
                } else {
                    dx = 0;
                }
            }

            if (cameraMode == CameraMode.THIRD_PERSON) {
                playerYaw = (float) Math.toDegrees(Math.atan2(dz, dx));
            }
        }
        player.move(dx * speed, 0, dz * speed, speed * 2.0f);

        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            if (player.isFlying()) player.move(0, speed, 0, speed * 2.0f);
            else player.jump(world, blockDataManager);
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            if (player.isFlying()) player.move(0, -speed, 0, speed * 2.0f);
        }

        if (gameMode == GameMode.CREATIVE) {
            if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) player.setFlying(true);
            if (glfwGetKey(window, GLFW_KEY_G) == GLFW_PRESS) player.setFlying(false);
        }
    }

    private void performCombatAttack(float damage) {
        Vector3f pPos = player.getPosition();
        Vector3f pDir = getLookDirection();

        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            com.voxel.entity.Entity e = entityManager.getEntity(i);
            if (e.dimension != activeDimension) continue;
            if (e instanceof com.voxel.entity.EnemyEntity) {
                com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) e;
                if (enemy.isDead()) continue;

                Vector3f toEnemy = new Vector3f(enemy.position).sub(pPos);
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
                            enemy.position.x, enemy.position.y + 2.0f, enemy.position.z,
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

    private void loop() {
        float lastTime = (float) glfwGetTime();
        int frames = 0;
        float fpsTime = 0;

        while (!glfwWindowShouldClose(window)) {
            float currentTime = (float) glfwGetTime();
            float dt = currentTime - lastTime;
            lastTime = currentTime;
            fpsTime += dt;
            frames++;
            if (fpsTime >= 1.0f) {
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

            redstoneManager.applyLampChanges();
            // Deferred world GPU upload (must happen on GL thread)
            if (needsWorldUpload) {
                uploadWorldToGpu();
                needsWorldUpload = false;
            }

            uploadDirtyChunks();

            // Slide the biome map when the world buffer recenters
            int curOX = world.getOffsetX();
            int curOZ = world.getOffsetZ();
            if (curOX != lastBiomeOffsetX || curOZ != lastBiomeOffsetZ) {
                biomeManager.slideBiomeMap(lastBiomeOffsetX, lastBiomeOffsetZ, curOX, curOZ);
                biomeManager.uploadBiomeMap();
                lastBiomeOffsetX = curOX;
                lastBiomeOffsetZ = curOZ;
            }

            updateInventoryUi();
            updateWindowTitle();

            uiManager.begin();
            for (UILayer layer : uiLayers) layer.render(uiManager);
            uiManager.end();

            entityManager.uploadToGPU(activeDimension);

            FloatBuffer plBuf = MemoryUtil.memAllocFloat(4);
            plBuf.put(0, Float.intBitsToFloat(0));
            glNamedBufferSubData(pointLightSSBO, 0, plBuf);
            MemoryUtil.memFree(plBuf);

            Vector3f cameraPos = getActiveCameraPosition();
            
            // Add Camera Shake
            if (cameraShake > 0.01f) {
                cameraPos.x += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
                cameraPos.y += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
                cameraPos.z += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
            }

            glUseProgram(computeProgram);
            glProgramUniform3f(computeProgram, 0, cameraPos.x, cameraPos.y, cameraPos.z);

            double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
            float fx = (float) (Math.cos(ry) * Math.cos(rp)), fy = (float) Math.sin(rp), fz = (float) (Math.sin(ry) * Math.cos(rp));
            float rx = -fz, rz = fx;
            float rl = (float) Math.sqrt(rx * rx + rz * rz);
            if (rl > 0) {
                rx /= rl;
                rz /= rl;
            }
            float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;

            glProgramUniform3f(computeProgram, 1, fx, fy, fz);
            glProgramUniform3f(computeProgram, 2, rx, 0, rz);
            glProgramUniform3f(computeProgram, 3, ux, uy, uz);
            glProgramUniform1f(computeProgram, 4, worldTime);
            glProgramUniform1i(computeProgram, 5, entityManager.getEntityCount(activeDimension));
            atmosphereRenderer.upload(worldTime, activeDimension);
            glProgramUniform1i(computeProgram, locDimensionID, activeDimension.id);
            // Upload world sliding window offset
            glProgramUniform3i(computeProgram, 6, world.getOffsetX(), world.getOffsetY(), world.getOffsetZ());

            // Upload UI UVs
            glUniform4f(locHeartUVs, uvHeartFull.x, uvHeartFull.y, uvHeartFull.z, uvHeartFull.w);
            glUniform4f(locHeartUVs + 1, uvHeartHalf.x, uvHeartHalf.y, uvHeartHalf.z, uvHeartHalf.w);
            glUniform4f(locHeartUVs + 2, uvHeartEmpty.x, uvHeartEmpty.y, uvHeartEmpty.z, uvHeartEmpty.w);

            bindTextures();

            glActiveTexture(GL_TEXTURE15);
            glBindTexture(GL_TEXTURE_2D, uiTextureId);
            glUniform1i(locUISource, 15);

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, bitmaskSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, occlusionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, pointLightSSBO);
            entityManager.bind(6, 7);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, craftingItemSSBO);

            uploadCraftingItems();

            glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
            glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(quadProgram);
            glBindTextureUnit(0, renderTexture);
            glUniform1i(glGetUniformLocation(quadProgram, "u_Pass"), 1);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            glfwSwapBuffers(window);
            glfwPollEvents();
            leftMousePressedThisFrame = false;
            // ctx.leftMousePressedThisFrame is consumed/reset by the logic thread in tick()
        }
    }

    private void handleKeyInput(long win, int key, int scancode, int action, int mods) {
        if (action == GLFW_PRESS) {
            if (commandMode) {
                handleCommandModeKey(key);
                return;
            }

            if (key == GLFW_KEY_R && player.isDead()) {
                player.respawn();
                setStatus("Respawned.");
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
                toggleInventory();
                showSelectedItemName();
                return;
            }

            if (key == GLFW_KEY_C) {
                combatMode = !combatMode;
                ctx.combatMode = combatMode;
                if (combatMode) {
                    cameraMode = CameraMode.THIRD_PERSON;
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
                            float dist = pPos.distance(enemy.position);
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
                if (ctx.craftingCutsceneActive) {
                    // Abort crafting cutscene
                    ctx.craftingCutsceneActive = false;
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
        }

        if (action == GLFW_RELEASE && key == GLFW_KEY_ESCAPE && commandMode) {
            cancelCommandMode();
        }
    }

    private void handleCommandModeKey(int key) {
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

    private void handleCharInput(long win, int codepoint) {
        if (!commandMode) return;
        if (codepoint < 32 || codepoint > 126) return;
        commandBuffer.append((char) codepoint);
    }

    private void handleCursorMoved(long win, double xpos, double ypos) {
        if (firstMouse) {
            lastMouseX = (float) xpos;
            lastMouseY = (float) ypos;
            firstMouse = false;
        }

        if (inventoryOpen || commandMode) {
            // Track mouse position for inventory UI interactions (slot clicks, item drag)
            lastMouseX = (float) xpos;
            lastMouseY = (float) ypos;
            return;
        }

        float xoffset = (float) xpos - lastMouseX;
        float yoffset = lastMouseY - (float) ypos;
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

    private void handleMouseButton(long win, int button, int action, int mods) {
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

        // Crafting table drag-and-drop via 3D raycast
        if (ctx.craftingTableOpen && inventoryOpen) {
            System.out.println("Crafting: mouse click at screen (" + lastMouseX + "," + lastMouseY + ")");
            int cell = raycastCraftingCell();
            if (cell >= 0) {
                System.out.println("Crafting: slot click " + cell);
                playerInventory.handleCrafting3x3SlotClick(cell);
                return;
            }
            System.out.println("Crafting: cell miss, falling through to UI");
            // Fall through to UI click handling for inventory slots
        }

        if (inventoryOpen) {
            for (int i = uiLayers.size() - 1; i >= 0; i--) {
                if (uiLayers.get(i).handleMouseClick(lastMouseX, lastMouseY)) return;
            }
            return;
        }

        if (commandMode) return;

        if (button == GLFW_MOUSE_BUTTON_RIGHT) {
            if ((mods & GLFW_MOD_SHIFT) != 0) {
                portalSystem.attemptActivate();
            } else {
                blockInteraction.attemptPlaceBlock();
            }
        }
    }

    private void openCommandMode() {
        if (inventoryOpen) setInventoryOpen(false);
        commandMode = true;
        ctx.commandMode = true;
        ctx.leftMousePressedThisFrame = false; // prevent stale press from inventory
        commandBuffer.setLength(0);
        updateCursorMode();
    }

    private void cancelCommandMode() {
        commandMode = false;
        ctx.commandMode = false;
        commandBuffer.setLength(0);
        updateCursorMode();
    }

    private void toggleInventory() {
        setInventoryOpen(!inventoryOpen);
    }

    private void toggleCameraMode() {
        cameraMode = cameraMode == CameraMode.FIRST_PERSON ? CameraMode.THIRD_PERSON : CameraMode.FIRST_PERSON;
        ctx.cameraMode = cameraMode;
        setStatus("Camera: " + (cameraMode == CameraMode.FIRST_PERSON ? "first person" : "third person"));
    }

    private void setInventoryOpen(boolean open) {
        inventoryOpen = open;
        ctx.inventoryOpen = open;
        if (open) {
            ctx.leftMousePressedThisFrame = false; // prevent stale press from world
        }
        if (!open) {
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
            ctx.furnaceOpen = false;
            ctx.chestOpen = false;
            ctx.activeUI = GameContext.ActiveUI.NONE;
            ctx.leftMousePressedThisFrame = false; // prevent stale press from inventory
        }
        updateCursorMode();
    }

    private void updateCursorMode() {
        boolean freeCursor = inventoryOpen || commandMode;
        glfwSetInputMode(window, GLFW_CURSOR, freeCursor ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
        if (!freeCursor) firstMouse = true;
    }

    private double lastTitleUpdate = 0.0;

    private void updateWindowTitle() {
        double now = glfwGetTime();
        if (now - lastTitleUpdate < 0.25) return;
        lastTitleUpdate = now;

        StringBuilder title = new StringBuilder("Voxel Engine | FPS: ").append(lastMeasuredFps);
        title.append(" | ").append(gameMode == GameMode.CREATIVE ? "creative" : "survival");
        Vector3f pos = player.getPosition();
        title.append(String.format(Locale.US, " | XYZ: %.2f, %.2f, %.2f", pos.x, pos.y, pos.z));

        int pcx = (int) Math.floor(pos.x) >> 4;
        int pcz = (int) Math.floor(pos.z) >> 4;
        if (!chunkManager.isChunkLoaded(pcx, pcz)) {
            title.append(" [WAITING FOR CHUNKS]");
        }

        if (commandMode) {
            title.append(" | CMD ").append(commandBuffer);
        } else if (!statusMessage.isEmpty() && glfwGetTime() < statusUntil) {
            title.append(" | ").append(statusMessage);
        }

        glfwSetWindowTitle(window, title.toString());
    }

    private void setStatus(String message) {
        statusMessage = message;
        statusUntil = glfwGetTime() + 3.0;
        statusLineOffset = 0;
        System.out.println(message);
    }    private void updateInventoryUi() {
        double time = glfwGetTime();
        crosshairElement.visible = !inventoryOpen && !commandMode;
        inventoryPanelElement.visible = inventoryOpen;
        hotbarActiveElement.visible = true;
        hotbarActiveElement.pos.y = HOTBAR_Y + playerInventory.getSelectedSlot() * SLOT_H;

        // Update crafting grid visibility and content
        // Toggle between 2x2 (inventory), 3x3 (crafting table), furnace, and chest UIs
        boolean use3x3 = ctx.craftingTableOpen && ctx.activeUI == GameContext.ActiveUI.CRAFTING_TABLE;
        boolean useFurnace = ctx.furnaceOpen && ctx.activeUI == GameContext.ActiveUI.FURNACE;
        boolean useChest = ctx.chestOpen && ctx.activeUI == GameContext.ActiveUI.CHEST;

        // --- Furnace UI ---
        if (useFurnace) {
            // Hide crafting UIs
            for (int i = 0; i < CRAFTING_SLOTS; i++) {
                craftingSlotBackgrounds[i].visible = false;
                craftingSlotItems[i].visible = false;
            }
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            chestPanelBg.visible = false;
            for (int i = 0; i < 20; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
                chestCountBars[i].visible = false;
                chestCountDigit1[i].visible = false;
                chestCountDigit2[i].visible = false;
            }

            FurnaceManager.FurnaceState state = ctx.furnaceManager.getState(ctx.furnaceBlockX, ctx.furnaceBlockY, ctx.furnaceBlockZ);

            furnacePanelBg.visible = inventoryOpen;
            furnaceInputBg.visible = inventoryOpen;
            furnaceFuelBg.visible = inventoryOpen;
            furnaceOutputBg.visible = inventoryOpen;
            furnaceProgressBar.visible = inventoryOpen;
            furnaceFuelBar.visible = inventoryOpen;
            furnaceFuelText.visible = inventoryOpen;

            // Input slot
            if (inventoryOpen && state.input != null) {
                ItemDefinition def = itemDefinitions.getDefinition(state.input.itemId);
                if (def != null) {
                    furnaceInputItem.visible = true;
                    furnaceInputItem.textureId = textureManager.getTextureArrayId();
                    furnaceInputItem.textureType = 2;
                    furnaceInputItem.layer = def.iconLayer;
                    furnaceInputItem.color.set(1, 1, 1, 1);
                } else {
                    furnaceInputItem.visible = false;
                }
            } else {
                furnaceInputItem.visible = false;
            }

            // Fuel slot
            if (inventoryOpen && state.fuel != null) {
                ItemDefinition def = itemDefinitions.getDefinition(state.fuel.itemId);
                if (def != null) {
                    furnaceFuelItem.visible = true;
                    furnaceFuelItem.textureId = textureManager.getTextureArrayId();
                    furnaceFuelItem.textureType = 2;
                    furnaceFuelItem.layer = def.iconLayer;
                    furnaceFuelItem.color.set(1, 1, 1, 1);
                } else {
                    furnaceFuelItem.visible = false;
                }
            } else {
                furnaceFuelItem.visible = false;
            }

            // Output slot
            if (inventoryOpen && state.output != null) {
                ItemDefinition def = itemDefinitions.getDefinition(state.output.itemId);
                if (def != null) {
                    furnaceOutputItem.visible = true;
                    furnaceOutputItem.textureId = textureManager.getTextureArrayId();
                    furnaceOutputItem.textureType = 2;
                    furnaceOutputItem.layer = def.iconLayer;
                    furnaceOutputItem.color.set(1, 1, 1, 1);
                } else {
                    furnaceOutputItem.visible = false;
                }
            } else {
                furnaceOutputItem.visible = false;
            }

            // Progress bar
            if (inventoryOpen && state.fuelBurnTime > 0 && state.input != null) {
                float progress = Math.min(1.0f, state.smeltProgress);
                furnaceProgressBar.size.set(20, (int)(50 * progress));
                furnaceProgressBar.color.set(0.8f + 0.2f * progress, 0.4f + 0.4f * progress, 0.2f, 0.9f);
            } else {
                furnaceProgressBar.size.set(0, 0);
            }

            // Fuel bar
            if (inventoryOpen && state.isLit()) {
                float fuelPct = state.fuelBurnTime / state.maxFuelBurnTime;
                furnaceFuelBar.size.set(60 * fuelPct, 20);
                furnaceFuelBar.color.set(1.0f, 0.5f, 0.0f, 0.8f);
                furnaceFuelText.text = String.format("%.1fs", state.fuelBurnTime);
            } else {
                furnaceFuelBar.size.set(0, 0);
                furnaceFuelText.text = "No fuel";
            }
        } else {
            furnacePanelBg.visible = false;
            furnaceInputBg.visible = false;
            furnaceFuelBg.visible = false;
            furnaceOutputBg.visible = false;
            furnaceInputItem.visible = false;
            furnaceFuelItem.visible = false;
            furnaceOutputItem.visible = false;
            furnaceProgressBar.visible = false;
            furnaceFuelBar.visible = false;
            furnaceFuelText.visible = false;
        }

        // --- Chest UI ---
        if (useChest) {
            // Hide crafting/furnace UIs
            for (int i = 0; i < CRAFTING_SLOTS; i++) {
                craftingSlotBackgrounds[i].visible = false;
                craftingSlotItems[i].visible = false;
            }
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            furnacePanelBg.visible = false;
            furnaceInputBg.visible = false;
            furnaceFuelBg.visible = false;
            furnaceOutputBg.visible = false;
            furnaceInputItem.visible = false;
            furnaceFuelItem.visible = false;
            furnaceOutputItem.visible = false;
            furnaceProgressBar.visible = false;
            furnaceFuelBar.visible = false;
            furnaceFuelText.visible = false;

            chestPanelBg.visible = inventoryOpen;

            ItemStack[] chestInv = ctx.chestManager.getInventory(ctx.chestBlockX, ctx.chestBlockY, ctx.chestBlockZ);
            for (int i = 0; i < 20; i++) {
                boolean slotVisible = inventoryOpen;
                chestSlotBackgrounds[i].visible = slotVisible;

                ItemStack stack = (chestInv != null && i < chestInv.length) ? chestInv[i] : null;
                UILayer.UIElement itemEl = chestSlotItems[i];
                UILayer.UIElement countBar = chestCountBars[i];
                UILayer.UIElement digit1 = chestCountDigit1[i];
                UILayer.UIElement digit2 = chestCountDigit2[i];

                if (!slotVisible || stack == null) {
                    itemEl.visible = false;
                    countBar.visible = false;
                    digit1.visible = false;
                    digit2.visible = false;
                    continue;
                }

                ItemDefinition def = itemDefinitions.getDefinition(stack.itemId);
                itemEl.visible = true;
                itemEl.textureId = textureManager.getTextureArrayId();
                itemEl.textureType = 2;
                itemEl.layer = def.iconLayer;
                itemEl.color.set(1, 1, 1, 1);
                itemEl.size.set(40, 40);
                itemEl.pos.set(chestSlotBackgrounds[i].pos.x + 24, chestSlotBackgrounds[i].pos.y + 16);

                if (inventoryOpen && chestSlotBackgrounds[i].isPointInside(lastMouseX, lastMouseY)) {
                    if (def != null) {
                        itemNameElement.text = def.displayName;
                        itemNameElement.visible = true;
                        itemNameElement.color.w = 1.0f;
                        itemNameDisplayUntil = time + 0.1;
                    }
                }

                if (def != null && def.maxStack > 1 && stack.count > 1) {
                    countBar.visible = true;
                    countBar.color.set(def.color.x, def.color.y, def.color.z, 0.85f);
                    countBar.pos.set(chestSlotBackgrounds[i].pos.x + 12, chestSlotBackgrounds[i].pos.y + SLOT_H - 12);
                    countBar.size.set((SLOT_W - 24) * Math.min(stack.count, def.maxStack) / (float) def.maxStack, 6);

                    if (fontTextureId != 0) {
                        if (stack.count >= 10) {
                            digit1.visible = true;
                            int d1 = stack.count / 10;
                            int charCode = 48 + d1;
                            digit1.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                            digit1.uvScale.set(1 / 16.0f, 1 / 16.0f);
                            digit2.visible = true;
                            int d2 = stack.count % 10;
                            charCode = 48 + d2;
                            digit2.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                            digit2.uvScale.set(1 / 16.0f, 1 / 16.0f);
                        } else {
                            digit1.visible = false;
                            digit2.visible = true;
                            int d2 = stack.count;
                            int charCode = 48 + d2;
                            digit2.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                            digit2.uvScale.set(1 / 16.0f, 1 / 16.0f);
                        }
                    } else {
                        digit1.visible = false;
                        digit2.visible = false;
                    }
                } else {
                    countBar.visible = false;
                    digit1.visible = false;
                    digit2.visible = false;
                }
            }
        } else {
            chestPanelBg.visible = false;
            for (int i = 0; i < 20; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
                chestCountBars[i].visible = false;
                chestCountDigit1[i].visible = false;
                chestCountDigit2[i].visible = false;
            }
        }

        // --- 3x3 Crafting table UI ---
        if (use3x3) {
            // Hide other UIs
            for (int i = 0; i < CRAFTING_SLOTS; i++) {
                craftingSlotBackgrounds[i].visible = false;
                craftingSlotItems[i].visible = false;
            }
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            // Hide furnace/chest during crafting table
            furnacePanelBg.visible = false;
            furnaceInputBg.visible = false;
            furnaceFuelBg.visible = false;
            furnaceOutputBg.visible = false;
            furnaceInputItem.visible = false;
            furnaceFuelItem.visible = false;
            furnaceOutputItem.visible = false;
            furnaceProgressBar.visible = false;
            furnaceFuelBar.visible = false;
            furnaceFuelText.visible = false;
            chestPanelBg.visible = false;
            for (int i = 0; i < 20; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
                chestCountBars[i].visible = false;
                chestCountDigit1[i].visible = false;
                chestCountDigit2[i].visible = false;
            }
        } else if (!useFurnace && !useChest) {
            // Show 2x2 crafting slots (default inventory mode)
            craftingTableBg.visible = false;
            for (int i = 0; i < 9; i++) {
                crafting3x3SlotBackgrounds[i].visible = false;
                crafting3x3SlotItems[i].visible = false;
            }
            // Hide furnace/chest in default mode
            furnacePanelBg.visible = false;
            furnaceInputBg.visible = false;
            furnaceFuelBg.visible = false;
            furnaceOutputBg.visible = false;
            furnaceInputItem.visible = false;
            furnaceFuelItem.visible = false;
            furnaceOutputItem.visible = false;
            furnaceProgressBar.visible = false;
            furnaceFuelBar.visible = false;
            furnaceFuelText.visible = false;
            chestPanelBg.visible = false;
            for (int i = 0; i < 20; i++) {
                chestSlotBackgrounds[i].visible = false;
                chestSlotItems[i].visible = false;
                chestCountBars[i].visible = false;
                chestCountDigit1[i].visible = false;
                chestCountDigit2[i].visible = false;
            }

            for (int i = 0; i < CRAFTING_SLOTS; i++) {
                boolean isResult = i == CRAFTING_RESULT_SLOT;
                boolean slotVisible = inventoryOpen;
                craftingSlotBackgrounds[i].visible = slotVisible;

                UILayer.UIElement itemElement = craftingSlotItems[i];
                String itemId = null;

                if (isResult) {
                    CraftingManager.CraftingRecipe match = ctx.craftingManager.matchRecipe(playerInventory.getCraftingGrid());
                    if (match != null) {
                        itemId = match.resultItemId;
                    }
                } else {
                    int gridRow = i / 2;
                    int gridCol = i % 2;
                    itemId = playerInventory.getCraftingGrid()[gridRow][gridCol];
                }

                if (slotVisible && itemId != null) {
                    ItemDefinition definition = itemDefinitions.getDefinition(itemId);
                    itemElement.visible = true;
                    itemElement.textureId = textureManager.getTextureArrayId();
                    itemElement.textureType = 2; // Array
                    itemElement.layer = definition.iconLayer;
                    itemElement.color.set(1, 1, 1, 1);
                    itemElement.pos.set(craftingSlotBackgrounds[i].pos.x + 24, craftingSlotBackgrounds[i].pos.y + 16);
                    itemElement.size.set(40, 40);
                } else {
                    itemElement.visible = false;
                }
            }
        }

        for (int index = 0; index < INVENTORY_SIZE; index++) {
            boolean slotVisible = index < HOTBAR_SIZE || inventoryOpen;
            slotBackgrounds[index].visible = slotVisible;

            ItemStack stack = playerInventory.getSlot(index);
            UILayer.UIElement itemElement = slotItemElements[index];
            UILayer.UIElement countBar = slotCountBars[index];
            UILayer.UIElement digit1 = slotCountDigit1[index];
            UILayer.UIElement digit2 = slotCountDigit2[index];

            if (!slotVisible || stack == null) {
                itemElement.visible = false;
                countBar.visible = false;
                digit1.visible = false;
                digit2.visible = false;
                continue;
            }

            ItemDefinition definition = itemDefinitions.getDefinition(stack.itemId);
            itemElement.visible = true;
            itemElement.textureId = textureManager.getTextureArrayId();
            itemElement.textureType = 2; // Array
            itemElement.layer = definition.iconLayer;
            itemElement.color.set(1, 1, 1, 1);
            
            if (definition.kind == ItemDefinitions.ItemKind.TOOL) {
                itemElement.size.set(40, 40);
                itemElement.pos.set(slotBackgrounds[index].pos.x + 24, slotBackgrounds[index].pos.y + 14);
            } else {
                itemElement.size.set(40, 40);
                itemElement.pos.set(slotBackgrounds[index].pos.x + 24, slotBackgrounds[index].pos.y + 16);
            }

            if (inventoryOpen && slotBackgrounds[index].isPointInside(lastMouseX, lastMouseY)) {
                itemNameElement.text = definition.displayName;
                itemNameElement.visible = true;
                itemNameElement.color.w = 1.0f;
                itemNameDisplayUntil = time + 0.1; // Stay while hovering
            }

            if (definition.maxStack > 1 && stack.count > 1) {
                countBar.visible = true;
                countBar.color.set(definition.color.x, definition.color.y, definition.color.z, 0.85f);
                countBar.pos.set(slotBackgrounds[index].pos.x + 12, slotBackgrounds[index].pos.y + SLOT_H - 12);
                countBar.size.set((SLOT_W - 24) * Math.min(stack.count, definition.maxStack) / (float) definition.maxStack, 6);

                if (fontTextureId != 0) {
                    if (stack.count >= 10) {
                        digit1.visible = true;
                        int d1 = stack.count / 10;
                        int charCode = 48 + d1;
                        digit1.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                        digit1.uvScale.set(1 / 16.0f, 1 / 16.0f);
                        
                        digit2.visible = true;
                        int d2 = stack.count % 10;
                        charCode = 48 + d2;
                        digit2.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                        digit2.uvScale.set(1 / 16.0f, 1 / 16.0f);
                    } else {
                        digit1.visible = false;
                        digit2.visible = true;
                        int d2 = stack.count;
                        int charCode = 48 + d2;
                        digit2.uvOffset.set((charCode % 16) / 16.0f, (charCode / 16) / 16.0f);
                        digit2.uvScale.set(1 / 16.0f, 1 / 16.0f);
                    }
                } else {
                    digit1.visible = false;
                    digit2.visible = false;
                }
            } else {
                countBar.visible = false;
                digit1.visible = false;
                digit2.visible = false;
            }
        }

        carriedItemElement.visible = inventoryOpen && playerInventory.getCarriedStack() != null;
        if (carriedItemElement.visible) {
            ItemDefinition definition = itemDefinitions.getDefinition(playerInventory.getCarriedStack().itemId);
            carriedItemElement.textureId = textureManager.getTextureArrayId();
            carriedItemElement.textureType = 2; // Array
            carriedItemElement.layer = definition.iconLayer;
            carriedItemElement.color.set(1, 1, 1, 0.9f);
            carriedItemElement.pos.set(lastMouseX - 14, lastMouseY - 14);
            carriedItemElement.size.set(28, 28);
        }

        if (itemNameDisplayUntil > time) {
            itemNameElement.visible = true;
            float alpha = (float) Math.min(1.0, (itemNameDisplayUntil - time) / 0.5);
            itemNameElement.color.w = alpha;
        } else {
            itemNameElement.visible = false;
        }

        commandTextElement.visible = commandMode;
        if (commandMode) {
            commandTextElement.text = commandBuffer.toString() + "_";
        }

        statusTextElement.visible = !statusMessage.isEmpty() && time < statusUntil;
        if (statusTextElement.visible) {
            statusTextElement.text = statusMessage;
            statusTextElement.lineOffset = statusLineOffset;
            float alpha = (float) Math.min(1.0, (statusUntil - time) / 0.5);
            statusTextElement.color.w = alpha;
        }

        // Update Player Hearts
        float hp = player.getHealth();
        for (int i = 0; i < 10; i++) {
            float texW = uiTextureSize.x;
            float texH = uiTextureSize.y;

            // Heart base/container behind each heart (always visible, 27x27)
            UILayer.UIElement heartBase = heartBases[i];
            heartBase.visible = !commandMode;
            heartBase.pos.set(HOTBAR_X + i * 30 - 3, height - 40 - 3);
            heartBase.uvOffset.set((uvHeartBase.x + 0.5f) / texW, (uvHeartBase.y + 0.5f) / texH);
            heartBase.uvScale.set((uvHeartBase.z - 1.0f) / texW, (uvHeartBase.w - 1.0f) / texH);

            // Heart icon on top (only show when not empty — base alone handles the empty look)
            float heartValue = hp - (i * 2);
            UILayer.UIElement heart = playerHearts[i];
            if (heartValue < 1.0f) {
                // Empty: hide heart icon, base container shows through
                heart.visible = false;
            } else {
                heart.visible = !commandMode;
                heart.pos.set(HOTBAR_X + i * 30, height - 40);

                Vector4f uv = (heartValue >= 2.0f) ? uvHeartFull : uvHeartHalf;
                // Inset UV by half a pixel to prevent texture atlas bleeding
                heart.uvOffset.set((uv.x + 0.5f) / texW, (uv.y + 0.5f) / texH);
                heart.uvScale.set((uv.z - 1.0f) / texW, (uv.w - 1.0f) / texH);
            }
        }
    }

    // --- Furnace slot click handler ---
    private void handleFurnaceSlotClick(int slot) {
        // slot 0 = input, 1 = fuel, 2 = output
        FurnaceManager.FurnaceState state = ctx.furnaceManager.getState(ctx.furnaceBlockX, ctx.furnaceBlockY, ctx.furnaceBlockZ);
        ItemStack carried = playerInventory.getCarriedStack();

        if (slot == 2) {
            // Output slot: take items out to inventory or carried
            if (state.output != null) {
                if (carried == null) {
                    if (playerInventory.addItem(state.output.itemId, state.output.count)) {
                        state.output = null;
                    }
                } else if (carried.itemId.equals(state.output.itemId) && carried.count + state.output.count <= 64) {
                    carried.count += state.output.count;
                    state.output = null;
                }
            }
            return;
        }

        // Input (0) or fuel (1) slot
        ItemStack slotStack = (slot == 0) ? state.input : state.fuel;

        if (carried == null && slotStack != null) {
            // Pick up the item from the slot
            playerInventory.setCarriedStack(slotStack.copy());
            if (slot == 0) state.input = null;
            else state.fuel = null;
        } else if (carried != null && slotStack == null) {
            // Place carried item into the slot
            ItemStack placed = carried.copy();
            placed.count = 1;
            if (slot == 0) state.input = placed;
            else state.fuel = placed;
            carried.count--;
            if (carried.count <= 0) playerInventory.setCarriedStack(null);
        } else if (carried != null && slotStack != null && carried.itemId.equals(slotStack.itemId) && slotStack.count < 64) {
            // Stack items
            int transfer = Math.min(carried.count, 64 - slotStack.count);
            slotStack.count += transfer;
            carried.count -= transfer;
            if (carried.count <= 0) playerInventory.setCarriedStack(null);
        }

        // Save furnace state after modification
        if (ctx.worldSaveManager != null) {
            ctx.worldSaveManager.saveFurnaceData(ctx.activeDimension, ctx.furnaceManager);
        }
    }

    // --- Chest slot click handler ---
    private void handleChestSlotClick(int slot) {
        ItemStack[] chestInv = ctx.chestManager.getInventory(ctx.chestBlockX, ctx.chestBlockY, ctx.chestBlockZ);
        if (chestInv == null) return;

        ItemStack slotStack = chestInv[slot];
        ItemStack carried = playerInventory.getCarriedStack();

        if (carried == null && slotStack != null) {
            // Pick up from chest
            playerInventory.setCarriedStack(slotStack.copy());
            chestInv[slot] = null;
        } else if (carried != null && slotStack == null) {
            // Place into chest
            ItemStack placed = carried.copy();
            chestInv[slot] = placed;
            playerInventory.setCarriedStack(null);
        } else if (carried != null && slotStack != null && carried.itemId.equals(slotStack.itemId) && slotStack.count < 64) {
            // Stack
            int transfer = Math.min(carried.count, 64 - slotStack.count);
            slotStack.count += transfer;
            carried.count -= transfer;
            if (carried.count <= 0) playerInventory.setCarriedStack(null);
        }

        ctx.chestManager.setInventory(ctx.chestBlockX, ctx.chestBlockY, ctx.chestBlockZ, chestInv);
        if (ctx.worldSaveManager != null) {
            ctx.worldSaveManager.saveChestData(ctx.activeDimension, ctx.chestManager);
        }
    }

    private void showSelectedItemName() {
        ItemStack stack = playerInventory.getSlot(playerInventory.getSelectedSlot());
        if (stack != null) {
            ItemDefinition definition = itemDefinitions.getDefinition(stack.itemId);
            if (definition != null) {
                itemNameElement.text = definition.displayName;
                itemNameDisplayUntil = glfwGetTime() + 3.0;
            }
        } else {
            itemNameDisplayUntil = 0.0;
        }
    }

    // Crafting table texture layout (16x16): 2x2 pixel cells, 1px borders, 4px margins
    private static final float CT_MARGIN = 4.0f / 16.0f;     // 0.25
    private static final float CT_CELL = 2.0f / 16.0f;       // 0.125
    private static final float CT_GAP = 1.0f / 16.0f;        // 0.0625
    private static final float CT_STEP = CT_CELL + CT_GAP;   // 0.1875
    private static final float CT_HALF_CELL = CT_CELL / 2.0f; // 0.0625
    private static final float CRAFTING_ITEM_SCALE = 0.125f; // 1/8 scale — fills one 2x2 pixel cell

    private void uploadCraftingItems() {
        String[][] grid = null;
        float bx, bz, by;

        if (ctx.craftingTableOpen) {
            // Use player's current crafting grid while UI is open
            grid = playerInventory.getCraftingGrid3x3();
            bx = ctx.craftingTableBlockX;
            bz = ctx.craftingTableBlockZ;
            by = ctx.craftingTableBlockY + 1.0f + CRAFTING_ITEM_SCALE * 0.5f;
        } else {
            // Show items from CraftingTableManager when not crafting
            if (ctx.craftingTableManager.hasGrid(ctx.craftingTableBlockX, ctx.craftingTableBlockY, ctx.craftingTableBlockZ)) {
                grid = ctx.craftingTableManager.getGrid(ctx.craftingTableBlockX, ctx.craftingTableBlockY, ctx.craftingTableBlockZ);
                bx = ctx.craftingTableBlockX;
                bz = ctx.craftingTableBlockZ;
                by = ctx.craftingTableBlockY + 1.0f + CRAFTING_ITEM_SCALE * 0.5f;
            } else {
                glProgramUniform1i(computeProgram, locCraftingItemCount, 0);
                return;
            }
        }

        float[] itemData = new float[9 * 8]; // 9 items * 8 floats per CraftingItem (2x vec4)
        int count = 0;

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                String itemId = grid[r][c];
                if (itemId != null) {
                    ItemDefinitions.ItemDefinition def = itemDefinitions.getDefinition(itemId);
                    // Only render blocks that have a blockId (skip tools like pickaxes)
                    if (def != null && def.blockId > 0) {
                        // Position at the center of the 2x2 pixel cell on the texture
                        // At yaw=0 (crafting camera), screen right = +Z, screen up = +X
                        // So columns map to Z (left-right) and rows map to X reversed (top-bottom)
                        // Item rests on the table surface: center Y = surface + half the item height
                        float pz = bz + CT_MARGIN + c * CT_STEP + CT_HALF_CELL;
                        float px = bx + (1.0f - CT_MARGIN) - r * CT_STEP - CT_HALF_CELL;
                        int idx = count * 8;
                        // position.xyz, w = blockId (stored as float bits for the shader)
                        itemData[idx] = px;
                        itemData[idx + 1] = by;
                        itemData[idx + 2] = pz;
                        itemData[idx + 3] = Float.intBitsToFloat(def.blockId);
                        // blockInfo.x = scale, yzw padding
                        itemData[idx + 4] = CRAFTING_ITEM_SCALE;
                        itemData[idx + 5] = 0;
                        itemData[idx + 6] = 0;
                        itemData[idx + 7] = 0;
                        count++;
                    }
                }
            }
        }

        if (count > 0) {
            java.nio.FloatBuffer buf = MemoryUtil.memAllocFloat(count * 8);
            buf.put(itemData, 0, count * 8);
            buf.flip();
            glNamedBufferSubData(craftingItemSSBO, 0, buf);
            MemoryUtil.memFree(buf);
        }

        glProgramUniform1i(computeProgram, locCraftingItemCount, count);
    }

    private void bindTextures() {
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
        glActiveTexture(GL_TEXTURE9);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getGrassColormapId());
        glUniform1i(locGrassColormap, 9);
        glActiveTexture(GL_TEXTURE10);
        glBindTexture(GL_TEXTURE_2D, uiManager.getUITexture());
        glUniform1i(locUITexture, 10);
        glActiveTexture(GL_TEXTURE14);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getFoliageColormapId());
        glUniform1i(locFoliageColormap, 14);
    }

    private void setupResources() {
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
        
        biomeManager = new com.voxel.utils.BiomeManager();
        biomeManager.loadColormaps(
            "src/main/resources/assets/minecraft/textures/colormap/grass.png",
            "src/main/resources/assets/minecraft/textures/colormap/foliage.png"
        );
        biomeManager.generateBiomeMap(2048);
        blockDataManager = new BlockDataManager();
        blockDataManager.registerBlock(1, "grass_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(2, "stone", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(3, "glass", textureManager, "src/main/resources/assets/minecraft/models/block", 150, 50, 255);
        blockDataManager.registerBlock(4, "oak_leaves", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(5, "oak_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(13, "dirt", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(14, "sand", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(15, "water", textureManager, "src/main/resources/assets/minecraft/models/block", 150, 100, 255);
        blockDataManager.registerBlock(16, "obsidian", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(17, "glowstone", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 0, 255, 255);
        blockDataManager.registerBlock(18, "end_stone", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(19, "nether_portal", textureManager, "src/main/resources/assets/minecraft/models/block", 60, 0, 255, 180);
        // --- Nether Dimension Blocks ---
        blockDataManager.registerBlock(20, "netherrack", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(21, "lava", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 50, 255, 200);
        blockDataManager.registerBlock(22, "soul_sand", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(23, "quartz_ore", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(24, "nether_bricks", textureManager, "src/main/resources/assets/minecraft/models/block");
        // --- Redstone Blocks ---
        blockDataManager.registerBlock(25, "redstone_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(26, "redstone_ore", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(27, "redstone_torch", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 0, 255, 200);
        blockDataManager.registerBlock(28, "redstone_lamp", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(29, "redstone_wire", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(30, "redstone_lamp_on", textureManager, "src/main/resources/assets/minecraft/models/block", 0, 0, 255, 255);
        // --- Piston Blocks ---
        blockDataManager.registerBlock(31, "piston_normal", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(32, "sticky_piston", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(33, "piston_head_normal", textureManager, "src/main/resources/assets/minecraft/models/block");
        // --- Aether Dimension Blocks ---
        String aetherModels = "src/main/resources/assets/aether/models/block";
        blockDataManager.registerBlock(100, "aether_grass_block", textureManager, aetherModels);
        blockDataManager.registerBlock(101, "holystone", textureManager, aetherModels);
        blockDataManager.registerBlock(102, "aether_dirt", textureManager, aetherModels);
        blockDataManager.registerBlock(103, "skyroot_log", textureManager, aetherModels);
        blockDataManager.registerBlock(104, "skyroot_leaves", textureManager, aetherModels);
        blockDataManager.registerBlock(105, "aerogel", textureManager, aetherModels, 120, 30, 255);
        blockDataManager.registerBlock(106, "aether_portal_ns", textureManager, aetherModels, 0, 0, 255, 0);
        blockDataManager.registerBlock(115, "aether_portal_ew", textureManager, aetherModels, 0, 0, 255, 0);
        
        blockDataManager.registerBlock(107, "ambrosium_ore", textureManager, aetherModels);
        blockDataManager.registerBlock(108, "gravitite_ore", textureManager, aetherModels);
        blockDataManager.registerBlock(109, "quicksoil", textureManager, aetherModels);
        blockDataManager.registerBlock(110, "icestone", textureManager, aetherModels);
        blockDataManager.registerBlock(111, "zanite_ore", textureManager, aetherModels);
        blockDataManager.registerBlock(112, "skyroot_planks", textureManager, aetherModels);
        blockDataManager.registerBlock(113, "mossy_holystone", textureManager, aetherModels);
        blockDataManager.registerBlock(114, "holystone_bricks", textureManager, aetherModels);
        // --- Vegetation & Decorative Blocks ---
        blockDataManager.registerBlock(119, "birch_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(120, "spruce_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(121, "dandelion", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(122, "rose", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(123, "tallgrass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(124, "blue_aercloud", textureManager, aetherModels, 100, 0, 255);
        blockDataManager.registerBlock(125, "cold_aercloud", textureManager, aetherModels, 100, 0, 255);
        blockDataManager.registerBlock(126, "golden_aercloud", textureManager, aetherModels, 100, 0, 255);
        blockDataManager.uploadToGPU();
    }

    private void generateCapeTexture() {
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

    private void setupQuad() {
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

    private void setupTexture() {
        renderTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(renderTexture, 1, GL_RGBA8, width, height);
        glTextureParameteri(renderTexture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(renderTexture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    private void uploadWorldToGpu() {
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

        // Crafting item SSBO (max 9 items * 32 bytes each = 288 bytes)
        craftingItemSSBO = glCreateBuffers();
        glNamedBufferStorage(craftingItemSSBO, 288, GL_DYNAMIC_STORAGE_BIT);

        uploadDirtyChunks();
    }

    // Reusable direct buffers for uploadDirtyChunks (allocated once, reused per frame)
    private java.nio.IntBuffer reusableVoxelBuf;
    private java.nio.IntBuffer reusableMaskBuf;
    private java.nio.ShortBuffer reusableOccBuf;
    private java.nio.IntBuffer reusableTableBuf;

    private void uploadDirtyChunks() {
        java.util.Set<Integer> dirty = chunkManager.getDirtySlots();
        if (dirty.isEmpty() && !chunkManager.isTableDirty()) return;

        int vpc = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        int poolSize = world.getPoolSizeForAlloc();

        // Lazy-init reusable buffers sized to current world
        if (reusableVoxelBuf == null || reusableVoxelBuf.capacity() < vpc) {
            if (reusableVoxelBuf != null) MemoryUtil.memFree(reusableVoxelBuf);
            if (reusableMaskBuf != null) MemoryUtil.memFree(reusableMaskBuf);
            if (reusableOccBuf != null) MemoryUtil.memFree(reusableOccBuf);
            if (reusableTableBuf != null) MemoryUtil.memFree(reusableTableBuf);
            reusableVoxelBuf = MemoryUtil.memAllocInt(vpc);
            reusableMaskBuf = MemoryUtil.memAllocInt(128);
            reusableOccBuf = MemoryUtil.memAllocShort(vpc);
            reusableTableBuf = MemoryUtil.memAllocInt(REGION_SIZE * REGION_SIZE * REGION_SIZE);
        }

        if (chunkManager.isTableDirty()) {
            int[] table = world.getIndirectionTable();
            reusableTableBuf.clear();
            reusableTableBuf.put(table).flip();
            glNamedBufferSubData(indirectionSSBO, 0, reusableTableBuf);
        }

        int[] pool = world.getChunkPool();
        int[] masks = world.getBitmaskPool();
        short[] occs = world.getOcclusionPool();
        for (int s : dirty) {
            reusableVoxelBuf.clear();
            reusableVoxelBuf.put(pool, s * vpc, vpc).flip();
            glNamedBufferSubData(chunkPoolSSBO, (long) s * vpc * Integer.BYTES, reusableVoxelBuf);

            reusableMaskBuf.clear();
            reusableMaskBuf.put(masks, s * 128, 128).flip();
            glNamedBufferSubData(bitmaskSSBO, (long) s * 128 * Integer.BYTES, reusableMaskBuf);

            reusableOccBuf.clear();
            reusableOccBuf.put(occs, s * vpc, vpc).flip();
            glNamedBufferSubData(occlusionSSBO, (long) s * vpc * Short.BYTES, reusableOccBuf);
        }
        chunkManager.clearDirty();
    }

    public static void main(String[] args) {
        new Main().run();
    }

    private Vector3f getLookDirection() {
        return new Vector3f(
            (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))),
            (float) Math.sin(Math.toRadians(pitch)),
            (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)))
        ).normalize();
    }

    private Vector3f getPlayerEyePosition() {
        return new Vector3f(player.getPosition()).add(0, PLAYER_EYE_HEIGHT, 0);
    }

    private Vector3f getActiveCameraPosition() {
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

        // Crafting camera: 45° isometric-like angle, position computed during cutscene setup
        if (ctx.craftingTableOpen) {
            return new Vector3f(ctx.cutsceneCameraTargetPos);
        }

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

    private Vector3f resolveCameraCollision(Vector3f origin, Vector3f desired) {
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

    private boolean isSolidCameraSample(Vector3f sample) {
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
    private int raycastCraftingCell() {
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

    private int[] raycastBlock(float maxDist) {
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
