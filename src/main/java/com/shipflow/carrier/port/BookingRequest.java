package com.shipflow.carrier.port;

import java.math.BigDecimal;

public record BookingRequest(String carrierCode, String bookingRef, String trackingNumber, String originHubCode,
                             String destinationPincode, int weightGrams, boolean cod, BigDecimal codAmount,
                             String currency) {
}
