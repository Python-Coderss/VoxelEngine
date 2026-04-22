package com.voxel;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.memAlloc;

public class ChildEntityManager {
    private static final int CHILD_SIZE = 64;

    private int maxChildren;
    private List<ChildEntity> children;
    private ByteBuffer childBuffer;

    public ChildEntityManager(int maxChildren) {
        this.maxChildren = maxChildren;
        children = new ArrayList<>();
        childBuffer = memAlloc(maxChildren * CHILD_SIZE);
        // Initialize buffer to zeros
        for (int i = 0; i < maxChildren * CHILD_SIZE; i++) {
            childBuffer.put(i, (byte) 0);
        }
    }

    public int add(ChildEntity child) {
        if (children.size() >= maxChildren) {
            throw new RuntimeException("Maximum children reached");
        }
        children.add(child);
        updateBuffer(children.size() - 1);
        return children.size() - 1;
    }

    public void remove(int id) {
        if (id >= 0 && id < children.size()) {
            children.remove(id);
        }
    }

    public void update(int id, ChildEntity updatedChild) {
        if (id >= 0 && id < children.size()) {
            children.set(id, updatedChild);
            updateBuffer(id);
        }
    }

    public ChildEntity get(int id) {
        if (id >= 0 && id < children.size()) {
            return children.get(id);
        }
        return null;
    }

    public Iterator<ChildEntity> iterator() {
        return children.iterator();
    }

    public int size() {
        return children.size();
    }

    private void updateBuffer(int idx) {
        ChildEntity child = children.get(idx);
        int offset = idx * CHILD_SIZE;
        childBuffer.putFloat(offset + 0, child.wx);
        childBuffer.putFloat(offset + 4, child.wy);
        childBuffer.putFloat(offset + 8, child.wz);
        childBuffer.putInt(offset + 12, child.axis);
        childBuffer.putFloat(offset + 16, child.cos);
        childBuffer.putFloat(offset + 20, child.sin);
        childBuffer.putInt(offset + 24, child.model);
    }

    public void updateAllBuffers() {
        for (int i = 0; i < children.size(); i++) {
            updateBuffer(i);
        }
    }

    public ByteBuffer getBuffer() {
        return childBuffer;
    }

    public void cleanup() {
        if (childBuffer != null) {
            // MemoryUtil.memFree(childBuffer);
        }
    }
}