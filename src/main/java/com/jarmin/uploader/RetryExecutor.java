package com.jarmin.uploader;

import com.jarmin.uploader.exception.ChunkUploadException;
import com.jarmin.uploader.exception.StoreException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Executes an action with a bounded number of retries and exponential backoff.
 *
 * <p>The action is attempted up to {@code 1 + maxRetries} times. A failure is retried only when
 * the supplied predicate classifies it as retryable; otherwise it fails immediately. Backoff for
 * the attempt after {@code n} prior failures is {@code min(max, base * 2^(n-1))}.
 */
final class RetryExecutor {

    /** Seam so tests can observe backoff without real sleeping. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final Sleeper REAL_SLEEPER = Thread::sleep;

    private final int maxRetries;
    private final Duration base;
    private final Duration max;
    private final Sleeper sleeper;

    RetryExecutor(int maxRetries, Duration base, Duration max) {
        this(maxRetries, base, max, REAL_SLEEPER);
    }

    RetryExecutor(int maxRetries, Duration base, Duration max, Sleeper sleeper) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, was " + maxRetries);
        }
        this.maxRetries = maxRetries;
        this.base = Objects.requireNonNull(base, "base");
        this.max = Objects.requireNonNull(max, "max");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    <T> T execute(Callable<T> action, Predicate<Exception> isRetryable) throws StoreException {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(isRetryable, "isRetryable");

        int attempt = 0;
        while (true) {
            try {
                return action.call();
            } catch (Exception e) {
                boolean exhausted = attempt >= maxRetries;
                if (exhausted || !isRetryable.test(e)) {
                    // A StoreException raised directly by the action carries its own semantics.
                    if (e instanceof StoreException se) {
                        throw se;
                    }
                    throw new ChunkUploadException(
                            "Operation failed after " + (attempt + 1) + " attempt(s)", e);
                }
                backoff(attempt);
                attempt++;
            }
        }
    }

    private void backoff(int priorFailures) throws StoreException {
        long baseMillis = base.toMillis();
        long delay = baseMillis << priorFailures; // base * 2^priorFailures
        // Guard against overflow from the shift and clamp to the configured maximum.
        if (delay < 0 || delay > max.toMillis()) {
            delay = max.toMillis();
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new StoreException("Interrupted during retry backoff", ie);
        }
    }
}
