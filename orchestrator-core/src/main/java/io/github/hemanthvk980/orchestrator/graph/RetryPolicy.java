package io.github.hemanthvk980.orchestrator.graph;

import java.time.Duration;

/**
 * Bounded retry with exponential backoff. Bounds are mandatory: an agent that keeps failing must
 * eventually stop and hand control back to a human rather than burn budget indefinitely.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

    public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("maxAttempts must be 1..10");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1");
    }

    public static RetryPolicy of(int maxAttempts, Duration initialBackoff) {
        return new RetryPolicy(maxAttempts, initialBackoff, 2.0, Duration.ofSeconds(30));
    }

    /** Backoff before attempt number {@code attempt} (1-based; attempt 1 has no wait). */
    public Duration backoffBefore(int attempt) {
        if (attempt <= 1) return Duration.ZERO;
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 2);
        return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
    }
}
