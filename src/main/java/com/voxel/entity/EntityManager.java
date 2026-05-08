package com.voxel.entity;

import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;

public class EntityManager {
    private List<Entity> entities;
    private int entitySSBO;
    private int partSSBO;
    
    private static final int MAX_ENTITIES = 1024;
    private static final int MAX_PARTS = 8192;
    
    // Entity data size: position(3) + padding(1) + rotation(3) + padding(1) + partCount(1) + partOffset(1) + padding(2) = 12 floats (48 bytes)
    private static final int ENTITY_STRIDE = 12;
    // Part data size: offset(3) + padding(1) + absoluteOffset(3) + padding(1) + size(3) + textureIndex(1) + rotation(3) + padding(1) = 16 floats (64 bytes)
    private static final int PART_STRIDE = 16;

    public EntityManager() {
        this.entities = new ArrayList<>();
        setupBuffers();
    }

    private void setupBuffers() {
        entitySSBO = glCreateBuffers();
        glNamedBufferStorage(entitySSBO, (long) MAX_ENTITIES * ENTITY_STRIDE * 4, GL_DYNAMIC_STORAGE_BIT);
        
        partSSBO = glCreateBuffers();
        glNamedBufferStorage(partSSBO, (long) MAX_PARTS * PART_STRIDE * 4, GL_DYNAMIC_STORAGE_BIT);
    }

    public void addEntity(Entity entity) {
        entities.add(entity);
    }

    public void update(float dt) {
        for (Entity entity : entities) {
            entity.update(dt);
        }
    }

    public void uploadToGPU() {
        java.nio.ByteBuffer entityBuffer = MemoryUtil.memAlloc(entities.size() * ENTITY_STRIDE * 4);
        List<ModelPart> allParts = new ArrayList<>();
        
        for (Entity entity : entities) {
            int partOffset = allParts.size();
            int partCount = entity.parts.size();
            
            entityBuffer.putFloat(entity.position.x).putFloat(entity.position.y).putFloat(entity.position.z);
            entityBuffer.putFloat(0); // Padding
            
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.x));
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.y));
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.z));
            entityBuffer.putFloat(0); // Padding
            
            entityBuffer.putInt(partCount);
            entityBuffer.putInt(partOffset);
            entityBuffer.putFloat(0).putFloat(0); // Padding
            
            allParts.addAll(entity.parts);
        }
        entityBuffer.flip();
        glNamedBufferSubData(entitySSBO, 0, entityBuffer);
        MemoryUtil.memFree(entityBuffer);

        if (!allParts.isEmpty()) {
            java.nio.ByteBuffer partBuffer = MemoryUtil.memAlloc(allParts.size() * PART_STRIDE * 4);
            for (ModelPart part : allParts) {
                partBuffer.putFloat(part.offset.x).putFloat(part.offset.y).putFloat(part.offset.z);
                partBuffer.putFloat(0); // Padding
                
                partBuffer.putFloat(part.absoluteOffset.x).putFloat(part.absoluteOffset.y).putFloat(part.absoluteOffset.z);
                partBuffer.putFloat(0); // Padding
                
                partBuffer.putFloat(part.size.x).putFloat(part.size.y).putFloat(part.size.z);
                partBuffer.putFloat((float)part.textureIndex);
                
                partBuffer.putFloat((float)Math.toRadians(part.rotation.x));
                partBuffer.putFloat((float)Math.toRadians(part.rotation.y));
                partBuffer.putFloat((float)Math.toRadians(part.rotation.z));
                partBuffer.putFloat(0); // Padding
            }
            partBuffer.flip();
            glNamedBufferSubData(partSSBO, 0, partBuffer);
            MemoryUtil.memFree(partBuffer);
        }
    }

    public void bind(int entityBinding, int partBinding) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, entityBinding, entitySSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, partBinding, partSSBO);
    }
    
    public int getEntityCount() {
        return entities.size();
    }
}
