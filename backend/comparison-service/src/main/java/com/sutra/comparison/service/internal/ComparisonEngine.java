package com.sutra.comparison.service.internal;

import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationItemResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.comparison.ComparisonResult;
import com.sutra.api.rules.TermsCheck;
import com.sutra.api.comparison.ComparisonResult.ItemCell;
import com.sutra.api.comparison.ComparisonResult.ItemRow;
import com.sutra.api.comparison.ComparisonResult.QuotationColumn;
import com.sutra.api.comparison.ComparisonResult.UnmatchedItem;
import com.sutra.common.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Builds the side-by-side comparison. Pure calculation over loaded entities - no persistence.
 *
 * <p>Prices are compared like for like: each matched line's total (after discount and tax) is divided by the
 * quoted quantity to get an effective unit price, then multiplied by the <em>requested</em> quantity. Ranking
 * uses the sum of those comparable totals, and only quotations that quote every requested item, in the request
 * currency, are ranked. The ranking is informational; nothing here selects a vendor.</p>
 */
@Component
public class ComparisonEngine {

    private final Clock clock;

    public ComparisonEngine(Clock clock) {
        this.clock = clock;
    }

    public ComparisonResult compare(PurchaseRequestResponse request, List<QuotationResponse> quotations) {
        List<String> warnings = new ArrayList<>();
        String currency = request.currency();
        LocalDate today = LocalDate.now(clock);

        // quotationId -> requestItemId -> matched line
        Map<Long, Map<Long, QuotationItemResponse>> matches = new HashMap<>();
        List<UnmatchedItem> unmatched = new ArrayList<>();
        for (QuotationResponse q : quotations) {
            Map<Long, QuotationItemResponse> byRequestItem = new LinkedHashMap<>();
            for (QuotationItemResponse item : q.items()) {
                if (item.requestItemId() == null) {
                    unmatched.add(new UnmatchedItem(q.id(), q.vendorName(), item.id(),
                            item.description(), item.lineTotal()));
                } else if (byRequestItem.putIfAbsent(item.requestItemId(), item) != null) {
                    warnings.add(q.vendorName() + " has several lines matched to request line "
                            + item.requestItemLineNumber() + "; only line "
                            + byRequestItem.get(item.requestItemId()).lineNumber() + " is compared");
                }
            }
            matches.put(q.id(), byRequestItem);
            if (!sameCurrency(q, currency)) {
                warnings.add(q.vendorName() + " quoted in " + q.currency() + " instead of " + currency
                        + "; it is shown but not ranked");
            }
        }

        // Rows: one per requested item
        List<ItemRow> rows = new ArrayList<>();
        Map<Long, BigDecimal> comparableTotals = new HashMap<>();
        Map<Long, Integer> quotedCounts = new HashMap<>();
        Map<Long, Boolean> pricedFully = new HashMap<>();
        for (QuotationResponse q : quotations) {
            comparableTotals.put(q.id(), BigDecimal.ZERO);
            quotedCounts.put(q.id(), 0);
            pricedFully.put(q.id(), true);
        }

        for (RequestItemResponse requested : request.items()) {
            List<CellDraft> drafts = new ArrayList<>();
            for (QuotationResponse q : quotations) {
                QuotationItemResponse item = matches.get(q.id()).get(requested.id());
                if (item == null) {
                    drafts.add(new CellDraft(q, null, null, null));
                    continue;
                }
                quotedCounts.merge(q.id(), 1, Integer::sum);
                BigDecimal effective = effectiveUnitPrice(item);
                BigDecimal comparable = effective == null ? null : Money.round(effective.multiply(requested.quantity()));
                if (comparable == null) {
                    pricedFully.put(q.id(), false);
                } else {
                    comparableTotals.merge(q.id(), comparable, BigDecimal::add);
                }
                drafts.add(new CellDraft(q, item, effective, comparable));
            }

            BigDecimal lowest = drafts.stream()
                    .filter(d -> d.effective != null && sameCurrency(d.quotation, currency))
                    .map(d -> d.effective).min(Comparator.naturalOrder()).orElse(null);
            BigDecimal highest = drafts.stream()
                    .filter(d -> d.effective != null && sameCurrency(d.quotation, currency))
                    .map(d -> d.effective).max(Comparator.naturalOrder()).orElse(null);

            List<ItemCell> cells = drafts.stream().map(d -> toCell(d, requested, lowest, currency)).toList();
            rows.add(new ItemRow(requested.id(), requested.lineNumber(), requested.itemCode(), requested.description(),
                    requested.quantity(), requested.unitOfMeasure(), cells, lowest, highest,
                    Money.percentDifference(highest, lowest)));
        }

        // Rank complete quotations in the request currency by comparable total
        int requestedCount = request.items().size();
        List<QuotationResponse> rankable = quotations.stream()
                .filter(q -> sameCurrency(q, currency))
                .filter(q -> quotedCounts.get(q.id()) == requestedCount && pricedFully.get(q.id()))
                .sorted(Comparator.comparing((QuotationResponse q) -> comparableTotals.get(q.id()))
                        .thenComparing(QuotationResponse::id))
                .toList();
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < rankable.size(); i++) {
            ranks.put(rankable.get(i).id(), i + 1);
        }
        BigDecimal lowestTotal = rankable.isEmpty() ? null : comparableTotals.get(rankable.get(0).id());
        if (rankable.isEmpty() && !quotations.isEmpty()) {
            warnings.add("No quotation covers every requested item with a price in " + currency
                    + ", so no overall ranking is shown");
        }

