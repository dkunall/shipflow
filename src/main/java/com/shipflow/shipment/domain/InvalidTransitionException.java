package com.shipflow.shipment.domain;

import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;

import java.util.Map;

public class InvalidTransitionException extends ShipFlowException {
    public InvalidTransitionException(ShipmentStatus from, ShipmentStatus to) {
        super(ErrorCode.INVALID_STATE_TRANSITION, "Cannot move shipment from " + from + " to " + to,
                Map.of("from", from.name(), "to", to.name(),
                        "allowed", ShipmentStateMachine.allowedFrom(from).stream().map(Enum::name).sorted().toList()));
    }
}
