package com.shipflow.shipment.application;

import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStatus;
import com.shipflow.shipment.persistence.ShipmentHistoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Applies one state-machine step: validate, mutate, audit, notify listeners. Always inside a caller's tx. */
@Component
public class ShipmentLifecycle {
    private final ShipmentHistoryRepository history;
    private final List<ShipmentTransitionListener> listeners;
    private final Clock clock;

    public ShipmentLifecycle(ShipmentHistoryRepository history, List<ShipmentTransitionListener> listeners, Clock clock) {
        this.history = history;
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(Shipment shipment, ShipmentStatus to, TransitionCause cause) {
        Instant now = clock.instant();
        ShipmentStatus from = shipment.moveTo(to, cause.scanOccurredAt(), now);
        Instant occurredAt = cause.scanOccurredAt() != null ? cause.scanOccurredAt() : now;
        history.append(shipment.getId(), from.name(), to.name(), cause.reason(), cause.actor(), cause.inferred(),
                cause.location(), occurredAt);
        TransitionEvent event = new TransitionEvent(shipment, from, to, occurredAt, cause);
        for (ShipmentTransitionListener l : listeners) {
            l.onTransition(event);
        }
    }

    /** Audit note for changes that are not status transitions (e.g. carrier failover while ALLOCATED). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void note(Shipment shipment, String actor, String reason) {
        history.append(shipment.getId(), shipment.getStatus().name(), shipment.getStatus().name(), reason, actor,
                false, null, clock.instant());
    }

    /** Initial CREATED entry. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Shipment shipment, String actor) {
        history.append(shipment.getId(), null, ShipmentStatus.CREATED.name(), "booked via " + shipment.getSource(),
                actor, false, null, shipment.getCreatedAt());
    }
}
