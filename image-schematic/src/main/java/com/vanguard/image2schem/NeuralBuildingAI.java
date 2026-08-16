package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/** Real local neural reconstruction pipeline used by the mod. */
public final class NeuralBuildingAI {
    private NeuralBuildingAI() {}

    public static ImageConverter.Result reconstruct(Path imagePath, int targetWidth, int requestedDepth, IntConsumer progress) throws IOException {
        try {
            progress.accept(1);
            BufferedImage src = ImageIO.read(imagePath.toFile());
            if (src == null) throw new IOException("Unsupported or unreadable image");

            // Real neural inference. On the first run the model is downloaded and cached locally.
            float[][] depth = NeuralDepthAI.estimate(src, p -> progress.accept(Math.max(1, Math.min(49, p))));
            progress.accept(50);

            // IMPORTANT: no LocalBuildingAI fallback here. That old code invented a giant rectangular shell.
            // The new builder uses the neural depth map itself as the geometry source and only places
            // blocks where image/depth evidence supports actual visible architecture.
            ImageConverter.Result result = DepthGeometryBuilder.build(src, depth, targetWidth, requestedDepth,
                    p -> progress.accept(Math.max(51, Math.min(99, p))));
            progress.accept(99);
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Neural AI failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }
}
