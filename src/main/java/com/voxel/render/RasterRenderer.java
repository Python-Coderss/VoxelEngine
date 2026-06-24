package com.voxel.render;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.TextureManager;
import com.voxel.utils.ShaderUtil;
import com.voxel.world.ChunkManager;
import com.voxel.world.DimensionType;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.*;

/**
 * Hybrid rasterized + compute shader renderer.
 * 
 * Pipeline:
 * 1. GBuffer pass: rasterize chunk meshes into MRT (position, normal, albedo, material)
 * 2. Lighting pass: compute shader reads GBuffer, applies sun + BFS light pool + reflection
 * 3. Sky: handled by lighting compute shader for sky pixels (depth>=1.0 from prepass)
 * 
 * Meshes are built lazily on first render and stored by absolute chunk sector coordinates.
 */
public class RasterRenderer {
    private final World world;
    private final ChunkManager chunkManager;
    private final BlockDataManager blockDataManager;
    private final TextureManager textureManager;
    private final ChunkMeshBuilder meshBuilder;

    // GBuffer shader
    private int gbufferProgram;
    private int locVPGbuffer;
    private int locWorldOffsetGbuffer;
    private int locCameraPosGbuffer;

    // GBuffer FBO + textures
    private int gbufferFBO;
    private int gbufferPosition, gbufferNormal, gbufferAlbedo, gbufferMaterial;
    private int gbufferDepth;

    // Lighting compute shader
    private int lightingProgram;
    private int locLightGBufferPos, locLightGBufferNorm, locLightGBufferAlbedo, locLightGBufferMat;
    private int locLightBlockData;
    private int locLightSunDir, locLightSunColor, locLightMoonDir, locLightMoonColor;
    private int locLightSkyZenith, locLightSkyHorizon, locLightAmbient, locLightDimensionID;
    private int locLightWorldOffset, locLightCameraPos;
    private int locLightCamForward, locLightCamRight, locLightCamUp, locLightAspectRatio;

    // Mesh storage: Map<absSectorKey, ChunkMesh> where key = absCX | (absCY << 20) | (absCZ << 40)
    private final Map<Long, ChunkMesh> meshes = new HashMap<>();

    // Frustum culling
    private static final int MAX_VISIBLE_SECTORS = 65536;
    private IntBuffer visBuffer;  // packed: absCX-offsetX/16 | (cy << 8) | ((absCZ-offsetZ/16) << 16)

    // Shared resources set from Main
    private int sharedIndirectionSSBO;
    private int sharedChunkPoolSSBO;
    private int sharedBitmaskSSBO;
    private int sharedLightSSBO;
    private int sharedBlockDataTex;

    // Reusable objects (avoid per-frame allocation)

    private int width, height;

    public RasterRenderer(World world, ChunkManager chunkManager, BlockDataManager blockDataManager, TextureManager textureManager) {
        this.world = world;
        this.chunkManager = chunkManager;
        this.blockDataManager = blockDataManager;
        this.textureManager = textureManager;
        this.meshBuilder = new ChunkMeshBuilder(world, blockDataManager);
    }

    public void setSharedResources(int indirectionSSBO, 
                                    int chunkPoolSSBO, int bitmaskSSBO, int lightSSBO,
                                    int blockDataTex) {
        this.sharedIndirectionSSBO = indirectionSSBO;
        this.sharedChunkPoolSSBO = chunkPoolSSBO;
        this.sharedBitmaskSSBO = bitmaskSSBO;
        this.sharedLightSSBO = lightSSBO;
        this.sharedBlockDataTex = blockDataTex;
    }

    /** Initialize shaders and FBO. Call after OpenGL context is current. */
    public void init(int width, int height) {
        this.width = width;
        this.height = height;

        // Compile GBuffer program
        gbufferProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/chunk_gbuffer.vert", GL_VERTEX_SHADER),
            ShaderUtil.compileShader("src/main/resources/shaders/chunk_gbuffer.frag", GL_FRAGMENT_SHADER)
        );
        locVPGbuffer = glGetUniformLocation(gbufferProgram, "u_VP");
        locWorldOffsetGbuffer = glGetUniformLocation(gbufferProgram, "u_WorldOffset");
        locCameraPosGbuffer = glGetUniformLocation(gbufferProgram, "u_CameraPos");

