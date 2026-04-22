package com.voxel;

import com.voxel.utils.GLUtil;
import com.voxel.utils.ShaderUtil;
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
    
    private int width = 1280, height = 720;
    
    private final int CHUNK_SIZE = 16;
    private final int REGION_SIZE = 128; // 128x128x128 chunks
    private final int POOL_SIZE = 16384; // Enough for a full 128x128 layer
    
    private final int EMPTY = 0xFFFFFFFF;

    private float camX = 1024, camY = 20, camZ = 1024; // Center of the world
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
                // Fill y=0 layer with chunks
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

        indirectionSSBO = glCreateBuffers();
        glNamedBufferStorage(indirectionSSBO, tableBuffer, 0);
        MemoryUtil.memFree(tableBuffer);

        chunkPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(chunkPoolSSBO, poolBuffer, 0);
        MemoryUtil.memFree(poolBuffer);
    }

    private void updateCamera(float dt) {
        float speed = 50.0f * dt; // Increased speed for larger world
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

            glUseProgram(computeProgram);
            glProgramUniform3f(computeProgram, 0, camX, camY, camZ);
            glProgramUniform3f(computeProgram, 1, forwardX, forwardY, forwardZ);
            glProgramUniform3f(computeProgram, 2, rightX, rightY, rightZ);
            glProgramUniform3f(computeProgram, 3, upX, upY, upZ);
            
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
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
