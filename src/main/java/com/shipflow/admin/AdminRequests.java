package com.shipflow.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class AdminRequests {
    private AdminRequests() {
    }

    public record CreateMerchant(@NotBlank @Size(max = 200) String name,
                                 @NotNull @Pattern(regexp = "AE|SA") String countryCode,
                                 @Pattern(regexp = "^https?://.+") @Size(max = 500) String webhookUrl) {
    }

    public record CreateCarrier(@NotBlank @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String code,
                                @NotBlank @Size(max = 100) String name,
                                @NotNull @Pattern(regexp = "OWN_FLEET|THIRD_PARTY") String kind,
                                @NotBlank @Size(max = 32) String integration,
                                @NotEmpty List<@Valid Zone> zones) {
    }

    public record Zone(@NotBlank String zoneCode, @NotNull @Min(1) @Max(30) Integer slaDays,
                       @NotNull Boolean codSupported, @NotNull @Min(1) Integer maxWeightGrams,
                       @NotNull @Pattern(regexp = "AED|SAR") String currency,
                       @NotNull @DecimalMin("0") BigDecimal extraPer500g,
                       @NotNull @DecimalMin("0") BigDecimal codFeePercent,
                       @NotNull @DecimalMin("0") BigDecimal codFeeMin,
                       @NotNull @Min(0) Integer defaultDailyCapacity,
                       @NotEmpty List<@Valid Slab> slabs) {
    }

    public record Slab(@NotNull @Min(1) Integer upToGrams, @NotNull @DecimalMin("0") BigDecimal price) {
    }

    public record SetCapacity(@NotBlank String zoneCode, @NotNull LocalDate date, @NotNull @Min(0) Integer capacity) {
    }

    public record SimulateCarrier(@NotNull @Pattern(regexp = "OK|FAIL|FLAKY|REJECT|TIMEOUT_BOOKED|TIMEOUT_LOST") String mode,
                                  @Min(0) @Max(60_000) Integer latencyMs) {
    }
}
