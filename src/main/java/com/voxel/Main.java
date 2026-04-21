package com.voxel;

import com.voxel.utils.GLUtil;
import com.voxel.utils.ShaderUtil;
import com.voxel.Entity;
import org.lwjgl.opengl.GL;
import org.lwjgl.glfw.GLFWErrorCallback;
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

public class Main {
    private long window;
    private int quadProgram, computeProgram;
    private int quadVAO, quadVBO, renderTexture;
    private int indirectionSSBO, chunkPoolSSBO;
    private int entityRootSSBO, childNodeSSBO, entityVoxelPoolSSBO;
    private java.nio.ByteBuffer entityRootBuffer;
    private Entity[] entities;
    
    private int width = 1280, height = 720;
    
    private final int CHUNK_SIZE = 16;
    private final int REGION_SIZE = 32;
    private final int POOL_SIZE = 1024;
    
    private final int EMPTY = 0xFFFFFFFF;

    private float camX = 256, camY = 10, camZ = 256;
    private float yaw = -45, pitch = -20;
    private float lastMouseX = width / 2f, lastMouseY = height / 2f;
    private boolean firstMouse = true;
    private float forwardX, forwardY, forwardZ;
    private float rightX, rightY, rightZ;
    private float upX, upY, upZ;

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
        glDeleteBuffers(entityRootSSBO);
        glDeleteBuffers(childNodeSSBO);
        glDeleteBuffers(entityVoxelPoolSSBO);
        if (entityRootBuffer != null) MemoryUtil.memFree(entityRootBuffer);
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
        setupWorld();
        setupEntities();
    }

    private void setupEntities() {
        int entityCount = 1024;
        entities = new Entity[entityCount];
        entityRootBuffer = MemoryUtil.memAlloc(entityCount * 64);
        for (int i = 0; i < entityCount * 64; i++) entityRootBuffer.put(i, (byte) 0);

        int childCount = 4096;
        java.nio.ByteBuffer childBuffer = MemoryUtil.memAlloc(childCount * 64);
        for (int i = 0; i < childCount * 64; i++) childBuffer.put(i, (byte) 0);

        int modelsCount = 128;
        int voxelsPerModel = 32 * 32 * 32;
        IntBuffer modelBuffer = MemoryUtil.memAllocInt(modelsCount * voxelsPerModel);
        for (int i = 0; i < modelsCount * voxelsPerModel; i++) modelBuffer.put(i, 0);

        // Populate many entities
        int idx = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                entities[idx] = new Entity(
                    x * 32.0f + 16.5f, // wx
                    8.5f,              // wy
                    z * 32.0f + 16.5f, // wz
                    1,                  // axis (Y)
                    1.0f,               // cos
                    0.0f,               // sin
                    0,                  // Model 0
                    0,                  // childStart
                    0                   // childCount
                );
                // Populate buffer from entity
                updateEntityBuffer(idx);
                idx++;
            }
        }

        // Model 0: 16x16x16 cube inside 32x32x32, centered at 16,16,16
        for (int vx = 8; vx < 24; vx++) {
            for (int vy = 8; vy < 24; vy++) {
                for (int vz = 8; vz < 24; vz++) {
                    modelBuffer.put(vx + (vy << 5) + (vz << 10), 3);
                }
            }
        }

        entityRootSSBO = glCreateBuffers();
        glNamedBufferStorage(entityRootSSBO, entityRootBuffer, GL_DYNAMIC_STORAGE_BIT);
        
        childNodeSSBO = glCreateBuffers();
        glNamedBufferStorage(childNodeSSBO, childBuffer, 0);
        MemoryUtil.memFree(childBuffer);

        entityVoxelPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(entityVoxelPoolSSBO, modelBuffer, 0);
        MemoryUtil.memFree(modelBuffer);
    }

    private void updateEntityBuffer(int idx) {
        Entity entity = entities[idx];
        int offset = idx * 64;
        entityRootBuffer.putFloat(offset + 0, entity.wx);
        entityRootBuffer.putFloat(offset + 4, entity.wy);
        entityRootBuffer.putFloat(offset + 8, entity.wz);
        entityRootBuffer.putInt(offset + 12, entity.axis);
        entityRootBuffer.putFloat(offset + 16, entity.cos);
        entityRootBuffer.putFloat(offset + 20, entity.sin);
        entityRootBuffer.putInt(offset + 24, entity.model);
        entityRootBuffer.putInt(offset + 28, entity.childStart);
        entityRootBuffer.putInt(offset + 32, entity.childCount);
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

    private void setupWorld() {
        int tableSize = REGION_SIZE * REGION_SIZE * REGION_SIZE;
        IntBuffer tableBuffer = MemoryUtil.memAllocInt(tableSize);
        for (int i = 0; i < tableSize; i++) tableBuffer.put(i, EMPTY);

        int voxelsPerChunk = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
        IntBuffer poolBuffer = MemoryUtil.memAllocInt(POOL_SIZE * voxelsPerChunk);
        for (int i = 0; i < POOL_SIZE * voxelsPerChunk; i++) poolBuffer.put(i, 0);

        int slot = 0;
        for (int cx = 0; cx < REGION_SIZE; cx++) {
            for (int cz = 0; cz < REGION_SIZE; cz++) {
                int tableIdx = cx + 0 * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
                tableBuffer.put(tableIdx, slot);

                int poolOffset = slot * voxelsPerChunk;
                int type = ((cx + cz) % 2 == 0) ? 1 : 2;
                for (int vx = 0; vx < CHUNK_SIZE; vx++) {
                    for (int vy = 0; vy < 2; vy++) {
                        for (int vz = 0; vz < CHUNK_SIZE; vz++) {
                            int vIdx = vx + vy * CHUNK_SIZE + vz * CHUNK_SIZE * CHUNK_SIZE;
                            poolBuffer.put(poolOffset + vIdx, type);
                        }
                    }
                }
                slot++;
                if (slot >= POOL_SIZE) break;
            }
            if (slot >= POOL_SIZE) break;
        }

        // Tag Stress Test Entities in the world (4x4x4 tagging to prevent rotational clipping)
        int entIdx = 0;
        for (int ez = 0; ez < 16; ez++) {
            for (int ex = 0; ex < 16; ex++) {
                int baseWX = ex * 32 + 15, baseWY = 7, baseWZ = ez * 32 + 15;
                for (int ox = 0; ox < 4; ox++) {
                    for (int oy = 0; oy < 4; oy++) {
                        for (int oz = 0; oz < 4; oz++) {
                            int wx = baseWX + ox, wy = baseWY + oy, wz = baseWZ + oz;
                            int cx = wx >> 4, cy = wy >> 4, cz = wz >> 4;
                            int tableIdx = cx + cy * REGION_SIZE + cz * REGION_SIZE * REGION_SIZE;
                            int slotVal = tableBuffer.get(tableIdx);
                            if (slotVal != EMPTY) {
                                int vIdx = (wx & 15) + ((wy & 15) << 4) + ((wz & 15) << 8);
                                poolBuffer.put(slotVal * voxelsPerChunk + vIdx, 0x80000000 | entIdx);
                            }
                        }
                    }
                }
                entIdx++;
            }
        }

        indirectionSSBO = glCreateBuffers();
        glNamedBufferStorage(indirectionSSBO, tableBuffer, 0);
        MemoryUtil.memFree(tableBuffer);

        chunkPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(chunkPoolSSBO, poolBuffer, 0);
        MemoryUtil.memFree(poolBuffer);
    }

    private void updateCamera(float dt) {
        float speed = 30.0f * dt;
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
                glfwSetWindowTitle(window, "Voxel Engine | FPS: " + frames);
                frames = 0;
                fpsTime = 0;
            }

            updateCamera(dt);

            float angle = (float) glfwGetTime() * 1.5f;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            // Update all 256 entity rotations
            for (int i = 0; i < 256; i++) {
                entities[i].setRotation(cos, sin);
                updateEntityBuffer(i);
            }
            // Single bulk upload to GPU (16KB)
            glNamedBufferSubData(entityRootSSBO, 0, entityRootBuffer);
            
            glUseProgram(computeProgram);
            glProgramUniform3f(computeProgram, 0, camX, camY, camZ);
            glProgramUniform3f(computeProgram, 1, forwardX, forwardY, forwardZ);
            glProgramUniform3f(computeProgram, 2, rightX, rightY, rightZ);
            glProgramUniform3f(computeProgram, 3, upX, upY, upZ);
            
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, entityRootSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, childNodeSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, entityVoxelPoolSSBO);
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
