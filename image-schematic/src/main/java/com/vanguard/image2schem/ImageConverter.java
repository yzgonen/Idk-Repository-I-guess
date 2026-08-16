package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ImageConverter {
    public record Result(int width, int height, int length, int[] paletteIds, Map<String, Integer> palette) {}

    private ImageConverter() {}

    public static Result convert(Path imagePath, int targetWidth, int maxDepth) throws IOException {
        BufferedImage src = ImageIO.read(imagePath.toFile());
        if (src == null) throw new IOException("Unsupported or unreadable image: " + imagePath.getFileName());

        targetWidth = Math.max(8, Math.min(256, targetWidth));
        maxDepth = Math.max(1, Math.min(8, maxDepth));
        int targetHeight = Math.max(1, Math.round(src.getHeight() * (targetWidth / (float) src.getWidth())));
        if (targetHeight > 256) {
            float scale = 256F / targetHeight;
            targetHeight = 256;
            targetWidth = Math.max(1, Math.round(targetWidth * scale));
        }

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        int[] blocks = new int[targetWidth * targetHeight * maxDepth];

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >>> 16) & 255;
                int gr = (rgb >>> 8) & 255;
                int b = rgb & 255;
                BlockPalette.Entry material = BlockPalette.nearest(r, gr, b);
                int id = palette.computeIfAbsent(material.block(), k -> palette.size());

                float luminance = (0.2126F * r + 0.7152F * gr + 0.0722F * b) / 255F;
                int depth = 1 + Math.round((1F - luminance) * (maxDepth - 1));

                // Sponge ordering: x changes fastest, then z, then y.
                int outY = targetHeight - 1 - y;
                for (int z = 0; z < maxDepth; z++) {
                    int index = x + z * targetWidth + outY * targetWidth * maxDepth;
                    blocks[index] = z < depth ? id : 0;
                }
            }
        }
        return new Result(targetWidth, targetHeight, maxDepth, blocks, palette);
    }
}
