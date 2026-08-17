package com.vanguard.image2schem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class NeuralBuildingAIFailFastTest {
    @Test
    void fastFailureDoesNotWaitForSlowSibling() {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch slowStarted = new CountDownLatch(1);
        long started = System.nanoTime();

        IOException error = assertThrows(IOException.class, () -> NeuralBuildingAI.runParallelFailFast(
                () -> {
                    assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
                    throw new IOException("planner failed immediately");
                },
                () -> {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                    return 42;
                }
        ));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(error.getMessage().contains("planner failed immediately"));
        assertTrue(elapsedMs < 2_000, "failure should surface quickly instead of waiting for sibling timeout; elapsed=" + elapsedMs + "ms");
        for (int i=0; i<20 && !interrupted.get(); i++) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
        assertTrue(interrupted.get(), "the slow sibling should be interrupted after the other stage fails");
    }

    @Test
    void returnsBothResultsRegardlessOfCompletionOrder() throws Exception {
        var result = NeuralBuildingAI.runParallelFailFast(
                () -> { Thread.sleep(80); return "groq"; },
                () -> { Thread.sleep(10); return 123; }
        );
        assertEquals("groq", result.first());
        assertEquals(123, result.second());
    }
}
