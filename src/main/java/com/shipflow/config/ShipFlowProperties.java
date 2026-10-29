package com.shipflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** All tunables in one typed, immutable place (values live in application.yml / env). */
@ConfigurationProperties("shipflow")
public record ShipFlowProperties(Jwt jwt, Scheduling scheduling, Idempotency idempotency, Allocation allocation,
                                 Scorecard scorecard, Booking booking, CircuitBreaker circuitBreaker, Ndr ndr,
                                 Risk risk, Edd edd, Webhook webhook, Cod cod, Routing routing) {

    public record Jwt(String secret, String issuer) {
    }

    public record Scheduling(boolean enabled) {
    }

    public record Idempotency(Duration ttl, Duration inProgressTimeout) {
    }

    /**
     * @param reserveLast        optimistic reserve-last allocation for bookings (see AllocationService.Reservation)
     * @param optimisticAttempts reserve-last attempts before the final pessimistic (reserve-first) attempt
     */
    public record Allocation(Map<String, Double> weights, int maxFailovers, boolean reserveLast, int optimisticAttempts) {
        public double weight(String rule) {
            return weights == null ? 0.0 : weights.getOrDefault(rule, 0.0);
        }
    }

    public record Scorecard(int windowDays, double priorWeight, double priorOnTime, double priorFirstAttempt,
                            double priorRto) {
    }

    /**
     * @param retryMaxAttempts total attempts per carrier call for technical failures (1 = no retry); all attempts
     *                         share the single {@code carrierCallTimeout} budget
     * @param retryBackoff     base wait before a retry; doubled per attempt and randomised by +-50 %
     */
    public record Booking(Duration carrierCallTimeout, Duration lease, Duration reconcileInterval,
                          Duration reconcileAfter, int batchSize, int retryMaxAttempts, Duration retryBackoff) {
    }

    public record CircuitBreaker(int windowSize, int minimumCalls, double failureRateThreshold, Duration openDuration,
                                 int halfOpenCalls) {
    }

    public record Ndr(int maxAttempts, List<String> immediateRtoReasons) {
    }

    public record Risk(int mediumThreshold, int highThreshold, BigDecimal highCodAmount) {
    }

    public record Edd(double leadTimePercentile, int minLaneSamples) {
    }

    public record Webhook(Duration pollInterval, int batchSize, int maxAttempts, Duration baseBackoff,
                          Duration maxBackoff, Duration requestTimeout, Duration lease, int workers) {
    }

    public record Cod(String reconcileCron, String reconcileZone) {
    }

    public record Routing(int maxStops) {
    }
}
