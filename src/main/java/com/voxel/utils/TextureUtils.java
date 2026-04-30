package com.voxel.utils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class TextureUtils {

    public static Color getAverageColor(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                System.err.println("File not found: " + filePath);
                return Color.BLACK;
            }

            BufferedImage image = ImageIO.read(file);
            long rSum = 0, gSum = 0, bSum = 0;
            int validPixels = 0;

            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    int argb = image.getRGB(x, y);
                    
                    // Extract components
                    int a = (argb >> 24) & 0xff;
                    int r = (argb >> 16) & 0xff;
                    int g = (argb >> 8) & 0xff;
                    int b = argb & 0xff;

                    // Filter: Not fully transparent AND not pure black (0,0,0)
                    if (a > 0 && (r > 0 || g > 0 || b > 0)) {
                        rSum += (long) r * r;
                        gSum += (long) g * g;
                        bSum += (long) b * b;
                        validPixels++;
                    }
                }
            }

            if (validPixels == 0) return Color.BLACK;

            return new Color(
                (int) Math.sqrt(rSum / validPixels),
                (int) Math.sqrt(gSum / validPixels),
                (int) Math.sqrt(bSum / validPixels)
            );

        } catch (IOException e) {
            e.printStackTrace();
            return Color.BLACK;
        }
    }

    public static void main(String[] args) {
        String path = "C:\\Users\\raman\\eclipse-workspace\\fastpbrjava\\VoxelEngine\\src\\main\\resources\\assets\\minecraft\\textures\\blocks\\diamond_block.png";
        Color avg = getAverageColor(path);
        System.out.println("Average Color: " + avg);
        System.out.println("RGB: " + avg.getRed() + ", " + avg.getGreen() + ", " + avg.getBlue());
        System.out.println("RGB unit vectors: " + (avg.getRed()/256.0) + "f, " + (avg.getGreen()/256.0) + "f, " + (avg.getBlue()/256.0) + "f");
        
    }
}
