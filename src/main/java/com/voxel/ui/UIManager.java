package com.voxel.ui;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.*;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import com.voxel.utils.ShaderUtil;

public class UIManager {
    private int program;
    private int vao, vbo;
    private int fbo, uiTexture;
    private int width, height;
    private Matrix4f projection;
    
    public UIManager(int width, int height) {
        this.width = width;
        this.height = height;
        this.projection = new Matrix4f().ortho(0, width, height, 0, -1, 1);
        
        program = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/ui.vert", GL_VERTEX_SHADER),
            ShaderUtil.compileShader("src/main/resources/shaders/ui.frag", GL_FRAGMENT_SHADER)
        );
        
        setupGeometry();
        setupFBO();
    }
    
    private void setupGeometry() {
        float[] vertices = {
            0,0, 0,0,
            1,0, 1,0,
            0,1, 0,1,
            1,0, 1,0,
            1,1, 1,1,
            0,1, 0,1
        };
        vao = glCreateVertexArrays();
        vbo = glCreateBuffers();
        FloatBuffer buffer = (FloatBuffer) BufferUtils.createFloatBuffer(vertices.length).put(vertices).flip();
        glNamedBufferStorage(vbo, buffer, 0);
        
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 2, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, 0);
        
        glEnableVertexArrayAttrib(vao, 1);
        glVertexArrayAttribFormat(vao, 1, 2, GL_FLOAT, false, 2 * Float.BYTES);
        glVertexArrayAttribBinding(vao, 1, 0);
        
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, 4 * Float.BYTES);
    }
    
    private void setupFBO() {
        fbo = glCreateFramebuffers();
        uiTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(uiTexture, 1, GL_RGBA8, width, height);
        glTextureParameteri(uiTexture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(uiTexture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        
        glNamedFramebufferTexture(fbo, GL_COLOR_ATTACHMENT0, uiTexture, 0);
        if (glCheckNamedFramebufferStatus(fbo, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("UI FBO Incomplete");
        }
    }
    
    public void begin() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(program);
        
        FloatBuffer fb = BufferUtils.createFloatBuffer(16);
        projection.get(fb);
        glUniformMatrix4fv(0, false, fb);
    }
    
    public void drawQuad(Vector2f pos, Vector2f size, float rotation, Vector4f color, int textureId) {
        glUniform2f(1, pos.x, pos.y);
        glUniform2f(2, size.x, size.y);
        glUniform1f(3, (float)Math.toRadians(rotation));
        glUniform1f(4, 0.0f);
        glUniform4f(10, color.x, color.y, color.z, color.w);
        
        if (textureId != 0) {
            glUniform1f(11, 1.0f);
            glBindTextureUnit(0, textureId);
        } else {
            glUniform1f(11, 0.0f);
        }
        
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }
    
    public void end() {
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    
    public int getUITexture() {
        return uiTexture;
    }

    public static int loadTexture(String path) {
        int textureId = glCreateTextures(GL_TEXTURE_2D);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);
            ByteBuffer data = STBImage.stbi_load(path, w, h, comp, 4);
            if (data == null) throw new RuntimeException("Failed to load UI texture: " + path);
            
            glTextureStorage2D(textureId, 1, GL_RGBA8, w.get(0), h.get(0));
            glTextureSubImage2D(textureId, 0, 0, 0, w.get(0), h.get(0), GL_RGBA, GL_UNSIGNED_BYTE, data);
            
            STBImage.stbi_image_free(data);
        }
        glTextureParameteri(textureId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(textureId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        return textureId;
    }
}
