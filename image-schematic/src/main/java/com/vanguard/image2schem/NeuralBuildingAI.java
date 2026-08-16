package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Groq-assisted local reconstruction pipeline used by the mod. */
public final class NeuralBuildingAI {
    private NeuralBuildingAI() {}

    public static ImageConverter.Result reconstruct(Path imagePath, int targetWidth, int requestedDepth, IntConsumer progress) throws IOException {
        try {
            progress.accept(1);
            BufferedImage src = ImageIO.read(imagePath.toFile());
            if (src == null) throw new IOException("Unsupported or unreadable image");

            // Groq planning is network-bound while Depth Anything is local compute.
            // Run them together instead of waiting for one and then starting the other.
            AtomicInteger shown = new AtomicInteger(2);
            IntConsumer parallelProgress = p -> {
                int mapped = Math.max(2, Math.min(45, p));
                int prev;
                do {
                    prev = shown.get();
                    if (mapped <= prev) return;
                } while (!shown.compareAndSet(prev, mapped));
                progress.accept(mapped);
            };

            CompletableFuture<GroqArchitectAI.Plan> groqFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return GroqArchitectAI.analyze(src, p -> parallelProgress.accept(2 + Math.round(Math.max(0, Math.min(24, p)) * 43f / 24f)));
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });

            CompletableFuture<float[][]> depthFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return NeuralDepthAI.estimate(src, p -> parallelProgress.accept(2 + Math.round(Math.max(0, Math.min(49, p)) * 43f / 49f)));
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });

            final GroqArchitectAI.Plan plan;
            final float[][] depth;
            try {
                plan = groqFuture.join();
                depth = depthFuture.join();
            } catch (CompletionException e) {
                // Stop the other task if one side failed.
                groqFuture.cancel(true);
                depthFuture.cancel(true);
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
            }

            progress.accept(50);

            // Local primitive-based architecture engine combines Groq's scene graph + neural depth.
            ImageConverter.Result result = GroqArchitectureBuilder.build(src, depth, plan, targetWidth, requestedDepth,
                    p -> progress.accept(Math.max(51, Math.min(99, p))));
            progress.accept(99);
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("AI generation failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }
}
