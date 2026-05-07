package com.voxel;

import com.voxel.lighting.LightPropagationEngine;
import com.voxel.lighting.LightSource;
import com.voxel.lighting.LightType;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.ShaderUtil;
import com.voxel.utils.TextureManager;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

// Import static constants and methods from GLFW and OpenGL for easier access
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
 * The Main class serves as the entry point for the Voxel Engine.
 * It handles window creation (using GLFW), OpenGL context initialization,
 * the main rendering loop, and input handling.
 *
 * For beginners:
 * - GLFW is a library for creating windows and handling input (keyboard/mouse).
 * - OpenGL is the API used for rendering 3D graphics on the GPU.
 */
public class Main {
    // Distance around debug areas to ensure chunks are allocated
    private static final int DEBUG_CHUNK_PADDING = 1;

    // Handle to the GLFW window
    private long window;

    // OpenGL Program IDs for our shaders
    private int quadProgram, computeProgram;

    // OpenGL Object IDs for the full-screen quad used to display the raytraced image
    private int quadVAO, quadVBO, renderTexture, godRayTexture, blurTexture, blurFBO;

    // SSBO (Shader Storage Buffer Object) IDs for world data
    // SSBOs allow us to pass large amounts of data to shaders
    private int indirectionSSBO, chunkPoolSSBO, lightPoolSSBO;

    // Entity Manager
    private com.voxel.entity.EntityManager entityManager;

    // Counter for the next available slot in the chunk pool
    private int nextWorldChunkSlot;
    
    // The world object containing voxel data
    private World world;

    // The engine responsible for calculating light levels in the world
    private LightPropagationEngine lightEngine;
    
    // Managers for textures, block properties, and biomes
    private TextureManager textureManager;
    private BlockDataManager blockDataManager;
    private com.voxel.utils.BiomeManager biomeManager;
    
    // Window dimensions
    private int width = 1280, height = 720;
    
    // Engine constants
    private final int CHUNK_SIZE = 16; // Chunks are 16x16x16 voxels
    private final int REGION_SIZE = 128; // The world is 128x128x128 chunks
    private final int POOL_SIZE = 16384; // Maximum number of allocated chunks
    
    // Constant representing an empty/unallocated chunk
    private final int EMPTY = 0xFFFFFFFF;

    // Camera state: Position and orientation
    private float camX = 1024, camY = 18, camZ = 1050; // Initial position to see the new demo
    private float yaw = -90, pitch = -10; // Looking towards the scene
    private float lastMouseX = width / 2f, lastMouseY = height / 2f; // For mouse movement delta
    private boolean firstMouse = true; // Flag to initialize mouse position

    // Camera direction vectors calculated from yaw and pitch
    private float forwardX, forwardY, forwardZ;
    private float rightX, rightY, rightZ;
    private float upX, upY, upZ;

    /**
     * Entry point of the application.
     */
    public void run() {
        init();     // Initialize GLFW, OpenGL, and resources
        loop();     // Enter the main rendering loop

        // Clean up resources once the loop ends
        glDeleteProgram(quadProgram);
        glDeleteProgram(computeProgram);
        glDeleteBuffers(quadVBO);
        glDeleteVertexArrays(quadVAO);
        glDeleteTextures(renderTexture);
        glDeleteTextures(godRayTexture);
        glDeleteBuffers(indirectionSSBO);
        glDeleteBuffers(chunkPoolSSBO);
        glDeleteBuffers(lightPoolSSBO);

        glfwDestroyWindow(window); // Close the window
        glfwTerminate();           // Shut down GLFW
        glfwSetErrorCallback(null).free(); // Free the error callback
    }

