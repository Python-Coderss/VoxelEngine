package com.voxel.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.*;

/**
 * GPU vertex data for one 16³ chunk section.
 * Stores:
 *  - positions (3 floats per vertex, 0..16 range)
 *  - normals (3 floats per vertex, packed as float)
 *  - texcoords (2 floats per vertex)
 *  - blockId (1 uint per vertex)
 *  - lightRGB (1 uint per vertex, packed 10-bit RGB)
 * 
 * Uses interleaved vertex format: pos(3f) + normal(3f) + uv(2f) + blockId(1ui) + light(1ui)
 * = 10 attributes per vertex, 32 bytes per vertex (8 floats + 2 uints = 40 bytes, padded)
 */
public class ChunkMesh {
    private int vao, vbo, ebo;
    private int vertexCount, indexCount;
    private boolean uploaded;
    private boolean dirty;

    public ChunkMesh() {
        vao = glCreateVertexArrays();
        vbo = glCreateBuffers();
        ebo = glCreateBuffers();
        uploaded = false;
        dirty = false;
    }

    /** Upload vertex + index data from the mesh builder output. */
    public void upload(float[] vertices, int[] indices) {
        this.vertexCount = vertices.length / 10; // 10 floats per vertex (pos3+norm3+uv2+blockId+light)
        this.indexCount = indices.length;

        if (vertexCount == 0) {
            uploaded = true;
            dirty = false;
            return;
        }

        // Vertex buffer
        FloatBuffer vb = org.lwjgl.system.MemoryUtil.memAllocFloat(vertices.length);
        vb.put(vertices).flip();
        glNamedBufferData(vbo, vb, GL_STATIC_DRAW);
        org.lwjgl.system.MemoryUtil.memFree(vb);

        // Index buffer
        IntBuffer ib = org.lwjgl.system.MemoryUtil.memAllocInt(indices.length);
        ib.put(indices).flip();
        glNamedBufferData(ebo, ib, GL_STATIC_DRAW);
        org.lwjgl.system.MemoryUtil.memFree(ib);

        // VAO setup: interleaved vertex attributes
        // Layout: pos(3f), normal(3f), uv(2f), blockId(1ui), lightRGB(1ui)
        // Total stride = 3*4 + 3*4 + 2*4 + 4 + 4 = 40 bytes
        int stride = 40;
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, stride);

        // aPos: 3 floats at offset 0
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, 0);

        // aNormal: 3 floats at offset 12
        glEnableVertexArrayAttrib(vao, 1);
        glVertexArrayAttribFormat(vao, 1, 3, GL_FLOAT, false, 12);
        glVertexArrayAttribBinding(vao, 1, 0);

        // aTexCoord: 2 floats at offset 24
        glEnableVertexArrayAttrib(vao, 2);
        glVertexArrayAttribFormat(vao, 2, 2, GL_FLOAT, false, 24);
        glVertexArrayAttribBinding(vao, 2, 0);

        // aBlockId: 1 uint at offset 32
        glEnableVertexArrayAttrib(vao, 3);
        glVertexArrayAttribIFormat(vao, 3, 1, GL_UNSIGNED_INT, 32);
        glVertexArrayAttribBinding(vao, 3, 0);

        // aLightRGB: 1 uint at offset 36
        glEnableVertexArrayAttrib(vao, 4);
        glVertexArrayAttribIFormat(vao, 4, 1, GL_UNSIGNED_INT, 36);
        glVertexArrayAttribBinding(vao, 4, 0);

        glVertexArrayElementBuffer(vao, ebo);
        uploaded = true;
        dirty = false;
    }

    public void markDirty() { dirty = true; }

    public boolean isDirty() { return dirty; }

    public boolean isEmpty() { return !uploaded || vertexCount == 0; }

    public int getVertexCount() { return vertexCount; }

    public int getIndexCount() { return indexCount; }

    public void bind() { glBindVertexArray(vao); }

    public void draw() {
        if (isEmpty()) return;
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
    }

    public void delete() {
        if (vao != 0) { glDeleteVertexArrays(vao); vao = 0; }
        if (vbo != 0) { glDeleteBuffers(vbo); vbo = 0; }
        if (ebo != 0) { glDeleteBuffers(ebo); ebo = 0; }
    }
}
