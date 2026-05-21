package com.voxel;

import com.voxel.crafting.CraftingManager;
import com.voxel.lighting.LightPropagationEngine;
import com.voxel.ui.UILayer;
import com.voxel.ui.UIManager;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.ShaderUtil;
import com.voxel.utils.TextureManager;
import com.voxel.world.DimensionManager;
import com.voxel.world.DimensionType;
import com.voxel.game.AtmosphereRenderer;
import com.voxel.game.BlockInteraction;
import com.voxel.game.CommandProcessor;
import com.voxel.game.GameContext;
import com.voxel.game.ItemDefinitions;
import com.voxel.game.ItemDefinitions.ItemDefinition;
import com.voxel.game.ItemDefinitions.ItemStack;
import com.voxel.game.PlayerInventory;
import com.voxel.game.PortalSystem;
import com.voxel.world.RedstoneLogger;
import com.voxel.world.RedstoneManager;
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
import java.util.List;
import java.util.Locale;

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
    private static final int INVENTORY_SIZE = 30;
    private static final int SLOT_W = 88;
    private static final int SLOT_H = 80;
    private static final int SLOT_TEX_W = 22;
    private static final int SLOT_TEX_H = 20;
    private static final int HOTBAR_X = 10;
    private static final int HOTBAR_Y = 100;
    // Inventory panel: covers 6 columns + crafting grid (2 grid cols + arrow + result slot)
    private static final int INVENTORY_PANEL_WIDTH = 780;
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
    private int locBiomeMap, locGrassColormap, locUITexture, locFoliageColormap, locUISource;
    private int locHeartUVs;
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
        chunkManager.shutdown();
        RedstoneLogger.shutdown();

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
        ctx.uploadWorldToGpu = this::uploadWorldToGpu;
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

        // Initialize dimension system (only create Overworld at startup to save memory)
        dimensionManager = new DimensionManager(blockDataManager);
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
        for (int i = 0; i < 5; i++) {
            com.voxel.entity.ZombieEntity zombie = new com.voxel.entity.ZombieEntity(100 + i, new Vector3f(1030 + i * 10, 64, 1030), textureManager, p);
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
        float uScale = (float) SLOT_TEX_W / uiTextureSize.x;
        float vScale = (float) SLOT_TEX_H / uiTextureSize.y;

        // Build crafting grid slots (placed to the right of the main inventory)
        int craftingGridX = HOTBAR_X + 360;
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
                background.uvOffset = new Vector2f(0.0f, 0.0f);
                background.uvScale = new Vector2f(uScale, vScale);
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
                background.uvOffset = new Vector2f(column == 0 ? 0.0f : 0.0f, column == 0 ? row * vScale : 0.0f);
                background.uvScale = new Vector2f(uScale, vScale);
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
            hotbarActiveElement.uvOffset = new Vector2f(22.0f / uiTextureSize.x, 0.0f);
            hotbarActiveElement.uvScale = new Vector2f(uScale, vScale);
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
            handleInput(dt);
            player.update(dt, world, blockDataManager);
        }

        player.setYaw(playerYaw);
        player.setPitch(pitch);

        if (playerEntity != null) {
            playerEntity.syncFromPlayer(player, playerYaw, pitch, cameraMode == CameraMode.THIRD_PERSON, dt);
        }

        worldTime += dt;
        blockInteraction.updateMining(dt);

        if (cameraShake > 0) cameraShake -= dt * 5.0f;

        // --- Enemy AI (now handled inside EnemyEntity) ---
        Vector3f pPos = player.getPosition();
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            com.voxel.entity.Entity e = entityManager.getEntity(i);
            if (e instanceof com.voxel.entity.EnemyEntity) {
                com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) e;
                if (!enemy.isDead()) {
                    enemy.updateAI(pPos, dt);
                }
            }
        }

        entityManager.update(dt);
        portalSystem.checkTeleport();

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
        if (inventoryOpen || commandMode || player.isDead()) return;

        // Roll / Dash (Left Alt)
        if (glfwGetKey(window, GLFW_KEY_LEFT_ALT) == GLFW_PRESS && cameraMode == CameraMode.THIRD_PERSON) {
            double now = glfwGetTime();
            if (now - lastRollTime > 0.8) {
                playerEntity.startRoll();
                Vector3f forward = getLookDirection();
                player.move(forward.x * 15, 0, forward.z * 15, 10.0f);
                lastRollTime = now;
            }
        }

        if (leftMousePressedThisFrame && !inventoryOpen) {
            double now = glfwGetTime();
            float attackCooldown = combatMode ? 0.6f : 0.25f;
            if (now - lastAttackTime > attackCooldown) {
                playerEntity.startAttack();
                performCombatAttack();
                lastAttackTime = now;
            }
        }

        float speed = player.isFlying() ? 1.5f : 0.4f;
        double ry = Math.toRadians(yaw);
        float fx = (float) Math.cos(ry), fz = (float) Math.sin(ry);
        float rx = -fz, rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl > 0) {
            rx /= rl;
            rz /= rl;
        }

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

    private void performCombatAttack() {
        Vector3f pPos = player.getPosition();
        Vector3f pDir = getLookDirection();

        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            com.voxel.entity.Entity e = entityManager.getEntity(i);
            if (e instanceof com.voxel.entity.EnemyEntity) {
                com.voxel.entity.EnemyEntity enemy = (com.voxel.entity.EnemyEntity) e;
                if (enemy.isDead()) continue;

                Vector3f toEnemy = new Vector3f(enemy.position).sub(pPos);
                float dist = toEnemy.length();

                if (dist < 4.5f) {
                    toEnemy.normalize();
                    float dot = toEnemy.dot(pDir);
                    if (dot > 0.45f) {
                        Vector3f knockback = new Vector3f(toEnemy).mul(1.1f);
                        enemy.takeDamage(4.0f, knockback);   // 5 hits for 20 HP
                        cameraShake = 1.3f;
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
            }
            syncGameState();

            redstoneManager.applyLampChanges();
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

            entityManager.uploadToGPU();

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
            glProgramUniform1i(computeProgram, 5, entityManager.getEntityCount());
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
                }
                setStatus("Combat Mode: " + (combatMode ? "ON (Story Mode style)" : "OFF"));
                return;
            }

            if (key == GLFW_KEY_ESCAPE) {
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
            playerInventory.setCarriedStack(null);
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
        System.out.println(message);
    }    private void updateInventoryUi() {
        double time = glfwGetTime();
        crosshairElement.visible = !inventoryOpen && !commandMode;
        inventoryPanelElement.visible = inventoryOpen;
        hotbarActiveElement.visible = true;
        hotbarActiveElement.pos.y = HOTBAR_Y + playerInventory.getSelectedSlot() * SLOT_H;

        // Update crafting grid visibility and content
        int craftingPanelX = HOTBAR_X + 360;
        int craftingPanelY = HOTBAR_Y + 20;
        for (int i = 0; i < CRAFTING_SLOTS; i++) {
            boolean isResult = i == CRAFTING_RESULT_SLOT;
            boolean slotVisible = inventoryOpen;
            craftingSlotBackgrounds[i].visible = slotVisible;

            UILayer.UIElement itemElement = craftingSlotItems[i];
            String itemId = null;

            if (isResult) {
                // Show the crafted result if pattern matches
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
        blockDataManager.registerBlock(1, "grass_normal", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(2, "stone", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(3, "glass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(4, "oak_leaves", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(5, "oak_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(13, "dirt", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(14, "sand", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(15, "water_still", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(16, "obsidian", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(17, "glowstone", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(18, "end_stone", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(19, "nether_portal", textureManager, "src/main/resources/assets/minecraft/models/block");
        // --- Nether Dimension Blocks ---
        blockDataManager.registerBlock(20, "netherrack", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(21, "lava_still", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(22, "soul_sand", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(23, "quartz_ore", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(24, "nether_brick", textureManager, "src/main/resources/assets/minecraft/models/block");
        // --- Redstone Blocks ---
        blockDataManager.registerBlock(25, "redstone_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(26, "redstone_ore", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(27, "redstone_torch", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(28, "redstone_lamp", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(29, "redstone_dust", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(30, "redstone_lamp_on", textureManager, "src/main/resources/assets/minecraft/models/block");
        // --- Aether Dimension Blocks ---
        String aetherModels = "src/main/resources/assets/aether/models/block";
        blockDataManager.registerBlock(100, "aether_grass_block", textureManager, aetherModels);
        blockDataManager.registerBlock(101, "holystone", textureManager, aetherModels);
        blockDataManager.registerBlock(102, "aether_dirt", textureManager, aetherModels);
        blockDataManager.registerBlock(103, "skyroot_log", textureManager, aetherModels);
        blockDataManager.registerBlock(104, "skyroot_leaves", textureManager, aetherModels);
        blockDataManager.registerBlock(105, "aerogel", textureManager, aetherModels);
        blockDataManager.registerBlock(106, "aether_portal", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(107, "ambrosium_ore", textureManager, aetherModels);
        blockDataManager.registerBlock(108, "gravitite_ore", textureManager, aetherModels);
        blockDataManager.registerBlock(109, "quicksoil", textureManager, aetherModels);
        blockDataManager.registerBlock(110, "icestone", textureManager, aetherModels);
        blockDataManager.registerBlock(111, "zanite_ore", textureManager, aetherModels);
        blockDataManager.registerBlock(112, "skyroot_planks", textureManager, aetherModels);
        blockDataManager.registerBlock(113, "mossy_holystone", textureManager, aetherModels);
        blockDataManager.registerBlock(114, "holystone_bricks", textureManager, aetherModels);
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
