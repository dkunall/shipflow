package com.shipflow.routing.domain;

public record RiderSlot(String riderId, int capacity) {
    public RiderSlot {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
    }
}
