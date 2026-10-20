package com.shipflow.booking.application;

import com.shipflow.booking.api.CreateShipmentRequest;
import com.shipflow.allocation.application.AllocationService;
import com.shipflow.booking.carrier.BookingOrchestrator;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.idempotency.IdempotencyService;
import com.shipflow.idempotency.RequestHasher;
import com.shipflow.security.Actor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Use-case entry for idempotent booking: [idempotency claim] -> tx(create + allocate + idempotency COMPLETE)
 * -> carrier booking with failover (outside any tx). Replays skip straight to reading the current state.
 */
@Service
public class ShipmentBookingFacade {
    public static final String OPERATION = "create-shipment";

    public record Result(UUID shipmentId, boolean replayed) {
    }

    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final ShipmentBookingService booking;
    private final BookingOrchestrator orchestrator;
    private final ShipFlowProperties.Allocation allocation;

    public ShipmentBookingFacade(IdempotencyService idempotency, RequestHasher hasher, ShipmentBookingService booking,
                                 BookingOrchestrator orchestrator, ShipFlowProperties props) {
        this.allocation = props.allocation();
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.booking = booking;
        this.orchestrator = orchestrator;
    }

    public Result create(Actor actor, UUID merchantId, String idempotencyKey, CreateShipmentRequest request,
                         String source, Object rawPayloadForHash) {
        var req = new IdempotencyService.Request(merchantId.toString(), idempotencyKey, OPERATION + ":" + source,
                hasher.hash(rawPayloadForHash == null ? request : rawPayloadForHash));
        int attempts = allocation.reserveLast() ? Math.max(1, allocation.optimisticAttempts()) + 1 : 1;
        var outcome = idempotency.execute(req, attempts, attempt -> new IdempotencyService.Created(
                booking.createAndAllocate(merchantId, request, source, actor.subject(),
                        attempt < attempts ? AllocationService.Reservation.LAST : AllocationService.Reservation.FIRST)
                        .shipmentId(), 201));
        if (!outcome.replayed()) {
            orchestrator.book(outcome.resourceId(), actor.subject());
        }
        return new Result(outcome.resourceId(), outcome.replayed());
    }
}
