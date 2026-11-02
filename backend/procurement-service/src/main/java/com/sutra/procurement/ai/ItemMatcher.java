package com.sutra.procurement.ai;

import com.sutra.procurement.entity.QuotationItem;
import com.sutra.procurement.entity.RequestItem;
import java.math.BigDecimal;
import java.util.List;

/** Matches quoted lines to requested lines. Each requested line is matched at most once. */
public interface ItemMatcher {

    /** Sets {@code requestItem} and {@code matchConfidence} on each quoted item that has a plausible match. */
    void assign(List<RequestItem> requested, List<QuotationItem> quoted);

    /** Score below which a quoted line is left unmatched. */
    BigDecimal MINIMUM_SCORE = new BigDecimal("0.35");
}
