package com.shipflow.booking.api;

import com.shipflow.shipment.domain.PaymentMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateShipmentRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._:#/-]+$", message = "invalid characters")
        String merchantOrderRef,
        @NotBlank @Size(max = 32) String originHubCode,
        @NotNull @Valid ConsigneeDto consignee,
        @NotNull @Positive @Max(70_000) Integer weightGrams,
        @NotNull PaymentMode paymentMode,
        @Valid MoneyDto codAmount,
        @NotNull @Valid MoneyDto declaredValue) {

    @AssertTrue(message = "codAmount (> 0) is required for COD and must be absent for PREPAID")
    public boolean isCodConsistent() {
        if (paymentMode == null) {
            return true;
        }
        if (paymentMode == PaymentMode.COD) {
            return codAmount != null && codAmount.amount() != null && codAmount.amount().signum() > 0;
        }
        return codAmount == null;
    }

    @AssertTrue(message = "codAmount and declaredValue must use the same currency")
    public boolean isSingleCurrency() {
        return codAmount == null || declaredValue == null || codAmount.currency() == null
                || codAmount.currency().equals(declaredValue.currency());
    }
}
