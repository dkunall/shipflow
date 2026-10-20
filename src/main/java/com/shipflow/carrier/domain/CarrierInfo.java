package com.shipflow.carrier.domain;

/** Immutable carrier master data (cached by {@link com.shipflow.carrier.CarrierDirectory}). */
public record CarrierInfo(long id, String code, String name, CarrierKind kind, String integration, boolean active,
                          String inboundWebhookSecret) {
}
