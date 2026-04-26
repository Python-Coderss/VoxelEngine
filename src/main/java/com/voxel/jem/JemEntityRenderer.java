package com.voxel.jem;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;

public final class JemEntityRenderer {
    private static final int MAX_PARTS = 1024;
    private static final int VOXELS_PER_PART = 16 * 16 * 16;
    private static final int MAX_VOXELS = MAX_PARTS * VOXELS_PER_PART;

    private final List<JemEntityInstance> entities = new ArrayList<>();

    private final int partsSSBO;
    private final int voxelPoolSSBO;

    public JemEntityRenderer() {
        partsSSBO = glCreateBuffers();
        glNamedBufferStorage(partsSSBO, (long) MAX_PARTS * 80L, GL_DYNAMIC_STORAGE_BIT);

        voxelPoolSSBO = glCreateBuffers();
        glNamedBufferStorage(voxelPoolSSBO, (long) MAX_VOXELS * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);
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

    public void updateGpuData() {
        List<JemPartInstance> allParts = new ArrayList<>();
        for (JemEntityInstance entity : entities) {
            entity.update();
            allParts.addAll(entity.getParts());
        }

        if (allParts.isEmpty()) {
            return;
        }

        ByteBuffer partBuffer = BufferUtils.createByteBuffer(allParts.size() * 80);
        IntBuffer voxelBuffer = BufferUtils.createIntBuffer(allParts.size() * VOXELS_PER_PART);

        int currentVoxelOffset = 0;
        float[] matrix = new float[16];
        for (JemPartInstance part : allParts) {
            part.worldToLocal.get(matrix);
            for (float value : matrix) {
                partBuffer.putFloat(value);
            }

            partBuffer.putInt(currentVoxelOffset);
            partBuffer.putInt(0);
            partBuffer.putInt(0);
            partBuffer.putInt(0);

            int[] voxelData = part.getVoxelData();
            if (voxelData != null) {
                for (int voxel : voxelData) {
                    voxelBuffer.put(voxel);
                }
            } else {
                for (int i = 0; i < VOXELS_PER_PART; i++) {
                    voxelBuffer.put(0);
                }
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

    public void destroy() {
        glDeleteBuffers(partsSSBO);
        glDeleteBuffers(voxelPoolSSBO);
    }
}
