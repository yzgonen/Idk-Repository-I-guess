package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
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
            // Run them concurrently, and surface whichever side fails first instead of waiting for the other side's timeout.
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

            ParallelPair<GroqArchitectAI.Plan, float[][]> pair = runParallelFailFast(
                    () -> GroqArchitectAI.analyze(src,
                            p -> parallelProgress.accept(2 + Math.round(Math.max(0, Math.min(24, p)) * 43f / 24f))),
                    () -> NeuralDepthAI.estimate(src,
                            p -> parallelProgress.accept(2 + Math.round(Math.max(0, Math.min(49, p)) * 43f / 49f)))
            );

            progress.accept(50);

            // Local primitive-based architecture engine combines Groq's scene graph + neural depth.
            ImageConverter.Result result = GroqArchitectureBuilder.build(src, pair.second(), pair.first(), targetWidth, requestedDepth,
                    p -> progress.accept(Math.max(51, Math.min(99, p))));
            progress.accept(99);
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("AI generation failed: " + safe(e), e);
        }
    }

    /**
     * Runs two independent stages on dedicated daemon threads and returns only when both succeed.
     * If either stage fails, the sibling is interrupted immediately. Package-private so regression tests can
     * verify fail-fast behavior without making real network requests.
     */
    static <A, B> ParallelPair<A, B> runParallelFailFast(Callable<A> first, Callable<B> second) throws IOException {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Image2Schem-AI-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2, factory);
        ExecutorCompletionService<TaggedValue> completed = new ExecutorCompletionService<>(executor);
        List<Future<TaggedValue>> futures = new ArrayList<>(2);
        futures.add(completed.submit(() -> new TaggedValue(0, first.call())));
        futures.add(completed.submit(() -> new TaggedValue(1, second.call())));

        Object firstValue = null;
        Object secondValue = null;
        try {
            for (int i = 0; i < 2; i++) {
                TaggedValue value;
                try {
                    value = completed.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelAll(futures);
                    throw new IOException("AI generation was interrupted.", e);
                } catch (CancellationException e) {
                    cancelAll(futures);
                    throw new IOException("AI generation was cancelled.", e);
                } catch (ExecutionException e) {
                    cancelAll(futures);
                    Throwable cause = unwrap(e.getCause());
                    if (cause instanceof IOException io) throw io;
                    throw new IOException(safe(cause), cause);
                }
                if (value.index() == 0) firstValue = value.value();
                else secondValue = value.value();
            }
            @SuppressWarnings("unchecked") A a = (A) firstValue;
            @SuppressWarnings("unchecked") B b = (B) secondValue;
            return new ParallelPair<>(a, b);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) future.cancel(true);
    }

    private static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while (current != null && (current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? t : current;
    }

    private static String safe(Throwable e) {
        return e == null ? "Unknown error" : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    static record ParallelPair<A, B>(A first, B second) {}
    private record TaggedValue(int index, Object value) {}
}
