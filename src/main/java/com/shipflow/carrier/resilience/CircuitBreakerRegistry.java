package com.shipflow.carrier.resilience;

import com.shipflow.config.ShipFlowProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One breaker per carrier code; exposes state as a gauge (0 closed, 1 half-open, 2 open). */
@Component
public class CircuitBreakerRegistry {
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final CircuitBreaker.Config config;
    private final Clock clock;
    private final MeterRegistry meters;

    public CircuitBreakerRegistry(ShipFlowProperties props, Clock clock, MeterRegistry meters) {
        var c = props.circuitBreaker();
        this.config = new CircuitBreaker.Config(c.windowSize(), c.minimumCalls(), c.failureRateThreshold(),
                c.openDuration(), c.halfOpenCalls());
        this.clock = clock;
        this.meters = meters;
    }

    public CircuitBreaker forCarrier(String carrierCode) {
        return breakers.computeIfAbsent(carrierCode, code -> {
            CircuitBreaker cb = new CircuitBreaker(code, config, clock);
            Gauge.builder("shipflow.carrier.circuit.state", cb, b -> switch (b.state()) {
                case CLOSED -> 0;
                case HALF_OPEN -> 1;
                case OPEN -> 2;
            }).tag("carrier", code).register(meters);
            return cb;
        });
    }

    public boolean isCallPermitted(String carrierCode) {
        CircuitBreaker cb = breakers.get(carrierCode);
        return cb == null || cb.isCallPermitted();
    }

    public void resetAll() {
        breakers.clear();
    }
}
