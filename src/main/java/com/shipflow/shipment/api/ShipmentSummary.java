package com.shipflow.shipment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Row of the merchant shipment list (one SQL query with a join; no per-row lookups). */
public record ShipmentSummary(UUID id, String trackingNumber, String merchantOrderRef, String status,
                              String carrierCode, String awb, LocalDate promisedDate, String paymentMode,
                              BigDecimal codAmount, String currency, String riskBand, int attemptCount,
                              Instant createdAt) {
}
