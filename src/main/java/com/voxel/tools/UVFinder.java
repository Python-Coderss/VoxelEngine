package com.voxel.tools;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Enhanced UV coordinate finder for Minecraft texture atlases.
 *
 * Features:
 *   - Load any PNG texture (default: ui.png)
 *   - Hover to see pixel coordinates + RGBA values
 *   - Click twice OR drag to select a region
 *   - Snaps to integer pixel coordinates
 *   - Displays pixel coords (x,y,w,h) + normalized UVs (u,v,u+w,v+h)
 *   - Copies JSON UV string to clipboard
 *   - Scroll to zoom (1x–16x), right-click to clear
 *   - Pixel grid at high zoom levels, major grid labels every 16px
 *   - Selection overlay with corner coordinate tags
 */
public class UVFinder extends JFrame {

    private static final int DEFAULT_SCALE = 4;

    private BufferedImage texture;
    private int texWidth = 256, texHeight = 256;
    private int scale = DEFAULT_SCALE;
    private Point hoverPixel = new Point(-1, -1);

    // Selection: supports both drag and click-twice modes
    private Point selStart = null;   // first click / drag start
    private Point selEnd = null;     // second click / drag end
    private boolean showGrid = true;
    private boolean snapToGrid = true;

    private final JLabel infoLabel;
    private final Canvas canvas;
    private File currentFile = null;

    public UVFinder() {
        this(true);
    }

