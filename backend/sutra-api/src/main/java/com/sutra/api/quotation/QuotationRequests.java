package com.sutra.api.quotation;

import com.sutra.api.enums.ApprovalDecision;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class QuotationRequests {

    private QuotationRequests() {
    }

    /** Manual correction of quotation header fields. Null fields are cleared, so send the full header. */
    public record QuotationUpdateRequest(
            @NotNull Long vendorId,
            @Size(max = 60) String quotationNumber,
            LocalDate quotationDate,
            LocalDate validUntil,
            @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal statedSubtotal,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal statedTaxTotal,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal statedGrandTotal,
            @Size(max = 4000) String deliveryTerms,
            @Size(max = 4000) String paymentTerms,
            @Size(max = 4000) String warrantyTerms,
            @Size(max = 4000) String notes) {

        @AssertTrue(message = "validUntil must not be before quotationDate")
        public boolean isValidityRangeValid() {
            return validUntil == null || quotationDate == null || !validUntil.isBefore(quotationDate);
        }
    }

    /** Add or correct a quotation line. Totals are always recalculated by the backend. */
    public record QuotationItemRequest(
            Long requestItemId,
            @Size(max = 40) String itemCode,
            @NotBlank @Size(max = 500) String description,
            @DecimalMin(value = "0.001") @Digits(integer = 12, fraction = 3) BigDecimal quantity,
            @Size(max = 30) String unitOfMeasure,
            @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal unitPrice,
            @DecimalMin("0") @DecimalMax("100") BigDecimal discountPercent,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal discountAmount,
            @DecimalMin("0") @DecimalMax("100") BigDecimal taxPercent,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal taxAmount,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal statedLineTotal,
            @Size(max = 2000) String notes) {
    }

    public record ResolveFlagRequest(@Size(max = 500) String comment) {
    }

    public record ApprovalRequest(
            @NotNull Long comparisonReportId,
            @NotNull ApprovalDecision decision,
            Long selectedQuotationId,
            @Size(max = 4000) String reason) {

        @AssertTrue(message = "selectedQuotationId is required when approving")
        public boolean isSelectionPresentForApproval() {
            return decision != ApprovalDecision.APPROVED || selectedQuotationId != null;
        }

        @AssertTrue(message = "reason is required when rejecting")
        public boolean isReasonPresentForRejection() {
            return decision != ApprovalDecision.REJECTED || (reason != null && !reason.isBlank());
        }
    }
}
