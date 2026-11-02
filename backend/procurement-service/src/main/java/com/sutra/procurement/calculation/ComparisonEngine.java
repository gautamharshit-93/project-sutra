package com.sutra.procurement.calculation;

import com.sutra.procurement.calculation.ComparisonResult.ItemCell;
import com.sutra.procurement.calculation.ComparisonResult.ItemRow;
import com.sutra.procurement.calculation.ComparisonResult.QuotationColumn;
import com.sutra.procurement.calculation.ComparisonResult.UnmatchedItem;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationItem;
import com.sutra.procurement.entity.RequestItem;
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

    public ComparisonResult compare(PurchaseRequest request, List<Quotation> quotations) {
        List<String> warnings = new ArrayList<>();
        String currency = request.getCurrency();
        LocalDate today = LocalDate.now(clock);

        // quotationId -> requestItemId -> matched line
        Map<Long, Map<Long, QuotationItem>> matches = new HashMap<>();
        List<UnmatchedItem> unmatched = new ArrayList<>();
        for (Quotation q : quotations) {
            Map<Long, QuotationItem> byRequestItem = new LinkedHashMap<>();
            for (QuotationItem item : q.getItems()) {
                if (item.getRequestItem() == null) {
                    unmatched.add(new UnmatchedItem(q.getId(), q.getVendor().getName(), item.getId(),
                            item.getDescription(), item.getLineTotal()));
                } else if (byRequestItem.putIfAbsent(item.getRequestItem().getId(), item) != null) {
                    warnings.add(q.getVendor().getName() + " has several lines matched to request line "
                            + item.getRequestItem().getLineNumber() + "; only line "
                            + byRequestItem.get(item.getRequestItem().getId()).getLineNumber() + " is compared");
                }
            }
            matches.put(q.getId(), byRequestItem);
            if (!sameCurrency(q, currency)) {
                warnings.add(q.getVendor().getName() + " quoted in " + q.getCurrency() + " instead of " + currency
                        + "; it is shown but not ranked");
            }
        }

        // Rows: one per requested item
        List<ItemRow> rows = new ArrayList<>();
        Map<Long, BigDecimal> comparableTotals = new HashMap<>();
        Map<Long, Integer> quotedCounts = new HashMap<>();
        Map<Long, Boolean> pricedFully = new HashMap<>();
        for (Quotation q : quotations) {
            comparableTotals.put(q.getId(), BigDecimal.ZERO);
            quotedCounts.put(q.getId(), 0);
            pricedFully.put(q.getId(), true);
        }

        for (RequestItem requested : request.getItems()) {
            List<CellDraft> drafts = new ArrayList<>();
            for (Quotation q : quotations) {
                QuotationItem item = matches.get(q.getId()).get(requested.getId());
                if (item == null) {
                    drafts.add(new CellDraft(q, null, null, null));
                    continue;
                }
                quotedCounts.merge(q.getId(), 1, Integer::sum);
                BigDecimal effective = effectiveUnitPrice(item);
                BigDecimal comparable = effective == null ? null : Money.round(effective.multiply(requested.getQuantity()));
                if (comparable == null) {
                    pricedFully.put(q.getId(), false);
                } else {
                    comparableTotals.merge(q.getId(), comparable, BigDecimal::add);
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
            rows.add(new ItemRow(requested.getId(), requested.getLineNumber(), requested.getItemCode(), requested.getDescription(),
                    requested.getQuantity(), requested.getUnitOfMeasure(), cells, lowest, highest,
                    Money.percentDifference(highest, lowest)));
        }

        // Rank complete quotations in the request currency by comparable total
        int requestedCount = request.getItems().size();
        List<Quotation> rankable = quotations.stream()
                .filter(q -> sameCurrency(q, currency))
                .filter(q -> quotedCounts.get(q.getId()) == requestedCount && pricedFully.get(q.getId()))
                .sorted(Comparator.comparing((Quotation q) -> comparableTotals.get(q.getId()))
                        .thenComparing(Quotation::getId))
                .toList();
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < rankable.size(); i++) {
            ranks.put(rankable.get(i).getId(), i + 1);
        }
        BigDecimal lowestTotal = rankable.isEmpty() ? null : comparableTotals.get(rankable.get(0).getId());
        if (rankable.isEmpty() && !quotations.isEmpty()) {
            warnings.add("No quotation covers every requested item with a price in " + currency
                    + ", so no overall ranking is shown");
        }

        List<QuotationColumn> columns = quotations.stream().map(q -> {
            int quoted = quotedCounts.get(q.getId());
            boolean complete = quoted == requestedCount && pricedFully.get(q.getId());
            BigDecimal comparable = Money.round(comparableTotals.get(q.getId()));
            Integer rank = ranks.get(q.getId());
            BigDecimal diff = rank == null || lowestTotal == null ? null : comparable.subtract(lowestTotal);
            BigDecimal diffPct = rank == null ? null : Money.percentDifference(comparable, lowestTotal);
            boolean expired = q.getValidUntil() != null && q.getValidUntil().isBefore(today);
            return new QuotationColumn(q.getId(), q.getVersion(), q.getVendor().getId(), q.getVendor().getName(),
                    q.getQuotationNumber(), q.getStatus(), q.getCurrency() == null ? currency : q.getCurrency(),
                    q.getSubtotal(), q.getDiscountTotal(), q.getTaxTotal(), q.getGrandTotal(), comparable,
                    q.getValidUntil(), expired, q.getDeliveryTerms(), q.getPaymentTerms(), q.getWarrantyTerms(),
                    quoted, requestedCount - quoted, complete, rank, diff, diffPct, q.openFlagCount(),
                    missingInformation(q, requestedCount - quoted, expired),
                    VendorGrader.grade(q, rank, diffPct, quoted, requestedCount, today));
        }).toList();

        Long lowestId = rankable.isEmpty() ? null : rankable.get(0).getId();
        return new ComparisonResult(request.getId(), currency, columns, rows, unmatched, warnings, lowestId,
                ComparisonResult.DISCLAIMER);
    }

    private ItemCell toCell(CellDraft d, RequestItem requested, BigDecimal lowest, String currency) {
        if (d.item == null) {
            return new ItemCell(d.quotation.getId(), "MISSING", null, null, null, null, null, null, null, null,
                    false, false, null, null);
        }
        QuotationItem item = d.item;
        boolean isLowest = lowest != null && d.effective != null && sameCurrency(d.quotation, currency)
                && d.effective.compareTo(lowest) == 0;
        BigDecimal diff = null;
        BigDecimal diffPct = null;
        if (lowest != null && d.effective != null && sameCurrency(d.quotation, currency)) {
            diff = Money.round(d.effective.subtract(lowest).multiply(requested.getQuantity()));
            diffPct = Money.percentDifference(d.effective, lowest);
        }
        boolean qtyMismatch = item.getQuantity() != null && item.getQuantity().compareTo(requested.getQuantity()) != 0;
        return new ItemCell(d.quotation.getId(), "MATCHED", item.getId(), item.getDescription(), item.getQuantity(),
                item.getUnitPrice(), item.getLineTotal(), d.effective, d.comparable, item.getMatchConfidence(),
                qtyMismatch, isLowest, diff, diffPct);
    }

    static BigDecimal effectiveUnitPrice(QuotationItem item) {
        if (item.getLineTotal() == null || item.getQuantity() == null || item.getQuantity().signum() == 0) {
            return null;
        }
        return item.getLineTotal().divide(item.getQuantity(), 4, RoundingMode.HALF_UP);
    }

    private static boolean sameCurrency(Quotation q, String requestCurrency) {
        return q.getCurrency() == null || Objects.equals(q.getCurrency(), requestCurrency);
    }

    private static List<String> missingInformation(Quotation q, int missingItems, boolean expired) {
        List<String> missing = new ArrayList<>();
        if (missingItems > 0) {
            missing.add(missingItems + " requested item(s) not quoted");
        }
        if (q.getValidUntil() == null) {
            missing.add("Validity date");
        } else if (expired) {
            missing.add("Quotation has expired");
        }
        if (q.getPaymentTerms() == null || q.getPaymentTerms().isBlank()) {
            missing.add("Payment terms");
        }
        if (q.getDeliveryTerms() == null || q.getDeliveryTerms().isBlank()) {
            missing.add("Delivery terms");
        }
        if (q.getCurrency() == null) {
            missing.add("Currency");
        }
        if (TermsCheck.incomplete(q.getWarrantyTerms()).isPresent()) {
            missing.add("Warranty incomplete");
        }
        if (TermsCheck.incomplete(q.getDeliveryTerms()).isPresent()) {
            missing.add("Delivery terms incomplete");
        }
        if (TermsCheck.incomplete(q.getPaymentTerms()).isPresent()) {
            missing.add("Payment terms incomplete");
        }
        return missing;
    }

    private record CellDraft(Quotation quotation, QuotationItem item, BigDecimal effective, BigDecimal comparable) {
    }
}
