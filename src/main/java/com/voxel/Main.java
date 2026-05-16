package com.voxel;

import com.voxel.utils.BlockDataManager;
import com.voxel.utils.ShaderUtil;
import com.voxel.utils.TextureManager;
import com.voxel.lighting.LightPropagationEngine;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

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
 * Strictly handles OpenGL and Input Polling.
 * Logic is offloaded to a background thread.
 */
public class Main {
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
    private Player player;
    
    private int width = 1280, height = 720;
    private final int CHUNK_SIZE = 16, REGION_SIZE = 128, POOL_SIZE = 16384;

    private float lastMouseX = width / 2f, lastMouseY = height / 2f;
    private boolean firstMouse = true;
    private float yaw = -90, pitch = 0;

    private Thread logicThread;
    private volatile boolean running = true;

    public void run() {
        init();
        
        // Start Logic Thread at 10 TPS with actual timing
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

        window = glfwCreateWindow(width, height, "Voxel Engine | 8-Bounce PBR", NULL, NULL);
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
            if (pitch > 89.0f) pitch = 89.0f; if (pitch < -89.0f) pitch = -89.0f;
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
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
        
        world = new World();
        com.voxel.world.WorldGenerator generator = new com.voxel.world.WorldGenerator(97 /*seed*/);
        LightPropagationEngine lightEngine = new LightPropagationEngine(world, blockDataManager);
        chunkManager = new com.voxel.world.ChunkManager(world, generator, lightEngine, 8);
        
        chunkManager.update(player.getPosition());
        uploadWorldToGpu();
    }

