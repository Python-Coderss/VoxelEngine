package com.voxel.tools;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Standalone UV coordinate finder for ui.png (256×256 texture atlas).
 *
 * Usage: Run this class directly — it has its own main() method.
 *   - Opens ui.png from the project resources
 *   - Hover to see pixel coordinates
 *   - Click & drag to select a region
 *   - Displays (x, y, w, h) pixel coords + normalized UV coords
 *   - Scroll to zoom, right-click to clear selection
 *
 * The coordinates shown are ready to paste into UIConstants or /setuv.
 */
public class UVFinder extends JFrame {

    private static final int DEFAULT_SCALE = 4;  // 4x zoom = 1024x1024 window
    private static final int UI_SIZE = 256;

    private BufferedImage texture;
    private int scale = DEFAULT_SCALE;
    private Point hoverPixel = new Point(-1, -1);
    private Point dragStart = null;
    private Point dragEnd = null;
    private boolean showGrid = true;

    private final JLabel infoLabel;
    private final Canvas canvas;

    public UVFinder() {
        setTitle("UV Finder — ui.png");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Load the texture
        texture = loadTexture();
        if (texture == null) {
            JOptionPane.showMessageDialog(this,
                "Could not load src/main/resources/ui/ui.png\n" +
                "Make sure you're running from the project root.",
                "Texture not found", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Info bar at top
        infoLabel = new JLabel(" Hover over the texture to see coordinates. Click & drag to select.");
        infoLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));
        infoLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(infoLabel, BorderLayout.NORTH);

        // Main canvas
        canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(UI_SIZE * scale, UI_SIZE * scale));
        canvas.setMinimumSize(new Dimension(512, 512));
        add(new JScrollPane(canvas), BorderLayout.CENTER);

        // Bottom toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton resetZoom = new JButton("Reset Zoom");
        resetZoom.addActionListener(e -> { scale = DEFAULT_SCALE; refresh(); });
        toolbar.add(resetZoom);

        JCheckBox gridToggle = new JCheckBox("Grid", showGrid);
        gridToggle.addActionListener(e -> { showGrid = gridToggle.isSelected(); canvas.repaint(); });
        toolbar.add(gridToggle);

        JLabel help = new JLabel("  Scroll=Zoom  |  Drag=Select  |  Right-click=Clear");
        help.setFont(new Font("SansSerif", Font.PLAIN, 11));
        help.setForeground(Color.GRAY);
        toolbar.add(help);
        add(toolbar, BorderLayout.SOUTH);

