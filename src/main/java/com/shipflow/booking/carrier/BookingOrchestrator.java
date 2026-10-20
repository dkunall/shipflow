package com.shipflow.booking.carrier;

import com.shipflow.allocation.application.AllocationService;
import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.carrier.port.BookingRequest;
import com.shipflow.carrier.resilience.CarrierGateway;
import com.shipflow.carrier.resilience.CarrierGateway.CallOutcome;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.network.NetworkDirectory;
import com.shipflow.observability.ShipFlowMetrics;
import com.shipflow.shipment.application.ShipmentLifecycle;
import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStatus;
import com.shipflow.shipment.persistence.ShipmentRepository;
import com.shipflow.webhook.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Books the allocated carrier and fails over to the next-best one when the carrier is down.
 * <pre>
 *   tx1: claim booking row (stable booking_ref, lease)            -- short, no external call
 *   ---: CarrierGateway.book (circuit breaker + timeout)           -- NO transaction / locks held
 *   tx2: Booked    -> booking CONFIRMED, shipment AWB
 *        Failed / CircuitOpen -> booking FAILED, release capacity, re-allocate excluding failed carriers,
 *                     loop (bounded by max-failovers)
 *        Uncertain (timeout) -> booking UNKNOWN; NO failover (the carrier may have booked it: failing over
 *                     could double-book). {@link BookingReconciler} resolves it via the carrier's lookup API.
 * </pre>
 */
