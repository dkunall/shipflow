package com.shipflow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Business metrics in one place (names are part of the ops contract; see README). */
@Component
public class ShipFlowMetrics {
    private final MeterRegistry registry;

    public ShipFlowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void shipmentBooked(String source) {
        counter("shipflow.shipments.booked", "source", source);
    }

    public void allocation(String result, String carrier) {
        counter("shipflow.allocations", "result", result, "carrier", carrier);
    }

    public void allocationLatency(Duration d) {
        Timer.builder("shipflow.allocation.latency").publishPercentileHistogram().register(registry).record(d);
    }

    public void failover(String fromCarrier, String reason) {
        counter("shipflow.allocation.failovers", "from", fromCarrier, "reason", reason);
    }

    public void carrierBooking(String carrier, String outcome) {
        counter("shipflow.carrier.bookings", "carrier", carrier, "outcome", outcome);
    }

    public void scan(String outcome, String source) {
        counter("shipflow.scans", "outcome", outcome, "source", source);
    }

    public void ndr(String reason, String action) {
        counter("shipflow.ndr", "reason", reason, "action", action);
    }

    public void webhook(String result) {
        counter("shipflow.webhook.deliveries", "result", result);
    }

    public void codReconciliation(String outcome, int count) {
        Counter.builder("shipflow.cod.reconciliation.results").tag("outcome", outcome).register(registry).increment(count);
    }

    public void idempotentReplay(String operation) {
        counter("shipflow.idempotency.replays", "operation", operation);
    }

    public MeterRegistry registry() {
        return registry;
    }

    private void counter(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }
}
