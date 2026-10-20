package com.shipflow.ndr;

/** Standard non-delivery reason codes reported by riders / carriers. */
public enum NdrReason {
    CUSTOMER_UNAVAILABLE,
    CUSTOMER_PHONE_UNREACHABLE,
    ADDRESS_INCOMPLETE,
    ADDRESS_NOT_FOUND,
    RESCHEDULE_REQUESTED,
    COD_NOT_READY,
    PREMISES_CLOSED,
    CUSTOMER_REFUSED,
    OUT_OF_DELIVERY_AREA
}
