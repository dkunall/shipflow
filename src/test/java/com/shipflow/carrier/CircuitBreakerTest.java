package com.shipflow.carrier;

import com.shipflow.carrier.resilience.CircuitBreaker;
import com.shipflow.carrier.resilience.CircuitBreaker.State;
import com.shipflow.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
    private final CircuitBreaker cb = new CircuitBreaker("X",
            new CircuitBreaker.Config(10, 5, 0.5, Duration.ofSeconds(30), 2), clock);

    @Test
    void staysClosedBelowMinimumCalls() {
        for (int i = 0; i < 4; i++) {
            cb.onFailure();
        }
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        cb.onFailure();
        assertThat(cb.state()).isEqualTo(State.OPEN);
        assertThat(cb.tryAcquirePermission()).isFalse();
    }

    @Test
    void opensOnlyAtThresholdFailureRate() {
        for (int i = 0; i < 6; i++) {
            cb.onSuccess();
        }
        for (int i = 0; i < 5; i++) {
            cb.onFailure();
        }
        // window 10: last 10 = 5 success + 5 failure = 50% => open
        assertThat(cb.failureRate()).isEqualTo(0.5);
        assertThat(cb.state()).isEqualTo(State.OPEN);
    }

    @Test
    void slidingWindowForgetsOldFailures() {
        for (int i = 0; i < 4; i++) {
            cb.onFailure();
        }
        for (int i = 0; i < 10; i++) {
            cb.onSuccess();
        }
        assertThat(cb.failureRate()).isZero();
        cb.onFailure();
        assertThat(cb.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void halfOpenAfterCooldownClosesOnSuccessesReopensOnFailure() {
        for (int i = 0; i < 5; i++) {
            cb.onFailure();
        }
        clock.advance(Duration.ofSeconds(29));
        assertThat(cb.isCallPermitted()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(cb.state()).isEqualTo(State.HALF_OPEN);
        assertThat(cb.tryAcquirePermission()).isTrue();
        assertThat(cb.tryAcquirePermission()).isTrue();
        assertThat(cb.tryAcquirePermission()).isFalse(); // only 2 trial calls
        cb.onFailure();
        assertThat(cb.state()).isEqualTo(State.OPEN);

        clock.advance(Duration.ofSeconds(30));
        assertThat(cb.tryAcquirePermission()).isTrue();
        cb.onSuccess();
        assertThat(cb.state()).isEqualTo(State.HALF_OPEN);
        assertThat(cb.tryAcquirePermission()).isTrue();
        cb.onSuccess();
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        assertThat(cb.failureRate()).isZero();
    }
}
