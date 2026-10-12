package com.hookshot.delivery;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with "equal jitter": the delay is uniformly distributed in
 * [ceiling/2, ceiling] where ceiling = min(max, base * 2^(attempt-1)).
 * Jitter prevents a burst of failures from retrying in lockstep (thundering herd).
 */
public final class BackoffPolicy {

    private final Duration base;
    private final Duration max;
    private final DoubleSupplier random;

    public BackoffPolicy(Duration base, Duration max, DoubleSupplier random) {
        this.base = base;
        this.max = max;
        this.random = random;
    }

    /** @param attempt the attempt that just failed, starting at 1 */
    public Duration delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        int exponent = Math.min(attempt - 1, 30);
        double ceilingMs = Math.min((double) max.toMillis(), base.toMillis() * Math.pow(2, exponent));
        double half = ceilingMs / 2.0;
        return Duration.ofMillis(Math.round(half + random.getAsDouble() * half));
    }
}
