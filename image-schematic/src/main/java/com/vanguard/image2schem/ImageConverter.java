package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
        int targetWidth = 96;
        if (sw < 512) targetWidth = 72;
        float ratio = targetWidth / (float) sw;
        int targetHeight = Math.max(24, Math.round(sh * ratio));
        if (targetHeight > 112) {
            float down = 112F / targetHeight;
            targetHeight = 112;
            targetWidth = Math.max(48, Math.round(targetWidth * down));
        }
        int depth = Math.max(20, Math.min(40, Math.round(targetWidth * 0.32F)));
        return new Suggestion(targetWidth, targetHeight, depth, sw, sh);
    }

    public static Result convert(Path imagePath, int targetWidth, int maxDepth) throws IOException {
        return convert(imagePath, targetWidth, maxDepth, ignored -> {});
    }

    public static Result convert(Path imagePath, int targetWidth, int maxDepth, IntConsumer progress) throws IOException {
        return NeuralBuildingAI.reconstruct(imagePath, targetWidth, maxDepth, progress);
    }
}