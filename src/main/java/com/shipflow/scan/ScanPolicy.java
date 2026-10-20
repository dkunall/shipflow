package com.shipflow.scan;

import com.shipflow.shipment.domain.ShipmentStatus;
import com.shipflow.shipment.domain.ShipmentStateMachine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Pure decision for an incoming scan (out-of-order safe, never regresses):
 * <ol>
 *   <li>scan from a carrier that is not assigned -> REJECTED</li>
 *   <li>occurredAt earlier than the event that produced the current status -> OUT_OF_ORDER</li>
 *   <li>target == current, or target ranks below current -> STALE</li>
 *   <li>no legal path (state machine) -> REJECTED</li>
 *   <li>otherwise APPLIED with the path (intermediate steps are inferred)</li>
 * </ol>
 */
public final class ScanPolicy {
    public record Decision(ScanOutcome outcome, List<ShipmentStatus> path, String detail) {
    }

    private ScanPolicy() {
    }

    public static Decision decide(ShipmentStatus current, Instant currentEventAt, Long assignedCarrierId,
                                  long scanCarrierId, ShipmentStatus target, Instant occurredAt) {
        if (assignedCarrierId == null || !Objects.equals(assignedCarrierId, scanCarrierId)) {
            return new Decision(ScanOutcome.REJECTED, List.of(), "carrier is not assigned to this shipment");
        }
        if (currentEventAt != null && occurredAt.isBefore(currentEventAt)) {
            return new Decision(ScanOutcome.OUT_OF_ORDER, List.of(),
                    "occurred before current status event (" + currentEventAt + "); recorded only");
        }
        if (target == current) {
            return new Decision(ScanOutcome.STALE, List.of(), "already " + current);
        }
        var path = ShipmentStateMachine.scanPath(current, target);
        if (path.isEmpty()) {
            if (target.rank() < current.rank() || current.isTerminal()) {
                return new Decision(ScanOutcome.STALE, List.of(), target + " would regress from " + current);
            }
            return new Decision(ScanOutcome.REJECTED, List.of(), "no legal transition " + current + " -> " + target);
        }
        return new Decision(ScanOutcome.APPLIED, path.get(), path.get().size() > 1
                ? "applied with inferred steps " + path.get().subList(0, path.get().size() - 1) : "applied");
    }
}
