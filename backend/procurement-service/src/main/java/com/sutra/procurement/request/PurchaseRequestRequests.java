package com.sutra.procurement.request;

import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class PurchaseRequestRequests {

    private PurchaseRequestRequests() {
    }

    public record PurchaseRequestRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotNull Long entityId,
            Long subEntityId,
            Long customerId,
            @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency,
            LocalDate requiredBy) {
    }

    public record RequestItemRequest(
            @Size(max = 40) String itemCode,
            @NotBlank @Size(max = 500) String description,
            @NotNull @DecimalMin(value = "0.001") @DecimalMax("999999999999") @Digits(integer = 12, fraction = 3)
            BigDecimal quantity,
            @NotBlank @Size(max = 30) String unitOfMeasure,
            @Size(max = 4000) String specifications) {
    }

    public record StatusChangeRequest(@NotNull PurchaseRequestStatus status) {
    }
}
