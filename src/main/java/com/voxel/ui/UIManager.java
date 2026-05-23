package com.voxel.ui;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector4f;
import java.util.HashMap;
import java.util.Map;
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
    private int uUVOffsetLoc, uUVScaleLoc, uTextureTypeLoc, uLayerLoc;
    private static final Map<Integer, Vector2i> textureSizes = new HashMap<>();
    
    public UIManager(int width, int height) {
        this.width = width;
        this.height = height;
        this.projection = new Matrix4f().ortho(0, width, height, 0, -1, 1);
        
        program = ShaderUtil.createProgram(
            ShaderUtil.compileShader("src/main/resources/shaders/ui.vert", GL_VERTEX_SHADER),
            ShaderUtil.compileShader("src/main/resources/shaders/ui.frag", GL_FRAGMENT_SHADER)
        );
        
        uUVOffsetLoc = glGetUniformLocation(program, "uUVOffset");
        uUVScaleLoc = glGetUniformLocation(program, "uUVScale");
        uTextureTypeLoc = glGetUniformLocation(program, "uTextureType");
        uLayerLoc = glGetUniformLocation(program, "uLayer");
        
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
        glTextureParameteri(uiTexture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(uiTexture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        
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
        drawQuad(pos, size, rotation, color, textureId, new Vector2f(0, 0), new Vector2f(1, 1));
    }
    
    public void drawQuad(Vector2f pos, Vector2f size, float rotation, Vector4f color, int textureId, Vector2f uvOffset, Vector2f uvScale) {
        drawQuad(pos, size, rotation, color, textureId, uvOffset, uvScale, 0, 0);
    }

    public void drawQuad(Vector2f pos, Vector2f size, float rotation, Vector4f color, int textureId, Vector2f uvOffset, Vector2f uvScale, int textureType, int layer) {
        glUniform2f(1, pos.x, pos.y);
        glUniform2f(2, size.x, size.y);
        glUniform1f(3, (float)Math.toRadians(rotation));
        glUniform1f(4, 0.0f);
        glUniform4f(10, color.x, color.y, color.z, color.w);
        glUniform2f(uUVOffsetLoc, uvOffset.x, uvOffset.y);
        glUniform2f(uUVScaleLoc, uvScale.x, uvScale.y);
        glUniform1i(uTextureTypeLoc, textureType);
        glUniform1i(uLayerLoc, layer);
        
        if (textureId != 0) {
            glUniform1f(11, 1.0f);
            if (textureType == 2) {
                glBindTextureUnit(1, textureId);
            } else {
                glBindTextureUnit(0, textureId);
            }
        } else {
            glUniform1f(11, 0.0f);
        }
        
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    public void drawString(String text, float x, float y, float scale, Vector4f color, int fontTextureId, int charLineLimit, int lineOffset) {
        if (fontTextureId == 0 || text == null || text.isEmpty()) return;
        
        // Pre-process: wrap at charLineLimit, then slice by lineOffset
        java.util.List<String> lines = wrapText(text, charLineLimit);
        
        float charSize = 8 * scale;
        float uvStep = 1.0f / 16.0f;
        float startX = x;
        
        for (int li = lineOffset; li < lines.size(); li++) {
            String line = lines.get(li);
            x = startX;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ' ') {
                    x += charSize;
                    continue;
                }
                int charCode = (int) c;
                float u = (charCode % 16) * uvStep;
                float v = (charCode / 16) * uvStep;
                drawQuad(new Vector2f(x, y), new Vector2f(charSize, charSize), 0, color, fontTextureId, new Vector2f(u, v), new Vector2f(uvStep, uvStep));
                x += charSize;
            }
            y += charSize;
        }
    }
    
    private java.util.List<String> wrapText(String text, int charLineLimit) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        // Split on existing newlines, then char-wrap each segment
        String[] segments = text.split("\n", -1);
        for (String segment : segments) {
            if (charLineLimit <= 0) {
                lines.add(segment);
            } else {
                int start = 0;
                while (start < segment.length()) {
                    int end = Math.min(start + charLineLimit, segment.length());
                    lines.add(segment.substring(start, end));
                    start = end;
                }
            }
        }
        return lines;
    }
    
    public void end() {
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    
    public int getUITexture() {
        return uiTexture;
    }
    
    public static Vector2i getTextureSize(int textureId) {
        return textureSizes.getOrDefault(textureId, new Vector2i(1, 1));
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
            
            textureSizes.put(textureId, new Vector2i(w.get(0), h.get(0)));
            STBImage.stbi_image_free(data);
        }
        glTextureParameteri(textureId, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(textureId, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(textureId, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(textureId, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return textureId;
    }
}
