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

            // Real local neural inference. The model is cached after the first download.
            float[][] depth = NeuralDepthAI.estimate(src, p -> progress.accept(Math.max(1, Math.min(49, p))));
            progress.accept(50);

            // v1 pipeline: neural depth -> plane fitting -> architecture solver -> material engine.
            // No old rectangular shell generator and no raw pixel extrusion fallback.
            ImageConverter.Result result = ArchitectureV1Builder.build(src, depth, targetWidth, requestedDepth,
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
