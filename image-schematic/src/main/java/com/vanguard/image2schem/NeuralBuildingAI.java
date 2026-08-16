package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/** Groq-assisted local reconstruction pipeline used by the mod. */
public final class NeuralBuildingAI {
    private NeuralBuildingAI() {}

    public static ImageConverter.Result reconstruct(Path imagePath, int targetWidth, int requestedDepth, IntConsumer progress) throws IOException {
        try {
            progress.accept(1);
            BufferedImage src = ImageIO.read(imagePath.toFile());
            if (src == null) throw new IOException("Unsupported or unreadable image");

            // 1) Groq is the architectural reasoning brain. One vision request creates a normalized scene graph.
            GroqArchitectAI.Plan plan = GroqArchitectAI.analyze(src,
                    p -> progress.accept(Math.max(2, Math.min(24, p))));
            progress.accept(25);

            // 2) Local neural depth supplies relative spatial evidence. Groq never creates blocks itself.
            float[][] depth = NeuralDepthAI.estimate(src,
                    p -> progress.accept(25 + Math.round(Math.max(0, Math.min(49, p)) * 34f / 49f)));
            progress.accept(59);

            // 3) Local primitive-based architecture engine combines Groq's scene graph + neural depth.
            ImageConverter.Result result = GroqArchitectureBuilder.build(src, depth, plan, targetWidth, requestedDepth,
                    p -> progress.accept(Math.max(60, Math.min(99, p))));
            progress.accept(99);
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("AI generation failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }
}
