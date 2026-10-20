package com.shipflow.webhook;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackoffPolicyTest {
    @Test
    void equalJitterExponentialWithinBounds() {
        BackoffPolicy low = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(30), () -> 0.0);
        BackoffPolicy high = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(30), () -> 0.999999);
        for (int attempt = 1; attempt <= 12; attempt++) {
            long exp = Math.min(Duration.ofMinutes(30).toMillis(), 2000L << (attempt - 1));
            assertThat(low.delayAfter(attempt).toMillis()).isEqualTo(exp / 2);
            assertThat(high.delayAfter(attempt).toMillis()).isBetween(exp / 2, exp);
        }
        assertThat(low.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(high.delayAfter(4).toMillis()).isBetween(8000L, 16000L);
    }

    @Test
    void neverOverflowsForLargeAttempts() {
        BackoffPolicy p = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(30), () -> 0.5);
        assertThat(p.delayAfter(1000)).isLessThanOrEqualTo(Duration.ofMinutes(30));
        assertThat(p.delayAfter(63).toMillis()).isPositive();
        assertThatThrownBy(() -> p.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
