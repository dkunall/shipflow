package com.shipflow.carrier.port;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Own fleet: booking is an in-house operation; the AWB is derived from the booking ref (idempotent by design). */
@Component
public class OwnFleetCarrierPort implements CarrierPort {
    public static final String INTEGRATION = "OWN_FLEET";

    @Override
    public String integration() {
        return INTEGRATION;
    }

    @Override
    public BookingResult book(BookingRequest request) {
        return new BookingResult("OF-" + request.trackingNumber());
    }

    @Override
    public Optional<BookingResult> lookup(String carrierCode, String bookingRef) {
        return Optional.empty();
    }

    @Override
    public void cancel(String carrierCode, String bookingRef) {
        // nothing external to cancel
    }
}
