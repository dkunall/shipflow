package com.shipflow.cod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A rider's / carrier's cash remittance statement. */
public record RemittanceRequest(
        @NotBlank String carrierCode,
        @NotBlank @Size(max = 100) String remittanceRef,
        @NotNull @Pattern(regexp = "AED|SAR") String currency,
        @NotNull Instant receivedAt,
        @NotEmpty @Size(max = 5000) List<@Valid Line> lines) {

    public record Line(@NotBlank @Size(max = 20) String trackingNumber,
                       @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }
}
