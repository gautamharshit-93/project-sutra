package com.sutra.quotation.service.internal;

import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rules.FlagCodes;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationFlag;
import com.sutra.api.enums.FlagSeverity;
import com.sutra.api.enums.QuotationStatus;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Recalculates a quotation and keeps its review flags current.
 *
 * <ul>
 *   <li>Open validation flags are dropped and regenerated from the current data on every run.</li>
 *   <li>A flag a person already resolved is not raised again for the same item, field and code.</li>
 *   <li>Extraction flags (low confidence, extractor notes) stay until resolved or the item is edited.</li>
 * </ul>
 * A read quotation is READY_FOR_COMPARISON straight away; warnings and notes stay open as "to check" and are shown
 * with the comparison. Only an open ERROR (a line without quantity or price, so no total can be calculated) or a
 * quotation without lines keeps it in NEEDS_REVIEW.
 */
@Service
public class QuotationReviewService {

    private final QuotationValidator validator;

    public QuotationReviewService(QuotationValidator validator) {
        this.validator = validator;
    }

    /** Must be called inside a transaction with the quotation's items, flags and request items loadable. */
    public void revalidate(Quotation quotation, PurchaseRequestResponse rfq) {
        quotation.getFlags().removeIf(f -> !f.isResolved() && FlagCodes.VALIDATION_CODES.contains(f.getCode()));

        Set<String> acknowledged = quotation.getFlags().stream()
                .filter(QuotationFlag::isResolved)
                .map(f -> key(f.getCode(), f.getQuotationItem() == null ? null : f.getQuotationItem().getId(),
                        f.getFieldName()))
                .collect(Collectors.toSet());

        List<FlagDraft> drafts = validator.validate(quotation, rfq);
        for (FlagDraft draft : drafts) {
            Long itemId = draft.item() == null ? null : draft.item().getId();
            if (itemId != null && acknowledged.contains(key(draft.code(), itemId, draft.fieldName()))) {
                continue;
            }
            if (draft.item() == null && acknowledged.contains(key(draft.code(), null, draft.fieldName()))) {
                continue;
            }
            quotation.addFlag(new QuotationFlag(draft.item(), draft.fieldName(), draft.severity(), draft.code(),
                    draft.message()));
        }
        updateStatus(quotation);
    }

    public void updateStatus(Quotation quotation) {
        // A failed quotation stays FAILED until someone enters at least one line manually.
        if (quotation.getStatus() == QuotationStatus.PROCESSING
                || (quotation.getStatus() == QuotationStatus.FAILED && quotation.getItems().isEmpty())) {
            return;
        }
        quotation.setStatus(hasOpenErrors(quotation) || quotation.getItems().isEmpty()
                ? QuotationStatus.NEEDS_REVIEW
                : QuotationStatus.READY_FOR_COMPARISON);
    }

    public static boolean hasBlockingFlags(Quotation quotation) {
        return quotation.getFlags().stream()
                .anyMatch(f -> !f.isResolved() && f.getSeverity() != FlagSeverity.INFO);
    }

    public static boolean hasOpenErrors(Quotation quotation) {
        return quotation.getFlags().stream()
                .anyMatch(f -> !f.isResolved() && f.getSeverity() == FlagSeverity.ERROR);
    }

    private static String key(String code, Long itemId, String field) {
        return code + "|" + Objects.toString(itemId, "-") + "|" + field;
    }
}
