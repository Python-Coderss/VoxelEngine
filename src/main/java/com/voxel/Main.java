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
    private int quadProgram, computeProgram, lightProgram;

    // OpenGL Object IDs for the full-screen quad used to display the raytraced image
    private int quadVAO, quadVBO, renderTexture;

    // SSBO (Shader Storage Buffer Object) IDs for world data
    // SSBOs allow us to pass large amounts of data to shaders
    private int indirectionSSBO, chunkPoolSSBO, lightPoolSSBO;

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
    private float camX = 1020, camY = 10, camZ = 1030; // Initial position to see the valley of torches
    private float yaw = -45, pitch = -20; // Yaw (left/right rotation) and Pitch (up/down rotation)
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
        // lightProgram handles GPU light propagation (Radiance Cascades)
        lightProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/light_propagate.comp", GL_COMPUTE_SHADER)
        );

        // Setup various engine components
        setupQuad();     // Full-screen rectangle geometry
        setupTexture();  // Texture where the raytracer will write its output
        setupWorld();    // Procedural world generation
        setupResources();// Loading textures and block data
        setupLighting(); // Initial light propagation

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
    }

    /**
     * Sets up the lighting engine and propagates initial light sources for radiance cascade demo.
     */
    private void setupLighting() {
        lightEngine = new LightPropagationEngine(world, blockDataManager);
        List<LightSource> sources = new ArrayList<>();

        sources.add(new LightSource(new Vector3i(1024, 128, 1024), new Vector3f(0.70f, 0.75f, 0.90f), 5, 2048, LightType.SUN));
        // Valley of torches: line of torches at y=3, z=1024, x from 1000 to 1050
        for (int x = 1000; x <= 1050; x += 5) {
            sources.add(new LightSource(new Vector3i(x, 8, 1024), new Vector3f(1.0f, 0.6f, 0.2f), 15, 30, LightType.BLOCK));
            // Place torch blocks
            world.setVoxel(x, 9, 1024, 7); // Planks base
            world.setVoxel(x, 10, 1024, 12); // Torch on planks (Correct ID: 12)
        }

        // Additional scattered lights for testing indirect cascades
        sources.add(new LightSource(new Vector3i(1025, 5, 1025), new Vector3f(0.2f, 1.0f, 0.2f), 12, 25, LightType.BLOCK));
        world.setVoxel(1025, 4, 1025, 7);
        world.setVoxel(1025, 5, 1025, 12);

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
     */
    private void setupWorld() {
        world = new World();
        nextWorldChunkSlot = 0;

        // 1. Generate Procedural Ground (Layers 0 and 1)
        for (int cx = 32; cx < 96; cx++) {
            for (int cz = 32; cz < 96; cz++) {
                if (nextWorldChunkSlot >= POOL_SIZE) break;
                int slot = allocateChunkSlot();
                world.setChunkSlot(cx, 0, cz, slot);
                for (int vx = 0; vx < CHUNK_SIZE; vx++) {
                    for (int vz = 0; vz < CHUNK_SIZE; vz++) {
                        world.setVoxelInPool(slot, vx, 0, vz, 2); // Stone base layer
                        world.setVoxelInPool(slot, vx, 1, vz, 1); // Grass top layer
                    }
                }
            }
        }
        
        // 2. Create "The Mirror House"
        int baseEX = 1020, baseEY = 2, baseEZ = 1020;
        // Floor made of planks
        for(int x=0; x<10; x++) for(int z=0; z<10; z++) world.setVoxel(baseEX+x, baseEY-1, baseEZ+z, 7);
        // Log Walls
        for(int y=0; y<5; y++) {
            for(int i=0; i<10; i++) {
                world.setVoxel(baseEX+i, baseEY+y, baseEZ, 6);
                world.setVoxel(baseEX+i, baseEY+y, baseEZ+9, 6);
                world.setVoxel(baseEX, baseEY+y, baseEZ+i, 6);
                world.setVoxel(baseEX+9, baseEY+y, baseEZ+i, 6);
            }
        }
        // Glass Windows
        for(int y=1; y<3; y++) {
            for(int i=2; i<8; i++) {
                world.setVoxel(baseEX+i, baseEY+y, baseEZ+9, 3);
                world.setVoxel(baseEX, baseEY+y, baseEZ+i, 3);
            }
        }
        // Reflective Mirror Wall inside (using Diamond blocks)
        for(int y=0; y<4; y++) for(int i=1; i<9; i++) world.setVoxel(baseEX+1, baseEY+y, baseEZ+i, 4);

        // 3. Reflective Gold & Diamond Platform
        for(int x=0; x<8; x++) {
            for(int z=0; z<8; z++) {
                world.setVoxel(1040+x, 2, 1040+z, 9); // Gold blocks
                if ((x+z)%2 == 0) world.setVoxel(1040+x, 3, 1040+z, 4); // Diamond checkerboard
            }
        }

        // 4. Large Water Pool
        for(int x=0; x<20; x++) {
            for(int z=0; z<20; z++) {
                world.setVoxel(1000+x, 1, 1000+z, 5); // Water blocks
                world.setVoxel(1000+x, 0, 1000+z, 2); // Stone base
            }
        }

        // 5. Partial blocks demo: stairs and slabs near torches
        for(int x=1010; x<1020; x++) {
            world.setVoxel(x, 2, 1020, 10); // brick_stairs
            world.setVoxel(x, 2, 1022, 11); // half_slab_oak
        }

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

            // --- Lighting Pass (Radiance Cascades) ---
            glUseProgram(lightProgram);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, lightPoolSSBO);

            // Pass 0: Cascade 0 (Direct/Short Range)
            glUniform1i(1, 0); 
            // Dispatch over a region around the player
            glDispatchCompute(64, 16, 64); 
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            // Pass 1: Cascade 1 (Indirect/Longer Range)
            glUniform1i(1, 1);
            glDispatchCompute(64, 16, 64);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            // --- Raytracing Pass (Compute Shader) ---
            glUseProgram(computeProgram);

            // Pass camera uniforms to the shader
            glProgramUniform3f(computeProgram, 0, camX, camY, camZ);
            glProgramUniform3f(computeProgram, 1, forwardX, forwardY, forwardZ);
            glProgramUniform3f(computeProgram, 2, rightX, rightY, rightZ);
            glProgramUniform3f(computeProgram, 3, upX, upY, upZ);

            // Bind textures to specific texture units for the shader
            glActiveTexture(GL_TEXTURE6);
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getTextureArrayId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_BlockTextures"), 6);

            glActiveTexture(GL_TEXTURE7);
            glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getTextureId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_BlockData"), 7);

            glActiveTexture(GL_TEXTURE10);
            glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBTextureId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_BlockAABBs"), 10);

            glActiveTexture(GL_TEXTURE11);
            glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getInfoTextureId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_BlockAABBInfo"), 11);

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

            // Bind the output image texture
            glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);

            // Dispatch the compute shader: one thread per pixel
            // We use 16x16 thread groups (matching layout in shader)
            glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);

            // Ensure all writes to the image are finished before we use it for rendering
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            
            // --- Final Display Pass (Graphics Pipeline) ---
            glClear(GL_COLOR_BUFFER_BIT); // Clear the back buffer
            glUseProgram(quadProgram);    // Use the quad shader
            glBindTextureUnit(0, renderTexture); // Bind the raytraced image
            glBindVertexArray(quadVAO);   // Bind the quad geometry
            glDrawArrays(GL_TRIANGLES, 0, 6); // Draw the quad to fill the screen

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
