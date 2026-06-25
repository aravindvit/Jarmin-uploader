package com.jarmin.uploader;

import com.jarmin.uploader.exception.ChunkUploadException;
import com.jarmin.uploader.exception.StoreException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryExecutorTest {

    private static final Predicate<Exception> ALWAYS_RETRY = e -> true;
    private static final Predicate<Exception> NEVER_RETRY = e -> false;

    /** Records requested sleeps instead of actually sleeping, so tests run instantly. */
    private final List<Long> sleeps = new ArrayList<>();
    private final RetryExecutor.Sleeper recordingSleeper = millis -> sleeps.add(millis);

    private RetryExecutor executor(int maxRetries) {
        return new RetryExecutor(maxRetries, Duration.ofMillis(100), Duration.ofSeconds(1), recordingSleeper);
    }

    @Test
    void returnsValueWhenActionSucceedsFirstTry() throws StoreException {
        // RX-1
        AtomicInteger calls = new AtomicInteger();
        String result = executor(3).execute(() -> {
            calls.incrementAndGet();
            return "ok";
        }, ALWAYS_RETRY);

        assertEquals("ok", result);
        assertEquals(1, calls.get());
        assertEquals(0, sleeps.size());
    }

    @Test
    void retriesThenSucceeds() throws StoreException {
        // RX-2
        AtomicInteger calls = new AtomicInteger();
        String result = executor(3).execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
            }
            return "ok";
        }, ALWAYS_RETRY);

        assertEquals("ok", result);
        assertEquals(3, calls.get());
        assertEquals(2, sleeps.size()); // two backoffs before the third attempt
    }

    @Test
    void throwsChunkUploadExceptionAfterRetriesExhausted() {
        // RX-3
        AtomicInteger calls = new AtomicInteger();
        ChunkUploadException ex = assertThrows(ChunkUploadException.class, () ->
                executor(3).execute(() -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("always");
                }, ALWAYS_RETRY));

        assertEquals(4, calls.get()); // 1 + maxRetries
        assertEquals("always", ex.getCause().getMessage());
    }

    @Test
    void nonRetryableFailsImmediately() {
        // RX-4
        AtomicInteger calls = new AtomicInteger();
        assertThrows(ChunkUploadException.class, () ->
                executor(3).execute(() -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("fatal");
                }, NEVER_RETRY));

        assertEquals(1, calls.get());
        assertEquals(0, sleeps.size());
    }

    @Test
    void backoffGrowsExponentiallyCappedAtMax() throws StoreException {
        // RX-5: base 100ms doubling -> 100, 200, 400, 800, capped at 1000
        executor(5).execute(new java.util.concurrent.Callable<String>() {
            int n = 0;
            @Override public String call() {
                if (n++ < 5) throw new RuntimeException("retry");
                return "ok";
            }
        }, ALWAYS_RETRY);

        assertEquals(List.of(100L, 200L, 400L, 800L, 1000L), sleeps);
    }

    @Test
    void zeroMaxRetriesMeansSingleAttempt() {
        // RX-6
        AtomicInteger calls = new AtomicInteger();
        assertThrows(ChunkUploadException.class, () ->
                executor(0).execute(() -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("x");
                }, ALWAYS_RETRY));

        assertEquals(1, calls.get());
        assertEquals(0, sleeps.size());
    }

    @Test
    void propagatesExistingStoreExceptionWithoutWrapping() {
        // A StoreException thrown by the action that is non-retryable surfaces as-is.
        KeyNotFoundSentinel sentinel = new KeyNotFoundSentinel();
        StoreException thrown = assertThrows(StoreException.class, () ->
                executor(3).execute(() -> {
                    throw sentinel;
                }, NEVER_RETRY));
        assertSame(sentinel, thrown);
    }

    private static final class KeyNotFoundSentinel extends StoreException {
        KeyNotFoundSentinel() {
            super("sentinel");
        }
    }
}
