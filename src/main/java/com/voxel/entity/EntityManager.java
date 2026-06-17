package com.voxel.entity;

import com.voxel.world.DimensionType;
import org.joml.Vector3f;
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
    
    // Entity data size: position(3) + health(1) + rotation(3) + maxHealth(1) + partCount(1) + partOffset(1) + hitFlash(1) + tintColorRGB(3) + tintAmount(1) = 16 floats (64 bytes)
    private static final int ENTITY_STRIDE = 16;
    // Part data size: offset(3) + uvU(1) + absOffset(3) + uvV(1) + size(3) + texIdx(1) + rotation(3) + mapping(1) = 16 floats (64 bytes)
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

    /**
     * Uploads all entities to GPU (legacy, for backward compatibility).
     */
    public void uploadToGPU() {
        uploadToGPU(null, null);
    }

    /**
     * Uploads entities to GPU, optionally filtering by dimension.
     * If activeDimension is null, all entities are uploaded.
     * If cameraPos is provided, only entities within 64 blocks are uploaded.
     */
    public void uploadToGPU(DimensionType activeDimension, Vector3f cameraPos) {
        float cullDistSq = cameraPos != null ? 64.0f * 64.0f : Float.MAX_VALUE; // 64-block radius

        // First pass: count visible entities
        int visibleCount = 0;
        for (Entity e : entities) {
            if (activeDimension != null && e.dimension != activeDimension) continue;
            if (cameraPos != null) {
                float dx = e.position.x - cameraPos.x;
                float dy = e.position.y - cameraPos.y;
                float dz = e.position.z - cameraPos.z;
                if (dx * dx + dy * dy + dz * dz > cullDistSq) continue;
            }
            visibleCount++;
        }

        java.nio.ByteBuffer entityBuffer = MemoryUtil.memAlloc(visibleCount * ENTITY_STRIDE * 4);
        List<ModelPart> allParts = new ArrayList<>();

        for (Entity entity : entities) {
            if (activeDimension != null && entity.dimension != activeDimension) continue;
            if (cameraPos != null) {
                float dx = entity.position.x - cameraPos.x;
                float dy = entity.position.y - cameraPos.y;
                float dz = entity.position.z - cameraPos.z;
                if (dx * dx + dy * dy + dz * dz > cullDistSq) continue;
            }
            int partOffset = allParts.size();
            int partCount = entity.parts.size();

            // position
            entityBuffer.putFloat(entity.position.x).putFloat(entity.position.y).putFloat(entity.position.z);

            float health = 1.0f;
            float maxHealth = 1.0f;
            if (entity instanceof EnemyEntity) {
                health = ((EnemyEntity) entity).getHealth();
                maxHealth = ((EnemyEntity) entity).getMaxHealth();
            }
            // health
            entityBuffer.putFloat(health);

            // rotation
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.x));
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.y));
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.z));
            
            // maxHealth
            entityBuffer.putFloat(maxHealth);

            // counts and offsets
            entityBuffer.putInt(partCount);
            entityBuffer.putInt(partOffset);
            // hitFlashTime for telegraphing (combat glow)
            float hitFlash = 0.0f;
            if (entity instanceof EnemyEntity) {
                hitFlash = ((EnemyEntity) entity).hitFlashTime;
            }
            entityBuffer.putFloat(hitFlash);
            // Tint color + amount
            entityBuffer.putFloat(entity.tintColor.x);
            entityBuffer.putFloat(entity.tintColor.y);
            entityBuffer.putFloat(entity.tintColor.z);
            entityBuffer.putFloat(entity.tintAmount);
            entityBuffer.putFloat(0.0f); // Padding for 64-byte alignment
            allParts.addAll(entity.parts);
        }
        entityBuffer.flip();
        glNamedBufferSubData(entitySSBO, 0, entityBuffer);
        MemoryUtil.memFree(entityBuffer);

        if (!allParts.isEmpty()) {
            java.nio.ByteBuffer partBuffer = MemoryUtil.memAlloc(allParts.size() * PART_STRIDE * 4);
            for (ModelPart part : allParts) {
                partBuffer.putFloat(part.offset.x).putFloat(part.offset.y).putFloat(part.offset.z);
                partBuffer.putFloat(part.uvOrigin.x); // UV Origin U
                
                partBuffer.putFloat(part.absoluteOffset.x).putFloat(part.absoluteOffset.y).putFloat(part.absoluteOffset.z);
                partBuffer.putFloat(part.uvOrigin.y); // UV Origin V
                
                partBuffer.putFloat(part.size.x).putFloat(part.size.y).putFloat(part.size.z);
                partBuffer.putFloat((float)part.textureIndex);
                
                partBuffer.putFloat((float)Math.toRadians(part.rotation.x));
                partBuffer.putFloat((float)Math.toRadians(part.rotation.y));
                partBuffer.putFloat((float)Math.toRadians(part.rotation.z));
                partBuffer.putFloat((float) part.textureMapping);
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

    /**
     * Returns the count of entities in the given dimension.
     * If dimension is null, returns the total count.
     */
    public int getEntityCount(DimensionType dimension) {
        if (dimension == null) return entities.size();
        int count = 0;
        for (Entity e : entities) {
            if (e.dimension == dimension) count++;
        }
        return count;
    }

    public Entity getEntity(int index) {
        if (index < 0 || index >= entities.size()) return null;
        return entities.get(index);
    }
}
