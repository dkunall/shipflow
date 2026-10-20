package com.shipflow.booking.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.booking.api.CreateShipmentRequest;

/**
 * E-store integration contract (Strategy): translate a platform's order payload into ShipFlow's booking
 * request. Adding Magento/WooCommerce/Salla = one new bean; the registry and endpoint stay unchanged.
 */
public interface OrderAdapter {
    /** path segment, e.g. "shopify" in POST /api/v1/integrations/shopify/orders */
    String source();

    CreateShipmentRequest toShipmentRequest(JsonNode payload, String originHubCode);

    /** natural idempotency key of the platform order (used when the caller sends no Idempotency-Key) */
    String externalOrderId(JsonNode payload);
}
