package com.shipflow.scan;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** One tracking event from a rider app / carrier webhook. Exactly one of trackingNumber / awb identifies it. */
public record ScanCommand(
        @NotBlank @Size(max = 32) String carrierCode,
        @NotBlank @Size(max = 100) String carrierEventId,
        @Size(max = 20) String trackingNumber,
        @Size(max = 64) String awb,
        @NotBlank String eventCode,
        @NotNull Instant occurredAt,
        @Size(max = 200) String location,
        @Size(max = 32) String ndrReason) {

    @AssertTrue(message = "exactly one of trackingNumber or awb is required")
    public boolean isIdentified() {
        return (trackingNumber == null) != (awb == null);
    }
}
