package com.voxel.ui;

import org.joml.Vector2f;
import org.joml.Vector4f;
import java.util.ArrayList;
import java.util.List;

public class UILayer {
    private final List<UIElement> elements = new ArrayList<>();
    private boolean visible = true;
    
    public void addElement(UIElement element) {
        elements.add(element);
    }
    
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
    
    public boolean isVisible() {
        return visible;
    }
    
    public void render(UIManager manager) {
        if (!visible) return;
        for (UIElement element : elements) {
            element.render(manager);
        }
    }
    
    public boolean handleMouseClick(float x, float y) {
        if (!visible) return false;
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (elements.get(i).isPointInside(x, y)) {
                if (elements.get(i).onClick != null) {
                    elements.get(i).onClick.run();
                    return true;
                }
            }
        }
        return false;
    }

    public static class UIElement {
        public Vector2f pos;
        public Vector2f size;
        public Vector4f color;
        public float rotation;
        public int textureId;
        public int textureType = 0; // 0: single, 1: font, 2: array
        public int layer = 0;
        public Vector2f uvOffset = new Vector2f(0, 0);
        public Vector2f uvScale = new Vector2f(1, 1);
        public boolean visible = true;
        public Runnable onClick;
        
        public UIElement(Vector2f pos, Vector2f size, Vector4f color) {
            this.pos = pos;
            this.size = size;
            this.color = color;
        }
        
        public void render(UIManager manager) {
            if (!visible) return;
            manager.drawQuad(pos, size, rotation, color, textureId, uvOffset, uvScale, textureType, layer);
        }
        
        public boolean isPointInside(float x, float y) {
            if (!visible) return false;
            return x >= pos.x && x <= pos.x + size.x && y >= pos.y && y <= pos.y + size.y;
        }
    }

    public static class UITextElement extends UIElement {
        public String text;
        public float scale;
        public int charLineLimit = 0;   // 0 = no wrapping
        public int lineOffset = 0;       // lines to skip from top (for scrolling)
        
        public UITextElement(Vector2f pos, String text, float scale, Vector4f color, int fontTextureId) {
            super(pos, new Vector2f(0, 0), color);
            this.text = text;
            this.scale = scale;
            this.textureId = fontTextureId;
        }
        
        @Override
        public void render(UIManager manager) {
            if (!visible || text == null || text.isEmpty()) return;
            manager.drawString(text, pos.x, pos.y, scale, color, textureId, charLineLimit, lineOffset);
        }
    }
}
