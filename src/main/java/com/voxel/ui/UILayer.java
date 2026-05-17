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
        public Runnable onClick;
        
        public UIElement(Vector2f pos, Vector2f size, Vector4f color) {
            this.pos = pos;
            this.size = size;
            this.color = color;
        }
        
        public void render(UIManager manager) {
            manager.drawQuad(pos, size, rotation, color, textureId);
        }
        
        public boolean isPointInside(float x, float y) {
            return x >= pos.x && x <= pos.x + size.x && y >= pos.y && y <= pos.y + size.y;
        }
    }
}