@Service
public class BookingOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(BookingOrchestrator.class);

    public enum Result { CONFIRMED, UNCERTAIN, UNALLOCATED, NOTHING_TO_DO }

    record Prepared(long bookingId, UUID shipmentId, CarrierInfo carrier, BookingRequest request) {
    }

    private final ShipmentRepository shipments;
    private final CarrierBookingRepository bookings;
    private final CarrierDirectory carriers;
    private final CarrierGateway gateway;
    private final AllocationService allocation;
    private final ShipmentLifecycle lifecycle;
    private final NetworkDirectory network;
    private final OutboxWriter outbox;
    private final ShipFlowMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ShipFlowProperties props;

    public BookingOrchestrator(ShipmentRepository shipments, CarrierBookingRepository bookings,
                               CarrierDirectory carriers, CarrierGateway gateway, AllocationService allocation,
                               ShipmentLifecycle lifecycle, NetworkDirectory network, OutboxWriter outbox,
                               ShipFlowMetrics metrics, PlatformTransactionManager txm, Clock clock,
                               ShipFlowProperties props) {
        this.shipments = shipments;
        this.bookings = bookings;
        this.carriers = carriers;
        this.gateway = gateway;
        this.allocation = allocation;
        this.lifecycle = lifecycle;
        this.network = network;
        this.outbox = outbox;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.props = props;
    }

    public static String bookingRef(UUID shipmentId, String carrierCode) {
        return "SF-" + shipmentId + "-" + carrierCode;
    }

    public Result book(UUID shipmentId, String actor) {
        int maxHops = props.allocation().maxFailovers();
        for (int hop = 0; hop <= maxHops; hop++) {
            Prepared p = tx.execute(s -> prepare(shipmentId));
            if (p == null) {
                return Result.NOTHING_TO_DO;
            }
            CallOutcome outcome = gateway.book(p.carrier(), p.request());
            metrics.carrierBooking(p.carrier().code(), outcome.getClass().getSimpleName());
            if (outcome instanceof CallOutcome.Booked b) {
                return confirm(p, b.awb()) ? Result.CONFIRMED : Result.NOTHING_TO_DO;
            }
            if (outcome instanceof CallOutcome.Uncertain u) {
                tx.executeWithoutResult(s -> bookings.markUnknown(p.bookingId(), u.reason(), clock.instant()));
                log.warn("carrier booking outcome uncertain shipment={} carrier={} reason={}", shipmentId,
                        p.carrier().code(), u.reason());
                return Result.UNCERTAIN;
            }
            String reason = outcome instanceof CallOutcome.Failed f ? (f.businessRejection() ? "rejected: " : "error: ")
                    + f.reason() : "circuit open";
            Boolean reallocated = tx.execute(s -> failover(p, reason, actor));
            if (!Boolean.TRUE.equals(reallocated)) {
                return Result.UNALLOCATED;
            }
        }
        log.warn("max failovers reached shipment={}", shipmentId);
        return Result.UNCERTAIN;
    }

    private Prepared prepare(UUID shipmentId) {
        Shipment s = shipments.findById(shipmentId).orElse(null);
        if (s == null || s.getStatus() != ShipmentStatus.ALLOCATED || s.getCarrierId() == null
                || s.getCarrierAwb() != null) {
            return null;
        }
        CarrierInfo carrier = carriers.require(s.getCarrierId());
        Instant now = clock.instant();
        String ref = bookingRef(s.getId(), carrier.code());
        Optional<Long> claimed = bookings.claim(s.getId(), carrier.id(), ref, now, now.plus(props.booking().lease()));
        if (claimed.isEmpty()) {
            return null;
        }
        String originHub = network.hub(s.getOriginHubId()).map(h -> h.code()).orElse("?");
        return new Prepared(claimed.get(), s.getId(), carrier, new BookingRequest(carrier.code(), ref,
                s.getTrackingNumber(), originHub, s.getDestinationPincode(), s.getWeightG(), s.isCod(),
                s.getCodAmount(), s.getCurrency()));
    }

    /** @return true if the shipment now carries this booking */
    boolean confirm(Prepared p, String awb) {
        Boolean stillWanted = tx.execute(st -> {
            Shipment s = shipments.findById(p.shipmentId()).orElseThrow();
            boolean wanted = s.getStatus() == ShipmentStatus.ALLOCATED && s.getCarrierId() != null
                    && s.getCarrierId() == p.carrier().id();
            if (!wanted) {
                bookings.fail(p.bookingId(), "booked after shipment moved on (" + s.getStatus() + ")", clock.instant());
                return false;
            }
            bookings.confirm(p.bookingId(), awb, clock.instant());
            s.confirmCarrierBooking(awb, clock.instant());
            outbox.write(s.getMerchantId(), s.getId(), "shipment.carrier_booked",
                    Map.of("shipmentId", s.getId().toString(), "trackingNumber", s.getTrackingNumber(),
                            "carrier", p.carrier().code(), "awb", awb));
            return true;
        });
        if (!Boolean.TRUE.equals(stillWanted)) {
            gateway.cancelQuietly(p.carrier(), p.request().bookingRef()); // undo the orphan consignment
            return false;
        }
        return true;
    }

    /** tx: mark failed, release capacity, re-allocate excluding all carriers that failed for this shipment. */
    Boolean failover(Prepared p, String reason, String actor) {
        Instant now = clock.instant();
        bookings.fail(p.bookingId(), reason, now);
        Shipment s = shipments.findById(p.shipmentId()).orElseThrow();
        if (s.getStatus() != ShipmentStatus.ALLOCATED || s.getCarrierId() == null || s.getCarrierId() != p.carrier().id()) {
            return false;
        }
        allocation.releaseReservation(s, "booking failed at " + p.carrier().code() + ": " + reason);
        metrics.failover(p.carrier().code(), reason.startsWith("circuit") ? "circuit_open" : "booking_failed");
        var result = allocation.allocate(s, bookings.failedCarriers(s.getId()), actor,
                "FAILOVER from " + p.carrier().code() + " (" + reason + ")");
        shipments.flush();
        return result.carrierId().isPresent();
    }

    /** Used by the reconciler when the carrier confirms an uncertain booking never happened. */
    public void resolveAsFailed(CarrierBookingRepository.Booking b, String reason, String actor) {
        CarrierInfo carrier = carriers.require(b.carrierId());
        Prepared p = new Prepared(b.id(), b.shipmentId(), carrier, null);
        Boolean reallocated = tx.execute(s -> failover(p, reason, actor));
        if (Boolean.TRUE.equals(reallocated)) {
            book(b.shipmentId(), actor);
        }
    }

    public boolean resolveAsBooked(CarrierBookingRepository.Booking b, String awb) {
        CarrierInfo carrier = carriers.require(b.carrierId());
        return confirm(new Prepared(b.id(), b.shipmentId(), carrier,
                new BookingRequest(carrier.code(), b.bookingRef(), null, null, null, 0, false, null, null)), awb);
    }
}
