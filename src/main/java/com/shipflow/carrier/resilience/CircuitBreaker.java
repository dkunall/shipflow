package com.shipflow.carrier.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Small count-based circuit breaker (thread-safe, clock-injected for deterministic tests).
 * <ul>
 *   <li>CLOSED: records outcomes in a ring buffer of the last {@code windowSize} calls; once at least
 *       {@code minimumCalls} are recorded and failure rate >= threshold -> OPEN;</li>
 *   <li>OPEN: rejects calls until {@code openDuration} elapsed, then HALF_OPEN;</li>
 *   <li>HALF_OPEN: lets {@code halfOpenCalls} trial calls through; all succeed -> CLOSED (window reset),
 *       any failure -> OPEN again.</li>
 * </ul>
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Config(int windowSize, int minimumCalls, double failureRateThreshold, Duration openDuration,
                         int halfOpenCalls) {
        public Config {
            if (windowSize < 1 || minimumCalls < 1 || minimumCalls > windowSize || halfOpenCalls < 1
                    || failureRateThreshold <= 0 || failureRateThreshold > 1) {
                throw new IllegalArgumentException("invalid circuit breaker config");
            }
        }
    }

    private final String name;
    private final Config config;
    private final Clock clock;
    private final boolean[] window;
    private int recorded;
    private int next;
    private int failures;
    private State state = State.CLOSED;
    private Instant openedAt;
    private int halfOpenPermitsIssued;
    private int halfOpenSuccesses;

    public CircuitBreaker(String name, Config config, Clock clock) {
        this.name = name;
        this.config = config;
        this.clock = clock;
        this.window = new boolean[config.windowSize()];
    }

    public String name() {
        return name;
    }

    /** Consumes a permit if a call may proceed now. */
    public synchronized boolean tryAcquirePermission() {
        refreshState();
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (halfOpenPermitsIssued < config.halfOpenCalls()) {
                    halfOpenPermitsIssued++;
                    yield true;
                }
                yield false;
            }
        };
    }

    /** Non-consuming check used by allocation to skip carriers whose breaker is open. */
    public synchronized boolean isCallPermitted() {
        refreshState();
        return state == State.CLOSED || (state == State.HALF_OPEN && halfOpenPermitsIssued < config.halfOpenCalls());
    }

    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= config.halfOpenCalls()) {
                transitionToClosed();
            }
            return;
        }
        if (state == State.CLOSED) {
            record(false);
        }
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            transitionToOpen();
            return;
        }
        if (state == State.CLOSED) {
            record(true);
            if (recorded >= config.minimumCalls() && (double) failures / recorded >= config.failureRateThreshold()) {
                transitionToOpen();
            }
        }
    }

    public synchronized State state() {
        refreshState();
        return state;
    }

    public synchronized double failureRate() {
        return recorded == 0 ? 0.0 : (double) failures / recorded;
    }

    private void record(boolean failure) {
        if (recorded == window.length) {
            if (window[next]) {
                failures--;
            }
        } else {
            recorded++;
        }
        window[next] = failure;
        if (failure) {
            failures++;
        }
        next = (next + 1) % window.length;
    }

    private void refreshState() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(config.openDuration()))) {
            state = State.HALF_OPEN;
            halfOpenPermitsIssued = 0;
            halfOpenSuccesses = 0;
        }
    }

    private void transitionToOpen() {
        state = State.OPEN;
        openedAt = clock.instant();
    }

    private void transitionToClosed() {
        state = State.CLOSED;
        recorded = 0;
        next = 0;
        failures = 0;
        java.util.Arrays.fill(window, false);
    }
}
