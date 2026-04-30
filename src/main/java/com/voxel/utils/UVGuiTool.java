package com.voxel.utils;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;

/**
 * A GUI tool to find Minecraft UV coordinates by clicking on a texture.
 * Click twice to define the top-left and bottom-right pixels (inclusive).
 */
public class UVGuiTool extends JFrame {
    private BufferedImage image;
    private Point firstClick = null;
    private Point secondClick = null;
    private final JLabel statusLabel = new JLabel("Click 'Load' to select a texture.");
    private final JPanel imagePanel;

    public UVGuiTool() {
        setTitle("Minecraft UV Finder");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        imagePanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (image != null) {
                    g.drawImage(image, 0, 0, getWidth(), getHeight(), null);
                    
                    // Draw selection helper with snapping
                    if (firstClick != null) {
                        g.setColor(Color.RED);
                        int x1 = snap(firstClick.x, getWidth());
                        int y1 = snap(firstClick.y, getHeight());
                        g.fillOval(x1 - 3, y1 - 3, 6, 6);
                        
                        if (secondClick != null) {
                            int x2 = snap(secondClick.x, getWidth());
                            int y2 = snap(secondClick.y, getHeight());
                            int rx = Math.min(x1, x2);
                            int ry = Math.min(y1, y2);
                            int rw = Math.abs(x1 - x2);
                            int rh = Math.abs(y1 - y2);
                            g.drawRect(rx, ry, rw, rh);
                            g.setColor(new Color(255, 0, 0, 50));
                            g.fillRect(rx, ry, rw, rh);
                        }
                    }
                }
            }

            private int snap(int screenCoord, int totalSize) {
                return Math.round((float) screenCoord / totalSize * 16) * totalSize / 16;
            }
        };

        imagePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (image == null) return;
                if (firstClick == null || secondClick != null) {
                    firstClick = e.getPoint();
                    secondClick = null;
                } else {
                    secondClick = e.getPoint();
                    calculateUVs();
                }
                imagePanel.repaint();
            }
        });

        JButton loadBtn = new JButton("Load Texture");
        loadBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser("src/main/resources/assets/minecraft/textures/blocks");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    image = ImageIO.read(fc.getSelectedFile());
                    imagePanel.setPreferredSize(new Dimension(512, 512));
                    pack();
                    statusLabel.setText("Loaded: " + fc.getSelectedFile().getName() + " (" + image.getWidth() + "x" + image.getHeight() + ")");
                    firstClick = null;
                    secondClick = null;
                    imagePanel.repaint();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Error loading image: " + ex.getMessage());
                }
            }
        });

        add(loadBtn, BorderLayout.NORTH);
        add(new JScrollPane(imagePanel), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        setSize(600, 650);
        setLocationRelativeTo(null);
    }

    private void calculateUVs() {
        // Convert screen pixels to 0-16 MC UV scale with integer rounding
        int u1 = Math.round((float) firstClick.x / imagePanel.getWidth() * 16);
        int v1 = Math.round((float) firstClick.y / imagePanel.getHeight() * 16);
        int u2 = Math.round((float) secondClick.x / imagePanel.getWidth() * 16);
        int v2 = Math.round((float) secondClick.y / imagePanel.getHeight() * 16);

        // Ensure order (u1,v1 < u2,v2)
        int minU = Math.min(u1, u2);
        int minV = Math.min(v1, v2);
        int maxU = Math.max(u1, u2);
        int maxV = Math.max(v1, v2);

        String result = String.format(Locale.US, "\"uv\": [ %d, %d, %d, %d ]", minU, minV, maxU, maxV);
        System.out.println(result);
        statusLabel.setText("Copied (Rounded): " + result);

        // Copy to clipboard
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(result), null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new UVGuiTool().setVisible(true));
    }
}