        List<QuotationColumn> columns = quotations.stream().map(q -> {
            int quoted = quotedCounts.get(q.id());
            boolean complete = quoted == requestedCount && pricedFully.get(q.id());
            BigDecimal comparable = Money.round(comparableTotals.get(q.id()));
            Integer rank = ranks.get(q.id());
            BigDecimal diff = rank == null || lowestTotal == null ? null : comparable.subtract(lowestTotal);
            BigDecimal diffPct = rank == null ? null : Money.percentDifference(comparable, lowestTotal);
            boolean expired = q.validUntil() != null && q.validUntil().isBefore(today);
            return new QuotationColumn(q.id(), q.version(), q.vendorId(), q.vendorName(),
                    q.quotationNumber(), q.status(), q.currency() == null ? currency : q.currency(),
                    q.subtotal(), q.discountTotal(), q.taxTotal(), q.grandTotal(), comparable,
                    q.validUntil(), expired, q.deliveryTerms(), q.paymentTerms(), q.warrantyTerms(),
                    quoted, requestedCount - quoted, complete, rank, diff, diffPct, openFlags(q),
                    missingInformation(q, requestedCount - quoted, expired),
                    VendorGrader.grade(q, rank, diffPct, quoted, requestedCount, today));
        }).toList();

        Long lowestId = rankable.isEmpty() ? null : rankable.get(0).id();
        return new ComparisonResult(request.id(), currency, columns, rows, unmatched, warnings, lowestId,
                ComparisonResult.DISCLAIMER);
    }

    private ItemCell toCell(CellDraft d, RequestItemResponse requested, BigDecimal lowest, String currency) {
        if (d.item == null) {
            return new ItemCell(d.quotation.id(), "MISSING", null, null, null, null, null, null, null, null,
                    false, false, null, null);
        }
        QuotationItemResponse item = d.item;
        boolean isLowest = lowest != null && d.effective != null && sameCurrency(d.quotation, currency)
                && d.effective.compareTo(lowest) == 0;
        BigDecimal diff = null;
        BigDecimal diffPct = null;
        if (lowest != null && d.effective != null && sameCurrency(d.quotation, currency)) {
            diff = Money.round(d.effective.subtract(lowest).multiply(requested.quantity()));
            diffPct = Money.percentDifference(d.effective, lowest);
        }
        boolean qtyMismatch = item.quantity() != null && item.quantity().compareTo(requested.quantity()) != 0;
        return new ItemCell(d.quotation.id(), "MATCHED", item.id(), item.description(), item.quantity(),
                item.unitPrice(), item.lineTotal(), d.effective, d.comparable, item.matchConfidence(),
                qtyMismatch, isLowest, diff, diffPct);
    }

    static BigDecimal effectiveUnitPrice(QuotationItemResponse item) {
        if (item.lineTotal() == null || item.quantity() == null || item.quantity().signum() == 0) {
            return null;
        }
        return item.lineTotal().divide(item.quantity(), 4, RoundingMode.HALF_UP);
    }

    private static boolean sameCurrency(QuotationResponse q, String requestCurrency) {
        return q.currency() == null || Objects.equals(q.currency(), requestCurrency);
    }

    private static List<String> missingInformation(QuotationResponse q, int missingItems, boolean expired) {
        List<String> missing = new ArrayList<>();
        if (missingItems > 0) {
            missing.add(missingItems + " requested item(s) not quoted");
        }
        if (q.validUntil() == null) {
            missing.add("Validity date");
        } else if (expired) {
            missing.add("QuotationResponse has expired");
        }
        if (q.paymentTerms() == null || q.paymentTerms().isBlank()) {
            missing.add("Payment terms");
        }
        if (q.deliveryTerms() == null || q.deliveryTerms().isBlank()) {
            missing.add("Delivery terms");
        }
        if (q.currency() == null) {
            missing.add("Currency");
        }
        if (TermsCheck.incomplete(q.warrantyTerms()).isPresent()) {
            missing.add("Warranty incomplete");
        }
        if (TermsCheck.incomplete(q.deliveryTerms()).isPresent()) {
            missing.add("Delivery terms incomplete");
        }
        if (TermsCheck.incomplete(q.paymentTerms()).isPresent()) {
            missing.add("Payment terms incomplete");
        }
        return missing;
    }

    private record CellDraft(QuotationResponse quotation, QuotationItemResponse item, BigDecimal effective, BigDecimal comparable) {
    }

    /** Notes on the quotation that nobody has resolved yet. */
    static long openFlags(QuotationResponse q) {
        return q.flags() == null ? 0 : q.flags().stream().filter(f -> !f.resolved()).count();
    }
}