        // Compile lighting compute shader
        lightingProgram = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/lighting.comp", GL_COMPUTE_SHADER)
        );
        locLightGBufferPos = glGetUniformLocation(lightingProgram, "u_GBufferPosition");
        locLightGBufferNorm = glGetUniformLocation(lightingProgram, "u_GBufferNormal");
        locLightGBufferAlbedo = glGetUniformLocation(lightingProgram, "u_GBufferAlbedo");
        locLightGBufferMat = glGetUniformLocation(lightingProgram, "u_GBufferMaterial");
        locLightBlockData = glGetUniformLocation(lightingProgram, "u_BlockData");
        locLightSunDir = glGetUniformLocation(lightingProgram, "u_SunDir");
        locLightSunColor = glGetUniformLocation(lightingProgram, "u_SunColor");
        locLightMoonDir = glGetUniformLocation(lightingProgram, "u_MoonDir");
        locLightMoonColor = glGetUniformLocation(lightingProgram, "u_MoonColor");
        locLightSkyZenith = glGetUniformLocation(lightingProgram, "u_SkyZenith");
        locLightSkyHorizon = glGetUniformLocation(lightingProgram, "u_SkyHorizon");
        locLightAmbient = glGetUniformLocation(lightingProgram, "u_Ambient");
        locLightDimensionID = glGetUniformLocation(lightingProgram, "u_DimensionID");
        locLightWorldOffset = glGetUniformLocation(lightingProgram, "u_WorldOffset");
        locLightCameraPos = glGetUniformLocation(lightingProgram, "u_CameraPos");
        locLightCamForward = glGetUniformLocation(lightingProgram, "u_CamForward");
        locLightCamRight = glGetUniformLocation(lightingProgram, "u_CamRight");
        locLightCamUp = glGetUniformLocation(lightingProgram, "u_CamUp");
        locLightAspectRatio = glGetUniformLocation(lightingProgram, "u_AspectRatio");

        // Create GBuffer FBO
        createGBuffer(width, height);

        // Visibility buffer
        visBuffer = MemoryUtil.memAllocInt(MAX_VISIBLE_SECTORS);
    }

    private void createGBuffer(int w, int h) {
        if (gbufferFBO != 0) {
            glDeleteFramebuffers(gbufferFBO);
            glDeleteTextures(gbufferPosition);
            glDeleteTextures(gbufferNormal);
            glDeleteTextures(gbufferAlbedo);
            glDeleteTextures(gbufferMaterial);
            glDeleteTextures(gbufferDepth);
        }

        gbufferFBO = glCreateFramebuffers();

        gbufferPosition = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(gbufferPosition, 1, GL_RGBA32F, w, h);
        glTextureParameteri(gbufferPosition, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(gbufferPosition, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glNamedFramebufferTexture(gbufferFBO, GL_COLOR_ATTACHMENT0, gbufferPosition, 0);

        gbufferNormal = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(gbufferNormal, 1, GL_RGBA16F, w, h);
        glTextureParameteri(gbufferNormal, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(gbufferNormal, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glNamedFramebufferTexture(gbufferFBO, GL_COLOR_ATTACHMENT1, gbufferNormal, 0);

        gbufferAlbedo = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(gbufferAlbedo, 1, GL_RGBA16F, w, h);
        glTextureParameteri(gbufferAlbedo, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(gbufferAlbedo, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glNamedFramebufferTexture(gbufferFBO, GL_COLOR_ATTACHMENT2, gbufferAlbedo, 0);

        gbufferMaterial = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(gbufferMaterial, 1, GL_RGBA16F, w, h);
        glTextureParameteri(gbufferMaterial, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(gbufferMaterial, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glNamedFramebufferTexture(gbufferFBO, GL_COLOR_ATTACHMENT3, gbufferMaterial, 0);

        gbufferDepth = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(gbufferDepth, 1, GL_DEPTH_COMPONENT24, w, h);
        glTextureParameteri(gbufferDepth, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(gbufferDepth, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glNamedFramebufferTexture(gbufferFBO, GL_DEPTH_ATTACHMENT, gbufferDepth, 0);

        int[] drawBuffers = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3};
        glNamedFramebufferDrawBuffers(gbufferFBO, drawBuffers);

        int status = glCheckNamedFramebufferStatus(gbufferFBO, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("GBuffer FBO incomplete: " + status);
        }
    }

    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
        createGBuffer(w, h);
    }

    /** Called when world changes (dimension switch). Clears all meshes. */
    public void clearAllMeshes() {
        for (ChunkMesh mesh : meshes.values()) mesh.delete();
        meshes.clear();
    }

    /**
     * Main render call. Assumes depth prepass has already been rendered.
     * Builds meshes lazily for visible chunks, then does GBuffer → lighting → writes to renderTexture.
     */
    public void render(Vector3f cameraPos, float fx, float fy, float fz, float fovRad,
                       float worldTime, DimensionType dimension, int renderTexture) {
        

        int worldOx = world.getOffsetX();
        int worldOy = world.getOffsetY();
        int worldOz = world.getOffsetZ();

        // --- Build frustum-culled visibility list from loaded chunks ---
        Matrix4f proj = new Matrix4f().perspective(fovRad, (float) width / height, 0.1f, 2048.0f);
        Vector3f lookTarget = new Vector3f(cameraPos.x + fx, cameraPos.y + fy, cameraPos.z + fz);
        Matrix4f viewM = new Matrix4f().lookAt(cameraPos, lookTarget, new Vector3f(0, 1, 0));
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(proj).mul(viewM));

        visBuffer.clear();
        int visibleCount = 0;

        for (Map.Entry<Long, Integer[]> entry : chunkManager.getLoadedChunks().entrySet()) {
            long chunkKey = entry.getKey();
            int absCX = (int)(chunkKey >> 32);
            int absCZ = (int)chunkKey;
            Integer[] slots = entry.getValue();
            if (slots == null) continue;

            int relCX = absCX - (worldOx >> 4);
            int relCZ = absCZ - (worldOz >> 4);

            for (int cy = 0; cy < 16; cy++) {
                if (slots[cy] == null || slots[cy] == World.EMPTY) continue;
                if (visibleCount >= MAX_VISIBLE_SECTORS) break;

                float minX = (float)(absCX << 4);
                float minZ = (float)(absCZ << 4);
                float minY = (float)(cy << 4) + worldOy;

                if (frustum.testAab(minX, minY, minZ, minX + 16, minY + 16, minZ + 16)) {
                    // Lazy mesh build: build on first visibility each frame
                    long sectorKey = sectorKey(absCX, cy, absCZ);
                    if (!meshes.containsKey(sectorKey)) {
                        buildMeshNow(absCX, cy, absCZ);
                    }
                    // Pack: low 8 bits = relCX, next 8 = cy, next 8 = relCZ
                    visBuffer.put(visibleCount++, (relCX & 0xFF) | ((cy & 0xFF) << 8) | ((relCZ & 0xFF) << 16));
                }
            }
            if (visibleCount >= MAX_VISIBLE_SECTORS) break;
        }
        visBuffer.position(0).limit(Math.max(visibleCount, 1));

        // --- GBuffer pass: rasterize visible chunk meshes ---
        glBindFramebuffer(GL_FRAMEBUFFER, gbufferFBO);
        glViewport(0, 0, width, height);
        glClearColor(0, 0, 0, 0);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);

        if (visibleCount > 0) {
            glUseProgram(gbufferProgram);

            // Block textures (GL_TEXTURE_2D_ARRAY)
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureManager.getTextureArrayId());

            // Block data TBO
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getTextureId());

            glUniform3i(locWorldOffsetGbuffer, worldOx, worldOy, worldOz);
            glUniform3f(locCameraPosGbuffer, cameraPos.x, cameraPos.y, cameraPos.z);

            Matrix4f vp = new Matrix4f(proj).mul(viewM);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                glUniformMatrix4fv(locVPGbuffer, false, vp.get(stack.mallocFloat(16)));
            }

            // Draw each visible chunk mesh
            visBuffer.position(0);
            for (int i = 0; i < visibleCount; i++) {
                int packed = visBuffer.get(i);
                int relCX = (byte)(packed & 0xFF);
                int cy = (packed >> 8) & 0xFF;
                int relCZ = (byte)((packed >> 16) & 0xFF);
                int absCX = relCX + (worldOx >> 4);
                int absCZ = relCZ + (worldOz >> 4);
                long sectorKey = sectorKey(absCX, cy, absCZ);
                ChunkMesh mesh = meshes.get(sectorKey);
                if (mesh != null && !mesh.isEmpty()) {
                    // Set per-instance chunk coord attribute (relative for vertex shader)
                    glVertexAttribI3i(5, relCX, cy, relCZ);
                    mesh.draw();
                }
            }
        }

        glDisable(GL_DEPTH_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // --- Lighting compute shader pass ---
        glUseProgram(lightingProgram);

        // Bind GBuffer textures
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gbufferPosition);
        glUniform1i(locLightGBufferPos, 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, gbufferNormal);
        glUniform1i(locLightGBufferNorm, 1);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, gbufferAlbedo);
        glUniform1i(locLightGBufferAlbedo, 2);

        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, gbufferMaterial);
        glUniform1i(locLightGBufferMat, 3);

        // Depth prepass textures

        // Block data TBO
        glActiveTexture(GL_TEXTURE7);
        glBindTexture(GL_TEXTURE_BUFFER, blockDataManager.getTextureId());
        glUniform1i(locLightBlockData, 7);

        // SSBOs for DDA
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, sharedIndirectionSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, sharedChunkPoolSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, sharedBitmaskSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, sharedLightSSBO);

        // World offset + camera
        glUniform3i(locLightWorldOffset, worldOx, worldOy, worldOz);
        glUniform3f(locLightCameraPos, cameraPos.x, cameraPos.y, cameraPos.z);

        // Camera vectors for sky/reflection ray computation
        float aspectRatio = (float) width / height;
        float crx = -fz, crz = fx;
        float crl = (float) Math.sqrt(crx * crx + crz * crz);
        if (crl > 0) { crx /= crl; crz /= crl; }
        float cux = -crz * fy, cuy = crz * fx - crx * fz, cuz = crx * fy;
        glUniform3f(locLightCamForward, fx, fy, fz);
        glUniform3f(locLightCamRight, crx, 0, crz);
        glUniform3f(locLightCamUp, cux, cuy, cuz);
        glUniform1f(locLightAspectRatio, aspectRatio);

        // Atmosphere
        uploadAtmosphere(worldTime, dimension);

        // Dispatch lighting compute shader
        glBindImageTexture(0, renderTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    /** Build a mesh for a chunk sector immediately (called from render thread). */
    private void buildMeshNow(int absCX, int absCY, int absCZ) {
        long key = sectorKey(absCX, absCY, absCZ);
        ChunkMesh mesh = meshes.computeIfAbsent(key, k -> new ChunkMesh());
        ChunkMeshBuilder.MeshResult result = meshBuilder.build(absCX, absCY, absCZ);
        mesh.upload(result.vertices, result.indices);
    }

    /** Mark chunk sector meshes as dirty (voxels changed). Called from main thread when slots become dirty. */
    public void markSectorDirty(int absCX, int absCY, int absCZ) {
        long key = sectorKey(absCX, absCY, absCZ);
        ChunkMesh mesh = meshes.get(key);
        if (mesh != null) {
            // Rebuild immediately (simple but works)
            ChunkMeshBuilder.MeshResult result = meshBuilder.build(absCX, absCY, absCZ);
            mesh.upload(result.vertices, result.indices);
        }
    }

    /** Remove meshes for an unloaded chunk column. */
    public void removeChunkColumn(int absCX, int absCZ) {
        for (int cy = 0; cy < 16; cy++) {
            long key = sectorKey(absCX, cy, absCZ);
            ChunkMesh mesh = meshes.remove(key);
            if (mesh != null) mesh.delete();
        }
    }

    private void uploadAtmosphere(float worldTime, DimensionType dim) {
        float t = worldTime;
        float cycle = (t / 1440.0f) % 1.0f;
        float angle = cycle * 2.0f * 3.14159265359f - (3.14159265359f * 0.5f);

        if (dim == DimensionType.NETHER) {
            glUniform3f(locLightSunDir, 0f, -0.5f, 0f);
            glUniform3f(locLightSunColor, 0.6f, 0.2f, 0.05f);
            glUniform3f(locLightMoonDir, 0f, 0.5f, 0f);
            glUniform3f(locLightMoonColor, 0f, 0f, 0f);
            glUniform3f(locLightSkyZenith, 0.3f, 0.05f, 0.02f);
            glUniform3f(locLightSkyHorizon, 0.5f, 0.15f, 0.05f);
            glUniform3f(locLightAmbient, 0.08f, 0.03f, 0.01f);
            glUniform1i(locLightDimensionID, dim.id);
        } else {
            float sinA = (float) Math.sin(angle);
            float cosA = (float) Math.cos(angle);
            float len = (float) Math.sqrt(cosA * cosA + sinA * sinA + 0.3f * 0.3f);
            glUniform3f(locLightSunDir, cosA / len, sinA / len, 0.3f / len);
            glUniform3f(locLightMoonDir, -cosA / len, -sinA / len, -0.3f / len);

            float h = sinA / len;
            float tSky = Math.max(0, Math.min(1, (h + 0.15f) / 0.3f));
            float sSky = tSky * tSky * (3 - 2 * tSky);
            glUniform3f(locLightSkyZenith, 0.01f + 0.09f * sSky, 0.02f + 0.33f * sSky, 0.05f + 0.7f * sSky);
            glUniform3f(locLightSkyHorizon, 0.02f + 0.88f * sSky, 0.03f + 0.37f * sSky, 0.08f + 0.82f * sSky);

            float tSun = Math.max(0, Math.min(1, (h + 0.1f) / 0.2f));
            float sSun = tSun * tSun * (3 - 2 * tSun);
            glUniform3f(locLightSunColor, sSun, 0.5f + 0.48f * sSun, 0.2f + 0.7f * sSun);

            tSun = Math.max(0, Math.min(1, (-h + 0.1f) / 0.2f));
            sSun = tSun * tSun * (3 - 2 * tSun);
            glUniform3f(locLightMoonColor, 0.2f * sSun, 0.25f * sSun, 0.4f * sSun);

            float tAmb = Math.max(0, Math.min(1, (h + 0.2f) / 0.4f));
            float sAmb = tAmb * tAmb * (3 - 2 * tAmb);
            glUniform3f(locLightAmbient, 0.02f + 0.02f * sAmb, 0.03f + 0.11f * sAmb, 0.07f + 0.23f * sAmb);

            glUniform1i(locLightDimensionID, dim.id);
        }
    }

    public void delete() {
        for (ChunkMesh mesh : meshes.values()) mesh.delete();
        meshes.clear();
        meshBuilder.delete();
        if (gbufferFBO != 0) glDeleteFramebuffers(gbufferFBO);
        if (gbufferPosition != 0) glDeleteTextures(gbufferPosition);
        if (gbufferNormal != 0) glDeleteTextures(gbufferNormal);
        if (gbufferAlbedo != 0) glDeleteTextures(gbufferAlbedo);
        if (gbufferMaterial != 0) glDeleteTextures(gbufferMaterial);
        if (gbufferDepth != 0) glDeleteTextures(gbufferDepth);
        if (lightingProgram != 0) glDeleteProgram(lightingProgram);
        if (gbufferProgram != 0) glDeleteProgram(gbufferProgram);
        if (visBuffer != null) MemoryUtil.memFree(visBuffer);
    }

    /** Pack absolute chunk sector coords into a long key. Bits: 0-19=cx, 20-27=cy, 28-47=cz */
    private static long sectorKey(int absCX, int absCY, int absCZ) {
        return ((long)(absCX & 0xFFFFF)) | ((long)(absCY & 0xFF) << 20) | ((long)(absCZ & 0xFFFFF) << 28);
    }
}
