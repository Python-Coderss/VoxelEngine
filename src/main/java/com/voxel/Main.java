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
import com.voxel.GameLogger;
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
    public int locBiomeMap, locGrassColormap, locUITexture, locFoliageColormap, locUISource;
    public int locHeartUVs;
    public int locCraftingItemCount;
    public int craftingItemSSBO;
    public java.util.Iterator<Integer> dirtyUploadIterator;
    public static final int MAX_DIRTY_UPLOADS_PER_FRAME = 48;
    public int locQuadPass; // Cached quad shader u_Pass uniform

    // Reusable direct buffer for SDF SSBO sub-uploads (avoid per-frame alloc).
    private java.nio.ByteBuffer reusableSdfBuf;

    // ── SDF sky early-out: replaces the rasterized depth prepass ──
    // The compute shader uses cheap Y/X/Z-plane SDF tests against these bounds
    // to skip DDA when the ray will not hit any loaded terrain.
    public int locVPMatrix = -1;             // unused (kept for binary-compat elsewhere if any)
    public int locWorldOffsetDepth = -1;     // unused

    public FloatBuffer persistentPlBuf; // Persistent FloatBuffer for pointLightSSBO (no per-frame alloc)
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

    public com.voxel.camera.CameraController cameraController;
    public com.voxel.ui.HudUI hud;

    public int width = 1280, height = 720;
    public final int CHUNK_SIZE = 16, REGION_SIZE = 128;

    public float lastMouseX = width / 2f, lastMouseY = height / 2f;
    public boolean firstMouse = true;
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
        glDeleteBuffers(lightSSBO);
        glDeleteBuffers(craftingItemSSBO);
        glDeleteBuffers(sdfSSBO);
        if (persistentPlBuf != null) MemoryUtil.memFree(persistentPlBuf);
        chunkManager.shutdown();
        RedstoneLogger.shutdown();
        WorldGenLogger.shutdown();
        GameLogger.shutdown();

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    public void init() {
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
        locQuadPass = glGetUniformLocation(quadProgram, "u_Pass"); // Cache to avoid per-frame lookup
        cacheUniformLocations();
        // Atmosphere uniforms handled by AtmosphereRenderer (no per-frame glGetUniformLocation)

        // (Rasterized depth prepass removed — replaced by cheaper SDF sky early-out
        //  in the compute shader, using u_MaxTerrain* uniforms uploaded every frame.)

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
        cameraController = new CameraController(ctx, this);
        ctx.activeDimension = activeDimension;
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

        // Tracks items dropped in the world (hover + auto-pickup). Initialized after
        // playerInventory because pickup uses ctx.playerInventory.addItem().
        ctx.droppedItemManager = new com.voxel.game.DroppedItemManager(ctx);

        blockInteraction = new BlockInteraction(ctx);
        portalSystem = new PortalSystem(ctx, blockInteraction);
        commandProcessor = new CommandProcessor(ctx);
        atmosphereRenderer = new AtmosphereRenderer(computeProgram);

        // Initialize crafting system (MUST be before setupUi)
        craftingManager = new CraftingManager();
        ctx.craftingManager = craftingManager;

        hud = new com.voxel.ui.HudUI(ctx, this, cameraController, playerInventory, textureManager, itemDefinitions, biomeManager);
        setupUi();

        // Initialize world save manager (dev/world folder)
        ctx.worldSaveManager = new com.voxel.world.WorldSaveManager("dev/world");

        // Initialize world gen logging
        GameLogger.init();

        WorldGenLogger.init();

        // Initialize dimension system (only create Overworld at startup to save memory)
        dimensionManager = new DimensionManager(blockDataManager, ctx.worldSaveManager, biomeManager);
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

        // Persistent FloatBuffer for pointLightSSBO zeroing
        persistentPlBuf = MemoryUtil.memAllocFloat(4);
        persistentPlBuf.put(0, Float.intBitsToFloat(0));

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

    public void cacheUniformLocations() {
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

    public void spawnInitialEnemies(Player p) {
        for (int i = 0; i < 3; i++) {
            com.voxel.entity.ZombieEntity zombie = new com.voxel.entity.ZombieEntity(100 + i, new Vector3f(1030 + i * 12, 64, 1030), textureManager, p);
            zombie.dimension = activeDimension;
            zombie.setWorld(world);
            entityManager.addEntity(zombie);
            
        }
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

    public void tick(float dt) {
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
            playerEntity.syncFromPlayer(player, playerYaw, pitch, cameraMode != CameraMode.FIRST_PERSON, dt);
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

        // Tick dropped items (bob animation + auto-pickup when player walks over)
        if (ctx.droppedItemManager != null) {
            ctx.droppedItemManager.update(dt, player.getPosition());
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

    public void handleInput(float dt) {
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

            if (cameraMode == CameraMode.THIRD_PERSON_FOLLOW) {
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

    public void performCombatAttack(float damage) {
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

    public void loop() {
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

            // Upload biome map to GPU when the gen thread has slid it
            if (chunkManager.isBiomeMapDirty()) {
                biomeManager.uploadBiomeMap();
                chunkManager.clearBiomeMapDirty();
            }

            updateInventoryUi();
            hud.updateWindowTitle();

            hud.uiManager.begin();
            for (UILayer layer : hud.uiLayers) layer.render(hud.uiManager);
            hud.uiManager.end();

            Vector3f cameraPos = getActiveCameraPosition();

            entityManager.uploadToGPU(activeDimension, cameraPos);

            persistentPlBuf.rewind();
            glNamedBufferSubData(pointLightSSBO, 0, persistentPlBuf);

            // Compute camera vectors early (used by prepass and compute dispatch)
            double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
            float fx = (float) (Math.cos(ry) * Math.cos(rp)), fy = (float) Math.sin(rp), fz = (float) (Math.sin(ry) * Math.cos(rp));
            float rx = -fz, rz = fx;
            float rl = (float) Math.sqrt(rx * rx + rz * rz);
            if (rl > 0) { rx /= rl; rz /= rl; }
            float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
            
            // Camera Shake (was previously after the rasterized depth prepass)
            if (cameraShake > 0.01f) {
                cameraPos.x += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
                cameraPos.y += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
                cameraPos.z += (float)(Math.random() - 0.5) * cameraShake * 0.1f;
            }

            glUseProgram(computeProgram);
            glProgramUniform3f(computeProgram, 0, cameraPos.x, cameraPos.y, cameraPos.z);

            glProgramUniform3f(computeProgram, 1, fx, fy, fz);
            glProgramUniform3f(computeProgram, 2, rx, 0, rz);
            glProgramUniform3f(computeProgram, 3, ux, uy, uz);
            glProgramUniform1f(computeProgram, 4, worldTime);
            glProgramUniform1i(computeProgram, 5, entityManager.getEntityCount(activeDimension));
            atmosphereRenderer.upload(worldTime, activeDimension);
            glProgramUniform1i(computeProgram, atmosphereRenderer.locDimensionID(), activeDimension.id);
            // Upload world sliding window offset
            glProgramUniform3i(computeProgram, 6, world.getOffsetX(), world.getOffsetY(), world.getOffsetZ());

            // Upload block break overlay uniforms
            if (ctx.breakTargetX != Integer.MIN_VALUE) {
                glProgramUniform3i(computeProgram, 19, ctx.breakTargetX, ctx.breakTargetY, ctx.breakTargetZ);
                glProgramUniform1f(computeProgram, 20, ctx.breakProgress / Math.max(1.0f, ctx.blockDataManager.getHardness(ctx.world.getVoxel(ctx.breakTargetX, ctx.breakTargetY, ctx.breakTargetZ))));
            } else {
                glProgramUniform3i(computeProgram, 19, 0, 0, 0);
                glProgramUniform1f(computeProgram, 20, 0.0f);
            }
            // Upload destroy stage base layer index (computed from Minecraft destroy_stage_0 texture)
            int destroyBaseLayer = textureManager.getTextureIndex("destroy_stage_0");
            glProgramUniform1i(computeProgram, 21, destroyBaseLayer < 0 ? -1 : destroyBaseLayer);

            // Upload UI UVs
            glUniform4f(locHeartUVs, hud.uvHeartFull.x, hud.uvHeartFull.y, hud.uvHeartFull.z, hud.uvHeartFull.w);
            glUniform4f(locHeartUVs + 1, hud.uvHeartHalf.x, hud.uvHeartHalf.y, hud.uvHeartHalf.z, hud.uvHeartHalf.w);
            glUniform4f(locHeartUVs + 2, hud.uvHeartEmpty.x, hud.uvHeartEmpty.y, hud.uvHeartEmpty.z, hud.uvHeartEmpty.w);

            bindTextures();

            // Bind destroy_stage texture array at texture unit 17
            glActiveTexture(GL_TEXTURE17);
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getDestroyStageArrayId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_DestroyStages"), 17);

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
            // SDF sky early-out uniforms (replaces depth-prepass textures)
            glProgramUniform1f(computeProgram, 22, chunkManager.getMaxTerrainY());
            glProgramUniform1f(computeProgram, 23, chunkManager.getMaxTerrainX());
            glProgramUniform1f(computeProgram, 24, chunkManager.getMaxTerrainZ());
            glProgramUniform1f(computeProgram, 25, chunkManager.getMinTerrainX());
            glProgramUniform1f(computeProgram, 26, chunkManager.getMinTerrainZ());
            // u_BoundsValid: 1 after first terrain bounds update (gen thread sets it);
            // 0 before any chunk loads → shader falls through to plain DDA.
            glProgramUniform1i(computeProgram, 27, chunkManager.areBoundsValid() ? 1 : 0);
            // u_TopSolidY: highest solid voxel Y across all loaded chunks. Lets the
            // shader early-out sky-ray pixels (camera above AND ray.y > 0) without
            // falling into the per-chunk sphere-trace loop. Sentinel -1 = no chunks
            // loaded yet → shader treats it as "no known ceiling".
            glProgramUniform1i(computeProgram, 28, chunkManager.getTopSolidY());

            uploadCraftingItems();

            glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
            glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(quadProgram);
            glBindTextureUnit(0, renderTexture);
            glUniform1i(locQuadPass, 1);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            glfwSwapBuffers(window);
            glfwPollEvents();
            leftMousePressedThisFrame = false;
            // ctx.leftMousePressedThisFrame is consumed/reset by the logic thread in tick()
        }
    }

    public void handleKeyInput(long win, int key, int scancode, int action, int mods) {
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

    public void handleCommandModeKey(int key) {
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
        if (!commandMode) return;
        if (codepoint < 32 || codepoint > 126) return;
        commandBuffer.append((char) codepoint);
    }

    public void handleCursorMoved(long win, double xpos, double ypos) {
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

    public void handleMouseButton(long win, int button, int action, int mods) {
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

        if (button == GLFW_MOUSE_BUTTON_RIGHT) {
            if ((mods & GLFW_MOD_SHIFT) != 0) {
                portalSystem.attemptActivate();
            } else {
                blockInteraction.attemptPlaceBlock();
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

    public void setInventoryOpen(boolean open) {
        hud.inventoryUiDirty = true;
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

    public void updateCursorMode() {
        boolean freeCursor = inventoryOpen || commandMode;
        glfwSetInputMode(window, GLFW_CURSOR, freeCursor ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
        if (!freeCursor) firstMouse = true;
    }

    public void setStatus(String message) {
        statusMessage = message;
        statusUntil = glfwGetTime() + 3.0;
        statusLineOffset = 0;
        System.out.println(message);
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

        // ---- Crafting-grid items (existing behaviour) ----
        String[][] grid = null;
        if (ctx.craftingTableOpen) {
            grid = playerInventory.getCraftingGrid3x3();
        } else if (ctx.craftingTableManager.hasGrid(ctx.craftingTableBlockX, ctx.craftingTableBlockY, ctx.craftingTableBlockZ)) {
            grid = ctx.craftingTableManager.getGrid(ctx.craftingTableBlockX, ctx.craftingTableBlockY, ctx.craftingTableBlockZ);
        }

        if (grid != null) {
            float bx = ctx.craftingTableBlockX;
            float bz = ctx.craftingTableBlockZ;
            float by = ctx.craftingTableBlockY + 1.0f + CRAFTING_ITEM_SCALE * 0.5f;

            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    String itemId = grid[r][c];
                    if (itemId == null) continue;
                    ItemDefinitions.ItemDefinition def = itemDefinitions.getDefinition(itemId);
                    if (def == null || def.blockId <= 0) continue;
                    float pz = bz + CT_MARGIN + c * CT_STEP + CT_HALF_CELL;
                    float px = bx + (1.0f - CT_MARGIN) - r * CT_STEP - CT_HALF_CELL;
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
            dropCount = ctx.droppedItemManager.buildUpload(reusableItemDataBuf);
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
        glActiveTexture(GL_TEXTURE9);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getGrassColormapId());
        glUniform1i(locGrassColormap, 9);
        glActiveTexture(GL_TEXTURE10);
        glBindTexture(GL_TEXTURE_2D, hud.uiManager.getUITexture());
        glUniform1i(locUITexture, 10);
        glActiveTexture(GL_TEXTURE14);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getFoliageColormapId());
        glUniform1i(locFoliageColormap, 14);
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
        textureManager.loadDestroyStages("src/main/resources/assets/minecraft/textures/blocks");
        
        biomeManager = new com.voxel.utils.BiomeManager();
        biomeManager.loadColormaps(
            "src/main/resources/assets/minecraft/textures/colormap/grass.png",
            "src/main/resources/assets/minecraft/textures/colormap/foliage.png"
        );
        
        blockDataManager = new BlockDataManager();
        blockRegistry = new BlockRegistry();
        shaderBlockRegistry = new ShaderBlockRegistry();
        blockRegistry.register("grass_block", 1);
        shaderBlockRegistry.register(1, 1);
        blockDataManager.registerBlock(1, "grass_block", textureManager, "src/main/resources/assets/minecraft/models/block");
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
        blockDataManager.registerBlock(26, "redstone_ore", textureManager, "src/main/resources/assets/minecraft/models/block");
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
        blockRegistry.register("piston_normal", 31);
        shaderBlockRegistry.register(31, 31);
        blockDataManager.registerBlock(31, "piston_normal", textureManager, "src/main/resources/assets/minecraft/models/block");
        shaderBlockRegistry.registerDirectional(31, com.voxel.utils.Direction.DOWN, 31, 0);
        shaderBlockRegistry.registerDirectional(31, com.voxel.utils.Direction.UP, 31, 1);
        shaderBlockRegistry.registerDirectional(31, com.voxel.utils.Direction.NORTH, 31, 2);
        shaderBlockRegistry.registerDirectional(31, com.voxel.utils.Direction.SOUTH, 31, 3);
        shaderBlockRegistry.registerDirectional(31, com.voxel.utils.Direction.WEST, 31, 4);
        shaderBlockRegistry.registerDirectional(31, com.voxel.utils.Direction.EAST, 31, 5);
        blockRegistry.register("sticky_piston", 32);
        shaderBlockRegistry.register(32, 32);
        blockDataManager.registerBlock(32, "sticky_piston", textureManager, "src/main/resources/assets/minecraft/models/block");
        shaderBlockRegistry.registerDirectional(32, com.voxel.utils.Direction.DOWN, 32, 0);
        shaderBlockRegistry.registerDirectional(32, com.voxel.utils.Direction.UP, 32, 1);
        shaderBlockRegistry.registerDirectional(32, com.voxel.utils.Direction.NORTH, 32, 2);
        shaderBlockRegistry.registerDirectional(32, com.voxel.utils.Direction.SOUTH, 32, 3);
        shaderBlockRegistry.registerDirectional(32, com.voxel.utils.Direction.WEST, 32, 4);
        shaderBlockRegistry.registerDirectional(32, com.voxel.utils.Direction.EAST, 32, 5);
        blockRegistry.register("piston_head_normal", 33);
        shaderBlockRegistry.register(33, 33);
        blockDataManager.registerBlock(33, "piston_head_normal", textureManager, "src/main/resources/assets/minecraft/models/block");
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
        blockDataManager.registerBlock(117, "furnace_on", textureManager, "src/main/resources/assets/minecraft/models/block");
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
        blockDataManager.registerBlock(61, "coal_ore", textureManager, mcModels);
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
        blockDataManager.registerBlock(81, "iron_ore", textureManager, mcModels);
        blockRegistry.register("gold_ore", 82);
        shaderBlockRegistry.register(82, 82);
        blockDataManager.registerBlock(82, "gold_ore", textureManager, mcModels);
        blockRegistry.register("diamond_ore", 83);
        shaderBlockRegistry.register(83, 83);
        blockDataManager.registerBlock(83, "diamond_ore", textureManager, mcModels);
        blockRegistry.register("emerald_ore", 84);
        shaderBlockRegistry.register(84, 84);
        blockDataManager.registerBlock(84, "emerald_ore", textureManager, mcModels);
        blockRegistry.register("lapis_ore", 85);
        shaderBlockRegistry.register(85, 85);
        blockDataManager.registerBlock(85, "lapis_ore", textureManager, mcModels);
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

        // --- Torch ---
        blockRegistry.register("torch", 211);
        shaderBlockRegistry.register(211, 211);
        blockDataManager.registerBlock(211, "normal_torch", textureManager, mcModels, 0, 0, 255, 200);
        blockDataManager.setLightColor(211, 255, 220, 140);

        // Register shader state variants for directional and on/off blocks
        shaderBlockRegistry.registerOnOff(28, true, 30);
        shaderBlockRegistry.registerOnOff(116, true, 117);
        shaderBlockRegistry.register(29, 29);

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

    public void setupTexture() {
        renderTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(renderTexture, 1, GL_RGBA8, width, height);
        glTextureParameteri(renderTexture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(renderTexture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
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
        // Light pool upload: check BEFORE the dirty-slots early return.
        // The gen thread sets lightsNeedUpload=true asynchronously after BFS;
        // if we early-return because dirty slots are exhausted, the light pool
        // would never reach the GPU.
        if (chunkManager.needsLightUpload() && !chunkManager.isLightingActive()) {
            int[] lightPool = world.getLightPool();

            // Warn instead of crash: if BFS ran but the light pool is all zeros,
            // this is normal for unlit areas (caves with no emissive blocks) or
            // when sky light has not been computed yet. Skip upload to avoid flushing
            // stale zeros over current GPU data.
            boolean anyNonZero = false;
            for (int v : lightPool) {
                if (v != 0) { anyNonZero = true; break; }
            }
            if (!anyNonZero) {
                GameLogger.log("WARN: lightsNeedUpload=true but light pool is all zeros — skipping upload");
                chunkManager.clearLightUpload();
                // Fall through: continue uploading dirty slots (just skip the stale light upload)
            }

            java.nio.IntBuffer lightBuf = MemoryUtil.memAllocInt(lightPool.length);
            lightBuf.put(lightPool).flip();
            glNamedBufferSubData(lightSSBO, 0, lightBuf);
            MemoryUtil.memFree(lightBuf);
            chunkManager.clearLightUpload();
        }

        java.util.Set<Integer> dirty = chunkManager.getDirtySlots();

        // Capped upload: persist an iterator across frames to avoid per-frame spikes
        if (dirtyUploadIterator == null) {
            dirtyUploadIterator = dirty.iterator();
        }

        boolean tableDirty = chunkManager.isTableDirty();
        if (!dirtyUploadIterator.hasNext() && !tableDirty) {
            dirtyUploadIterator = null;
            return;
        }

        int vpc = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        int poolSize = world.getPoolSizeForAlloc();

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

        int uploaded = 0;
        while (dirtyUploadIterator.hasNext() && uploaded < MAX_DIRTY_UPLOADS_PER_FRAME) {
            int s = dirtyUploadIterator.next();
            dirtyUploadIterator.remove();

            reusableVoxelBuf.clear();
            reusableVoxelBuf.put(pool, s * vpc, vpc).flip();
            glNamedBufferSubData(chunkPoolSSBO, (long) s * vpc * Integer.BYTES, reusableVoxelBuf);

            reusableMaskBuf.clear();
            reusableMaskBuf.put(masks, s * 128, 128).flip();
            glNamedBufferSubData(bitmaskSSBO, (long) s * 128 * Integer.BYTES, reusableMaskBuf);

            reusableOccBuf.clear();
            reusableOccBuf.put(occs, s * vpc, vpc).flip();
            glNamedBufferSubData(occlusionSSBO, (long) s * vpc * Short.BYTES, reusableOccBuf);

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

        // Iterator exhausted: reset for next cycle
        if (!dirtyUploadIterator.hasNext()) {
            dirtyUploadIterator = null;
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
