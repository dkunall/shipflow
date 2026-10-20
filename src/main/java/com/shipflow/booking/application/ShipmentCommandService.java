package com.shipflow.booking.application;

import com.shipflow.shipment.application.ShipmentLifecycle;
import com.shipflow.shipment.application.TransitionCause;

import com.shipflow.allocation.application.AllocationService;
import com.shipflow.booking.carrier.BookingOrchestrator;
import com.shipflow.booking.carrier.CarrierBookingRepository;
import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.resilience.CarrierGateway;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.security.Actor;
import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStatus;
import com.shipflow.shipment.persistence.ShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Shipment commands other than booking/scans: cancel, manual re-allocation, manual RTO. */
@Service
public class ShipmentCommandService {
    private final ShipmentRepository shipments;
    private final ShipmentLifecycle lifecycle;
    private final AllocationService allocation;
    private final CarrierBookingRepository bookings;
    private final CarrierDirectory carriers;
    private final CarrierGateway gateway;
    private final BookingOrchestrator orchestrator;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ShipmentCommandService(ShipmentRepository shipments, ShipmentLifecycle lifecycle,
                                  AllocationService allocation, CarrierBookingRepository bookings,
                                  CarrierDirectory carriers, CarrierGateway gateway, BookingOrchestrator orchestrator,
                                  PlatformTransactionManager txm, Clock clock) {
        this.shipments = shipments;
        this.lifecycle = lifecycle;
        this.allocation = allocation;
        this.bookings = bookings;
        this.carriers = carriers;
        this.gateway = gateway;
        this.orchestrator = orchestrator;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    public Shipment load(Actor actor, UUID id) {
        Shipment s = shipments.findById(id).orElseThrow(() -> ShipFlowException.notFound("shipment", id));
        actor.assertCanAccessMerchant(s.getMerchantId());
        return s;
    }

    /** CREATED/ALLOCATED -> CANCELLED, capacity released, carrier told after commit (best effort). */
    public Shipment cancel(Actor actor, UUID id, String reason) {
        record Outcome(Shipment s, Optional<CarrierBookingRepository.Booking> booking) {
        }
        Outcome o = tx.execute(st -> {
            Shipment s = load(actor, id);
            lifecycle.transition(s, ShipmentStatus.CANCELLED, TransitionCause.system(actor.subject(),
                    reason == null ? "cancelled" : "cancelled: " + reason));
            allocation.releaseReservation(s, "shipment cancelled");
            var booking = bookings.cancelActive(s.getId(), clock.instant());
            shipments.flush();
            return new Outcome(s, booking);
        });
        o.booking().ifPresent(b -> gateway.cancelQuietly(carriers.require(b.carrierId()), b.bookingRef()));
        return o.s();
    }

    /**
     * Ops: re-run allocation. CREATED (e.g. no capacity earlier) or ALLOCATED-but-not-picked-up (e.g. carrier
     * strike): the current reservation is released, the active carrier booking cancelled (carrier told after commit)
     * and the shipment re-allocated excluding the given carriers plus any whose booking already failed/was cancelled.
     */
    public Shipment reallocate(Actor actor, UUID id, Set<String> excludeCarrierCodes, String reason) {
        record Outcome(Optional<CarrierBookingRepository.Booking> cancelled) {
        }
        Outcome o = tx.execute(st -> {
            Shipment s = load(actor, id);
            if (s.getStatus() != ShipmentStatus.CREATED && s.getStatus() != ShipmentStatus.ALLOCATED) {
                throw new ShipFlowException(ErrorCode.INVALID_STATE_TRANSITION,
                        "only CREATED or ALLOCATED shipments can be re-allocated (is " + s.getStatus() + ")");
            }
            Set<Long> excluded = new HashSet<>();
            for (String code : excludeCarrierCodes) {
                excluded.add(carriers.requireCode(code).id());
            }
            Optional<CarrierBookingRepository.Booking> cancelled = Optional.empty();
            if (s.getStatus() == ShipmentStatus.ALLOCATED) {
                allocation.releaseReservation(s, "manual re-allocation" + (reason == null ? "" : ": " + reason));
                cancelled = bookings.cancelActive(s.getId(), clock.instant());
            }
            excluded.addAll(bookings.failedCarriers(s.getId()));
            allocation.allocate(s, excluded, actor.subject(),
                    "MANUAL_REALLOCATION" + (reason == null ? "" : " (" + reason + ")"));
            shipments.flush();
            return new Outcome(cancelled);
        });
        o.cancelled().ifPresent(b -> gateway.cancelQuietly(carriers.require(b.carrierId()), b.bookingRef()));
        orchestrator.book(id, actor.subject());
        return shipments.findById(id).orElseThrow();
    }

    /** Ops: force return-to-origin for a failed delivery. */
    public Shipment initiateRto(Actor actor, UUID id, String reason) {
        return tx.execute(st -> {
            Shipment s = load(actor, id);
            lifecycle.transition(s, ShipmentStatus.RTO_INITIATED, TransitionCause.system(actor.subject(),
                    "manual RTO" + (reason == null ? "" : ": " + reason)));
            shipments.flush();
            return s;
        });
    }
}
