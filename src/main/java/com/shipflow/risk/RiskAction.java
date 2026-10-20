package com.shipflow.risk;

public enum RiskAction {
    /** rider must collect an OTP from the consignee before marking delivered */
    REQUIRE_DELIVERY_OTP,
    /** merchant is notified to nudge the customer to pay online instead of COD */
    PREPAID_CONVERSION_NUDGE,
    /** call the customer to confirm the address before dispatch */
    ADDRESS_VERIFICATION_CALL
}