        // Mouse listeners
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hoverPixel = screenToTex(e.getPoint());
                updateInfo();
                canvas.repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart != null) {
                    dragEnd = screenToTex(e.getPoint());
                    clampDrag();
                    updateInfo();
                    canvas.repaint();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    dragStart = null;
                    dragEnd = null;
                    updateInfo();
                    canvas.repaint();
                    return;
                }
                dragStart = screenToTex(e.getPoint());
                dragEnd = null;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragStart != null) {
                    dragEnd = screenToTex(e.getPoint());
                    clampDrag();
                    updateInfo();
                    canvas.repaint();
                }
            }
        };
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseListener(mouse);

        // Mouse wheel zoom
        canvas.addMouseWheelListener(e -> {
            int notches = e.getWheelRotation();
            if (notches < 0) scale = Math.min(16, scale + 1);
            else scale = Math.max(1, scale - 1);
            refresh();
        });

        pack();
        setLocationRelativeTo(null);
    }

    private BufferedImage loadTexture() {
        // Try multiple project-relative paths
        String[] paths = {
            "src/main/resources/ui/ui.png",
            "../ui/ui.png",
            "resources/ui/ui.png"
        };
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) {
                try {
                    return ImageIO.read(f);
                } catch (IOException e) {
                    System.err.println("Error reading " + p + ": " + e.getMessage());
                }
            }
        }
        return null;
    }

    private Point screenToTex(Point screen) {
        Rectangle view = canvas.getViewRect();
        int tx = (screen.x + view.x) / scale;
        int ty = (screen.y + view.y) / scale;
        return new Point(
            Math.max(0, Math.min(UI_SIZE - 1, tx)),
            Math.max(0, Math.min(UI_SIZE - 1, ty))
        );
    }

    private void clampDrag() {
        if (dragStart != null && dragEnd != null) {
            dragEnd.x = Math.max(0, Math.min(UI_SIZE - 1, dragEnd.x));
            dragEnd.y = Math.max(0, Math.min(UI_SIZE - 1, dragEnd.y));
        }
    }

    private Rectangle getSelection() {
        if (dragStart == null || dragEnd == null) return null;
        int x = Math.min(dragStart.x, dragEnd.x);
        int y = Math.min(dragStart.y, dragEnd.y);
        int w = Math.abs(dragEnd.x - dragStart.x) + 1;
        int h = Math.abs(dragEnd.y - dragStart.y) + 1;
        return new Rectangle(x, y, w, h);
    }

    private void updateInfo() {
        StringBuilder sb = new StringBuilder();

        // Hover pixel
        if (hoverPixel.x >= 0 && hoverPixel.y >= 0) {
            Color c = new Color(texture.getRGB(hoverPixel.x, hoverPixel.y));
            sb.append(String.format(" Pixel: (%3d, %3d)  RGBA: (%3d, %3d, %3d, %3d)  ",
                hoverPixel.x, hoverPixel.y, c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha()));
        }

        // Selection
        Rectangle sel = getSelection();
        if (sel != null) {
            sb.append(String.format("  Selection: (%3d, %3d, %3d, %3d)  UV: (%.4f, %.4f, %.4f, %.4f)",
                sel.x, sel.y, sel.width, sel.height,
                (float) sel.x / UI_SIZE, (float) sel.y / UI_SIZE,
                (float) sel.width / UI_SIZE, (float) sel.height / UI_SIZE));
        }

        infoLabel.setText(sb.toString());
    }

    private void refresh() {
        canvas.setPreferredSize(new Dimension(UI_SIZE * scale, UI_SIZE * scale));
        canvas.revalidate();
        canvas.repaint();
    }

    // ---- Canvas rendering ----

    private class Canvas extends JPanel {
        private Rectangle getViewRect() {
            Container p = getParent();
            if (p instanceof JViewport) return ((JViewport) p).getViewRect();
            return new Rectangle(0, 0, getWidth(), getHeight());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            // Draw the texture
            g2.drawImage(texture, 0, 0, UI_SIZE * scale, UI_SIZE * scale, null);

            // Pixel grid
            if (showGrid && scale >= 4) {
                g2.setColor(new Color(0, 0, 0, 30));
                for (int x = 0; x <= UI_SIZE; x++) {
                    int sx = x * scale;
                    g2.drawLine(sx, 0, sx, UI_SIZE * scale);
                }
                for (int y = 0; y <= UI_SIZE; y++) {
                    int sy = y * scale;
                    g2.drawLine(0, sy, UI_SIZE * scale, sy);
                }
            }

            // Grid labels (every 16 pixels for major grid lines)
            if (scale >= 3) {
                g2.setFont(new Font("Monospaced", Font.PLAIN, Math.min(scale * 2, 11)));
                g2.setColor(new Color(255, 200, 0, 180));
                for (int x = 0; x < UI_SIZE; x += 16) {
                    g2.drawString(String.valueOf(x), x * scale + 2, (UI_SIZE * scale) - 4);
                }
                for (int y = 0; y < UI_SIZE; y += 16) {
                    g2.drawString(String.valueOf(y), 2, y * scale + scale - 2);
                }
            }

            // Selection highlight
            Rectangle sel = getSelection();
            if (sel != null) {
                int sx = sel.x * scale;
                int sy = sel.y * scale;
                int sw = sel.width * scale;
                int sh = sel.height * scale;

                // Semi-transparent blue fill
                g2.setColor(new Color(60, 120, 255, 60));
                g2.fillRect(sx, sy, sw, sh);

                // Selection border
                g2.setColor(new Color(60, 120, 255, 220));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRect(sx, sy, sw, sh);

                // Coordinate tags at corners
                g2.setFont(new Font("Monospaced", Font.BOLD, Math.min(scale * 2, 12)));
                g2.setColor(Color.WHITE);
                String topLeft = String.format("(%d,%d)", sel.x, sel.y);
                String botRight = String.format("(%d,%d)", sel.x + sel.width - 1, sel.y + sel.height - 1);
                g2.drawString(topLeft, sx + 3, sy + scale - 2);
                g2.drawString(botRight, sx + sw - g2.getFontMetrics().stringWidth(botRight) - 3, sy + sh - 3);
            }

            // Hover crosshair
            if (hoverPixel.x >= 0 && hoverPixel.y >= 0 && sel == null) {
                int hx = hoverPixel.x * scale;
                int hy = hoverPixel.y * scale;
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(hx - 8, hy, hx + 8, hy);
                g2.drawLine(hx, hy - 8, hx, hy + 8);
            }
        }
    }

    // ---- Launch ----

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new UVFinder().setVisible(true);
        });
    }
}
