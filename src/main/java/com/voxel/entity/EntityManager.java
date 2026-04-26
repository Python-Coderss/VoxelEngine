package com.voxel.entity;

import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.*;

public class EntityManager {
    private final List<Entity> entities = new ArrayList<>();
    private int partsSSBO;
    
    private static final int MAX_PARTS = 1024;

    public EntityManager() {
        partsSSBO = glCreateBuffers();
        // Each part is:
        // mat4 worldToLocal (64 bytes)
        // mat4 worldTransform (64 bytes)
        // vec3 min (12 bytes) + int blockId (4 bytes)
        // vec3 max (12 bytes) + int pad (4 bytes)
        // Total = 64 + 64 + 16 + 16 = 160 bytes
        glNamedBufferStorage(partsSSBO, (long) MAX_PARTS * 160, GL_DYNAMIC_STORAGE_BIT);
    }

    public void addEntity(Entity entity) {
        entities.add(entity);
    }

    public void updateGpuData() {
        List<EntityPart> allParts = new ArrayList<>();
        for (Entity entity : entities) {
            entity.update();
            entity.getAllParts(allParts);
        }

        if (allParts.isEmpty()) return;

        ByteBuffer partBuffer = BufferUtils.createByteBuffer(allParts.size() * 160);

        float[] mat = new float[16];
        for (EntityPart part : allParts) {
            // Write worldToLocal Matrix4f (64 bytes)
            part.worldToLocal.get(mat);
            for (float f : mat) partBuffer.putFloat(f);

            // Write worldTransform Matrix4f (64 bytes)
            part.worldTransform.get(mat);
            for (float f : mat) partBuffer.putFloat(f);
            
            // Write min (12 bytes) + blockId (4 bytes)
            partBuffer.putFloat(part.min.x);
            partBuffer.putFloat(part.min.y);
            partBuffer.putFloat(part.min.z);
            partBuffer.putInt(part.blockId);

            // Write max (12 bytes) + pad (4 bytes)
            partBuffer.putFloat(part.max.x);
            partBuffer.putFloat(part.max.y);
            partBuffer.putFloat(part.max.z);
            partBuffer.putInt(0);
        }
        partBuffer.flip();

        glNamedBufferSubData(partsSSBO, 0, partBuffer);
    }

    public void bind(int partsBinding) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, partsBinding, partsSSBO);
    }

    public int getNumParts() {
        int count = 0;
        for (Entity entity : entities) {
            List<EntityPart> parts = new ArrayList<>();
            entity.getAllParts(parts);
            count += parts.size();
        }
        return count;
    }
}
