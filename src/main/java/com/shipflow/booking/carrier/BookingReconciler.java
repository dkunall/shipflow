package com.shipflow.booking.carrier;

import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.resilience.CarrierGateway;
import com.shipflow.config.ShipFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves uncertain carrier bookings (timeouts, crashes mid-call) by asking the carrier what happened for the
 * stable booking_ref, and re-drives ALLOCATED shipments that never got a booking attempt.
 */
@Component
public class BookingReconciler {
    private static final Logger log = LoggerFactory.getLogger(BookingReconciler.class);
    private static final String ACTOR = "system:booking-reconciler";

    private final CarrierBookingRepository bookings;
    private final CarrierDirectory carriers;
    private final CarrierGateway gateway;
    private final BookingOrchestrator orchestrator;
    private final ShipFlowProperties.Booking props;
    private final Clock clock;

    public BookingReconciler(CarrierBookingRepository bookings, CarrierDirectory carriers, CarrierGateway gateway,
                             BookingOrchestrator orchestrator, ShipFlowProperties props, Clock clock) {
        this.bookings = bookings;
        this.carriers = carriers;
        this.gateway = gateway;
        this.orchestrator = orchestrator;
        this.props = props.booking();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shipflow.booking.reconcile-interval}")
    public void scheduled() {
        try {
            runOnce(props.reconcileAfter());
        } catch (RuntimeException e) {
            log.warn("booking reconciliation failed: {}", e.toString());
        }
    }

    public record Report(int confirmed, int failedOver, int stillUnknown, int redriven) {
    }

    public Report runOnce(java.time.Duration minAge) {
        Instant now = clock.instant();
        int confirmed = 0, failed = 0, unknown = 0, redriven = 0;
        for (var b : bookings.uncertain(now.minus(minAge), now, props.batchSize())) {
            var carrier = carriers.require(b.carrierId());
            Optional<Optional<String>> answer = gateway.lookup(carrier, b.bookingRef());
            if (answer.isEmpty()) {
                unknown++;
                continue;
            }
            if (answer.get().isPresent()) {
                orchestrator.resolveAsBooked(b, answer.get().get());
                confirmed++;
            } else {
                orchestrator.resolveAsFailed(b, "carrier has no booking for " + b.bookingRef(), ACTOR);
                failed++;
            }
        }
        for (UUID id : bookings.strandedAllocated(now.minus(minAge), props.batchSize())) {
            orchestrator.book(id, ACTOR);
            redriven++;
        }
        if (confirmed + failed + redriven > 0) {
            log.info("booking reconciliation confirmed={} failedOver={} unknown={} redriven={}", confirmed, failed,
                    unknown, redriven);
        }
        return new Report(confirmed, failed, unknown, redriven);
    }
}
