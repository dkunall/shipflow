package com.shipflow.support;

import com.shipflow.config.ShipFlowProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Same values as application.yml, for pure unit tests. */
public final class TestProps {
    private TestProps() {
    }

    public static final Map<String, Double> WEIGHTS = Map.of("cost", 0.35, "sla", 0.20, "capacity", 0.10,
            "on-time", 0.15, "first-attempt", 0.10, "rto", 0.10);

    public static ShipFlowProperties defaults() {
        return new ShipFlowProperties(
                new ShipFlowProperties.Jwt(TestJwt.SECRET, "shipflow"),
                new ShipFlowProperties.Scheduling(false),
                new ShipFlowProperties.Idempotency(Duration.ofHours(24), Duration.ofMinutes(1)),
                new ShipFlowProperties.Allocation(WEIGHTS, 3),
                scorecard(),
                new ShipFlowProperties.Booking(Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(15),
                        Duration.ofMinutes(1), 50),
                new ShipFlowProperties.CircuitBreaker(20, 5, 0.5, Duration.ofSeconds(30), 2),
                new ShipFlowProperties.Ndr(3, List.of("CUSTOMER_REFUSED", "OUT_OF_DELIVERY_AREA")),
                new ShipFlowProperties.Risk(35, 60, new BigDecimal("500")),
                new ShipFlowProperties.Edd(0.8, 30),
                new ShipFlowProperties.Webhook(Duration.ofSeconds(1), 50, 8, Duration.ofSeconds(2), Duration.ofMinutes(30),
                        Duration.ofSeconds(5), Duration.ofMinutes(1), 8),
                new ShipFlowProperties.Cod("0 30 2 * * *", "Asia/Dubai"),
                new ShipFlowProperties.Routing(2000));
    }

    public static ShipFlowProperties.Scorecard scorecard() {
        return new ShipFlowProperties.Scorecard(30, 20, 0.85, 0.80, 0.08);
    }
}
