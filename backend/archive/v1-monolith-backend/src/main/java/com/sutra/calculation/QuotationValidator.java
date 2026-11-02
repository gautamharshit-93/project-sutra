package com.sutra.calculation;

import com.sutra.config.SutraProperties;
import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.Quotation;
import com.sutra.entity.QuotationItem;
import com.sutra.entity.RequestItem;
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

    public QuotationValidator(QuotationCalculator calculator, SutraProperties properties, Clock clock) {
        this.calculator = calculator;
        this.matchThreshold = properties.ai().matchConfidenceThreshold();
        this.clock = clock;
    }

    /** Recalculates totals and returns every validation flag for the quotation's current data. */
    public List<FlagDraft> validate(Quotation quotation) {
        List<FlagDraft> flags = new ArrayList<>(calculator.recalculate(quotation));
        PurchaseRequest request = quotation.getPurchaseRequest();

        checkHeader(quotation, request, flags);
        checkItemMatches(quotation, flags);
        checkMissingRequestItems(quotation, request, flags);
        return flags;
    }

    private void checkHeader(Quotation q, PurchaseRequest request, List<FlagDraft> flags) {
        if (q.getCurrency() == null) {
            flags.add(FlagDraft.warning(null, "currency", FlagCodes.MISSING_CURRENCY,
                    "Currency is not stated; the request currency " + request.getCurrency() + " is assumed"));
        } else if (!q.getCurrency().equals(request.getCurrency())) {
            flags.add(FlagDraft.warning(null, "currency", FlagCodes.CURRENCY_MISMATCH,
                    "Quotation currency " + q.getCurrency() + " differs from the request currency "
                            + request.getCurrency() + "; totals are not converted"));
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

    private void checkItemMatches(Quotation q, List<FlagDraft> flags) {
        for (QuotationItem item : q.getItems()) {
            RequestItem matched = item.getRequestItem();
            if (matched == null) {
                flags.add(FlagDraft.warning(item, "requestItem", FlagCodes.UNMATCHED_ITEM,
                        "Line " + item.getLineNumber() + " (\"" + abbreviate(item.getDescription())
                                + "\") does not match any requested item"));
                continue;
            }
            if (!item.isManuallyEdited() && item.getMatchConfidence() != null
                    && item.getMatchConfidence().compareTo(matchThreshold) < 0) {
                flags.add(FlagDraft.warning(item, "requestItem", FlagCodes.LOW_MATCH_CONFIDENCE,
                        "Line " + item.getLineNumber() + " was matched to request line " + matched.getLineNumber()
                                + " with low confidence (" + item.getMatchConfidence() + "); please confirm"));
            }
            if (item.getQuantity() != null && item.getQuantity().compareTo(matched.getQuantity()) != 0) {
                flags.add(FlagDraft.warning(item, "quantity", FlagCodes.QUANTITY_MISMATCH,
                        "Line " + item.getLineNumber() + ": quoted quantity "
                                + item.getQuantity().stripTrailingZeros().toPlainString() + " differs from requested "
                                + matched.getQuantity().stripTrailingZeros().toPlainString()));
            }
            if (item.getUnitOfMeasure() != null
                    && !normaliseUom(item.getUnitOfMeasure()).equals(normaliseUom(matched.getUnitOfMeasure()))) {
                flags.add(FlagDraft.info(item, "unitOfMeasure", FlagCodes.UOM_MISMATCH,
                        "Line " + item.getLineNumber() + ": unit '" + item.getUnitOfMeasure()
                                + "' differs from requested '" + matched.getUnitOfMeasure() + "'"));
            }
        }
    }

    private void checkMissingRequestItems(Quotation q, PurchaseRequest request, List<FlagDraft> flags) {
        Set<Long> quoted = q.getItems().stream()
                .filter(i -> i.getRequestItem() != null)
                .map(i -> i.getRequestItem().getId())
                .collect(Collectors.toSet());
        for (RequestItem requested : request.getItems()) {
            if (!quoted.contains(requested.getId())) {
                flags.add(FlagDraft.warning(null, "items", FlagCodes.MISSING_REQUEST_ITEM,
                        "Requested line " + requested.getLineNumber() + " (\"" + abbreviate(requested.getDescription())
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
