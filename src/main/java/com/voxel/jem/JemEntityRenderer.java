package com.voxel.jem;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage3D;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.*;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;

public final class JemEntityRenderer {
    private static final int MAX_PARTS = 1024;
    private static final int PART_STRUCT_SIZE = 160;

    private final List<JemEntityInstance> entities = new ArrayList<>();

    private final int partsSSBO;
    private final int metadataSSBO;
    private int textureArrayId;

    public JemEntityRenderer() {
        partsSSBO = glCreateBuffers();
        glNamedBufferStorage(partsSSBO, (long) MAX_PARTS * PART_STRUCT_SIZE, GL_DYNAMIC_STORAGE_BIT);

        metadataSSBO = glCreateBuffers();
    }

    public void addEntity(JemEntityInstance entity) {
        entities.add(entity);
    }

    public int getNumParts() {
        int count = 0;
        for (JemEntityInstance entity : entities) {
            count += entity.getParts().size();
        }
        return count;
    }

    public void uploadMetadata() {
        List<EntityPartMetadata> metadata = JemModelLoader.getPartMetadata();
        // Each entry is 112 bytes: 96 for UVs, 4 for textureIdx, 12 for padding
        ByteBuffer buffer = BufferUtils.createByteBuffer(metadata.size() * 112);
        for (EntityPartMetadata meta : metadata) {
            for (int i = 0; i < 6; i++) {
                buffer.putFloat(meta.uvs[i].x);
                buffer.putFloat(meta.uvs[i].y);
                buffer.putFloat(meta.uvs[i].z);
                buffer.putFloat(meta.uvs[i].w);
            }
            buffer.putInt(meta.textureIdx);
            buffer.putInt(0); // padding
            buffer.putInt(0); // padding
            buffer.putInt(0); // padding
        }
        buffer.flip();
        glNamedBufferData(metadataSSBO, buffer, GL_STATIC_DRAW);

        List<JemModelLoader.TextureImage> textures = JemModelLoader.getUniqueTextures();
        if (!textures.isEmpty()) {
            if (textureArrayId != 0) {
                glDeleteTextures(textureArrayId);
            }

            int maxWidth = 0, maxHeight = 0;
            for (JemModelLoader.TextureImage tex : textures) {
                maxWidth = Math.max(maxWidth, tex.getWidth());
                maxHeight = Math.max(maxHeight, tex.getHeight());
            }

            textureArrayId = glCreateTextures(GL_TEXTURE_2D_ARRAY);
            glTextureStorage3D(textureArrayId, 1, GL_RGBA8, maxWidth, maxHeight, textures.size());
            for (int i = 0; i < textures.size(); i++) {
                JemModelLoader.TextureImage tex = textures.get(i);
                IntBuffer pixels = BufferUtils.createIntBuffer(tex.getArgb().length);
                pixels.put(tex.getArgb()).flip();
                glTextureSubImage3D(textureArrayId, 0, 0, 0, i, tex.getWidth(), tex.getHeight(), 1, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
            }
            glTextureParameteri(textureArrayId, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameteri(textureArrayId, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameteri(textureArrayId, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(textureArrayId, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
    }

    public void updateGpuData() {
        List<JemPartInstance> allParts = new ArrayList<>();
        for (JemEntityInstance entity : entities) {
            entity.update();
            allParts.addAll(entity.getParts());
        }

        if (allParts.isEmpty()) {
            return;
        }

        ByteBuffer partBuffer = BufferUtils.createByteBuffer(allParts.size() * PART_STRUCT_SIZE);
        float[] matrix = new float[16];
        for (JemPartInstance part : allParts) {
            part.worldToLocal.get(matrix);
            for (float value : matrix) partBuffer.putFloat(value);
            part.worldTransform.get(matrix);
            for (float value : matrix) partBuffer.putFloat(value);

            partBuffer.putFloat(part.definition.min.x);
            partBuffer.putFloat(part.definition.min.y);
            partBuffer.putFloat(part.definition.min.z);
            partBuffer.putInt(part.getBlockId());

            partBuffer.putFloat(part.definition.max.x);
            partBuffer.putFloat(part.definition.max.y);
            partBuffer.putFloat(part.definition.max.z);
            partBuffer.putInt(0); // padding
        }
        partBuffer.flip();
        glNamedBufferSubData(partsSSBO, 0, partBuffer);
    }

    public void bind(int partsBinding, int metadataBinding, int textureBinding) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, partsBinding, partsSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, metadataBinding, metadataSSBO);
        if (textureArrayId != 0) {
            glBindTextureUnit(textureBinding, textureArrayId);
        }
    }

    public void destroy() {
        glDeleteBuffers(partsSSBO);
        glDeleteBuffers(metadataSSBO);
        if (textureArrayId != 0) glDeleteTextures(textureArrayId);
    }
}
