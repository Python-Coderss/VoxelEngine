package com.voxel.render;

import com.voxel.Main;
import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.camera.CameraController;
import com.voxel.crafting.CraftingManager;
import com.voxel.entity.EntityManager;
import com.voxel.game.AtmosphereRenderer;
import com.voxel.game.GameContext;
import com.voxel.utils.BiomeManager;
import com.voxel.utils.TextureManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Locale;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.*;

/**
 * Owns the OpenGL render loop, GL handles (programs, FBOs, SSBOs, VAOs/VBOs), shader
 * uniform-location cache, GPU uploads, and crafting-item texture-array uploads.
 *
 * Kept as a back-pointer to Main for cross-thread coordination flags that Main owns
 * (needsWorldUpload, needsCursorUpdate, etc.) and for accessing Main's window hwnd.
 */
public class Renderer {
    public static final int MAX_DIRTY_UPLOADS_PER_FRAME = 48;
    private static final float FOV_DEGREES = 70.0f;
    private static final float FOV_RAD = (float) Math.toRadians(FOV_DEGREES);
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE = 2048.0f;

    public int quadProgram, computeProgram;
    public int quadVAO, quadVBO, renderTexture;

    public int indirectionSSBO, chunkPoolSSBO, bitmaskSSBO, occlusionSSBO, pointLightSSBO, lightSSBO;
    public int craftingItemSSBO;

    public int locBlockTextures, locEntityTextures, locBlockData, locBlockAABBs, locBlockAABBInfo, locBlockAABBUVs;
    public int locBiomeMap, locGrassColormap, locUITexture, locFoliageColormap, locUISource;
    public int locHeartUVs;
    public int locCraftingItemCount;
    public int locQuadPass;

    public java.nio.FloatBuffer persistentPlBuf;

    private final GameContext ctx;
    private final Main main;
    private final CameraController camera;
    private final TextureManager textureManager;
    private final BlockDataManager blockDataManager;
    private final BiomeManager biomeManager;
    private final AtmosphereRenderer atmosphereRenderer;
    private final com.voxel.world.ChunkManager chunkManager;
    private final EntityManager entityManager;
    private final World world;

    public Renderer(GameContext ctx, Main main, CameraController camera,
                    TextureManager textureManager, BlockDataManager blockDataManager,
                    BiomeManager biomeManager, AtmosphereRenderer atmosphereRenderer,
                    com.voxel.world.ChunkManager chunkManager, EntityManager entityManager, World world) {
        this.ctx = ctx;
        this.main = main;
        this.camera = camera;
        this.textureManager = textureManager;
        this.blockDataManager = blockDataManager;
        this.biomeManager = biomeManager;
        this.atmosphereRenderer = atmosphereRenderer;
        this.chunkManager = chunkManager;
        this.entityManager = entityManager;
        this.world = world;
    }

    /** Cache uniform locations to avoid per-frame GL queries. */
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

