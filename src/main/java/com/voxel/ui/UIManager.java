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
        
        program = ShaderUtil.createGraphicsProgramCached("ui",
            "src/main/resources/shaders/ui.vert",
            "src/main/resources/shaders/ui.frag");
        
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

    /**
     * Draws a quad whose texture tiles across it instead of stretching once.
     * The texture must have been loaded with {@link #loadTexture(String, boolean)}
     * and {@code repeat=true} so GL_REPEAT is set; one draw call covers the whole
     * surface, which keeps MC's 16x16 dirt panels at native pixel density.
     */
    public void drawQuadTiled(Vector2f pos, Vector2f size, Vector4f color, int textureId,
                              float tileW, float tileH) {
        if (textureId == 0 || tileW <= 0f || tileH <= 0f) return;
        glUniform2f(1, pos.x, pos.y);
        glUniform2f(2, size.x, size.y);
        glUniform1f(3, 0.0f);
        glUniform1f(4, 0.0f);
        glUniform4f(10, color.x, color.y, color.z, color.w);
        glUniform2f(uUVOffsetLoc, 0f, 0f);
        glUniform2f(uUVScaleLoc, size.x / tileW, size.y / tileH);
        glUniform1i(uTextureTypeLoc, 0);
        glUniform1i(uLayerLoc, 0);
        glUniform1f(11, 1.0f);
        glBindTextureUnit(0, textureId);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    /**
     * Minecraft-style drop shadow: one hard-edged stamp one pixel down-right,
     * then the fill pass. Used by menu buttons, title cards and captions.
     */
    public void drawStringShadow(String text, float x, float y, float scale, Vector4f color,
                                 int fontTextureId, int charLineLimit, int lineOffset) {
        if (fontTextureId == 0 || text == null || text.isEmpty()) return;
        float off = Math.max(1f, scale);
        Vector4f shadow = UiPalette.ink(color.w * 0.85f);
        drawString(text, x + off, y + off, scale, shadow, fontTextureId, charLineLimit, lineOffset);
        drawString(text, x, y, scale, color, fontTextureId, charLineLimit, lineOffset);
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

    /**
     * Draws text with a flat colour outline: the string is stamped four
     * times (left/right/up/down by {@code outlineWidth}) in {@code outlineColor}
     * beneath the fill pass. Outline alpha is scaled by the fill alpha.
     */
    public void drawStringOutlined(String text, float x, float y, float scale, Vector4f color,
                                   int fontTextureId, int charLineLimit, int lineOffset,
                                   Vector4f outlineColor, float outlineWidth) {
        if (fontTextureId == 0 || text == null || text.isEmpty()) return;
        Vector4f oc = new Vector4f(outlineColor.x, outlineColor.y, outlineColor.z,
            outlineColor.w * color.w);
        float w = outlineWidth;
        drawString(text, x - w, y, scale, oc, fontTextureId, charLineLimit, lineOffset);
        drawString(text, x + w, y, scale, oc, fontTextureId, charLineLimit, lineOffset);
        drawString(text, x, y - w, scale, oc, fontTextureId, charLineLimit, lineOffset);
        drawString(text, x, y + w, scale, oc, fontTextureId, charLineLimit, lineOffset);
        drawString(text, x, y, scale, color, fontTextureId, charLineLimit, lineOffset);
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
        return loadTexture(path, false);
    }

    /**
     * Loads a PNG as a 2D texture. {@code repeat} selects GL_REPEAT wrapping so
     * the caller can tile it with {@link #drawQuadTiled}. Nearest filtering is
     * deliberate: MC GUI art is pixel art and must stay crisp.
     */
    public static int loadTexture(String path, boolean repeat) {
        return loadTexture(path, repeat, null);
    }

    /**
     * Loads a PNG and reports the bounds of its opaque content into
     * {@code boundsOut} as {x, y, w, h} in texture pixels (or {0,0,w,h} when
     * the image is fully opaque). Callers use it to derive exact sprite UVs
     * from the image itself instead of hand-maintained rectangles — padding or
     * an off-by-a-few-pixels crop then cannot shift the art.
     */
    public static int loadTextureWithBounds(String path, boolean repeat, int[] boundsOut) {
        return loadTexture(path, repeat, boundsOut);
    }

    private static int loadTexture(String path, boolean repeat, int[] boundsOut) {
        int textureId = glCreateTextures(GL_TEXTURE_2D);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);
            ByteBuffer data = STBImage.stbi_load(path, w, h, comp, 4);
            if (data == null) throw new RuntimeException("Failed to load UI texture: " + path);
            int tw = w.get(0), th = h.get(0);

            glTextureStorage2D(textureId, 1, GL_RGBA8, tw, th);
            glTextureSubImage2D(textureId, 0, 0, 0, tw, th, GL_RGBA, GL_UNSIGNED_BYTE, data);

            if (boundsOut != null) opaqueBounds(data, tw, th, boundsOut);
            textureSizes.put(textureId, new Vector2i(tw, th));
            STBImage.stbi_image_free(data);
        }
        glTextureParameteri(textureId, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(textureId, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(textureId, GL_TEXTURE_WRAP_S, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
        glTextureParameteri(textureId, GL_TEXTURE_WRAP_T, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
        return textureId;
    }

    /** Alpha-aware content box of a straight RGBA buffer, written as {x,y,w,h}. */
    private static void opaqueBounds(ByteBuffer rgba, int w, int h, int[] out) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = rgba.get((y * w + x) * 4 + 3) & 0xFF;
                if (a > 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            out[0] = 0; out[1] = 0; out[2] = w; out[3] = h;
            return;
        }
        out[0] = minX;
        out[1] = minY;
        out[2] = maxX - minX + 1;
        out[3] = maxY - minY + 1;
    }
}
