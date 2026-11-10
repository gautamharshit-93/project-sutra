package com.sutra.api.rules;

import java.util.Set;

/** Stable codes for review flags. */
public final class FlagCodes {

    // Raised during extraction. They stay until a person resolves them or edits the item.
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String EXTRACTOR_NOTE = "EXTRACTOR_NOTE";
    public static final String NO_ITEMS_EXTRACTED = "NO_ITEMS_EXTRACTED";
    public static final String VENDOR_NAME_MISMATCH = "VENDOR_NAME_MISMATCH";

    // Raised by backend validation. Regenerated on every recalculation.
    public static final String MISSING_QUANTITY = "MISSING_QUANTITY";
    public static final String MISSING_UNIT_PRICE = "MISSING_UNIT_PRICE";
    public static final String LINE_TOTAL_MISMATCH = "LINE_TOTAL_MISMATCH";
    public static final String DISCOUNT_CONFLICT = "DISCOUNT_CONFLICT";
    public static final String TAX_CONFLICT = "TAX_CONFLICT";
    public static final String SUBTOTAL_MISMATCH = "SUBTOTAL_MISMATCH";
    public static final String TAX_TOTAL_MISMATCH = "TAX_TOTAL_MISMATCH";
    public static final String GRAND_TOTAL_MISMATCH = "GRAND_TOTAL_MISMATCH";
    public static final String UNMATCHED_ITEM = "UNMATCHED_ITEM";
    public static final String LOW_MATCH_CONFIDENCE = "LOW_MATCH_CONFIDENCE";
    public static final String MISSING_REQUEST_ITEM = "MISSING_REQUEST_ITEM";
    public static final String QUANTITY_MISMATCH = "QUANTITY_MISMATCH";
    public static final String UOM_MISMATCH = "UOM_MISMATCH";
    public static final String MISSING_CURRENCY = "MISSING_CURRENCY";
    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String MISSING_VALIDITY = "MISSING_VALIDITY";
    public static final String VALIDITY_EXPIRED = "VALIDITY_EXPIRED";
    public static final String MISSING_PAYMENT_TERMS = "MISSING_PAYMENT_TERMS";
    public static final String MISSING_DELIVERY_TERMS = "MISSING_DELIVERY_TERMS";
    public static final String DOCUMENT_LEVEL_TAX = "DOCUMENT_LEVEL_TAX";
    public static final String INCOMPLETE_TERMS = "INCOMPLETE_TERMS";
    public static final String CONDITIONAL_TERMS = "CONDITIONAL_TERMS";

    public static final Set<String> VALIDATION_CODES = Set.of(DOCUMENT_LEVEL_TAX, INCOMPLETE_TERMS, CONDITIONAL_TERMS,
            MISSING_QUANTITY, MISSING_UNIT_PRICE, LINE_TOTAL_MISMATCH, DISCOUNT_CONFLICT, TAX_CONFLICT,
            SUBTOTAL_MISMATCH, TAX_TOTAL_MISMATCH, GRAND_TOTAL_MISMATCH, UNMATCHED_ITEM, LOW_MATCH_CONFIDENCE,
            MISSING_REQUEST_ITEM, QUANTITY_MISMATCH, UOM_MISMATCH, MISSING_CURRENCY, CURRENCY_MISMATCH,
            MISSING_VALIDITY, VALIDITY_EXPIRED, MISSING_PAYMENT_TERMS, MISSING_DELIVERY_TERMS);

    private FlagCodes() {
    }
}
