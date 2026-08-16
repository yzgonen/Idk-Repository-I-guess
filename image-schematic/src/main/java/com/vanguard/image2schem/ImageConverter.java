package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

public final class ImageConverter {
    public record Result(int width, int height, int length, int[] paletteIds, Map<String, Integer> palette) {}
    public record Suggestion(int width, int height, int depth, int sourceWidth, int sourceHeight) {}

    private ImageConverter() {}

    public static Suggestion suggest(Path imagePath) throws IOException {
        BufferedImage src = ImageIO.read(imagePath.toFile());
        if (src == null) throw new IOException("Unsupported or unreadable image: " + imagePath.getFileName());

        int sw = src.getWidth();
        int sh = src.getHeight();
        int targetWidth = Math.min(128, Math.max(32, sw / Math.max(1, sw / 128)));
        float ratio = targetWidth / (float) sw;
        int targetHeight = Math.max(1, Math.round(sh * ratio));
        if (targetHeight > 128) {
            float down = 128F / targetHeight;
            targetHeight = 128;
            targetWidth = Math.max(8, Math.round(targetWidth * down));
        }

        // Estimate useful relief depth from image contrast.
        long sum = 0;
        long sumSq = 0;
        int samples = 0;
        int stepX = Math.max(1, sw / 64);
        int stepY = Math.max(1, sh / 64);
        for (int y = 0; y < sh; y += stepY) {
            for (int x = 0; x < sw; x += stepX) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >>> 16) & 255;
                int g = (rgb >>> 8) & 255;
                int b = rgb & 255;
                int lum = Math.round(0.2126F * r + 0.7152F * g + 0.0722F * b);
                sum += lum;
                sumSq += (long) lum * lum;
                samples++;
            }
        }
        double mean = sum / (double) Math.max(1, samples);
        double variance = sumSq / (double) Math.max(1, samples) - mean * mean;
        double stdev = Math.sqrt(Math.max(0, variance));
        int depth = stdev > 70 ? 6 : stdev > 50 ? 5 : stdev > 32 ? 4 : 3;

        return new Suggestion(targetWidth, targetHeight, depth, sw, sh);
    }

    public static Result convert(Path imagePath, int targetWidth, int maxDepth) throws IOException {
        return convert(imagePath, targetWidth, maxDepth, ignored -> {});
    }

    public static Result convert(Path imagePath, int targetWidth, int maxDepth, IntConsumer progress) throws IOException {
        progress.accept(1);
        BufferedImage src = ImageIO.read(imagePath.toFile());
        if (src == null) throw new IOException("Unsupported or unreadable image: " + imagePath.getFileName());
        progress.accept(5);

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
        progress.accept(15);

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

                int outY = targetHeight - 1 - y;
                for (int z = 0; z < maxDepth; z++) {
                    int index = x + z * targetWidth + outY * targetWidth * maxDepth;
                    blocks[index] = z < depth ? id : 0;
                }
            }
            progress.accept(15 + Math.round(75F * (y + 1) / targetHeight));
        }
        progress.accept(92);
        return new Result(targetWidth, targetHeight, maxDepth, blocks, palette);
    }
}
