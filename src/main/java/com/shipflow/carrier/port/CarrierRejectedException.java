package com.shipflow.carrier.port;

/** Definitive business rejection by the carrier (not a technical failure). */
public class CarrierRejectedException extends RuntimeException {
    public CarrierRejectedException(String message) {
        super(message);
    }
}
