package com.shipflow.allocation.application;

import com.shipflow.common.error.TransientConflictException;

/** Reserve-last allocation picked a carrier whose capacity was taken by a concurrent commit; retry the tx. */
public class CapacityRaceLostException extends TransientConflictException {
    public CapacityRaceLostException(String carrierCode) {
        super("capacity of " + carrierCode + " was taken concurrently");
    }
}
