package com.sutra.quotation.service.internal;

import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.quotation.config.QuotationProperties;
import com.sutra.api.rules.FlagCodes;
import com.sutra.api.rules.TermsCheck;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationItem;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Checks a quotation against its purchase request: matching, missing items, quantities,
 * currency, validity and commercial terms. Arithmetic is delegated to {@link QuotationCalculator}.
 */
@Component
public class QuotationValidator {

    private final QuotationCalculator calculator;
    private final BigDecimal matchThreshold;
    private final Clock clock;

    public QuotationValidator(QuotationCalculator calculator, QuotationProperties properties, Clock clock) {
        this.calculator = calculator;
        this.matchThreshold = properties.matchConfidenceThreshold();
        this.clock = clock;
    }

    /** Recalculates totals and returns every validation flag for the quotation's current data. */
    public List<FlagDraft> validate(Quotation quotation, PurchaseRequestResponse request) {
        List<FlagDraft> flags = new ArrayList<>(calculator.recalculate(quotation));

        checkHeader(quotation, request, flags);
        checkItemMatches(quotation, request, flags);
        checkMissingRequestItems(quotation, request, flags);
        return flags;
    }

    private void checkHeader(Quotation q, PurchaseRequestResponse request, List<FlagDraft> flags) {
        if (q.getCurrency() == null) {
            flags.add(FlagDraft.warning(null, "currency", FlagCodes.MISSING_CURRENCY,
                    "Currency is not stated; the request currency " + request.currency() + " is assumed"));
        } else if (!q.getCurrency().equals(request.currency())) {
            flags.add(FlagDraft.warning(null, "currency", FlagCodes.CURRENCY_MISMATCH,
                    "Quotation currency " + q.getCurrency() + " differs from the request currency "
                            + request.currency() + "; totals are not converted"));
        }
        if (q.getValidUntil() == null) {
            flags.add(FlagDraft.warning(null, "validUntil", FlagCodes.MISSING_VALIDITY,
                    "Quotation validity date is missing"));
        } else if (q.getValidUntil().isBefore(LocalDate.now(clock))) {
            flags.add(FlagDraft.warning(null, "validUntil", FlagCodes.VALIDITY_EXPIRED,
                    "Quotation expired on " + q.getValidUntil()));
        }
        if (isBlank(q.getPaymentTerms())) {
            flags.add(FlagDraft.info(null, "paymentTerms", FlagCodes.MISSING_PAYMENT_TERMS,
                    "Payment terms are not stated"));
        }
        if (isBlank(q.getDeliveryTerms())) {
            flags.add(FlagDraft.info(null, "deliveryTerms", FlagCodes.MISSING_DELIVERY_TERMS,
                    "Delivery terms are not stated"));
        }
        // Terms the vendor explicitly left open (e.g. "UPS warranty not specified") must be confirmed before award.
        Map<String, String> terms = new LinkedHashMap<>();
        terms.put("deliveryTerms", q.getDeliveryTerms());
        terms.put("paymentTerms", q.getPaymentTerms());
        terms.put("warrantyTerms", q.getWarrantyTerms());
        terms.forEach((field, text) -> TermsCheck.incomplete(text).ifPresent(sentence ->
                flags.add(FlagDraft.warning(null, field, FlagCodes.INCOMPLETE_TERMS,
                        TermsCheck.label(field) + " are incomplete: \"" + sentence + "\" - confirm with the vendor"))));
        for (String text : new String[] {q.getNotes(), q.getDeliveryTerms(), q.getPaymentTerms()}) {
            var conditional = TermsCheck.conditional(text);
            if (conditional.isPresent()) {
                flags.add(FlagDraft.info(null, "notes", FlagCodes.CONDITIONAL_TERMS,
                        "Conditional offer: \"" + conditional.get() + "\""));
                break;
            }
        }
    }

    private void checkItemMatches(Quotation q, PurchaseRequestResponse request, List<FlagDraft> flags) {
        Map<Long, RequestItemResponse> requested = request.items().stream()
                .collect(Collectors.toMap(RequestItemResponse::id, r -> r));
        for (QuotationItem item : q.getItems()) {
            RequestItemResponse matched = item.getRequestItemId() == null ? null : requested.get(item.getRequestItemId());
            if (matched == null) {
                flags.add(FlagDraft.warning(item, "requestItem", FlagCodes.UNMATCHED_ITEM,
                        "Line " + item.getLineNumber() + " (\"" + abbreviate(item.getDescription())
                                + "\") does not match any requested item"));
                continue;
            }
            if (!item.isManuallyEdited() && item.getMatchConfidence() != null
                    && item.getMatchConfidence().compareTo(matchThreshold) < 0) {
                flags.add(FlagDraft.warning(item, "requestItem", FlagCodes.LOW_MATCH_CONFIDENCE,
                        "Line " + item.getLineNumber() + " was matched to request line " + matched.lineNumber()
                                + " with low confidence (" + item.getMatchConfidence() + "); please confirm"));
            }
            if (item.getQuantity() != null && item.getQuantity().compareTo(matched.quantity()) != 0) {
                flags.add(FlagDraft.warning(item, "quantity", FlagCodes.QUANTITY_MISMATCH,
                        "Line " + item.getLineNumber() + ": quoted quantity "
                                + item.getQuantity().stripTrailingZeros().toPlainString() + " differs from requested "
                                + matched.quantity().stripTrailingZeros().toPlainString()));
            }
            if (item.getUnitOfMeasure() != null
                    && !normaliseUom(item.getUnitOfMeasure()).equals(normaliseUom(matched.unitOfMeasure()))) {
                flags.add(FlagDraft.info(item, "unitOfMeasure", FlagCodes.UOM_MISMATCH,
                        "Line " + item.getLineNumber() + ": unit '" + item.getUnitOfMeasure()
                                + "' differs from requested '" + matched.unitOfMeasure() + "'"));
            }
        }
    }

    private void checkMissingRequestItems(Quotation q, PurchaseRequestResponse request, List<FlagDraft> flags) {
        Set<Long> quoted = q.getItems().stream()
                .map(QuotationItem::getRequestItemId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        for (RequestItemResponse requested : request.items()) {
            if (!quoted.contains(requested.id())) {
                flags.add(FlagDraft.warning(null, "items", FlagCodes.MISSING_REQUEST_ITEM,
                        "Requested line " + requested.lineNumber() + " (\"" + abbreviate(requested.description())
                                + "\") is not quoted"));
            }
        }
    }

    /** Lower-case, strip punctuation and a trailing plural "s" so "Nos." equals "no" and "pcs" equals "pc". */
    static String normaliseUom(String uom) {
        String s = uom.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (s.length() > 2 && s.endsWith("s")) {
            s = s.substring(0, s.length() - 1);
        }
        return switch (s) {
            case "no", "nos", "number", "unit", "piece", "pc", "ea", "each", "pce" -> "unit";
            default -> s;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }
}