    public UVFinder(boolean loadDefault) {
        setTitle("UV Finder");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        if (loadDefault) {
            texture = loadDefaultTexture();
        }

        if (texture != null) {
            texWidth = texture.getWidth();
            texHeight = texture.getHeight();
            setTitle("UV Finder — " + (currentFile != null ? currentFile.getName() : texWidth + "x" + texHeight));
        }

        // Info bar at top
        infoLabel = new JLabel(" Hover to see coordinates. Click twice or drag to select a region.");
        infoLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));
        infoLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(infoLabel, BorderLayout.NORTH);

        // Main canvas
        canvas = new Canvas();
        updateCanvasSize();
        canvas.setMinimumSize(new Dimension(256, 256));
        add(new JScrollPane(canvas), BorderLayout.CENTER);

        // Bottom toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));

        JButton loadBtn = new JButton("Load Texture");
        loadBtn.addActionListener(e -> loadTextureDialog());
        toolbar.add(loadBtn);
        toolbar.add(Box.createHorizontalStrut(8));

        JButton resetZoom = new JButton("Reset Zoom");
        resetZoom.addActionListener(e -> { scale = DEFAULT_SCALE; updateCanvasSize(); });
        toolbar.add(resetZoom);
        toolbar.add(Box.createHorizontalStrut(8));

        JCheckBox gridToggle = new JCheckBox("Grid", showGrid);
        gridToggle.addActionListener(e -> { showGrid = gridToggle.isSelected(); canvas.repaint(); });
        toolbar.add(gridToggle);
        toolbar.add(Box.createHorizontalStrut(8));

        JCheckBox snapToggle = new JCheckBox("Snap 16", snapToGrid);
        snapToggle.addActionListener(e -> { snapToGrid = snapToggle.isSelected(); canvas.repaint(); });
        toolbar.add(snapToggle);
        toolbar.add(Box.createHorizontalStrut(8));

        JButton copyBtn = new JButton("Copy UV");
        copyBtn.addActionListener(e -> copyUVToClipboard());
        toolbar.add(copyBtn);
        toolbar.add(Box.createHorizontalStrut(8));

        toolbar.add(Box.createHorizontalGlue());
        JLabel help = new JLabel("  Scroll=Zoom  |  Drag or Click×2=Select  |  Right-click=Clear");
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
                if (selStart != null) {
                    selEnd = screenToTex(e.getPoint());
                    clampSelEnd();
                    updateInfo();
                    canvas.repaint();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    clearSelection();
                    return;
                }
                Point texP = screenToTex(e.getPoint());

                // Click-twice mode: if we already have a start, this is the second click
                if (selStart != null && selEnd == null) {
                    selEnd = texP;
                    clampSelEnd();
                    // Finish selection
                    updateInfo();
                    canvas.repaint();
                    return;
                }

                // First click: start a new selection
                selStart = texP;
                selEnd = null;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (selStart != null && selEnd != null) {
                    // Drag finished — keep the selection
                    clampSelEnd();
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
            updateCanvasSize();
        });

        pack();
        setSize(900, 800);
        setLocationRelativeTo(null);
    }

    // ========================================================================
    // Texture loading
    // ========================================================================

    private BufferedImage loadDefaultTexture() {
        String[] paths = {
            "./src/main/resources/ui/ui.png",
            "src/main/resources/ui/ui.png",
            "../ui/ui.png",
            "resources/ui/ui.png",
            "./target/classes/ui/ui.png",
            "target/classes/ui/ui.png"
        };
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) {
                try {
                    currentFile = f;
                    return ImageIO.read(f);
                } catch (IOException e) {
                    System.err.println("Error reading " + p + ": " + e.getMessage());
                }
            }
        }
        return null;
    }

    private void loadTextureDialog() {
        String defaultDir = "src/main/resources/ui";
        File dir = new File(defaultDir);
        if (!dir.exists()) dir = new File("src/main/resources");
        JFileChooser fc = new JFileChooser(dir);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            try {
                BufferedImage img = ImageIO.read(f);
                if (img == null) {
                    JOptionPane.showMessageDialog(this, "Could not load image (unsupported format).");
                    return;
                }
                texture = img;
                texWidth = img.getWidth();
                texHeight = img.getHeight();
                currentFile = f;
                clearSelection();
                scale = DEFAULT_SCALE;
                setTitle("UV Finder — " + f.getName() + " (" + texWidth + "x" + texHeight + ")");
                updateCanvasSize();
                infoLabel.setText(" Loaded: " + f.getName() + " (" + texWidth + "x" + texHeight + ")");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Error loading image: " + e.getMessage());
            }
        }
    }

    // ========================================================================
    // Coordinate conversion
    // ========================================================================

    private Point screenToTex(Point screen) {
        Rectangle view = canvas.getViewRect();
        int tx = (screen.x + view.x) / scale;
        int ty = (screen.y + view.y) / scale;
        tx = Math.max(0, Math.min(texWidth - 1, tx));
        ty = Math.max(0, Math.min(texHeight - 1, ty));
        if (snapToGrid) {
            tx = Math.round(tx / 16.0f) * 16;
            ty = Math.round(ty / 16.0f) * 16;
            tx = Math.max(0, Math.min(texWidth - 1, tx));
            ty = Math.max(0, Math.min(texHeight - 1, ty));
        }
        return new Point(tx, ty);
    }

    private void clampSelEnd() {
        if (selStart != null && selEnd != null) {
            selEnd.x = Math.max(0, Math.min(texWidth - 1, selEnd.x));
            selEnd.y = Math.max(0, Math.min(texHeight - 1, selEnd.y));
        }
    }

    // ========================================================================
    // Selection
    // ========================================================================

    private Rectangle getSelection() {
        if (selStart == null || selEnd == null) return null;
        int x = Math.min(selStart.x, selEnd.x);
        int y = Math.min(selStart.y, selEnd.y);
        int w = Math.abs(selEnd.x - selStart.x) + 1;
        int h = Math.abs(selEnd.y - selStart.y) + 1;
        return new Rectangle(x, y, w, h);
    }

    private void clearSelection() {
        selStart = null;
        selEnd = null;
        updateInfo();
        canvas.repaint();
    }

    // ========================================================================
    // Display info
    // ========================================================================

    private void updateInfo() {
        StringBuilder sb = new StringBuilder();

        // Hover pixel
        if (hoverPixel.x >= 0 && hoverPixel.y >= 0 && texture != null) {
            int rgb = texture.getRGB(hoverPixel.x, hoverPixel.y);
            Color c = new Color(rgb, true);
            sb.append(String.format(" Pixel: (%3d, %3d)", hoverPixel.x, hoverPixel.y));
            if (c.getAlpha() < 255) {
                sb.append(String.format("  RGBA: (%3d, %3d, %3d, %3d)", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha()));
            } else {
                sb.append(String.format("  RGB: (%3d, %3d, %3d)", c.getRed(), c.getGreen(), c.getBlue()));
            }
        }

        // Selection
        Rectangle sel = getSelection();
        if (sel != null) {
            float texW = (float) texWidth;
            float texH = (float) texHeight;
            sb.append(String.format(Locale.US,
                "  |  Sel Pix: (%d, %d, %d, %d)  UV: (%.4f, %.4f, %.4f, %.4f)  JSON: \"uv\": [%d, %d, %d, %d]",
                sel.x, sel.y, sel.width, sel.height,
                sel.x / texW, sel.y / texH,
                sel.width / texW, sel.height / texH,
                sel.x, sel.y, sel.x + sel.width, sel.y + sel.height
            ));
        }

        infoLabel.setText(sb.toString());
    }

    private void copyUVToClipboard() {
        Rectangle sel = getSelection();
        if (sel == null) {
            JOptionPane.showMessageDialog(this, "No selection to copy. Click or drag to select a region first.");
            return;
        }
        String result = String.format(Locale.US, "\"uv\": [ %d, %d, %d, %d ]",
            sel.x, sel.y, sel.x + sel.width, sel.y + sel.height);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(result), null);
        infoLabel.setText(" Copied to clipboard: " + result);
    }

    // ========================================================================
    // Canvas sizing
    // ========================================================================

    private void updateCanvasSize() {
        if (canvas != null) {
            canvas.setPreferredSize(new Dimension(texWidth * scale, texHeight * scale));
            canvas.revalidate();
            canvas.repaint();
        }
    }

    // ========================================================================
    // Canvas rendering
    // ========================================================================

    private class Canvas extends JPanel {
        private Rectangle getViewRect() {
            Container p = getParent();
            if (p instanceof JViewport) return ((JViewport) p).getViewRect();
            return new Rectangle(0, 0, getWidth(), getHeight());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (texture == null) {
                g.setColor(Color.DARK_GRAY);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.GRAY);
                g.setFont(new Font("SansSerif", Font.PLAIN, 18));
                String msg = "No texture loaded. Click \"Load Texture\" below.";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
                return;
            }

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            // Draw the texture
            g2.drawImage(texture, 0, 0, texWidth * scale, texHeight * scale, null);

            // Pixel grid (show when scale >= 3)
            if (showGrid && scale >= 3) {
                g2.setColor(new Color(0, 0, 0, 25));
                for (int x = 0; x <= texWidth; x++) {
                    int sx = x * scale;
                    g2.drawLine(sx, 0, sx, texHeight * scale);
                }
                for (int y = 0; y <= texHeight; y++) {
                    int sy = y * scale;
                    g2.drawLine(0, sy, texWidth * scale, sy);
                }
            }

            // Major grid labels (every 16 pixels, when scale >= 3)
            if (showGrid && scale >= 3) {
                g2.setFont(new Font("Monospaced", Font.PLAIN, Math.min(scale * 2, 11)));
                g2.setColor(new Color(255, 200, 0, 180));
                for (int x = 0; x < texWidth; x += 16) {
                    g2.drawString(String.valueOf(x), x * scale + 2, (texHeight * scale) - 3);
                }
                for (int y = 0; y < texHeight; y += 16) {
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
                g2.setColor(new Color(60, 120, 255, 50));
                g2.fillRect(sx, sy, sw, sh);

                // Solid border
                g2.setColor(new Color(60, 120, 255, 220));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRect(sx, sy, sw, sh);

                // Corner coordinate tags
                g2.setFont(new Font("Monospaced", Font.BOLD, Math.min(scale * 2, 12)));
                String topLeft = String.format("(%d,%d)", sel.x, sel.y);
                String botRight = String.format("(%d,%d)", sel.x + sel.width - 1, sel.y + sel.height - 1);

                // Text with shadow
                g2.setColor(Color.BLACK);
                g2.drawString(topLeft, sx + 4, sy + scale - 1);
                g2.drawString(botRight, sx + sw - g2.getFontMetrics().stringWidth(botRight) - 2, sy + sh - 2);
                g2.setColor(Color.WHITE);
                g2.drawString(topLeft, sx + 3, sy + scale - 2);
                g2.drawString(botRight, sx + sw - g2.getFontMetrics().stringWidth(botRight) - 3, sy + sh - 3);

                // Dimension label in center
                String dimLabel = sel.width + "x" + sel.height;
                g2.setFont(new Font("Monospaced", Font.BOLD, Math.min(scale * 2, 13)));
                int dimW = g2.getFontMetrics().stringWidth(dimLabel);
                int dimX = sx + (sw - dimW) / 2;
                int dimY = sy + sh / 2;
                g2.setColor(new Color(0, 0, 0, 160));
                g2.fillRect(dimX - 3, dimY - scale + 1, dimW + 6, scale + 2);
                g2.setColor(Color.WHITE);
                g2.drawString(dimLabel, dimX, dimY + 2);
            }

            // Selection start point marker (when waiting for second click)
            if (selStart != null && selEnd == null) {
                int hx = selStart.x * scale;
                int hy = selStart.y * scale;
                g2.setColor(new Color(255, 80, 80, 200));
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(hx - 5, hy - 5, 10, 10);
                g2.drawLine(hx - 8, hy, hx + 8, hy);
                g2.drawLine(hx, hy - 8, hx, hy + 8);
            }

            // Hover crosshair (only when not in selection)
            if (hoverPixel.x >= 0 && hoverPixel.y >= 0 && selStart == null && texture != null) {
                int hx = hoverPixel.x * scale;
                int hy = hoverPixel.y * scale;
                g2.setColor(new Color(255, 255, 255, 160));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(hx - 8, hy, hx + 8, hy);
                g2.drawLine(hx, hy - 8, hx, hy + 8);
            }
        }
    }

    // ========================================================================
    // Launch
    // ========================================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new UVFinder().setVisible(true);
        });
    }
}
