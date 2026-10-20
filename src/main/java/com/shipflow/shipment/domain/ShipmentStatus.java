package com.shipflow.shipment.domain;

/**
 * Shipment lifecycle states. {@code rank} defines the monotonic progression used for out-of-order scan handling:
 * every transition goes to a state of equal or higher rank (equal rank = legitimate loops such as hub hops
 * and delivery re-attempts), so a late event can never move a shipment "backwards".
 */
public enum ShipmentStatus {
    CREATED(0, false),
    ALLOCATED(10, false),
    PICKED_UP(20, false),
    IN_TRANSIT(30, false),
    AT_HUB(30, false),
    OUT_FOR_DELIVERY(40, false),
    DELIVERY_FAILED(40, false),
    RTO_INITIATED(60, false),
    RTO_IN_TRANSIT(70, false),
    DELIVERED(100, true),
    RTO_DELIVERED(100, true),
    CANCELLED(100, true);

    private final int rank;
    private final boolean terminal;

    ShipmentStatus(int rank, boolean terminal) {
        this.rank = rank;
        this.terminal = terminal;
    }

    public int rank() {
        return rank;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
