package com.voxel;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.memAlloc;

public class EntityManager {
    private static final int MAX_ENTITIES = 1024;
    private static final int ENTITY_SIZE = 64;

    private List<Entity> entities;
    private ByteBuffer entityRootBuffer;

    public EntityManager() {
        entities = new ArrayList<>();
        entityRootBuffer = memAlloc(MAX_ENTITIES * ENTITY_SIZE);
        // Initialize buffer to zeros
        for (int i = 0; i < MAX_ENTITIES * ENTITY_SIZE; i++) {
            entityRootBuffer.put(i, (byte) 0);
        }
    }

    public int add(Entity entity) {
        if (entities.size() >= MAX_ENTITIES) {
            throw new RuntimeException("Maximum entities reached");
        }
        entities.add(entity);
        updateBuffer(entities.size() - 1);
        return entities.size() - 1;
    }

    public void remove(int id) {
        if (id >= 0 && id < entities.size()) {
            entities.remove(id);
        }
    }

    public void update(int id, Entity updatedEntity) {
        if (id >= 0 && id < entities.size()) {
            entities.set(id, updatedEntity);
            updateBuffer(id);
        }
    }

    public Entity get(int id) {
        if (id >= 0 && id < entities.size()) {
            return entities.get(id);
        }
        return null;
    }

    public Iterator<Entity> iterator() {
        return entities.iterator();
    }

    public int size() {
        return entities.size();
    }

    private void updateBuffer(int idx) {
        Entity entity = entities.get(idx);
        int offset = idx * ENTITY_SIZE;
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

    public void updateAllBuffers() {
        for (int i = 0; i < entities.size(); i++) {
            updateBuffer(i);
        }
    }

    public ByteBuffer getBuffer() {
        return entityRootBuffer;
    }

    public void cleanup() {
        if (entityRootBuffer != null) {
            // Note: MemoryUtil.memFree(entityRootBuffer); but since it's managed here, perhaps caller frees
        }
    }
}