    /** Bind block/entity/biome/colormap/UI textures to texture units. */
    public void bindTextures() {
        glActiveTexture(GL_TEXTURE6);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getTextureArrayId());
        glUniform1i(locBlockTextures, 6);
        glActiveTexture(GL_TEXTURE7);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getTextureId());
        glUniform1i(locBlockData, 7);
        glActiveTexture(GL_TEXTURE8);
        glBindTexture(GL_TEXTURE_2D, biomeManager.getBiomeMapId());
        glUniform1i(locBiomeMap, 8);
        glActiveTexture(GL_TEXTURE11);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBTextureId());
        glUniform1i(locBlockAABBs, 11);
        glActiveTexture(GL_TEXTURE12);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getInfoTextureId());
        glUniform1i(locBlockAABBInfo, 12);
        glActiveTexture(GL_TEXTURE13);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getAABBUVTextureId());
        glUniform1i(locBlockAABBUVs, 13);
    }

    /** One frame of the render loop. */
    public void loop() {
        float lastTime = (float) glfwGetTime();
        int frames = 0;
        float fpsTime = 0;
        while (!glfwWindowShouldClose(main.window)) {
            float currentTime = (float) glfwGetTime();
            float dt = currentTime - lastTime;
            lastTime = currentTime;
            fpsTime += dt;
            frames++;
            if (fpsTime >= 1.0f) {
                ctx.lastMeasuredFps = frames;
                frames = 0;
                fpsTime = 0;
            }

            if (main.player.isDead()) {
                main.statusMessage = "YOU DIED! Press R to respawn.";
                main.statusUntil = glfwGetTime() + 1.0;
            }

            main.syncGameState();
            if (main.needsWorldUpload) {
                main.uploadWorldToGpu();
                main.needsWorldUpload = false;
            }
            main.uploadDirtyChunks();
            if (chunkManager.isBiomeMapDirty()) {
                biomeManager.uploadBiomeMap();
                chunkManager.clearBiomeMapDirty();
            }
            main.hud.updateInventoryUi();
            main.hud.updateWindowTitle();
            main.hud.beginFrame();

            Vector3f cameraPos = camera.getActiveCameraPosition();
            entityManager.uploadToGPU(ctx.activeDimension, cameraPos);

            persistentPlBuf.rewind();
            glNamedBufferSubData(pointLightSSBO, 0, persistentPlBuf);

            double ry = Math.toRadians(main.yaw), rp = Math.toRadians(main.pitch);
            float fx = (float) (Math.cos(ry) * Math.cos(rp)), fy = (float) Math.sin(rp), fz = (float) (Math.sin(ry) * Math.cos(rp));
            float rx = -fz, rz = fx;
            float rl = (float) Math.sqrt(rx * rx + rz * rz);
            if (rl > 0) { rx /= rl; rz /= rl; }
            float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;

            if (main.cameraShake > 0.01f) {
                cameraPos.x += (float)(Math.random() - 0.5) * main.cameraShake * 0.1f;
                cameraPos.y += (float)(Math.random() - 0.5) * main.cameraShake * 0.1f;
                cameraPos.z += (float)(Math.random() - 0.5) * main.cameraShake * 0.1f;
            }

            glUseProgram(computeProgram);
            glProgramUniform3f(computeProgram, 0, cameraPos.x, cameraPos.y, cameraPos.z);
            glProgramUniform3f(computeProgram, 1, fx, fy, fz);
            glProgramUniform3f(computeProgram, 2, rx, 0, rz);
            glProgramUniform3f(computeProgram, 3, ux, uy, uz);
            glProgramUniform1f(computeProgram, 4, ctx.worldTime);
            glProgramUniform1i(computeProgram, 5, entityManager.getEntityCount(ctx.activeDimension));
            atmosphereRenderer.upload(ctx.worldTime, ctx.activeDimension);
            glProgramUniform1i(computeProgram, atmosphereRenderer.locDimensionID(), ctx.activeDimension.id);
            glProgramUniform3i(computeProgram, 6, world.getOffsetX(), world.getOffsetY(), world.getOffsetZ());

            // SDF sky early-out uniforms: max terrain Y bounds allow skipping DDA
            // when camera is above all loaded terrain and looking up.
            glProgramUniform1f(computeProgram, 22, chunkManager.getMaxTerrainY());
            glProgramUniform1f(computeProgram, 23, chunkManager.getMaxTerrainX());
            glProgramUniform1f(computeProgram, 24, chunkManager.getMaxTerrainZ());
            glProgramUniform1f(computeProgram, 25, chunkManager.getMinTerrainX());
            glProgramUniform1f(computeProgram, 26, chunkManager.getMinTerrainZ());
            glProgramUniform1i(computeProgram, 27, chunkManager.areBoundsValid() ? 1 : 0);

            if (ctx.breakTargetX != Integer.MIN_VALUE) {
                glProgramUniform3i(computeProgram, 19, ctx.breakTargetX, ctx.breakTargetY, ctx.breakTargetZ);
                float hardness = blockDataManager.getHardness(world.getVoxel(ctx.breakTargetX, ctx.breakTargetY, ctx.breakTargetZ));
                glProgramUniform1f(computeProgram, 20, ctx.breakProgress / Math.max(1.0f, hardness));
            } else {
                glProgramUniform3i(computeProgram, 19, 0, 0, 0);
                glProgramUniform1f(computeProgram, 20, 0.0f);
            }
            int destroyBaseLayer = textureManager.getTextureIndex("destroy_stage_0");
            glProgramUniform1i(computeProgram, 21, destroyBaseLayer < 0 ? -1 : destroyBaseLayer);

            glUniform4f(locHeartUVs, main.hud.uvHeartFull.x, main.hud.uvHeartFull.y, main.hud.uvHeartFull.z, main.hud.uvHeartFull.w);
            glUniform4f(locHeartUVs + 1, main.hud.uvHeartHalf.x, main.hud.uvHeartHalf.y, main.hud.uvHeartHalf.z, main.hud.uvHeartHalf.w);
            glUniform4f(locHeartUVs + 2, main.hud.uvHeartEmpty.x, main.hud.uvHeartEmpty.y, main.hud.uvHeartEmpty.z, main.hud.uvHeartEmpty.w);

            bindTextures();

            glActiveTexture(GL_TEXTURE17);
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getDestroyStageArrayId());
            glUniform1i(glGetUniformLocation(computeProgram, "u_DestroyStages"), 17);

            glActiveTexture(GL_TEXTURE15);
            glBindTexture(GL_TEXTURE_2D, main.hud.uiTextureId);
            glUniform1i(locUISource, 15);

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, indirectionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, chunkPoolSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, bitmaskSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, occlusionSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, pointLightSSBO);
            entityManager.bind(6, 7);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, craftingItemSSBO);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, lightSSBO);

            uploadCraftingItems();

            glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
            glDispatchCompute((main.width + 15) / 16, (main.height + 15) / 16, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, main.width, main.height);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(quadProgram);
            glBindTextureUnit(0, renderTexture);
            glUniform1i(locQuadPass, 1);
            glBindVertexArray(quadVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            glfwSwapBuffers(main.window);
            glfwPollEvents();
            main.leftMousePressedThisFrame = false;
        }
    }

    /** Upload the 3x3 crafting grid state into the craftingItemSSBO for shader rendering. */
    public void uploadCraftingItems() {
        main.uploadCraftingItems();
    }
}
