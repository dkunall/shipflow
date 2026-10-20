package com.shipflow.webhook;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with "equal jitter": d = min(max, base * 2^(attempt-1)); wait = d/2 + random(0, d/2).
 * Keeps a guaranteed minimum spacing while de-synchronising retries of many failing events.
 */
public final class BackoffPolicy {
    private final Duration base;
    private final Duration max;
    private final DoubleSupplier random;

    public BackoffPolicy(Duration base, Duration max, DoubleSupplier random) {
        if (base.isNegative() || base.isZero() || max.compareTo(base) < 0) {
            throw new IllegalArgumentException("invalid backoff bounds");
        }
        this.base = base;
        this.max = max;
        this.random = random;
    }

    /** @param attempt 1-based number of the attempt that just failed */
    public Duration delayAfter(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        long baseMs = base.toMillis();
        long capMs = max.toMillis();
        int shift = Math.min(attempt - 1, 30);
        long exp = baseMs > (capMs >> shift) ? capMs : Math.min(capMs, baseMs << shift);
        long half = exp / 2;
        return Duration.ofMillis(half + (long) (random.getAsDouble() * (exp - half)));
    }
}
