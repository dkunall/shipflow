package com.shipflow.shipment.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.shipflow.shipment.domain.ShipmentStatus.*;

/**
 * Explicit transition table (the single source of truth for what may follow what).
 * <p>Two kinds of use:
 * <ul>
 *   <li>{@link #canTransition} - strict single-step check used by every state change (commands and scans);</li>
 *   <li>{@link #scanPath} - carriers routinely skip scans (no IN_TRANSIT before AT_HUB, no OUT_FOR_DELIVERY
 *       before DELIVERED). For scan-driven changes we search the shortest path of explicit edges, so the skipped
 *       intermediate states are recorded as <em>inferred</em> history entries instead of rejecting real events.
 *       Inference never passes through system-only states (ALLOCATED, CREATED, CANCELLED) nor through
 *       DELIVERY_FAILED (an NDR needs a real reason code) nor through terminal states.</li>
 * </ul>
 */
public final class ShipmentStateMachine {

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> TRANSITIONS = new EnumMap<>(ShipmentStatus.class);
    /** states that only system commands (booking, allocation, cancel) can enter; never targets of a scan */
    private static final Set<ShipmentStatus> SYSTEM_ONLY = EnumSet.of(CREATED, ALLOCATED, CANCELLED);
    private static final Set<ShipmentStatus> NEVER_INFERRED = EnumSet.of(DELIVERY_FAILED, CREATED, ALLOCATED, CANCELLED,
            DELIVERED, RTO_DELIVERED);

    static {
        TRANSITIONS.put(CREATED, EnumSet.of(ALLOCATED, CANCELLED));
        // ALLOCATED -> CREATED: carrier released (all carriers failed / ops de-allocation); system only
        TRANSITIONS.put(ALLOCATED, EnumSet.of(PICKED_UP, CANCELLED, CREATED));
        TRANSITIONS.put(PICKED_UP, EnumSet.of(IN_TRANSIT, AT_HUB, OUT_FOR_DELIVERY));
        TRANSITIONS.put(IN_TRANSIT, EnumSet.of(AT_HUB, OUT_FOR_DELIVERY));
        TRANSITIONS.put(AT_HUB, EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY));
        TRANSITIONS.put(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, DELIVERY_FAILED));
        TRANSITIONS.put(DELIVERY_FAILED, EnumSet.of(OUT_FOR_DELIVERY, RTO_INITIATED));
        TRANSITIONS.put(RTO_INITIATED, EnumSet.of(RTO_IN_TRANSIT));
        TRANSITIONS.put(RTO_IN_TRANSIT, EnumSet.of(RTO_DELIVERED));
        TRANSITIONS.put(DELIVERED, EnumSet.noneOf(ShipmentStatus.class));
        TRANSITIONS.put(RTO_DELIVERED, EnumSet.noneOf(ShipmentStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(ShipmentStatus.class));
    }

    private ShipmentStateMachine() {
    }

    public static boolean canTransition(ShipmentStatus from, ShipmentStatus to) {
        return TRANSITIONS.get(from).contains(to);
    }

    public static Set<ShipmentStatus> allowedFrom(ShipmentStatus from) {
        return Collections.unmodifiableSet(TRANSITIONS.get(from));
    }

    public static boolean isScanTarget(ShipmentStatus s) {
        return !SYSTEM_ONLY.contains(s);
    }

    /**
     * Shortest sequence of explicit edges from {@code from} to {@code to} (excluding {@code from}, ending with
     * {@code to}) usable by a scan; empty if the scan cannot legally move the shipment there.
     */
    public static Optional<List<ShipmentStatus>> scanPath(ShipmentStatus from, ShipmentStatus to) {
        if (from == to || !isScanTarget(to) || from.isTerminal()) {
            return Optional.empty();
        }
        Map<ShipmentStatus, ShipmentStatus> parent = new EnumMap<>(ShipmentStatus.class);
        Deque<ShipmentStatus> queue = new ArrayDeque<>();
        queue.add(from);
        parent.put(from, from);
        while (!queue.isEmpty()) {
            ShipmentStatus cur = queue.poll();
            for (ShipmentStatus next : TRANSITIONS.get(cur)) {
                if (parent.containsKey(next) || SYSTEM_ONLY.contains(next) || next.rank() < cur.rank()) {
                    continue;
                }
                parent.put(next, cur);
                if (next == to) {
                    List<ShipmentStatus> path = new ArrayList<>();
                    for (ShipmentStatus s = to; s != from; s = parent.get(s)) {
                        path.add(s);
                    }
                    Collections.reverse(path);
                    return Optional.of(List.copyOf(path));
                }
                if (!NEVER_INFERRED.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return Optional.empty();
    }
}
