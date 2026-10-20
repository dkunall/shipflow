package com.shipflow.shipment.application;

import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStatus;

import java.time.Instant;

/** Published synchronously (same transaction) to every {@link ShipmentTransitionListener}. */
public record TransitionEvent(Shipment shipment, ShipmentStatus from, ShipmentStatus to, Instant occurredAt,
                              TransitionCause cause) {
}
