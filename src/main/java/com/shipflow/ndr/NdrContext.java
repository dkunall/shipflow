package com.shipflow.ndr;

/** Inputs to re-attempt policies for one failed delivery attempt. */
public record NdrContext(int attemptNo, NdrReason reason, boolean cod, String riskBand) {
}
