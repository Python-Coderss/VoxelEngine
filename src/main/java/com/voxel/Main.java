package com.voxel;

import com.voxel.jem.JemEntityInstance;
import com.voxel.jem.JemEntityRenderer;
import com.voxel.jem.JemModelLoader;
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

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Main {
    private static final int[] DEBUG_PART_BLOCK_IDS = {2, 4, 6, 7, 8, 9, 3};
    private static final int DEBUG_CHUNK_PADDING = 1;

    private long window;
    private int quadProgram, computeProgram;
    private int quadVAO, quadVBO, renderTexture;
    private int indirectionSSBO, chunkPoolSSBO, lightPoolSSBO;
    private int nextWorldChunkSlot;
    
    private World world;
    private LightPropagationEngine lightEngine;
    
    private TextureManager textureManager;
    private BlockDataManager blockDataManager;
    private com.voxel.utils.BiomeManager biomeManager;
    
    private JemEntityRenderer entityRenderer;
    private JemEntityInstance player;
    
    private int width = 1280, height = 720;
    
    private final int CHUNK_SIZE = 16;
    private final int REGION_SIZE = 128; // 128x128x128 chunks
    private final int POOL_SIZE = 16384; 
    
    private final int EMPTY = 0xFFFFFFFF;

    private float camX = 1024, camY = 20, camZ = 1024; // Center of the world
    private float yaw = -45, pitch = -20;
    private float lastMouseX = width / 2f, lastMouseY = height / 2f;
    private boolean firstMouse = true;
    private float forwardX, forwardY, forwardZ;
    private float rightX, rightY, rightZ;
    private float upX, upY, upZ;

	private JemEntityInstance cow;

	private JemEntityInstance pig;

    public void run() {
        init();
        loop();
        glDeleteProgram(quadProgram);
        glDeleteProgram(computeProgram);
        glDeleteBuffers(quadVBO);
        glDeleteVertexArrays(quadVAO);
        glDeleteTextures(renderTexture);
        glDeleteBuffers(indirectionSSBO);
        glDeleteBuffers(chunkPoolSSBO);
        glDeleteBuffers(lightPoolSSBO);
        if (entityRenderer != null) {
            entityRenderer.destroy();
        }
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
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
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Voxel Engine", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) glfwSetWindowShouldClose(w, true);
        });

        glfwSetCursorPosCallback(window, (w, xpos, ypos) -> {
            if (firstMouse) { lastMouseX = (float) xpos; lastMouseY = (float) ypos; firstMouse = false; }
            float xoffset = (float) xpos - lastMouseX;
            float yoffset = lastMouseY - (float) ypos;
            lastMouseX = (float) xpos; lastMouseY = (float) ypos;
            float sensitivity = 0.1f;
            yaw += xoffset * sensitivity;
            pitch += yoffset * sensitivity;
            if (pitch > 89.0f) pitch = 89.0f;
            if (pitch < -89.0f) pitch = -89.0f;
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0); // V-Sync OFF
        glfwShowWindow(window);
        GL.createCapabilities();

        quadProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/quad.vert", GL_VERTEX_SHADER),
            ShaderUtil.compileShader("src/main/resources/shaders/quad.frag", GL_FRAGMENT_SHADER)
        );
        computeProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/raytracer.comp", GL_COMPUTE_SHADER)
        );

        setupQuad();
        setupTexture();
        setupEntities();
        setupWorld();
        setupResources();
        setupLighting();
    }

    private void setupResources() {
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
        blockDataManager.registerBlock(4, "diamond_block", textureManager, "src/main/resources/assets/minecraft/models/block"); 
        blockDataManager.registerBlock(5, "water_still", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(6, "oak_log", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(7, "oak_planks", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(8, "brick", textureManager, "src/main/resources/assets/minecraft/models/block");
        blockDataManager.registerBlock(9, "gold_block", textureManager, "src/main/resources/assets/minecraft/models/block");
        
        blockDataManager.uploadToGPU();
    }

    private void setupEntities() {
        entityRenderer = new JemEntityRenderer();

        player = loadEntity("creeper", 1024, 2, 1024);
        entityRenderer.addEntity(player);

        cow = loadEntity("cow", 1040, 2, 1024);
        pig = loadEntity("pig", 1060, 2, 1024);
        entityRenderer.addEntity(cow);
        entityRenderer.addEntity(pig);

        
    }

    private JemEntityInstance loadEntity(String modelName, float x, float y, float z) {
        try {
            JemEntityInstance entity = JemModelLoader.loadEntity(modelName);
            entity.position.set(x, y, z);
            return entity;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load JEM model '" + modelName + "'", e);
        }
    }

    private void setupQuad() {
        float[] vertices = {-1,-1, 1,-1, -1,1, 1,-1, 1,1, -1,1};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(vertices.length).put(vertices); buffer.flip();
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

    private void setupLighting() {
        lightEngine = new LightPropagationEngine(world);
        List<LightSource> sources = new ArrayList<>();
        
        // Sun light (high up)
        sources.add(new LightSource(new Vector3i(1024, 63, 1024), new Vector3f(0.9f, 0.85f, 0.7f), 10, 512, LightType.SUN));


        // Block lights
        sources.add(new LightSource(new Vector3i(1030, 2, 1030), new Vector3f(1.0f, 0.1f, 0.1f), 15, 25, LightType.BLOCK));
        sources.add(new LightSource(new Vector3i(1010, 2, 1010), new Vector3f(0.1f, 1.0f, 0.1f), 15, 25, LightType.BLOCK));
        sources.add(new LightSource(new Vector3i(1024, 2, 1040), new Vector3f(0.2f, 0.4f, 1.0f), 15, 25, LightType.BLOCK));
        sources.add(new LightSource(new Vector3i(1024, 3, 1024), new Vector3f(0.2f, 1f, 1.0f), 15, 25, LightType.BLOCK));

        // Place visible blocks for the light sources
        world.setVoxel(1024, 63, 1024, 3); // Sun (Red voxel for visibility)

        world.setVoxel(1030, 2, 1030, 3); // Red
        world.setVoxel(1010, 2, 1010, 4); // Green
        world.setVoxel(1024, 2, 1040, 3); // Blue (using Red voxel for visibility)
        
        lightEngine.propagateAllLights(sources);

        int[] lightPool = world.getLightPool();
        IntBuffer buffer = MemoryUtil.memAllocInt(lightPool.length);
        buffer.put(lightPool).flip();
        lightPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(lightPoolSSBO, buffer, 0);
        MemoryUtil.memFree(buffer);
    }

    private void setupWorld() {
        world = new World();
        nextWorldChunkSlot = 0;

        // 1. Procedural Ground (Layers 0-1)
        for (int cx = 32; cx < 96; cx++) {
            for (int cz = 32; cz < 96; cz++) {
                if (nextWorldChunkSlot >= POOL_SIZE) break;
                int slot = allocateChunkSlot();
                world.setChunkSlot(cx, 0, cz, slot);
                for (int vx = 0; vx < CHUNK_SIZE; vx++) {
                    for (int vz = 0; vz < CHUNK_SIZE; vz++) {
                        world.setVoxelInPool(slot, vx, 0, vz, 2); // Stone base
                        world.setVoxelInPool(slot, vx, 1, vz, 1); // Grass top
                    }
                }
            }
        }
        stampEntityDebugChunks(player, 64, 3, 64);
        stampEntityDebugChunks(cow, 64, 6, 64);
        stampEntityDebugChunks(pig, 64, 9, 64);
        
        // 2. The Mirror House (1020, 2, 1020)
        int baseEX = 1020, baseEY = 2, baseEZ = 1020;
        // Floor
        for(int x=0; x<10; x++) for(int z=0; z<10; z++) world.setVoxel(baseEX+x, baseEY-1, baseEZ+z, 7); // Planks
        // Walls
        for(int y=0; y<5; y++) {
            for(int i=0; i<10; i++) {
                world.setVoxel(baseEX+i, baseEY+y, baseEZ, 6);     // Back log
                world.setVoxel(baseEX+i, baseEY+y, baseEZ+9, 6);   // Front log
                world.setVoxel(baseEX, baseEY+y, baseEZ+i, 6);     // Left log
                world.setVoxel(baseEX+9, baseEY+y, baseEZ+i, 6);   // Right log
            }
        }
        // Glass Windows
        for(int y=1; y<3; y++) {
            for(int i=2; i<8; i++) {
                world.setVoxel(baseEX+i, baseEY+y, baseEZ+9, 3); // Front window
                world.setVoxel(baseEX, baseEY+y, baseEZ+i, 3);   // Side window
            }
        }
        // Mirror Wall inside
        for(int y=0; y<4; y++) for(int i=1; i<9; i++) world.setVoxel(baseEX+1, baseEY+y, baseEZ+i, 4);

        // 3. Gold & Diamond Reflective Platform (1040, 2, 1040)
        for(int x=0; x<8; x++) {
            for(int z=0; z<8; z++) {
                world.setVoxel(1040+x, 2, 1040+z, 9); // Gold base
                if ((x+z)%2 == 0) world.setVoxel(1040+x, 3, 1040+z, 4); // Diamond checker
            }
        }

        // 4. Large Water Pool (1000, 1, 1000)
        for(int x=0; x<20; x++) {
            for(int z=0; z<20; z++) {
                world.setVoxel(1000+x, 1, 1000+z, 5); // Water surface
                world.setVoxel(1000+x, 0, 1000+z, 2); // Stone bottom
            }
        }

        uploadWorldToGpu();
    }

    private int allocateChunkSlot() {
        if (nextWorldChunkSlot >= POOL_SIZE) {
            throw new IllegalStateException("World chunk pool exhausted");
        }
        return nextWorldChunkSlot++;
    }

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

    private void uploadWorldToGpu() {
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

    private void stampEntityDebugChunks(JemEntityInstance entity, int baseChunkX, int chunkY, int chunkZ) {
        List<com.voxel.jem.JemPartDefinition> parts = entity.getModel().getParts();
        ensureDebugChunkPadding(baseChunkX, chunkY, chunkZ, parts.size(), DEBUG_CHUNK_PADDING);
        System.out.printf("Debug stamping model '%s' with %d baked parts at chunk row (%d,%d,%d)%n",
                entity.getModel().getName(), parts.size(), baseChunkX, chunkY, chunkZ);

        for (int i = 0; i < parts.size(); i++) {
            com.voxel.jem.JemPartDefinition part = parts.get(i);
            int chunkX = baseChunkX + i;
            int slot = ensureChunkSlot(chunkX, chunkY, chunkZ);
            int blockId = DEBUG_PART_BLOCK_IDS[i % DEBUG_PART_BLOCK_IDS.length];
            int solidCount = stampPartIntoChunk(part, slot, blockId);
            System.out.printf("  part[%02d] %-20s chunk=(%d,%d,%d) voxels=%d origin=(%.2f, %.2f, %.2f) offset=(%.2f, %.2f, %.2f) scale=(%.3f, %.3f, %.3f)%n",
                    i,
                    part.name,
                    chunkX, chunkY, chunkZ,
                    solidCount,
                    part.origin.x, part.origin.y, part.origin.z,
                    part.gridOffset.x, part.gridOffset.y, part.gridOffset.z,
                    part.voxelScale.x, part.voxelScale.y, part.voxelScale.z);
        }
    }

    private void ensureDebugChunkPadding(int baseChunkX, int chunkY, int chunkZ, int chunkCount, int padding) {
        int minX = Math.max(0, baseChunkX - padding);
        int maxX = Math.min(REGION_SIZE - 1, baseChunkX + chunkCount - 1 + padding);
        int minY = Math.max(0, chunkY - padding);
        int maxY = Math.min(REGION_SIZE - 1, chunkY + padding);
        int minZ = Math.max(0, chunkZ - padding);
        int maxZ = Math.min(REGION_SIZE - 1, chunkZ + padding);

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    ensureChunkSlot(cx, cy, cz);
                }
            }
        }
    }

    private int stampPartIntoChunk(com.voxel.jem.JemPartDefinition part, int slot, int blockId) {
        int filled = 0;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < CHUNK_SIZE; y++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    int voxel = part.voxelData[x + y * CHUNK_SIZE + z * CHUNK_SIZE * CHUNK_SIZE];
                    if ((voxel >>> 24) != 0) {
                        world.setVoxelInPool(slot, x, y, z, blockId);
                        filled++;
                    } else {
                        world.setVoxelInPool(slot, x, y, z, 0);
                    }
                }
            }
        }
        return filled;
    }

    private void updateCamera(float dt) {
        float speed = 5.0f * dt;
        double radYaw = Math.toRadians(yaw), radPitch = Math.toRadians(pitch);
        forwardX = (float) (Math.cos(radYaw) * Math.cos(radPitch));
        forwardY = (float) Math.sin(radPitch);
        forwardZ = (float) (Math.sin(radYaw) * Math.cos(radPitch));
        float len = (float) Math.sqrt(forwardX*forwardX + forwardY*forwardY + forwardZ*forwardZ);
        forwardX /= len; forwardY /= len; forwardZ /= len;
        
        rightX = -forwardZ; rightY = 0; rightZ = forwardX;
        len = (float) Math.sqrt(rightX*rightX + rightZ*rightZ);
        if (len > 0) { rightX /= len; rightZ /= len; }
        
        upX = rightY * forwardZ - rightZ * forwardY;
        upY = rightZ * forwardX - rightX * forwardZ;
        upZ = rightX * forwardY - rightY * forwardX;
        len = (float) Math.sqrt(upX*upX + upY*upY + upZ*upZ);
        if (len > 0) { upX /= len; upY /= len; upZ /= len; }

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) { camX += forwardX * speed; camY += forwardY * speed; camZ += forwardZ * speed; }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) { camX -= forwardX * speed; camY -= forwardY * speed; camZ -= forwardZ * speed; }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { camX -= rightX * speed; camY -= rightY * speed; camZ -= rightZ * speed; }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { camX += rightX * speed; camY += rightY * speed; camZ += rightZ * speed; }
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) camY += speed;
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) camY -= speed;
    }

    private void loop() {
        float lastTime = (float) glfwGetTime();
        float fpsTime = 0;
        int frames = 0;

        while (!glfwWindowShouldClose(window)) {
            float currentTime = (float) glfwGetTime();
            float dt = currentTime - lastTime;
            lastTime = currentTime;

            fpsTime += dt;
            frames++;
            if (fpsTime >= 1.0f) {
                glfwSetWindowTitle(window, String.format("Voxel Engine | FPS: %d | Pos: %.2f, %.2f, %.2f", frames, camX, camY, camZ));
                frames = 0;
                fpsTime = 0;
            }

            updateCamera(dt);

            entityRenderer.updateGpuData();

            glUseProgram(computeProgram);
            glProgramUniform3f(computeProgram, 0, camX, camY, camZ);
            glProgramUniform3f(computeProgram, 1, forwardX, forwardY, forwardZ);
            glProgramUniform3f(computeProgram, 2, rightX, rightY, rightZ);
            glProgramUniform3f(computeProgram, 3, upX, upY, upZ);
            glProgramUniform1i(computeProgram, glGetUniformLocation(computeProgram, "numEntityParts"), entityRenderer.getNumParts());

            glActiveTexture(GL_TEXTURE6);
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getTextureArrayId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_BlockTextures"), 6);

            glActiveTexture(GL_TEXTURE7);
            glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getTextureId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_BlockData"), 7);

            glActiveTexture(GL_TEXTURE8);
            glBindTexture(GL_TEXTURE_2D, biomeManager.getBiomeMapId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_BiomeMap"), 8);

            glActiveTexture(GL_TEXTURE9);
            glBindTexture(GL_TEXTURE_2D, biomeManager.getGrassColormapId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_GrassColormap"), 9);

            glActiveTexture(GL_TEXTURE10);
            glBindTexture(GL_TEXTURE_2D, biomeManager.getFoliageColormapId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_FoliageColormap"), 10);
            
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, lightPoolSSBO);
            entityRenderer.bind(3, 4);

            glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
            glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(quadProgram);
            glBindTextureUnit(0, renderTexture);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    public static void main(String[] args) { new Main().run(); }
}
