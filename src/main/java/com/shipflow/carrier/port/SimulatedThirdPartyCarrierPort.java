package com.shipflow.carrier.port;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Adapter for 3PLs whose integration is "SIMULATED_3PL" (all demo 3PLs). A real 3PL = a sibling class. */
@Component
public class SimulatedThirdPartyCarrierPort implements CarrierPort {
    public static final String INTEGRATION = "SIMULATED_3PL";
    private final SimulatedCarrierNetwork network;

    public SimulatedThirdPartyCarrierPort(SimulatedCarrierNetwork network) {
        this.network = network;
    }

    @Override
    public String integration() {
        return INTEGRATION;
    }

    @Override
    public BookingResult book(BookingRequest request) throws Exception {
        return network.book(request);
    }

    @Override
    public Optional<BookingResult> lookup(String carrierCode, String bookingRef) {
        return network.lookup(bookingRef);
    }

    @Override
    public void cancel(String carrierCode, String bookingRef) {
        network.cancel(bookingRef);
    }
}