    /**
     * Initializes GLFW, creates the window, and sets up OpenGL state.
     */
    private void init() {
        // Set up an error callback to print GLFW errors to the console
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        // Configure window hints for the upcoming window creation
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // Window stays hidden after creation until we are ready
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // Allow window resizing

        // Request OpenGL 4.3 Core Profile
        // Core profile removes deprecated features for better performance and modern practices
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        // Create the window
        window = glfwCreateWindow(width, height, "Voxel Engine", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        // Set a key callback to handle the Escape key for closing the window
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) glfwSetWindowShouldClose(w, true);
        });

        // Set a cursor position callback to handle camera rotation via mouse movement
        glfwSetCursorPosCallback(window, (w, xpos, ypos) -> {
            if (firstMouse) {
                lastMouseX = (float) xpos;
                lastMouseY = (float) ypos;
                firstMouse = false;
            }
            // Calculate mouse movement offset
            float xoffset = (float) xpos - lastMouseX;
            float yoffset = lastMouseY - (float) ypos; // Reversed since y-coordinates go from bottom to top
            lastMouseX = (float) xpos;
            lastMouseY = (float) ypos;

            float sensitivity = 0.1f;
            yaw += xoffset * sensitivity;
            pitch += yoffset * sensitivity;

            // Constrain the pitch so the camera doesn't flip
            if (pitch > 89.0f) pitch = 89.0f;
            if (pitch < -89.0f) pitch = -89.0f;
        });

        // Capture the mouse cursor and hide it
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        // Make the OpenGL context current for the current thread
        glfwMakeContextCurrent(window);

        // Disable V-Sync for uncapped frame rates
        glfwSwapInterval(0);

        // Finally show the window
        glfwShowWindow(window);

        // This line is critical for LWJGL's interoperation with OpenGL's context.
        // It makes the OpenGL functions available for use.
        GL.createCapabilities();

        // Compile and link the shader programs
        // quadProgram is for drawing the final image to the screen
        quadProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/quad.vert", GL_VERTEX_SHADER),
            ShaderUtil.compileShader("src/main/resources/shaders/quad.frag", GL_FRAGMENT_SHADER)
        );
        // computeProgram is where the raytracing logic lives
        computeProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/raytracer.comp", GL_COMPUTE_SHADER)
        );
        

        // Setup various engine components
        entityManager = new com.voxel.entity.EntityManager();
        setupQuad();     // Full-screen rectangle geometry
        setupTexture();  // Texture where the raytracer will write its output
          
        setupResources();// Loading textures and block data
        setupLighting(); // Initial light propagation and Procedural world generation

        // Finalize world data and upload to GPU
        uploadWorldToGpu();
    }

    /**
     * Loads textures and registers block types.
     */
    private void setupResources() {
        textureManager = new TextureManager();
        // Load all block textures from the specified directory into a Texture Array
        textureManager.loadTextures("src/main/resources/assets/minecraft/textures/blocks");

        biomeManager = new com.voxel.utils.BiomeManager();
        // Load color maps used for tinting grass and foliage based on biome
        biomeManager.loadColormaps(
            "src/main/resources/assets/minecraft/textures/colormap/grass.png",
            "src/main/resources/assets/minecraft/textures/colormap/foliage.png"
        );
        // Generate a 2D map representing biomes across the world
        biomeManager.generateBiomeMap(2048);

        blockDataManager = new BlockDataManager();
        // Register various blocks with their corresponding textures and properties
        blockDataManager.registerBlock(1, "grass_normal", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(2, "stone", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(3, "glass", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(4, "diamond_block", textureManager, "src/main/resources/assets/minecraft/models/block"); 
        blockDataManager.registerBlock(5, "water_still", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(6, "oak_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(7, "oak_planks", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(8, "brick", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(9, "gold_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(10, "brick_stairs", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(11, "half_slab_oak", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(12, "torch", textureManager, "src/main/resources/assets/minecraft/models/block");
        
        // Demo blocks
        blockDataManager.registerBlock(20, "white_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(21, "red_lamp", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(22, "blue_lamp", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(23, "green_lamp", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(24, "yellow_lamp", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(25, "red_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        
        // Upload block property data to the GPU in a texture buffer
        blockDataManager.uploadToGPU();
    }

    /**
     * Sets up a simple quad (two triangles) that covers the entire screen.
     * This is used to display the output of the compute shader.
     */
    private void setupQuad() {
        // Coordinates for two triangles forming a square from (-1,-1) to (1,1)
        float[] vertices = {-1,-1, 1,-1, -1,1, 1,-1, 1,1, -1,1};

        // Use MemoryStack for efficient off-heap memory allocation (required by LWJGL)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(vertices.length).put(vertices);
            buffer.flip(); // Prepare the buffer for reading

            // Create a VBO (Vertex Buffer Object) to store the vertex data on the GPU
            quadVBO = glCreateBuffers();
            glNamedBufferStorage(quadVBO, buffer, 0); // Upload data

            // Create a VAO (Vertex Array Object) to define the layout of the vertex data
            quadVAO = glCreateVertexArrays();
            glEnableVertexArrayAttrib(quadVAO, 0); // Enable attribute index 0
            glVertexArrayAttribFormat(quadVAO, 0, 2, GL_FLOAT, false, 0); // 2 floats per vertex
            glVertexArrayAttribBinding(quadVAO, 0, 0); // Bind attribute to binding point 0
            glVertexArrayVertexBuffer(quadVAO, 0, quadVBO, 0, 2 * Float.BYTES); // Link VBO to binding point
        }
    }

    /**
     * Creates the texture that the raytracer compute shader will write into.
     */
    private void setupTexture() {
        renderTexture = glCreateTextures(GL_TEXTURE_2D);
        // Allocate storage for the texture
        glTextureStorage2D(renderTexture, 1, GL_RGBA8, width, height);
        // Use Nearest filtering for a crisp voxel look
        glTextureParameteri(renderTexture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(renderTexture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        godRayTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(godRayTexture, 1, GL_RGBA8, width, height);
        // Use Linear filtering for god rays as they are smooth
        glTextureParameteri(godRayTexture, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(godRayTexture, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        // Intermediate texture for separable blur
        blurTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(blurTexture, 1, GL_RGBA8, width, height);
        glTextureParameteri(blurTexture, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(blurTexture, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        // Framebuffer for rendering the intermediate blur pass
        blurFBO = glCreateFramebuffers();
        glNamedFramebufferTexture(blurFBO, GL_COLOR_ATTACHMENT0, blurTexture, 0);
    }

    /**
     * Sets up the lighting engine and propagates initial light sources for radiance cascade demo.
     */
    private void setupLighting() {
        
        List<LightSource> sources = new ArrayList<>();

        
        setupWorld(sources);

        lightEngine = new LightPropagationEngine(world, blockDataManager);
        
        // Calculate light propagation with radiance cascades
        lightEngine.propagateAllLights(sources);

        // Upload the resulting light data to the GPU in an SSBO
        int[] lightPool = world.getLightPool();
        IntBuffer buffer = MemoryUtil.memAllocInt(lightPool.length);
        buffer.put(lightPool).flip();
        lightPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(lightPoolSSBO, buffer, 0);
        MemoryUtil.memFree(buffer);
    }

    /**
     * Procedurally generates the world content and uploads it to the GPU.
     * Updated for a dramatic lighting demo: "The White Gallery"
     * @param sources 
     */
    private void setupWorld(List<LightSource> sources) {
        world = new World();
        nextWorldChunkSlot = 0;

        // Base coordinates for the demo gallery
        int startX = 1000, startZ = 1000;
        int size = 48;
        int height = 24;

        // Pre-allocate chunks for the demo area to ensure setVoxel works
        for (int cx = (startX >> 4) - 1; cx <= ((startX + size) >> 4) + 1; cx++) {
            for (int cy = 0; cy <= (height >> 4) + 1; cy++) {
                for (int cz = (startZ >> 4) - 1; cz <= ((startZ + size) >> 4) + 1; cz++) {
                    ensureChunkSlot(cx, cy, cz);
                }
            }
        }

        // 1. Build a Large White Room (with one red wall)
        for (int x = startX; x < startX + size; x++) {
            for (int z = startZ; z < startZ + size; z++) {
                // Floor
                world.setVoxel(x, 0, z, 20); // White Block
                
                // Ceiling
                world.setVoxel(x, height, z, 20);

                // Walls
                if (x == startX || x == startX + size - 1 || z == startZ || z == startZ + size - 1) {
                    for (int y = 1; y < height; y++) {
                        int type = 20; // White
                        if (z == startZ) type = 25; // Red Wall
                        world.setVoxel(x, y, z, type);
                    }
                }
            }
        }

        // 2. Add Shadow Demonstrators (Pillars and Blocks)
        for (int i = 0; i < 3; i++) {
            int px = startX + 12 + i * 12;
            int pz = startZ + 12;
            for (int y = 1; y < 16; y++) {
                world.setVoxel(px, y, pz, 20); // White pillars
            }
        }

        // 3. Light Bleeding Showcase (Narrow slits with bright light behind)
        int slitX = startX + size - 1;
        for (int y = 2; y < height - 2; y += 4) {
            // Create a small room behind the slit
            for (int dx = 1; dx < 5; dx++) {
                for (int dz = -2; dz < 3; dz++) {
                    for (int dy = 0; dy < 3; dy++) {
                        world.setVoxel(slitX + dx, y + dy, startZ + size / 2 + dz, 20);
                    }
                }
            }
            // The light source behind the wall
            Vector3i pos = new Vector3i(slitX + 3, y + 1, startZ + size / 2);
            sources.add(new LightSource(pos, new Vector3f(1.0f, 0.5f, 0.1f), 15, 256, LightType.BLOCK));
            world.setVoxel(pos.x, pos.y, pos.z, 21); // Red Lamp (Glowing Orange-ish)
        }

        // 4. Color Bleeding Showcase (Lamps in corners)
        // Red Lamp
        Vector3i redPos = new Vector3i(startX + 5, 2, startZ + 5);
        world.setVoxel(redPos.x, redPos.y, redPos.z, 21);
        sources.add(new LightSource(redPos, new Vector3f(1.0f, 0.1f, 0.1f), 12, 24, LightType.BLOCK));

        // Blue Lamp
        Vector3i bluePos = new Vector3i(startX + size - 6, 2, startZ + 5);
        world.setVoxel(bluePos.x, bluePos.y, bluePos.z, 22);
        sources.add(new LightSource(bluePos, new Vector3f(0.1f, 0.2f, 1.0f), 12, 24, LightType.BLOCK));

        // Green Lamp
        Vector3i greenPos = new Vector3i(startX + 5, 2, startZ + size - 6);
        world.setVoxel(greenPos.x, greenPos.y, greenPos.z, 23);
        sources.add(new LightSource(greenPos, new Vector3f(0.1f, 1.0f, 0.2f), 12, 24, LightType.BLOCK));

        // Yellow Lamp
        Vector3i yellowPos = new Vector3i(startX + size - 6, 2, startZ + size - 6);
        world.setVoxel(yellowPos.x, yellowPos.y, yellowPos.z, 24);
        sources.add(new LightSource(yellowPos, new Vector3f(1.0f, 1.0f, 0.1f), 12, 24, LightType.BLOCK));

        // 5. Roof Slit for Natural Light
        for (int x = startX + size / 2 - 2; x < startX + size / 2 + 2; x++) {
            for (int z = startZ + 5; z < startZ + size - 5; z++) {
                world.setVoxel(x, height, z, 0); // Air (Slit in ceiling)
            }
        }

        // 6. Add Entities
        int headTex = textureManager.getTextureIndex("concrete_green");
        int bodyTex = textureManager.getTextureIndex("concrete_cyan");
        int armTex = textureManager.getTextureIndex("concrete_lime");
        int legTex = textureManager.getTextureIndex("concrete_blue");
        
        entityManager.addEntity(new com.voxel.entity.ZombieEntity(1, new Vector3f(startX + 10, 2.5f, startZ + 10), headTex, bodyTex, armTex, legTex));
        entityManager.addEntity(new com.voxel.entity.ZombieEntity(2, new Vector3f(startX + 15, 2.5f, startZ + 15), headTex, bodyTex, armTex, legTex));
    }

    /**
     * Allocates a new chunk slot from the pool.
     */
    private int allocateChunkSlot() {
        if (nextWorldChunkSlot >= POOL_SIZE) {
            throw new IllegalStateException("World chunk pool exhausted");
        }
        return nextWorldChunkSlot++;
    }

    /**
     * Ensures a chunk exists at the given coordinates, allocating a new one if necessary.
     */
    private int ensureChunkSlot(int cx, int cy, int cz) {
        int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
        int[] table = world.getIndirectionTable();
        int slot = table[tableIdx];
        if (slot != EMPTY) {
            return slot;
        }

        slot = allocateChunkSlot();
        world.setChunkSlot(cx, cy, cz, slot);
        return slot;
    }

    /**
     * Uploads the world's indirection table and chunk pool to the GPU via SSBOs.
     */
    private void uploadWorldToGpu() {
        // Upload the indirection table (maps world coords to chunk pool slots)
        int[] indirectionTable = world.getIndirectionTable();
        IntBuffer tableBuffer = MemoryUtil.memAllocInt(indirectionTable.length);
        tableBuffer.put(indirectionTable).flip();
        if (indirectionSSBO == 0) {
            indirectionSSBO = glCreateBuffers();
            glNamedBufferStorage(indirectionSSBO, tableBuffer, 0);
        } else {
            glNamedBufferSubData(indirectionSSBO, 0, tableBuffer);
        }
        MemoryUtil.memFree(tableBuffer);

        // Upload the chunk pool (contains the actual voxel data)
        int[] chunkPool = world.getChunkPool();
        IntBuffer poolBuffer = MemoryUtil.memAllocInt(chunkPool.length);
        poolBuffer.put(chunkPool).flip();
        if (chunkPoolSSBO == 0) {
            chunkPoolSSBO = glCreateBuffers();
            glNamedBufferStorage(chunkPoolSSBO, poolBuffer, 0);
        } else {
            glNamedBufferSubData(chunkPoolSSBO, 0, poolBuffer);
        }
        MemoryUtil.memFree(poolBuffer);
    }

    /**
     * Updates the camera position and orientation based on keyboard input and mouse movement.
     * @param dt Delta time since the last frame.
     */
    private void updateCamera(float dt) {
        float speed = 5.0f * dt; // Movement speed
        double radYaw = Math.toRadians(yaw), radPitch = Math.toRadians(pitch);

        // Calculate the forward vector from yaw and pitch
        forwardX = (float) (Math.cos(radYaw) * Math.cos(radPitch));
        forwardY = (float) Math.sin(radPitch);
        forwardZ = (float) (Math.sin(radYaw) * Math.cos(radPitch));
        float len = (float) Math.sqrt(forwardX*forwardX + forwardY*forwardY + forwardZ*forwardZ);
        forwardX /= len; forwardY /= len; forwardZ /= len;
        
        // Calculate the right vector (perpendicular to forward and global up)
        rightX = -forwardZ; rightY = 0; rightZ = forwardX;
        len = (float) Math.sqrt(rightX*rightX + rightZ*rightZ);
        if (len > 0) { rightX /= len; rightZ /= len; }
        
        // Calculate the camera's local up vector
        upX = rightY * forwardZ - rightZ * forwardY;
        upY = rightZ * forwardX - rightX * forwardZ;
        upZ = rightX * forwardY - rightY * forwardX;
        len = (float) Math.sqrt(upX*upX + upY*upY + upZ*upZ);
        if (len > 0) { upX /= len; upY /= len; upZ /= len; }

        // Keyboard controls (WASD, Space, Shift)
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) { camX += forwardX * speed; camY += forwardY * speed; camZ += forwardZ * speed; }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) { camX -= forwardX * speed; camY -= forwardY * speed; camZ -= forwardZ * speed; }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { camX -= rightX * speed; camY -= rightY * speed; camZ -= rightZ * speed; }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { camX += rightX * speed; camY += rightY * speed; camZ += rightZ * speed; }
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) camY += speed;
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) camY -= speed;
    }

    /**
     * The main application loop. Handles timing, camera updates, and rendering.
     */
    private void loop() {
        float lastTime = (float) glfwGetTime();
        float fpsTime = 0;
        int frames = 0;

        // Keep running until the window should close
        while (!glfwWindowShouldClose(window)) {
            float currentTime = (float) glfwGetTime();
            float dt = currentTime - lastTime; // Calculate time elapsed since last frame
            lastTime = currentTime;

            // Track and display FPS in the window title
            fpsTime += dt;
            frames++;
            if (fpsTime >= 1.0f) {
                glfwSetWindowTitle(window, String.format("Voxel Engine | FPS: %d | Pos: %.2f, %.2f, %.2f", frames, camX, camY, camZ));
                frames = 0;
                fpsTime = 0;
            }

            // Update camera based on input
            updateCamera(dt);

            // Update entities
            entityManager.update(dt);
            entityManager.uploadToGPU();


            // --- Raytracing Pass (Compute Shader) ---
            glUseProgram(computeProgram);

            // Pass camera and time uniforms to the shader
            glProgramUniform3f(computeProgram, 0, camX, camY, camZ);
            glProgramUniform3f(computeProgram, 1, forwardX, forwardY, forwardZ);
            glProgramUniform3f(computeProgram, 2, rightX, rightY, rightZ);
            glProgramUniform3f(computeProgram, 3, upX, upY, upZ);
            glProgramUniform1f(computeProgram, 4, currentTime); // Pass u_Time
            glProgramUniform1i(computeProgram, 5, entityManager.getEntityCount()); // Pass u_EntityCount

            // Bind textures to specific texture units for the shader
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
            glBindTexture(GL_TEXTURE_2D, biomeManager.getFoliageColormapId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_FoliageColormap"), 10);
            
            // Bind SSBOs to their respective binding points (defined in the shader)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, lightPoolSSBO);
            
            entityManager.bind(3, 4); // Entity SSBO at 3, Part SSBO at 4

            // Bind the output image texture
            glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
            glBindImageTexture(1, godRayTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);

            // Dispatch the compute shader: one thread per pixel
            // We use 16x16 thread groups (matching layout in shader)
            glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);

            // Ensure all writes to the image are finished before we use it for rendering
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            
            // --- Final Display Pass (Graphics Pipeline) ---
            
            // Pass 1: Horizontal Blur of God Rays into intermediate buffer
            glBindFramebuffer(GL_FRAMEBUFFER, blurFBO);
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(quadProgram);
            glBindTextureUnit(0, godRayTexture); // inputTexture for blur
            glUniform1i(glGetUniformLocation(quadProgram, "u_Pass"), 0);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            // Pass 2: Vertical Blur + Combine with Sharp Scene to Screen
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindTextureUnit(0, renderTexture); // inputTexture (sharp voxels)
            glBindTextureUnit(1, blurTexture);   // u_GodRayTexture (H-blurred)
            glUniform1i(glGetUniformLocation(quadProgram, "u_GodRayTexture"), 1);
            glUniform1i(glGetUniformLocation(quadProgram, "u_Pass"), 1);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            glfwSwapBuffers(window); // Swap the front and back buffers
            glfwPollEvents();        // Process window and input events
        }
    }

    /**
     * Main method to launch the application.
     */
    public static void main(String[] args) {
        System.out.println("Voxel Engine Version 1.1.0.1");
        new Main().run();
    }
}
