package com.shipflow.shipment.application;

import java.time.Instant;

/**
 * Why a transition happens. {@code scanOccurredAt} is set only for scan-driven transitions (it advances the
 * shipment's event clock used for out-of-order detection); system/command transitions leave it null.
 */
public record TransitionCause(String actor, String reason, Instant scanOccurredAt, boolean inferred, String location,
                              String ndrReason) {

    public static TransitionCause system(String actor, String reason) {
        return new TransitionCause(actor, reason, null, false, null, null);
    }

    public static TransitionCause scan(String actor, Instant occurredAt, boolean inferred, String location,
                                       String ndrReason) {
        return new TransitionCause(actor, inferred ? "inferred from later scan" : "scan", occurredAt, inferred,
                location, ndrReason);
    }
}
