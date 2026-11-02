package com.sutra.calculation;

import com.sutra.entity.QuotationItem;
import com.sutra.entity.enums.FlagSeverity;

/** A review flag produced by validation, before it is attached to a quotation. */
public record FlagDraft(QuotationItem item, String fieldName, FlagSeverity severity, String code, String message) {

    public static FlagDraft error(QuotationItem item, String field, String code, String message) {
        return new FlagDraft(item, field, FlagSeverity.ERROR, code, message);
    }

    public static FlagDraft warning(QuotationItem item, String field, String code, String message) {
        return new FlagDraft(item, field, FlagSeverity.WARNING, code, message);
    }

    public static FlagDraft info(QuotationItem item, String field, String code, String message) {
        return new FlagDraft(item, field, FlagSeverity.INFO, code, message);
    }
}
