package com.shipflow.shipment.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ShipmentView(UUID id, String trackingNumber, String merchantOrderRef, UUID merchantId, String status,
                           String source, CarrierRef carrier, String awb, Amount shippingCost, String paymentMode,
                           Amount codAmount, Amount declaredValue, int weightGrams, Destination destination,
                           LocalDate dispatchDate, LocalDate promisedDate, JsonNode edd, Risk risk, int attemptCount,
                           String lastNdrReason, Instant deliveredAt, Instant createdAt, Instant updatedAt,
                           long version) {

    public record CarrierRef(String code, String name, String kind) {
    }

    public record Amount(BigDecimal amount, String currency) {
    }

    public record Destination(String pincode, String zone, String city, String countryCode) {
    }

    public record Risk(int score, String band, JsonNode assessment) {
    }
}
