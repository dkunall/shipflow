package com.shipflow.allocation.filters;

import com.shipflow.allocation.domain.AllocationContext;
import com.shipflow.allocation.domain.CandidateFilter;
import com.shipflow.allocation.domain.CarrierCandidate;
import com.shipflow.carrier.port.CarrierPortRegistry;
import com.shipflow.carrier.resilience.CircuitBreakerRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Built-in hard constraints. Each is an independent bean; add a new one by adding a CandidateFilter bean. */
public final class Filters {
    private Filters() {
    }

    @Component
    @Order(10)
    public static class ExcludedCarrierFilter implements CandidateFilter {
        public String name() {
            return "excluded";
        }

        public Optional<String> reject(CarrierCandidate c, AllocationContext ctx) {
            return ctx.excludedCarrierIds().contains(c.carrier().id())
                    ? Optional.of("excluded after failed booking attempt") : Optional.empty();
        }
    }

    @Component
    @Order(20)
    public static class ActiveIntegrationFilter implements CandidateFilter {
        private final CarrierPortRegistry ports;

        public ActiveIntegrationFilter(CarrierPortRegistry ports) {
            this.ports = ports;
        }

        public String name() {
            return "integration";
        }

        public Optional<String> reject(CarrierCandidate c, AllocationContext ctx) {
            if (!c.carrier().active()) {
                return Optional.of("carrier inactive");
            }
            return ports.supports(c.carrier().integration()) ? Optional.empty()
                    : Optional.of("no adapter for integration " + c.carrier().integration());
        }
    }

    @Component
    @Order(30)
    public static class CodSupportFilter implements CandidateFilter {
        public String name() {
            return "cod";
        }

        public Optional<String> reject(CarrierCandidate c, AllocationContext ctx) {
            return ctx.isCod() && !c.codSupported() ? Optional.of("COD not supported in zone") : Optional.empty();
        }
    }

    @Component
    @Order(40)
    public static class WeightLimitFilter implements CandidateFilter {
        public String name() {
            return "weight";
        }

        public Optional<String> reject(CarrierCandidate c, AllocationContext ctx) {
            return ctx.weightGrams() > c.rateCard().maxWeightGrams()
                    ? Optional.of("weight " + ctx.weightGrams() + " g > max " + c.rateCard().maxWeightGrams() + " g")
                    : Optional.empty();
        }
    }

    @Component
    @Order(50)
    public static class CircuitBreakerFilter implements CandidateFilter {
        private final CircuitBreakerRegistry breakers;

        public CircuitBreakerFilter(CircuitBreakerRegistry breakers) {
            this.breakers = breakers;
        }

        public String name() {
            return "circuit-breaker";
        }

        public Optional<String> reject(CarrierCandidate c, AllocationContext ctx) {
            return breakers.isCallPermitted(c.carrier().code()) ? Optional.empty()
                    : Optional.of("carrier API circuit open (outage)");
        }
    }

    @Component
    @Order(60)
    public static class CapacityFilter implements CandidateFilter {
        public String name() {
            return "capacity";
        }

        public Optional<String> reject(CarrierCandidate c, AllocationContext ctx) {
            return c.remainingCapacity() > 0 ? Optional.empty()
                    : Optional.of("no capacity left on " + ctx.serviceDate() + " (" + c.reserved() + "/" + c.capacity() + ")");
        }
    }
}
