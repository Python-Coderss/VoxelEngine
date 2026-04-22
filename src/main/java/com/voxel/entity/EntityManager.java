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
    private int voxelPoolSSBO;
    
    private static final int MAX_PARTS = 1024;
    private static final int VOXELS_PER_PART = 16 * 16 * 16;
    private static final int MAX_VOXELS = MAX_PARTS * VOXELS_PER_PART;

    public EntityManager() {
        partsSSBO = glCreateBuffers();
        // Each part is: mat4 (64 bytes) + int (4 bytes) + 3 pads (12 bytes) = 80 bytes
        glNamedBufferStorage(partsSSBO, (long) MAX_PARTS * 80, GL_DYNAMIC_STORAGE_BIT);
        
        voxelPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(voxelPoolSSBO, (long) MAX_VOXELS * 4, GL_DYNAMIC_STORAGE_BIT);
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

        ByteBuffer partBuffer = BufferUtils.createByteBuffer(allParts.size() * 80);
        IntBuffer voxelBuffer = BufferUtils.createIntBuffer(allParts.size() * VOXELS_PER_PART);

        int currentVoxelOffset = 0;
        for (EntityPart part : allParts) {
            // Write Matrix4f (16 floats)
            float[] mat = new float[16];
            part.worldToLocal.get(mat);
            for (float f : mat) partBuffer.putFloat(f);
            
            // Write Voxel Offset and Padding
            partBuffer.putInt(currentVoxelOffset);
            partBuffer.putInt(0); // pad1
            partBuffer.putInt(0); // pad2
            partBuffer.putInt(0); // pad3

            // Write Voxel Data
            if (part.voxelData != null) {
                for (short v : part.voxelData) {
                    voxelBuffer.put(v & 0xFFFF);
                }
            } else {
                for (int i = 0; i < VOXELS_PER_PART; i++) voxelBuffer.put(0);
            }
            
            currentVoxelOffset += VOXELS_PER_PART;
        }
        partBuffer.flip();
        voxelBuffer.flip();

        glNamedBufferSubData(partsSSBO, 0, partBuffer);
        glNamedBufferSubData(voxelPoolSSBO, 0, voxelBuffer);
    }

    public void bind(int partsBinding, int voxelBinding) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, partsBinding, partsSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, voxelBinding, voxelPoolSSBO);
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
