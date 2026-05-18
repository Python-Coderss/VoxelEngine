package com.voxel;

import com.voxel.lighting.LightPropagationEngine;
import com.voxel.ui.UILayer;
import com.voxel.ui.UIManager;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.ShaderUtil;
import com.voxel.utils.TextureManager;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

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
    private static final float DAY_START_TIME = 720.0f;
    private static final float PLAYER_HALF_WIDTH = 0.3f;
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_EYE_HEIGHT = 1.6f;
    private static final float THIRD_PERSON_DISTANCE = 4.0f;
    private static final float THIRD_PERSON_TARGET_HEIGHT = 1.35f;
    private static final float CAMERA_COLLISION_STEP = 0.1f;
    private static final int[] SKIN_HEAD_REGION = {8, 8, 8, 8};
    private static final int[] SKIN_BODY_REGION = {20, 20, 8, 12};
    private static final int[] SKIN_RIGHT_ARM_REGION = {44, 20, 4, 12};
    private static final int[] SKIN_LEFT_ARM_REGION = {36, 52, 4, 12};
    private static final int[] SKIN_RIGHT_LEG_REGION = {4, 20, 4, 12};
    private static final int[] SKIN_LEFT_LEG_REGION = {20, 52, 4, 12};

    private long window;
    private int quadProgram, computeProgram;
    private int quadVAO, quadVBO, renderTexture;
    private int indirectionSSBO, chunkPoolSSBO, bitmaskSSBO, occlusionSSBO, pointLightSSBO;

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

    private int width = 1280, height = 720;
    private final int CHUNK_SIZE = 16, REGION_SIZE = 128, POOL_SIZE = 16384;

    private float lastMouseX = width / 2f, lastMouseY = height / 2f;
    private boolean firstMouse = true;
    private float yaw = -90, pitch = 0;
    private float playerYaw = -90;

    private int selectedSlot = 0;
    private final ItemStack[] inventory = new ItemStack[INVENTORY_SIZE];
    private ItemStack carriedStack;

    private final Map<String, ItemDefinition> itemRegistry = new HashMap<>();
    private final Map<String, String> itemAliases = new HashMap<>();
    private final Map<Integer, String> blockItemByBlockId = new HashMap<>();

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
    private double itemNameDisplayUntil = 0.0;
    private UILayer.UITextElement commandTextElement;
    private UILayer.UITextElement statusTextElement;

    private Thread logicThread;
    private volatile boolean running = true;
    private CameraMode cameraMode = CameraMode.FIRST_PERSON;

    private enum GameMode {
        SURVIVAL,
        CREATIVE
    }

    private enum CameraMode {
        FIRST_PERSON,
        THIRD_PERSON
    }

    private enum ItemKind {
        BLOCK,
        TOOL
    }

    private enum ToolType {
        HAND,
        PICKAXE,
        SHOVEL,
        AXE
    }

    private static final class ItemDefinition {
        final String id;
        final String displayName;
        final ItemKind kind;
        final int blockId;
        final int iconLayer;
        final ToolType toolType;
        final float miningSpeed;
        final int maxStack;
        final Vector4f color;

        ItemDefinition(String id, String displayName, ItemKind kind, int blockId, int iconLayer, ToolType toolType, float miningSpeed, int maxStack, Vector4f color) {
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.blockId = blockId;
            this.iconLayer = iconLayer;
            this.toolType = toolType;
            this.miningSpeed = miningSpeed;
            this.maxStack = maxStack;
            this.color = new Vector4f(color);
        }
    }

    private static final class ItemStack {
        String itemId;
        int count;

        ItemStack(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }

        ItemStack copy() {
            return new ItemStack(itemId, count);
        }
    }

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

        entityManager = new com.voxel.entity.EntityManager();
        player = new Player(1024, 100, 1024);

        setupQuad();
        setupTexture();
        setupResources();
        setupItemRegistry();
        populateStartingInventory();

        uiManager = new UIManager(width, height);
        setupUi();

        world = new World();
        com.voxel.world.WorldGenerator generator = new com.voxel.world.WorldGenerator(97);
        LightPropagationEngine lightEngine = new LightPropagationEngine(world, blockDataManager);
        chunkManager = new com.voxel.world.ChunkManager(world, generator, lightEngine, 8);

        for (int i = 0; i < 5; i++) {
            com.voxel.entity.Entity e = new com.voxel.entity.Entity(i, new Vector3f(1024 + (i - 2) * 15, 75, 1024 + (i - 2) * 8));
            e.addPart(new com.voxel.entity.ModelPart("cube", new Vector3f(0, 0, 0), new Vector3f(32, 32, 32), 3));
            entityManager.addEntity(e);
        }

        for (int i = 0; i < 3; i++) {
            com.voxel.entity.Entity zombie = new com.voxel.entity.Entity(100 + i, new Vector3f(1030 + i * 10, 64, 1030));
            zombie.loadModel("src/main/resources/assets/minecraft/models/entity/zombie.json", textureManager);
            entityManager.addEntity(zombie);
        }

        playerEntity = new com.voxel.entity.PlayerEntity(10_000, new Vector3f(player.getPosition()), textureManager);
        entityManager.addEntity(playerEntity);

        chunkManager.update(player.getPosition());
        uploadWorldToGpu();
        updateCursorMode();
        setStatus("Mode: survival. Press E for inventory, / for commands.");
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
            new Vector2f(420, SLOT_H * HOTBAR_SIZE + 24),
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
            background.onClick = () -> handleInventorySlotClick(slotIndex);
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
            new Vector2f(HOTBAR_X, HOTBAR_Y + selectedSlot * SLOT_H),
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

    private void setupItemRegistry() {
        itemRegistry.clear();
        itemAliases.clear();
        blockItemByBlockId.clear();

        registerBlockItem("grass", "Grass Block", 1, "grass_side");
        registerBlockItem("stone", "Stone", 2, "stone");
        registerBlockItem("glass", "Glass", 3, "glass");
        registerBlockItem("leaves", "Oak Leaves", 4, "leaves_oak");
        registerBlockItem("dirt", "Dirt", 13, "dirt");
        registerBlockItem("sand", "Sand", 14, "sand");

        registerToolItem("wood_pickaxe", "Wood Pickaxe", "wood_pickaxe", ToolType.PICKAXE, 4.5f, new Vector4f(1, 1, 1, 1));
        registerToolItem("wood_shovel", "Wood Shovel", "wood_shovel", ToolType.SHOVEL, 4.0f, new Vector4f(1, 1, 1, 1));
        registerToolItem("wood_axe", "Wood Axe", "wood_axe", ToolType.AXE, 4.2f, new Vector4f(1, 1, 1, 1));

        registerAlias("pickaxe", "wood_pickaxe");
        registerAlias("shovel", "wood_shovel");
        registerAlias("axe", "wood_axe");
    }

    private void registerBlockItem(String itemId, String displayName, int blockId, String textureName) {
        Color albedo = blockDataManager.getAlbedo(blockId);
        Vector4f color = new Vector4f(albedo.getRed() / 255.0f, albedo.getGreen() / 255.0f, albedo.getBlue() / 255.0f, 1.0f);
        int iconLayer = textureManager.getTextureIndex(textureName);
        ItemDefinition definition = new ItemDefinition(itemId, displayName, ItemKind.BLOCK, blockId, iconLayer, ToolType.HAND, 1.0f, 64, color);
        itemRegistry.put(itemId, definition);
        blockItemByBlockId.put(blockId, itemId);
        registerAlias(itemId, itemId);
        registerAlias(itemId + "_block", itemId);
        registerAlias(displayName.toLowerCase(Locale.ROOT).replace(' ', '_'), itemId);
    }

    private void registerToolItem(String itemId, String displayName, String textureName, ToolType toolType, float miningSpeed, Vector4f color) {
        int iconLayer = textureManager.getTextureIndex(textureName);
        ItemDefinition definition = new ItemDefinition(itemId, displayName, ItemKind.TOOL, 0, iconLayer, toolType, miningSpeed, 1, color);
        itemRegistry.put(itemId, definition);
        registerAlias(itemId, itemId);
        registerAlias(displayName.toLowerCase(Locale.ROOT).replace(' ', '_'), itemId);
    }

    private void registerAlias(String alias, String itemId) {
        itemAliases.put(alias.toLowerCase(Locale.ROOT), itemId);
    }

    private void populateStartingInventory() {
        inventory[0] = new ItemStack("wood_pickaxe", 1);
        inventory[1] = new ItemStack("wood_shovel", 1);
        inventory[2] = new ItemStack("stone", 32);
        inventory[3] = new ItemStack("dirt", 32);
        inventory[4] = new ItemStack("glass", 16);
        inventory[5] = new ItemStack("wood_axe", 1);
        inventory[6] = new ItemStack("grass", 32);
        inventory[7] = new ItemStack("sand", 32);
        inventory[8] = new ItemStack("leaves", 24);
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

    private void tick(float dt) {
        if (!running) return;

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
        updateMining(dt);

        float entityTime = (float) glfwGetTime();
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            com.voxel.entity.Entity e = entityManager.getEntity(i);
            if (e == playerEntity) {
                continue;
            }
            if (e.id < 100) {
                e.rotation.y += dt * 45.0f;
                e.rotation.x += dt * 15.0f;
                e.position.y = 75.0f + (float) Math.sin(entityTime + i) * 5.0f;
            } else {
                e.position.x += (float) Math.cos(entityTime * 0.5f + i) * 0.05f;
                e.position.z += (float) Math.sin(entityTime * 0.5f + i) * 0.05f;
            }
        }

        entityManager.update(dt);
        chunkManager.update(player.getPosition());
    }

    private void handleInput(float dt) {
        if (inventoryOpen || commandMode) return;

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
            if (cameraMode == CameraMode.THIRD_PERSON) {
                playerYaw = (float) Math.toDegrees(Math.atan2(dz, dx));
            }
        }
        player.move(dx * speed, 0, dz * speed, speed * 2.0f);

        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            if (player.isFlying()) player.move(0, speed, 0, speed * 2.0f);
            else player.jump();
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            if (player.isFlying()) player.move(0, -speed, 0, speed * 2.0f);
        }

        if (gameMode == GameMode.CREATIVE) {
            if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) player.setFlying(true);
            if (glfwGetKey(window, GLFW_KEY_G) == GLFW_PRESS) player.setFlying(false);
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

            uploadDirtyChunks();
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

            bindTextures();

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
        }
    }

    private void handleKeyInput(long win, int key, int scancode, int action, int mods) {
        if (action == GLFW_PRESS) {
            if (commandMode) {
                handleCommandModeKey(key);
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
                selectedSlot = key - GLFW_KEY_1;
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
            updateCursorMode();
            executeCommand(text);
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
    }

    private void handleMouseButton(long win, int button, int action, int mods) {
        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW_PRESS) {
                leftMouseHeld = true;
                leftMousePressedThisFrame = true;
            } else if (action == GLFW_RELEASE) {
                leftMouseHeld = false;
                resetMining();
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
            attemptPlaceBlock();
        }
    }

    private void openCommandMode() {
        if (inventoryOpen) setInventoryOpen(false);
        commandMode = true;
        commandBuffer.setLength(0);
        updateCursorMode();
    }

    private void cancelCommandMode() {
        commandMode = false;
        commandBuffer.setLength(0);
        updateCursorMode();
    }

    private void toggleInventory() {
        setInventoryOpen(!inventoryOpen);
    }

    private void toggleCameraMode() {
        cameraMode = cameraMode == CameraMode.FIRST_PERSON ? CameraMode.THIRD_PERSON : CameraMode.FIRST_PERSON;
        setStatus("Camera: " + (cameraMode == CameraMode.FIRST_PERSON ? "first person" : "third person"));
    }

    private void setInventoryOpen(boolean open) {
        inventoryOpen = open;
        if (!open) carriedStack = null;
        updateCursorMode();
    }

    private void updateCursorMode() {
        boolean freeCursor = inventoryOpen || commandMode;
        glfwSetInputMode(window, GLFW_CURSOR, freeCursor ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
        if (!freeCursor) firstMouse = true;
    }

    private void updateWindowTitle() {
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
    }

    private void executeCommand(String raw) {
        if (raw.isEmpty()) return;
        String commandText = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = commandText.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return;

        String command = parts[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "gamemode":
                handleGamemodeCommand(parts);
                break;
            case "give":
                handleGiveCommand(parts);
                break;
            case "slotclear":
                handleSlotClearCommand(parts);
                break;
            default:
                setStatus("Unknown command: /" + command);
                break;
        }
    }

    private void handleGamemodeCommand(String[] parts) {
        if (parts.length < 2) {
            setStatus("Usage: /gamemode <survival|creative>");
            return;
        }

        String value = parts[1].toLowerCase(Locale.ROOT);
        if (value.equals("survival") || value.equals("s")) {
            gameMode = GameMode.SURVIVAL;
            player.setFlying(false);
            setStatus("Gamemode set to survival");
        } else if (value.equals("creative") || value.equals("c")) {
            gameMode = GameMode.CREATIVE;
            player.setFlying(true);
            setStatus("Gamemode set to creative");
        } else {
            setStatus("Invalid gamemode: " + parts[1]);
        }
    }

    private void handleGiveCommand(String[] parts) {
        if (parts.length < 2) {
            setStatus("Usage: /give <item> [amount]");
            return;
        }

        String itemId = resolveItemId(parts[1]);
        if (itemId == null) {
            setStatus("Unknown item: " + parts[1]);
            return;
        }

        ItemDefinition definition = getItemDefinition(itemId);
        int amount = definition.maxStack == 1 ? 1 : 1;
        if (parts.length >= 3) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                setStatus("Invalid amount: " + parts[2]);
                return;
            }
        }

        boolean added = addItem(itemId, amount);
        if (added) {
            setStatus("Given " + amount + " " + definition.displayName);
        } else {
            setStatus("Inventory full");
        }
    }

    private void handleSlotClearCommand(String[] parts) {
        int slotIndex = selectedSlot;
        if (parts.length >= 2) {
            try {
                slotIndex = Integer.parseInt(parts[1]) - 1;
            } catch (NumberFormatException e) {
                setStatus("Invalid slot: " + parts[1]);
                return;
            }
        }

        if (slotIndex < 0 || slotIndex >= INVENTORY_SIZE) {
            setStatus("Slot out of range. Use 1-" + INVENTORY_SIZE);
            return;
        }

        inventory[slotIndex] = null;
        if (carriedStack != null && slotIndex == selectedSlot && !inventoryOpen) carriedStack = null;
        setStatus("Cleared slot " + (slotIndex + 1));
    }

    private String resolveItemId(String token) {
        if (token == null) return null;
        String normalized = token.toLowerCase(Locale.ROOT);
        if (itemRegistry.containsKey(normalized)) return normalized;
        if (itemAliases.containsKey(normalized)) return itemAliases.get(normalized);

        Integer blockId = blockDataManager.findBlockId(normalized);
        if (blockId != null) return blockItemByBlockId.get(blockId);
        return null;
    }

    private ItemDefinition getItemDefinition(String itemId) {
        return itemRegistry.get(itemId);
    }

    private boolean addItem(String itemId, int count) {
        ItemDefinition definition = getItemDefinition(itemId);
        if (definition == null) return false;

        int remaining = count;
        if (definition.maxStack > 1) {
            for (int i = 0; i < INVENTORY_SIZE && remaining > 0; i++) {
                ItemStack stack = inventory[i];
                if (stack != null && stack.itemId.equals(itemId) && stack.count < definition.maxStack) {
                    int moved = Math.min(definition.maxStack - stack.count, remaining);
                    stack.count += moved;
                    remaining -= moved;
                }
            }
        }

        for (int i = 0; i < INVENTORY_SIZE && remaining > 0; i++) {
            if (inventory[i] == null) {
                int moved = Math.min(definition.maxStack, remaining);
                inventory[i] = new ItemStack(itemId, moved);
                remaining -= moved;
            }
        }

        return remaining == 0;
    }

    private void handleInventorySlotClick(int slotIndex) {
        if (!inventoryOpen) return;

        ItemStack slotStack = inventory[slotIndex];
        if (carriedStack == null) {
            if (slotStack == null) return;
            carriedStack = slotStack;
            inventory[slotIndex] = null;
            return;
        }

        if (slotStack == null) {
            inventory[slotIndex] = carriedStack;
            carriedStack = null;
            return;
        }

        if (slotStack.itemId.equals(carriedStack.itemId)) {
            ItemDefinition definition = getItemDefinition(slotStack.itemId);
            if (definition != null && definition.maxStack > 1 && slotStack.count < definition.maxStack) {
                int moved = Math.min(definition.maxStack - slotStack.count, carriedStack.count);
                slotStack.count += moved;
                carriedStack.count -= moved;
                if (carriedStack.count <= 0) carriedStack = null;
                return;
            }
        }

        inventory[slotIndex] = carriedStack;
        carriedStack = slotStack;
    }

    private void updateInventoryUi() {
        double time = glfwGetTime();
        crosshairElement.visible = !inventoryOpen && !commandMode;
        inventoryPanelElement.visible = inventoryOpen;
        hotbarActiveElement.visible = true;
        hotbarActiveElement.pos.y = HOTBAR_Y + selectedSlot * SLOT_H;

        for (int index = 0; index < INVENTORY_SIZE; index++) {
            boolean slotVisible = index < HOTBAR_SIZE || inventoryOpen;
            slotBackgrounds[index].visible = slotVisible;

            ItemStack stack = inventory[index];
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

            ItemDefinition definition = getItemDefinition(stack.itemId);
            itemElement.visible = true;
            itemElement.textureId = textureManager.getTextureArrayId();
            itemElement.textureType = 2; // Array
            itemElement.layer = definition.iconLayer;
            itemElement.color.set(1, 1, 1, 1);
            
            if (definition.kind == ItemKind.TOOL) {
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

        carriedItemElement.visible = inventoryOpen && carriedStack != null;
        if (carriedItemElement.visible) {
            ItemDefinition definition = getItemDefinition(carriedStack.itemId);
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
    }

    private void showSelectedItemName() {
        ItemStack stack = inventory[selectedSlot];
        if (stack != null) {
            ItemDefinition definition = getItemDefinition(stack.itemId);
            if (definition != null) {
                itemNameElement.text = definition.displayName;
                itemNameDisplayUntil = glfwGetTime() + 3.0;
            }
        } else {
            itemNameDisplayUntil = 0.0;
        }
    }

    private void updateMining(float dt) {
        if (inventoryOpen || commandMode || !leftMouseHeld) {
            resetMining();
            return;
        }

        int[] hit = raycastBlock(6.0f);
        if (hit == null) {
            resetMining();
            return;
        }

        int blockId = world.getVoxel(hit[0], hit[1], hit[2]);
        if (blockId == 0) {
            resetMining();
            return;
        }

        if (gameMode == GameMode.CREATIVE) {
            if (leftMousePressedThisFrame) {
                breakBlock(hit[0], hit[1], hit[2], blockId, false);
            }
            return;
        }

        if (hit[0] != breakTargetX || hit[1] != breakTargetY || hit[2] != breakTargetZ) {
            breakTargetX = hit[0];
            breakTargetY = hit[1];
            breakTargetZ = hit[2];
            breakProgress = 0.0f;
        }

        breakProgress += dt * getMiningSpeed(blockId);
        if (breakProgress >= blockDataManager.getHardness(blockId)) {
            breakBlock(hit[0], hit[1], hit[2], blockId, true);
            resetMining();
        }
    }

    private float getMiningSpeed(int blockId) {
        String preferredTool = blockDataManager.getPreferredTool(blockId);
        ItemStack selected = inventory[selectedSlot];
        ItemDefinition selectedDefinition = selected != null ? getItemDefinition(selected.itemId) : null;

        ToolType activeTool = ToolType.HAND;
        float toolSpeed = 1.0f;
        if (selectedDefinition != null && selectedDefinition.kind == ItemKind.TOOL) {
            activeTool = selectedDefinition.toolType;
            toolSpeed = selectedDefinition.miningSpeed;
        }

        if ("pickaxe".equals(preferredTool)) {
            return activeTool == ToolType.PICKAXE ? toolSpeed : 0.55f;
        }
        if ("shovel".equals(preferredTool)) {
            return activeTool == ToolType.SHOVEL ? toolSpeed : 0.75f;
        }
        if ("axe".equals(preferredTool)) {
            return activeTool == ToolType.AXE ? toolSpeed : 0.85f;
        }
        return activeTool == ToolType.HAND ? 1.2f : Math.max(1.0f, toolSpeed * 0.7f);
    }

    private void breakBlock(int x, int y, int z, int blockId, boolean collectDrop) {
        if (!chunkManager.setVoxel(x, y, z, 0)) return;

        if (collectDrop) {
            String dropItem = blockItemByBlockId.get(blockId);
            if (dropItem != null) addItem(dropItem, 1);
        }
    }

    private void attemptPlaceBlock() {
        int[] hit = raycastBlock(6.0f);
        if (hit == null) return;

        ItemStack selected = inventory[selectedSlot];
        if (selected == null) {
            setStatus("Selected slot is empty");
            return;
        }

        ItemDefinition definition = getItemDefinition(selected.itemId);
        if (definition == null || definition.kind != ItemKind.BLOCK) {
            setStatus("Select a block item to place");
            return;
        }

        int px = hit[3];
        int py = hit[4];
        int pz = hit[5];
        if (world.getVoxel(px, py, pz) != 0) return;
        if (intersectsPlayer(px, py, pz)) return;
        if (!chunkManager.setVoxel(px, py, pz, definition.blockId)) return;

        if (gameMode == GameMode.SURVIVAL) {
            selected.count--;
            if (selected.count <= 0) inventory[selectedSlot] = null;
        }
    }

    private boolean intersectsPlayer(int x, int y, int z) {
        Vector3f pos = player.getPosition();
        float playerMinX = pos.x - PLAYER_HALF_WIDTH;
        float playerMaxX = pos.x + PLAYER_HALF_WIDTH;
        float playerMinY = pos.y;
        float playerMaxY = pos.y + PLAYER_HEIGHT;
        float playerMinZ = pos.z - PLAYER_HALF_WIDTH;
        float playerMaxZ = pos.z + PLAYER_HALF_WIDTH;

        return playerMaxX > x && playerMinX < x + 1
            && playerMaxY > y && playerMinY < y + 1
            && playerMaxZ > z && playerMinZ < z + 1;
    }

    private void resetMining() {
        breakTargetX = Integer.MIN_VALUE;
        breakTargetY = Integer.MIN_VALUE;
        breakTargetZ = Integer.MIN_VALUE;
        breakProgress = 0.0f;
    }

    private void bindTextures() {
        glActiveTexture(GL_TEXTURE6);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getTextureArrayId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockTextures"), 6);
        glActiveTexture(GL_TEXTURE7);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getTextureId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockData"), 7);
        glActiveTexture(GL_TEXTURE12);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBTextureId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockAABBs"), 12);
        glActiveTexture(GL_TEXTURE11);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getInfoTextureId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockAABBInfo"), 11);
        glActiveTexture(GL_TEXTURE13);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBUVTextureId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockAABBUVs"), 13);
        glActiveTexture(GL_TEXTURE8);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getBiomeMapId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BiomeMap"), 8);
        glActiveTexture(GL_TEXTURE9);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getGrassColormapId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_GrassColormap"), 9);
        glActiveTexture(GL_TEXTURE10);
        glBindTexture(GL_TEXTURE_2D, uiManager.getUITexture());
        glUniform1i(glGetUniformLocation(computeProgram, "u_UITexture"), 10);
        glActiveTexture(GL_TEXTURE14);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getFoliageColormapId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_FoliageColormap"), 14);
    }

    private void setupResources() {
        generatePlayerSkinTextures();
        textureManager = new TextureManager();
        textureManager.loadTextures("src/main/resources/assets/minecraft/textures/blocks");
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
        blockDataManager.registerBlock(13, "dirt", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(14, "sand", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.uploadToGPU();
    }

    private void generatePlayerSkinTextures() {
        File skinFile = new File("src/main/resources/assets/minecraft/textures/entity/player_skin.png");
        if (!skinFile.exists()) {
            return;
        }

        try {
            BufferedImage skin = ImageIO.read(skinFile);
            if (skin == null) {
                return;
            }

            writeSkinCuboidAtlas(
                skin,
                "player_skin_head",
                8, 8, 8,
                new int[]{16, 8, 8, 8},
                new int[]{0, 8, 8, 8},
                new int[]{8, 8, 8, 8},
                new int[]{24, 8, 8, 8},
                new int[]{8, 0, 8, 8},
                new int[]{16, 0, 8, 8}
            );
            writeSkinCuboidAtlas(
                skin,
                "player_skin_body",
                8, 4, 12,
                new int[]{16, 20, 4, 12},
                new int[]{28, 20, 4, 12},
                new int[]{20, 20, 8, 12},
                new int[]{32, 20, 8, 12},
                new int[]{20, 16, 8, 4},
                new int[]{28, 16, 8, 4}
            );
            writeSkinCuboidAtlas(
                skin,
                "player_skin_right_arm",
                4, 4, 12,
                new int[]{40, 20, 4, 12},
                new int[]{48, 20, 4, 12},
                new int[]{44, 20, 4, 12},
                new int[]{52, 20, 4, 12},
                new int[]{44, 16, 4, 4},
                new int[]{48, 16, 4, 4}
            );
            writeSkinCuboidAtlas(
                skin,
                "player_skin_left_arm",
                4, 4, 12,
                new int[]{32, 52, 4, 12},
                new int[]{40, 52, 4, 12},
                new int[]{36, 52, 4, 12},
                new int[]{44, 52, 4, 12},
                new int[]{36, 48, 4, 4},
                new int[]{40, 48, 4, 4}
            );
            writeSkinCuboidAtlas(
                skin,
                "player_skin_right_leg",
                4, 4, 12,
                new int[]{0, 20, 4, 12},
                new int[]{8, 20, 4, 12},
                new int[]{4, 20, 4, 12},
                new int[]{12, 20, 4, 12},
                new int[]{4, 16, 4, 4},
                new int[]{8, 16, 4, 4}
            );
            writeSkinCuboidAtlas(
                skin,
                "player_skin_left_leg",
                4, 4, 12,
                new int[]{16, 52, 4, 12},
                new int[]{24, 52, 4, 12},
                new int[]{20, 52, 4, 12},
                new int[]{28, 52, 4, 12},
                new int[]{20, 48, 4, 4},
                new int[]{24, 48, 4, 4}
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare player skin textures", e);
        }
    }

    private void writeSkinCuboidAtlas(
        BufferedImage skin,
        String textureName,
        int frontWidth,
        int depthWidth,
        int faceHeight,
        int[] leftRegion,
        int[] rightRegion,
        int[] frontRegion,
        int[] backRegion,
        int[] topRegion,
        int[] bottomRegion
    ) throws IOException {
        float atlasWidth = (depthWidth * 2.0f) + (frontWidth * 2.0f);
        float atlasHeight = depthWidth + faceHeight;

        BufferedImage atlas = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        drawSkinRegion(graphics, skin, leftRegion,
            scaledCoord(0.0f, atlasWidth), scaledCoord(depthWidth, atlasWidth),
            scaledCoord(depthWidth, atlasHeight), scaledCoord(depthWidth + faceHeight, atlasHeight));
        drawSkinRegion(graphics, skin, frontRegion,
            scaledCoord(depthWidth, atlasWidth), scaledCoord(depthWidth + frontWidth, atlasWidth),
            scaledCoord(depthWidth, atlasHeight), scaledCoord(depthWidth + faceHeight, atlasHeight));
        drawSkinRegion(graphics, skin, rightRegion,
            scaledCoord(depthWidth + frontWidth, atlasWidth), scaledCoord((depthWidth * 2.0f) + frontWidth, atlasWidth),
            scaledCoord(depthWidth, atlasHeight), scaledCoord(depthWidth + faceHeight, atlasHeight));
        drawSkinRegion(graphics, skin, backRegion,
            scaledCoord((depthWidth * 2.0f) + frontWidth, atlasWidth), scaledCoord((depthWidth * 2.0f) + (frontWidth * 2.0f), atlasWidth),
            scaledCoord(depthWidth, atlasHeight), scaledCoord(depthWidth + faceHeight, atlasHeight));
        drawSkinRegion(graphics, skin, topRegion,
            scaledCoord(depthWidth, atlasWidth), scaledCoord(depthWidth + frontWidth, atlasWidth),
            scaledCoord(0.0f, atlasHeight), scaledCoord(depthWidth, atlasHeight));
        drawSkinRegion(graphics, skin, bottomRegion,
            scaledCoord(depthWidth + frontWidth, atlasWidth), scaledCoord((depthWidth + frontWidth) + frontWidth, atlasWidth),
            scaledCoord(0.0f, atlasHeight), scaledCoord(depthWidth, atlasHeight));
        graphics.dispose();

        File output = new File("src/main/resources/assets/minecraft/textures/blocks/" + textureName + ".png");
        ImageIO.write(atlas, "png", output);
    }

    private int scaledCoord(float value, float total) {
        return Math.round((value / total) * 16.0f);
    }

    private void drawSkinRegion(Graphics2D graphics, BufferedImage skin, int[] src, int dstX0, int dstX1, int dstY0, int dstY1) {
        graphics.drawImage(
            skin,
            dstX0,
            dstY0,
            dstX1,
            dstY1,
            src[0],
            src[1],
            src[0] + src[2],
            src[1] + src[3],
            null
        );
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
        int[] table = world.getIndirectionTable();
        IntBuffer buf = MemoryUtil.memAllocInt(table.length);
        buf.put(table).flip();
        indirectionSSBO = glCreateBuffers();
        glNamedBufferStorage(indirectionSSBO, buf, GL_DYNAMIC_STORAGE_BIT);
        MemoryUtil.memFree(buf);

        chunkPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(chunkPoolSSBO, (long) POOL_SIZE * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);

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

    private void uploadDirtyChunks() {
        java.util.Set<Integer> dirty = chunkManager.getDirtySlots();
        if (dirty.isEmpty() && !chunkManager.isTableDirty()) return;
        if (chunkManager.isTableDirty()) {
            int[] table = world.getIndirectionTable();
            IntBuffer buf = MemoryUtil.memAllocInt(table.length);
            buf.put(table).flip();
            glNamedBufferSubData(indirectionSSBO, 0, buf);
            MemoryUtil.memFree(buf);
        }

        int[] pool = world.getChunkPool();
        int[] masks = world.getBitmaskPool();
        short[] occs = world.getOcclusionPool();
        int vpc = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        for (int s : dirty) {
            IntBuffer buf = MemoryUtil.memAllocInt(vpc);
            buf.put(pool, s * vpc, vpc).flip();
            glNamedBufferSubData(chunkPoolSSBO, (long) s * vpc * Integer.BYTES, buf);
            MemoryUtil.memFree(buf);

            IntBuffer mbuf = MemoryUtil.memAllocInt(128);
            mbuf.put(masks, s * 128, 128).flip();
            glNamedBufferSubData(bitmaskSSBO, (long) s * 128 * Integer.BYTES, mbuf);
            MemoryUtil.memFree(mbuf);

            java.nio.ShortBuffer obuf = MemoryUtil.memAllocShort(vpc);
            obuf.put(occs, s * vpc, vpc).flip();
            glNamedBufferSubData(occlusionSSBO, (long) s * vpc * Short.BYTES, obuf);
            MemoryUtil.memFree(obuf);
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

        Vector3f target = new Vector3f(player.getPosition()).add(0, THIRD_PERSON_TARGET_HEIGHT, 0);
        Vector3f desired = new Vector3f(target).sub(new Vector3f(getLookDirection()).mul(THIRD_PERSON_DISTANCE));
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