    private void logicLoop() {
        long lastTime = System.nanoTime();
        final long targetNanos = 16_666_666L; // 60 TPS (physics/logic tick rate)

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
                    try { Thread.sleep(sleepTime); } catch (InterruptedException ignored) {}
                }
            } else {
                Thread.yield();
            }
        }
    }

    private void tick(float dt) {
        if (!running) return;
        player.setYaw(yaw);
        player.setPitch(pitch);
        handleInput(dt);
        player.update(dt, world, blockDataManager);
        entityManager.update(dt);
        chunkManager.update(player.getPosition());
    }

    private void handleInput(float dt) {
        float speed = player.isFlying() ? 1.5f : 0.4f;
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
        float fx = (float) (Math.cos(ry) * Math.cos(rp)), fz = (float) (Math.sin(ry) * Math.cos(rp));
        float rx = -fz, rz = fx;
        float rl = (float) Math.sqrt(rx*rx + rz*rz); if (rl > 0) { rx /= rl; rz /= rl; }

        float dx = 0, dz = 0;
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) { dx += fx; dz += fz; }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) { dx -= fx; dz -= fz; }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { dx -= rx; dz -= rz; }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { dx += rx; dz += rz; }
        
        float mvLen = (float) Math.sqrt(dx*dx + dz*dz);
        if (mvLen > 0) { dx /= mvLen; dz /= mvLen; }
        player.move(dx * speed, 0, dz * speed, speed * 2.0f);

        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            if (player.isFlying()) player.move(0, speed, 0, speed * 2.0f);
            else player.jump();
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            if (player.isFlying()) player.move(0, -speed, 0, speed * 2.0f);
        }
        
        if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) player.setFlying(true);
        if (glfwGetKey(window, GLFW_KEY_G) == GLFW_PRESS) player.setFlying(false);
    }

    private void loop() {
        float lastTime = (float) glfwGetTime();
        int frames = 0; float fpsTime = 0;

        while (!glfwWindowShouldClose(window)) {
            float currentTime = (float) glfwGetTime();
            float dt = currentTime - lastTime; lastTime = currentTime;
            fpsTime += dt; frames++;
            if (fpsTime >= 1.0f) {
                glfwSetWindowTitle(window, String.format("Voxel Engine | FPS: %d", frames));
                frames = 0; fpsTime = 0;
            }

            uploadDirtyChunks();
            entityManager.uploadToGPU();

            // Reset Point Light count to 0
            FloatBuffer plBuf = MemoryUtil.memAllocFloat(4);
            plBuf.put(0, Float.intBitsToFloat(0));
            glNamedBufferSubData(pointLightSSBO, 0, plBuf);
            MemoryUtil.memFree(plBuf);

            Vector3f pPos = player.getPosition();
            glUseProgram(computeProgram);
            glProgramUniform3f(computeProgram, 0, pPos.x, pPos.y + 1.6f, pPos.z);
            
            double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
            float fx = (float)(Math.cos(ry)*Math.cos(rp)), fy = (float)Math.sin(rp), fz = (float)(Math.sin(ry)*Math.cos(rp));
            float rx = -fz, rz = fx; 
            float rl = (float)Math.sqrt(rx*rx+rz*rz); if(rl>0){rx/=rl; rz/=rl;}
            float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
            
            glProgramUniform3f(computeProgram, 1, fx, fy, fz);
            glProgramUniform3f(computeProgram, 2, rx, 0, rz);
            glProgramUniform3f(computeProgram, 3, ux, uy, uz); 
            glProgramUniform1f(computeProgram, 4, currentTime);
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
            
            // Single Pass: Draw renderTexture to screen
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
        }
    }

    private void bindTextures() {
        glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getTextureArrayId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockTextures"), 6);
        glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getTextureId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockData"), 7);
        glActiveTexture(GL_TEXTURE12); glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBTextureId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockAABBs"), 12);
        glActiveTexture(GL_TEXTURE11); glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getInfoTextureId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockAABBInfo"), 11);
        glActiveTexture(GL_TEXTURE13); glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBUVTextureId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BlockAABBUVs"), 13);
        glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D, biomeManager.getBiomeMapId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_BiomeMap"), 8);
        glActiveTexture(GL_TEXTURE9); glBindTexture(GL_TEXTURE_2D, biomeManager.getGrassColormapId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_GrassColormap"), 9);
        glActiveTexture(GL_TEXTURE10); glBindTexture(GL_TEXTURE_2D, biomeManager.getFoliageColormapId());
        glUniform1i(glGetUniformLocation(computeProgram, "u_FoliageColormap"), 10);
    }

    private void setupResources() {
        textureManager = new TextureManager();
        textureManager.loadTextures("src/main/resources/assets/minecraft/textures/blocks");
        biomeManager = new com.voxel.utils.BiomeManager();
        biomeManager.loadColormaps("src/main/resources/assets/minecraft/textures/colormap/grass.png", "src/main/resources/assets/minecraft/textures/colormap/foliage.png");
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

    private void setupQuad() {
        float[] vertices = {-1,-1, 1,-1, -1,1, 1,-1, 1,1, -1,1};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(vertices.length).put(vertices); buffer.flip();
            quadVBO = glCreateBuffers(); glNamedBufferStorage(quadVBO, buffer, 0);
            quadVAO = glCreateVertexArrays(); glEnableVertexArrayAttrib(quadVAO, 0);
            glVertexArrayAttribFormat(quadVAO, 0, 2, GL_FLOAT, false, 0); glVertexArrayAttribBinding(quadVAO, 0, 0);
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
        IntBuffer buf = MemoryUtil.memAllocInt(table.length); buf.put(table).flip();
        indirectionSSBO = glCreateBuffers(); glNamedBufferStorage(indirectionSSBO, buf, GL_DYNAMIC_STORAGE_BIT);
        MemoryUtil.memFree(buf);
        chunkPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(chunkPoolSSBO, (long)POOL_SIZE * CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);
        
        int[] bitmaskPool = world.getBitmaskPool();
        IntBuffer bbuf = MemoryUtil.memAllocInt(bitmaskPool.length); bbuf.put(bitmaskPool).flip();
        bitmaskSSBO = glCreateBuffers();
        glNamedBufferStorage(bitmaskSSBO, bbuf, GL_DYNAMIC_STORAGE_BIT);
        MemoryUtil.memFree(bbuf);
        
        short[] occlusionPool = world.getOcclusionPool();
        java.nio.ShortBuffer obuf = MemoryUtil.memAllocShort(occlusionPool.length); obuf.put(occlusionPool).flip();
        occlusionSSBO = glCreateBuffers();
        glNamedBufferStorage(occlusionSSBO, obuf, GL_DYNAMIC_STORAGE_BIT);
        MemoryUtil.memFree(obuf);
        
        pointLightSSBO = glCreateBuffers();
        glNamedBufferStorage(pointLightSSBO, 4096, GL_DYNAMIC_STORAGE_BIT); // 4KB buffer for roughly 128 lights
        
        uploadDirtyChunks();
    }

    private void uploadDirtyChunks() {
        java.util.Set<Integer> dirty = chunkManager.getDirtySlots();
        if (dirty.isEmpty() && !chunkManager.isTableDirty()) return;
        if (chunkManager.isTableDirty()) {
            int[] table = world.getIndirectionTable();
            IntBuffer buf = MemoryUtil.memAllocInt(table.length); buf.put(table).flip();
            glNamedBufferSubData(indirectionSSBO, 0, buf); MemoryUtil.memFree(buf);
        }
        int[] pool = world.getChunkPool(); int[] masks = world.getBitmaskPool();
        short[] occs = world.getOcclusionPool();
        int vpc = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        for (int s : dirty) {
            IntBuffer buf = MemoryUtil.memAllocInt(vpc); buf.put(pool, s * vpc, vpc).flip();
            glNamedBufferSubData(chunkPoolSSBO, (long)s * vpc * Integer.BYTES, buf); MemoryUtil.memFree(buf);
            IntBuffer mbuf = MemoryUtil.memAllocInt(128); mbuf.put(masks, s * 128, 128).flip();
            glNamedBufferSubData(bitmaskSSBO, (long)s * 128 * Integer.BYTES, mbuf); MemoryUtil.memFree(mbuf);
            
            java.nio.ShortBuffer obuf = MemoryUtil.memAllocShort(vpc); obuf.put(occs, s * vpc, vpc).flip();
            glNamedBufferSubData(occlusionSSBO, (long)s * vpc * Short.BYTES, obuf); MemoryUtil.memFree(obuf);
        }
        chunkManager.clearDirty();
    }

    public static void main(String[] args) { new Main().run(); }
}
