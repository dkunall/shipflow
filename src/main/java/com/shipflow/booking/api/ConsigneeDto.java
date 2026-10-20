package com.shipflow.booking.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConsigneeDto(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "must be 8-15 digits, optional leading +") String phone,
        @NotBlank @Size(max = 300) String addressLine1,
        @Size(max = 300) String addressLine2,
        @Size(max = 200) String landmark,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 16) String pincode,
        @DecimalMin("-90") @DecimalMax("90") Double lat,
        @DecimalMin("-180") @DecimalMax("180") Double lng) {
}
